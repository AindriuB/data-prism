package io.github.aindriub.dataprism.hazelcast;

import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.map.IMap;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Stops one caller inside {@code create} until the test lets it go. By default that is after it has claimed the id
 * and reserved the cap and before it inserts the approval; {@link #beforeSecondClaimCheck()} stops it after the
 * insert instead, before it checks that it still holds the claim. Waits are on latches and on observable lock
 * state, never on sleeps.
 */
final class StalledInsert {

    /** Counted down when the caller is stopped at the insert. */
    final CountDownLatch reached = new CountDownLatch(1);
    private final CountDownLatch resume = new CountDownLatch(1);
    private final AtomicBoolean first = new AtomicBoolean(true);
    private final String mapName;
    private final String method;
    private final int arguments;
    private final int stopAtCall;
    private final java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
    private volatile boolean failOnResume;
    /** Calls that always throw once {@link #arm()} was called: {@code map + ":" + method}. */
    private final java.util.Set<String> failing = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** Calls that always throw {@link IllegalArgumentException} once armed. */
    private final java.util.Set<String> rejecting = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** Calls that always throw {@link com.hazelcast.core.HazelcastInstanceNotActiveException} once armed. */
    private final java.util.Set<String> inactive = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private volatile boolean armed;
    /** Calls that run twice once armed, returning the second result, as a retried invocation would. */
    private final java.util.Set<String> repeating = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final java.util.Map<String, java.util.concurrent.atomic.AtomicInteger> counts =
            new java.util.concurrent.ConcurrentHashMap<>();

    StalledInsert() {
        this(PrivacyCluster.APPROVAL_MAP, "putIfAbsent", 4, 1);
    }

    private StalledInsert(String mapName, String method, int arguments, int stopAtCall) {
        this.mapName = mapName;
        this.method = method;
        this.arguments = arguments;
        this.stopAtCall = stopAtCall;
    }

    /** Stops the caller after its approval is inserted, just before it reads its claim to confirm it is still held. */
    static StalledInsert beforeSecondClaimCheck() {
        return new StalledInsert(PrivacyCluster.APPROVAL_ID_MAP, "get", 1, 2);
    }

    /** An injector that never stops a caller; only {@link #failing} and {@link #repeating} act, once armed. */
    static StalledInsert passive() {
        return new StalledInsert("none", "none", 0, 1);
    }

    /** Stops the caller at its first entry processor on the approval map, which is where a decision commits. */
    static StalledInsert beforeDecisionCommit() {
        return new StalledInsert(PrivacyCluster.APPROVAL_MAP, "executeOnKey", 2, 1);
    }

    /** How many times {@code method} was called on the map {@code mapName} through this injector's cluster. */
    int callsTo(String mapName, String method) {
        java.util.concurrent.atomic.AtomicInteger count = counts.get(mapName + ":" + method);
        return count == null ? 0 : count.get();
    }

    /** Stops the caller after it claimed the id and before it reserves the cap in the pending map. */
    static StalledInsert beforeReserve() {
        return new StalledInsert(PrivacyCluster.APPROVAL_PENDING_MAP, "executeOnKey", 2, 1);
    }

    /** When the stopped call is let go it fails instead of running, as an insert that threw would. */
    StalledInsert failingOnResume() {
        this.failOnResume = true;
        return this;
    }

    /**
     * Makes every call of {@code method} on the map {@code mapName} throw once {@link #arm()} was called, as a
     * call that failed in the cluster would. Calls on the same instance of other methods run as usual.
     */
    StalledInsert failing(String mapName, String method) {
        failing.add(mapName + ":" + method);
        return this;
    }

    /**
     * Makes every call of {@code method} on the map {@code mapName} throw {@link IllegalArgumentException} once
     * armed, as a lock or lease the cluster rejects does.
     */
    StalledInsert rejecting(String mapName, String method) {
        rejecting.add(mapName + ":" + method);
        return this;
    }

    /**
     * Makes every call of {@code method} on the map {@code mapName} throw
     * {@link com.hazelcast.core.HazelcastInstanceNotActiveException} once armed, as a member that is shutting down
     * does: a cluster fault that is not an {@link IllegalArgumentException}.
     */
    StalledInsert inactive(String mapName, String method) {
        inactive.add(mapName + ":" + method);
        return this;
    }

    /**
     * Makes every call of {@code method} on {@code mapName} run twice once armed and return the second result, as an
     * invocation retried after the first attempt already applied would.
     */
    StalledInsert repeating(String mapName, String method) {
        repeating.add(mapName + ":" + method);
        return this;
    }

    /** Starts the failures registered with {@link #failing}. */
    void arm() {
        armed = true;
    }

    /**
     * A cluster over {@code member} whose first approval insert blocks until {@link #resume()}, and whose calls
     * registered with {@link #failing} throw once armed.
     */
    PrivacyCluster over(PrivacyCluster member) {
        HazelcastInstance real = member.instance();
        java.util.Map<String, Object> proxies = new java.util.concurrent.ConcurrentHashMap<>();
        HazelcastInstance stalledInstance = (HazelcastInstance) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{HazelcastInstance.class}, (proxy, method, args) -> {
                    if (!method.getName().equals("getMap")) {
                        return invoke(method, real, args);
                    }
                    String name = (String) args[0];
                    if (armed && failing.contains(name + ":getMap")) {
                        throw new IllegalStateException("injected failure");
                    }
                    IMap<?, ?> target = real.getMap(name);
                    return proxies.computeIfAbsent(name, ignored -> Proxy.newProxyInstance(
                            getClass().getClassLoader(), new Class<?>[]{IMap.class}, (mapProxy, call, callArgs) -> {
                                counts.computeIfAbsent(name + ":" + call.getName(),
                                        ignoredKey -> new java.util.concurrent.atomic.AtomicInteger())
                                        .incrementAndGet();
                                if (armed && rejecting.contains(name + ":" + call.getName())) {
                                    throw new IllegalArgumentException("injected rejection");
                                }
                                if (armed && inactive.contains(name + ":" + call.getName())) {
                                    throw new com.hazelcast.core.HazelcastInstanceNotActiveException();
                                }
                                if (armed && failing.contains(name + ":" + call.getName())) {
                                    throw new IllegalStateException("injected failure");
                                }
                                if (name.equals(mapName) && call.getName().equals(this.method) && callArgs != null
                                        && callArgs.length == arguments && calls.incrementAndGet() == stopAtCall
                                        && first.compareAndSet(true, false)) {
                                    reached.countDown();
                                    if (!resume.await(30, TimeUnit.SECONDS)) {
                                        throw new AssertionError("caller never resumed");
                                    }
                                    if (failOnResume) throw new IllegalStateException("injected failure");
                                }
                                if (armed && repeating.contains(name + ":" + call.getName())) {
                                    invoke(call, target, callArgs);
                                }
                                return invoke(call, target, callArgs);
                            }));
                });
        return PrivacyCluster.using(stalledInstance, false, TestMembers.KEYS);
    }

    void resume() {
        resume.countDown();
    }

    /**
     * Blocks until {@code key} of {@code map} is free on {@code other}. A lock the stalled caller took with a
     * lease frees itself when the lease runs out, so this returns only once the lease has expired; a lock that is
     * never taken, or was released, returns at once.
     */
    static void awaitFree(PrivacyCluster other, String map, String key) throws InterruptedException {
        IMap<String, ?> entries = other.instance().getMap(map);
        if (!entries.tryLock(key, 10, TimeUnit.SECONDS)) throw new AssertionError("lock never freed");
        entries.unlock(key);
    }

    private static Object invoke(Method method, Object target, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException failure) {
            throw failure.getCause();
        }
    }
}

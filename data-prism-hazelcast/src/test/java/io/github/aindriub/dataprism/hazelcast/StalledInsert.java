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

    /** Stops the caller after it claimed the id and before it reserves the cap in the pending map. */
    static StalledInsert beforeReserve() {
        return new StalledInsert(PrivacyCluster.APPROVAL_PENDING_MAP, "executeOnKey", 2, 1);
    }

    /** When the stopped call is let go it fails instead of running, as an insert that threw would. */
    StalledInsert failingOnResume() {
        this.failOnResume = true;
        return this;
    }

    /** A cluster over {@code member} whose first approval insert blocks until {@link #resume()}. */
    PrivacyCluster over(PrivacyCluster member) {
        HazelcastInstance real = member.instance();
        IMap<?, ?> approvals = real.getMap(mapName);
        Object stalledMap = Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{IMap.class},
                (proxy, method, args) -> {
                    if (method.getName().equals(this.method) && args != null && args.length == arguments
                            && calls.incrementAndGet() == stopAtCall && first.compareAndSet(true, false)) {
                        reached.countDown();
                        if (!resume.await(30, TimeUnit.SECONDS)) throw new AssertionError("caller never resumed");
                        if (failOnResume) throw new IllegalStateException("injected failure");
                    }
                    return invoke(method, approvals, args);
                });
        HazelcastInstance stalledInstance = (HazelcastInstance) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{HazelcastInstance.class}, (proxy, method, args) ->
                        method.getName().equals("getMap") && args[0].equals(mapName)
                                ? stalledMap : invoke(method, real, args));
        return PrivacyCluster.using(stalledInstance, false);
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

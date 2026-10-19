package io.github.aindriub.dataprism.hazelcast;

import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.map.IMap;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.OptionalInt;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

import static io.github.aindriub.dataprism.hazelcast.TestMembers.NOW;
import static org.assertj.core.api.Assertions.assertThat;

/** A client paused past its lease must not overwrite a later client's admitted count. */
class CounterLeaseExpiryTest {
    private static final Duration TIMEOUT = Duration.ofMillis(100);

    @Test void budgetRemainsAtomicAfterTheCallerLeaseExpires() throws Exception {
        afterLeaseExpiry(PrivacyCluster.BUDGET_MAP, ScopeKeys.budget("scope", "subject"),
                cluster -> new HazelcastScopeBudget(cluster, TIMEOUT).tryRead("scope", "subject", 1));
    }

    @Test void rateLimitRemainsAtomicAfterTheCallerLeaseExpires() throws Exception {
        afterLeaseExpiry(PrivacyCluster.CALLER_RATE_MAP, "operator",
                cluster -> new HazelcastCallerRateLimiter(cluster, TIMEOUT)
                        .tryAcquire("operator", 1, Duration.ofMinutes(10), NOW));
    }

    private void afterLeaseExpiry(String mapName, String key, Function<PrivacyCluster, Boolean> acquire)
            throws Exception {
        PrivacyCluster[] members = TestMembers.pair(TestMembers.uniqueName("counter-lease"), OptionalInt.empty());
        CountDownLatch paused = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        try (var caller = Executors.newSingleThreadExecutor()) {
            IMap<String, Object> map = members[0].instance().getMap(mapName);
            AtomicBoolean first = new AtomicBoolean(true);
            // Pause after the old client's read, or before the atomic processor in the fixed path.
            Object delayedMap = Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{IMap.class},
                    (proxy, method, args) -> {
                        boolean stop = (method.getName().equals("get") || method.getName().equals("getOrDefault")
                                || method.getName().equals("executeOnKey")) && first.compareAndSet(true, false);
                        Object result = null;
                        if (!method.getName().equals("executeOnKey")) result = invoke(method, map, args);
                        if (stop) {
                            paused.countDown();
                            if (!resume.await(10, TimeUnit.SECONDS)) throw new AssertionError("caller never resumed");
                        }
                        return method.getName().equals("executeOnKey") ? invoke(method, map, args) : result;
                    });
            HazelcastInstance delayedInstance = (HazelcastInstance) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{HazelcastInstance.class}, (proxy, method, args) ->
                            method.getName().equals("getMap") && args[0].equals(mapName)
                                    ? delayedMap : invoke(method, members[0].instance(), args));
            PrivacyCluster delayed = PrivacyCluster.using(delayedInstance, false);
            var stalled = caller.submit(() -> acquire.apply(delayed));
            try {
                assertThat(paused.await(10, TimeUnit.SECONDS)).isTrue();
                // Acquire the same key from the other member: success proves the original lease expired.
                IMap<String, Object> otherMap = members[1].instance().getMap(mapName);
                assertThat(otherMap.tryLock(key, 5, TimeUnit.SECONDS)).isTrue();
                try {
                    assertThat(acquire.apply(members[1])).isTrue();
                } finally {
                    otherMap.unlock(key);
                }
                resume.countDown();
                assertThat(stalled.get(10, TimeUnit.SECONDS)).isFalse();
                assertThat(acquire.apply(members[1])).isFalse();
            } finally {
                resume.countDown();
            }
        } finally {
            resume.countDown();
            for (PrivacyCluster member : members) member.close();
        }
    }

    private static Object invoke(Method method, Object target, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException failure) {
            throw failure.getCause();
        }
    }
}

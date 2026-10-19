package io.github.aindriub.dataprism.hazelcast;

import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.map.IMap;
import io.github.aindriub.dataprism.core.refusal.ClusterLockTimeoutException;
import io.github.aindriub.dataprism.oversight.ApprovalRefusedException;
import io.github.aindriub.dataprism.oversight.ApprovalRequest;
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

import static io.github.aindriub.dataprism.hazelcast.TestMembers.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PendingCapLockTest {
    private static final Duration TIMEOUT = Duration.ofMillis(100);

    @Test void aCallerPausedAfterCountingCannotOutliveRequesterLockOwnership() throws Exception {
        PrivacyCluster[] members = TestMembers.pair(TestMembers.uniqueName("pending-cap-lock"), OptionalInt.empty());
        CountDownLatch counted = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        try (var caller = Executors.newSingleThreadExecutor()) {
            IMap<String, String> map = members[0].instance().getMap(PrivacyCluster.APPROVAL_MAP);
            AtomicBoolean first = new AtomicBoolean(true);
            Object delayedMap = Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{IMap.class},
                    (proxy, method, args) -> {
                        Object snapshot = invoke(method, map, args);
                        if (method.getName().equals("values") && first.compareAndSet(true, false)) {
                            counted.countDown();
                            if (!resume.await(10, TimeUnit.SECONDS)) throw new AssertionError("caller never resumed");
                        }
                        return snapshot;
                    });
            HazelcastInstance delayedInstance = (HazelcastInstance) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{HazelcastInstance.class}, (proxy, method, args) ->
                            method.getName().equals("getMap") && args[0].equals(PrivacyCluster.APPROVAL_MAP)
                                    ? delayedMap : invoke(method, members[0].instance(), args));
            var delayed = new HazelcastApprovalStore(PrivacyCluster.using(delayedInstance, false), TIMEOUT);
            var other = new HazelcastApprovalStore(members[1], TIMEOUT);
            ApprovalRequest a = TestMembers.request("request-a", "scope-a", ApprovalRequest.Status.PENDING);
            ApprovalRequest b = TestMembers.request("request-b", "scope-b", ApprovalRequest.Status.PENDING);
            var stalled = caller.submit(() -> delayed.create(a, 1));
            try {
                assertThat(counted.await(10, TimeUnit.SECONDS)).isTrue();
                // Keep A stopped beyond the former 500 ms lease, without relying on scheduler timing.
                assertThat(resume.await(ClusterLocks.leaseMillis(TIMEOUT) + 100, TimeUnit.MILLISECONDS)).isFalse();
                // Lease expiry is processed asynchronously. Wait for actual ownership transfer on the old path,
                // rather than assuming it happens exactly at 500 ms. The retained lock must never transfer.
                IMap<String, String> otherMap = members[1].instance().getMap(PrivacyCluster.APPROVAL_MAP);
                String requesterKey = HazelcastApprovalStore.requesterLockKey(a.kind(), a.requesterPrincipalId());
                boolean acquired = otherMap.tryLock(requesterKey, 3, TimeUnit.SECONDS);
                try {
                    if (acquired) {
                        other.create(b, 1);
                    } else {
                        assertThatThrownBy(() -> other.create(b, 1)).isInstanceOf(ClusterLockTimeoutException.class);
                    }
                } finally {
                    if (acquired) otherMap.unlock(requesterKey);
                }
                resume.countDown();
                assertThat(stalled.get(10, TimeUnit.SECONDS)).isEqualTo(a);
                assertThat(other.pending(NOW)).containsExactly(a);
                assertThat(other.find(b.approvalId())).isEmpty();
                assertThatThrownBy(() -> other.create(b, 1)).isInstanceOf(ApprovalRefusedException.class)
                        .satisfies(failure -> assertThat(((ApprovalRefusedException) failure).code())
                                .isEqualTo(ApprovalRefusedException.Code.TOO_MANY_PENDING));
                other.approve(a.approvalId(), "bob", NOW);
                assertThat(other.create(b, 1)).isEqualTo(b);
                assertThat(other.pending(NOW)).containsExactly(b);
                assertThat(map.isLocked(HazelcastApprovalStore.requesterLockKey(a.kind(), a.requesterPrincipalId())))
                        .isFalse();
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

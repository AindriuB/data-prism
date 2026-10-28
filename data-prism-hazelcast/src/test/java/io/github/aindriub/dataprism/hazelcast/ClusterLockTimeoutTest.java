package io.github.aindriub.dataprism.hazelcast;

import com.hazelcast.map.IMap;
import io.github.aindriub.dataprism.core.refusal.ClusterLockTimeoutException;
import io.github.aindriub.dataprism.core.refusal.PrivacyRefusedException;
import io.github.aindriub.dataprism.core.refusal.RefusalCodes;
import io.github.aindriub.dataprism.oversight.ApprovalRequest;
import io.github.aindriub.dataprism.oversight.ApprovalRequest.Kind;
import io.github.aindriub.dataprism.oversight.ApprovalRequest.Status;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.OptionalInt;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import static io.github.aindriub.dataprism.hazelcast.TestMembers.NOW;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * A lock someone else holds must make a call refuse within the lock timeout, never hang the request
 * thread, and must leave the counters and approvals as they were.
 */
class ClusterLockTimeoutTest {

    private static final Duration TIMEOUT = Duration.ofMillis(300);

    private PrivacyCluster[] members;
    private PrivacyCluster cluster;
    private HazelcastApprovalStore approvals;
    private HazelcastScopeBudget budget;
    private HazelcastCallerRateLimiter limiter;
    private ExecutorService caller;

    @BeforeEach
    void start() {
        members = TestMembers.pair(TestMembers.uniqueName("locks"), OptionalInt.empty());
        cluster = members[0];
        // The first member writes its sentinel and card before the second joins, so partitions are still moving to
        // the second member when the pair returns. A lock wait measured meanwhile includes the migration.
        assertThat(MemberCardTest.Fixture.await(30, () -> cluster.instance().getPartitionService().isClusterSafe()))
                .as("the partitions settled").isTrue();
        approvals = new HazelcastApprovalStore(cluster, TIMEOUT);
        budget = new HazelcastScopeBudget(cluster, TIMEOUT);
        limiter = new HazelcastCallerRateLimiter(cluster, TIMEOUT);
        caller = Executors.newSingleThreadExecutor();
    }

    @AfterEach
    void stop() {
        caller.shutdownNow();
        for (PrivacyCluster member : members) {
            member.close();
        }
    }

    private interface Call {
        void run() throws Exception;
    }

    /**
     * Holds {@code key} on another thread, runs {@code call} on the caller thread and waits at most
     * three times the timeout. Returns the failure it ended with, which is null if it returned normally.
     * Always releases the lock, so a call that hangs cannot hang the suite.
     */
    private Throwable callWhileLocked(String map, String key, Call call, long[] elapsedMillis) throws Exception {
        IMap<String, Object> held = cluster.instance().getMap(map);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread holder = new Thread(() -> {
            held.lock(key);
            locked.countDown();
            try {
                release.await();
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        });
        holder.start();
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
        long start = System.nanoTime();
        Future<?> running = caller.submit(() -> {
            call.run();
            return null;
        });
        try {
            running.get(3 * TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            return null;
        } catch (ExecutionException failed) {
            return failed.getCause();
        } catch (TimeoutException hung) {
            throw new AssertionError("the call hung on a held lock for more than 3 x the lock timeout");
        } finally {
            elapsedMillis[0] = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            held.forceUnlock(key);
            release.countDown();
            holder.join();
        }
    }

    private void refusesWhileLocked(String map, String key, Call call) throws Exception {
        long[] elapsed = new long[1];
        Throwable failure = callWhileLocked(map, key, call, elapsed);
        assertThat(failure).isInstanceOf(ClusterLockTimeoutException.class);
        assertThat(elapsed[0]).isLessThanOrEqualTo(TIMEOUT.toMillis() + 500);
        assertThat(((ClusterLockTimeoutException) failure).code()).isEqualTo("CLUSTER_LOCK_TIMEOUT");
    }

    private ApprovalRequest seeded(String id, Status status) {
        ApprovalRequest pending = TestMembers.request(id, "scope-1", Status.PENDING);
        approvals.create(pending);
        if (status == Status.APPROVED) {
            approvals.approve(id, "bob", NOW);
        }
        return pending;
    }

    private static String entryKey(String id) {
        return TestMembers.SCOPE_KEYS.approval("scope-1", id);
    }

    @Test
    void aHeldBudgetLockRefusesWithTheClusterLockCodeAndCountsNothing() throws Exception {
        refusesWhileLocked(PrivacyCluster.BUDGET_MAP, TestMembers.SCOPE_KEYS.budget("scope-1", "subject-1"),
                () -> budget.tryRead("scope-1", "subject-1", 5));
        assertThat(budget.reads("scope-1", "subject-1")).isZero();
        assertThat(budget.tryRead("scope-1", "subject-1", 5)).isTrue();
    }

    @Test
    void theBudgetCodeSurvivesSanitising() throws Exception {
        long[] elapsed = new long[1];
        Throwable failure = callWhileLocked(PrivacyCluster.BUDGET_MAP, TestMembers.SCOPE_KEYS.budget("scope-1", "subject-1"),
                () -> budget.tryRead("scope-1", "subject-1", 5), elapsed);
        assertThat(failure).isInstanceOf(PrivacyRefusedException.class);
        assertThat(RefusalCodes.sanitise(((PrivacyRefusedException) failure).code()))
                .isEqualTo("CLUSTER_LOCK_TIMEOUT");
    }

    @Test
    void aBudgetClusterFailureThatIsNotATimeoutStillRefusesTheRead() {
        HazelcastScopeBudget broken = new HazelcastScopeBudget(cluster, TIMEOUT);
        cluster.instance().shutdown();
        assertThat(broken.tryRead("scope-1", "subject-1", 5)).isFalse();
    }

    @Test
    void aHeldRateLimiterLockRefusesAndCountsNothing() throws Exception {
        refusesWhileLocked(PrivacyCluster.CALLER_RATE_MAP, TestMembers.SCOPE_KEYS.callerRate("alice"),
                () -> limiter.tryAcquire("alice", 1, Duration.ofMinutes(10), NOW));
        assertThat(limiter.tryAcquire("alice", 1, Duration.ofMinutes(10), NOW)).isTrue();
        assertThat(limiter.tryAcquire("alice", 1, Duration.ofMinutes(10), NOW)).isFalse();
    }

    @Test
    void aHeldEntryLockRefusesConsumeAndLeavesTheApprovalApproved() throws Exception {
        seeded("consume-1", Status.APPROVED);
        refusesWhileLocked(PrivacyCluster.APPROVAL_MAP, entryKey("consume-1"),
                () -> approvals.consumeApproved(Kind.TOOL_CALL, "alice", "scope-1", "search", "fp-consume-1", NOW));
        assertThat(approvals.find("consume-1")).get().extracting(ApprovalRequest::status).isEqualTo(Status.APPROVED);
        assertThat(approvals.consumeApproved(Kind.TOOL_CALL, "alice", "scope-1", "search", "fp-consume-1", NOW))
                .isPresent();
    }

    @Test
    void aHeldEntryLockRefusesApproveAndLeavesTheApprovalPending() throws Exception {
        seeded("approve-1", Status.PENDING);
        refusesWhileLocked(PrivacyCluster.APPROVAL_MAP, entryKey("approve-1"),
                () -> approvals.approve("approve-1", "bob", NOW));
        assertThat(approvals.find("approve-1")).get().extracting(ApprovalRequest::status).isEqualTo(Status.PENDING);
    }

    @Test
    void aHeldEntryLockRefusesRejectAndLeavesTheApprovalPending() throws Exception {
        seeded("reject-1", Status.PENDING);
        refusesWhileLocked(PrivacyCluster.APPROVAL_MAP, entryKey("reject-1"),
                () -> approvals.reject("reject-1", "bob", NOW));
        assertThat(approvals.find("reject-1")).get().extracting(ApprovalRequest::status).isEqualTo(Status.PENDING);
    }

    @Test
    void aHeldIdClaimLockRefusesCreateAndCreatesNothing() throws Exception {
        refusesWhileLocked(PrivacyCluster.APPROVAL_ID_MAP, TestMembers.SCOPE_KEYS.claim("create-1"),
                () -> approvals.create(TestMembers.request("create-1", "scope-1", Status.PENDING)));
        assertThat(approvals.find("create-1")).isEmpty();
    }

    @Test
    void aHeldEntryKeyLockRefusesCreateAndCreatesNothing() throws Exception {
        refusesWhileLocked(PrivacyCluster.APPROVAL_MAP, entryKey("create-3"),
                () -> approvals.create(TestMembers.request("create-3", "scope-1", Status.PENDING)));
        assertThat(approvals.find("create-3")).isEmpty();
        assertThat(cluster.instance().getMap(PrivacyCluster.APPROVAL_ID_MAP).containsKey(TestMembers.SCOPE_KEYS.claim("create-3"))).isFalse();
    }

    @Test
    void aHeldPendingEntryLockRefusesCappedCreateAndCreatesNothing() throws Exception {
        ApprovalRequest request = TestMembers.request("create-2", "scope-1", Status.PENDING);
        refusesWhileLocked(PrivacyCluster.APPROVAL_PENDING_MAP,
                TestMembers.SCOPE_KEYS.pending(request.kind(), request.requesterPrincipalId()),
                () -> approvals.create(request, 5));
        assertThat(approvals.find("create-2")).isEmpty();
        assertThat(cluster.instance().getMap(PrivacyCluster.APPROVAL_ID_MAP).containsKey(TestMembers.SCOPE_KEYS.claim("create-2"))).isFalse();
    }

    @Test
    void successfulCallsLeaveNoLockBehind() {
        seeded("clean-1", Status.APPROVED);
        approvals.consumeApproved(Kind.TOOL_CALL, "alice", "scope-1", "search", "fp-clean-1", NOW);
        approvals.create(TestMembers.request("clean-2", "scope-1", Status.PENDING), 5);
        approvals.reject("clean-2", "bob", NOW);
        budget.tryRead("scope-1", "subject-1", 5);
        limiter.tryAcquire("alice", 5, Duration.ofMinutes(10), NOW);
        IMap<String, Object> approvalMap = cluster.instance().getMap(PrivacyCluster.APPROVAL_MAP);
        assertThat(approvalMap.isLocked(entryKey("clean-1"))).isFalse();
        assertThat(approvalMap.isLocked(entryKey("clean-2"))).isFalse();
        assertThat(approvalMap.isLocked("clean-2")).isFalse();
        assertThat(cluster.instance().getMap(PrivacyCluster.APPROVAL_ID_MAP).isLocked(TestMembers.SCOPE_KEYS.claim("clean-2"))).isFalse();
        assertThat(cluster.instance().getMap(PrivacyCluster.APPROVAL_PENDING_MAP)
                .isLocked(TestMembers.SCOPE_KEYS.pending(Kind.TOOL_CALL, "alice"))).isFalse();
        assertThat(cluster.instance().getMap(PrivacyCluster.BUDGET_MAP)
                .isLocked(TestMembers.SCOPE_KEYS.budget("scope-1", "subject-1"))).isFalse();
        assertThat(cluster.instance().getMap(PrivacyCluster.CALLER_RATE_MAP).isLocked(TestMembers.SCOPE_KEYS.callerRate("alice"))).isFalse();
    }

    @Test
    void aTimeoutBelowTheMinimumOrAboveTheMaximumIsRefused() {
        for (Duration bad : new Duration[]{Duration.ofMillis(99), Duration.ofSeconds(31), Duration.ZERO,
                Duration.ofMillis(-1)}) {
            org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                    () -> new HazelcastApprovalStore(cluster, bad));
            org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                    () -> new HazelcastScopeBudget(cluster, bad));
            org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                    () -> new HazelcastCallerRateLimiter(cluster, bad));
        }
        new HazelcastApprovalStore(cluster, Duration.ofMillis(100));
        new HazelcastApprovalStore(cluster, Duration.ofSeconds(30));
    }

    @Test
    void deleteAllSharesOneDeadlineAcrossHeldKeys() throws Exception {
        IMap<String, Object> map = cluster.instance().getMap("deleteall-deadline");
        map.put("k1", 1);
        map.put("k2", 2);
        map.put("k3", 3);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread holder = new Thread(() -> {
            map.lock("k1");
            map.lock("k2");
            locked.countDown();
            try {
                release.await();
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        });
        holder.start();
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
        try {
            long start = System.nanoTime();
            int skipped = ClusterLocks.deleteAll(map, java.util.List.of("k1", "k2", "k3"), Duration.ofSeconds(1));
            long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            assertThat(skipped).isEqualTo(2);
            assertThat(elapsed).isLessThan(1500);
            assertThat(map.containsKey("k3")).isFalse();
        } finally {
            map.forceUnlock("k1");
            map.forceUnlock("k2");
            release.countDown();
            holder.join();
        }
    }
}

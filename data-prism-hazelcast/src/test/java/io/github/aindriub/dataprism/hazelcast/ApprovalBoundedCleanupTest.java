package io.github.aindriub.dataprism.hazelcast;

import com.hazelcast.core.EntryView;
import com.hazelcast.map.IMap;
import io.github.aindriub.dataprism.core.metrics.Metric;
import io.github.aindriub.dataprism.core.refusal.ClusterLockTimeoutException;
import io.github.aindriub.dataprism.core.refusal.ClusterOperationRefusedException;
import io.github.aindriub.dataprism.oversight.ApprovalRefusedException;
import io.github.aindriub.dataprism.oversight.ApprovalRefusedException.Code;
import io.github.aindriub.dataprism.oversight.ApprovalRequest;
import io.github.aindriub.dataprism.oversight.ApprovalRequest.Kind;
import io.github.aindriub.dataprism.oversight.ApprovalRequest.Status;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static io.github.aindriub.dataprism.hazelcast.TestMembers.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * After a decision has committed, and while a scope ends, no call waits for a lock beyond the lock timeout: a held
 * pending key makes a release skip (an over-count the sweep removes), never a hang and never a failed decision, and
 * a held approval entry makes the scope end refuse and leave everything in place. A decision either commits with its
 * expiry or not at all.
 */
class ApprovalBoundedCleanupTest {

    private static final Duration TIMEOUT = Duration.ofMillis(300);
    private static final Duration WITHIN = TIMEOUT.multipliedBy(3);
    private static final Instant LONG_AFTER = NOW.plus(Duration.ofHours(3));

    private PrivacyCluster[] members;
    private ExecutorService pool;
    private RecordingPrivacyMetrics metrics;
    private HazelcastApprovalStore store;

    @BeforeEach
    void start() {
        members = TestMembers.pair(TestMembers.uniqueName("bounded"), OptionalInt.empty());
        pool = Executors.newCachedThreadPool();
        metrics = new RecordingPrivacyMetrics();
        store = new HazelcastApprovalStore(members[0], TIMEOUT, metrics);
    }

    @AfterEach
    void stop() {
        pool.shutdownNow();
        for (PrivacyCluster member : members) {
            member.close();
        }
    }

    private static ApprovalRequest request(String id, String scope, String requester) {
        return new ApprovalRequest(id, Kind.TOOL_CALL, requester, "client", scope, "search", "fp-" + id,
                "PERSON_NAME", "synthetic", "audit", "CASE-1", NOW, NOW.plus(1, ChronoUnit.HOURS), Status.PENDING,
                null, null);
    }

    /** Holds a lock on {@code key} of {@code map} from the other member until the returned latch is counted down. */
    private CountDownLatch hold(String map, String key) throws Exception {
        IMap<String, String> held = members[1].instance().getMap(map);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        pool.submit(() -> {
            held.lock(key);
            locked.countDown();
            try {
                release.await();
            } finally {
                held.unlock(key);
            }
            return null;
        });
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
        return release;
    }

    private <T> T within(Duration bound, Callable<T> call) throws Exception {
        Future<T> running = pool.submit(call);
        try {
            return running.get(bound.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException hung) {
            running.cancel(true);
            throw new AssertionError("the call did not return within " + bound);
        }
    }

    private static String pendingKey() {
        return TestMembers.SCOPE_KEYS.pending(Kind.TOOL_CALL, "alice");
    }

    private void sweepPendingOnBothMembers(Instant now) {
        for (PrivacyCluster member : members) {
            member.sweepPendingApprovals(now);
        }
    }

    private void awaitSlotFreeLock() throws InterruptedException {
        StalledInsert.awaitFree(members[0], PrivacyCluster.APPROVAL_PENDING_MAP, pendingKey());
    }

    // ----- approve and reject behind a held pending lock

    @ParameterizedTest
    @ValueSource(strings = {"approve", "reject"})
    void aDecisionBehindAHeldPendingLockCommitsAndReturnsWithinTheBoundAndOnlyOverCounts(String decision)
            throws Exception {
        store.create(request("a-1", "scope-a", "alice"), 1);
        CountDownLatch release = hold(PrivacyCluster.APPROVAL_PENDING_MAP, pendingKey());
        try {
            ApprovalRequest decided = within(WITHIN, () -> decision.equals("approve")
                    ? store.approve("a-1", "bob", NOW.plusSeconds(1))
                    : store.reject("a-1", "bob", NOW.plusSeconds(1)));
            Status expected = decision.equals("approve") ? Status.APPROVED : Status.REJECTED;
            assertThat(decided.status()).isEqualTo(expected);
            assertThat(store.find("a-1")).get().extracting(ApprovalRequest::status).isEqualTo(expected);
            assertThat(metrics.countOf(Metric.APPROVAL_RELEASE_SKIPPED)).isEqualTo(1);
        } finally {
            release.countDown();
        }
        awaitSlotFreeLock();
        // The slot is still counted: a new create for alice at cap 1 is refused until the sweep has run.
        assertThatThrownBy(() -> store.create(request("a-2", "scope-a", "alice"), 1))
                .isInstanceOf(ApprovalRefusedException.class)
                .satisfies(failure -> assertThat(((ApprovalRefusedException) failure).code())
                        .isEqualTo(Code.TOO_MANY_PENDING));
        sweepPendingOnBothMembers(LONG_AFTER);
        assertThat(store.create(request("a-2", "scope-a", "alice"), 1).approvalId()).isEqualTo("a-2");
    }

    @Test
    void aDecisionWhoseLockIsFreeReleasesItsSlotAndCountsNothingSkipped() throws Exception {
        store.create(request("a-1", "scope-a", "alice"), 1);
        store.approve("a-1", "bob", NOW.plusSeconds(1));
        assertThat(metrics.countOf(Metric.APPROVAL_RELEASE_SKIPPED)).isZero();
        assertThat(store.create(request("a-2", "scope-a", "alice"), 1).approvalId()).isEqualTo("a-2");
    }

    @Test
    void anExpiredDecisionBehindAHeldPendingLockStillRefusesExpiredWithinTheBound() throws Exception {
        store.create(request("a-1", "scope-a", "alice"), 1);
        CountDownLatch release = hold(PrivacyCluster.APPROVAL_PENDING_MAP, pendingKey());
        try {
            Future<?> deciding = pool.submit(() -> store.approve("a-1", "bob", LONG_AFTER));
            assertThatThrownBy(() -> deciding.get(WITHIN.toMillis(), TimeUnit.MILLISECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .satisfies(failure -> assertThat(((ApprovalRefusedException) failure.getCause()).code())
                            .isEqualTo(Code.EXPIRED));
            assertThat(metrics.countOf(Metric.APPROVAL_RELEASE_SKIPPED)).isEqualTo(1);
        } finally {
            release.countDown();
        }
    }

    // ----- forget and endScope

    @Test
    void forgetBehindAHeldApprovalLockRefusesWithinTheBoundAndLeavesTheEntryItsClaimAndItsSlot() throws Exception {
        store.create(request("f-1", "scope-f", "alice"), 5);
        store.create(request("f-2", "scope-f", "carol"), 5);
        String heldKey = TestMembers.SCOPE_KEYS.approval("scope-f", "f-1");
        CountDownLatch release = hold(PrivacyCluster.APPROVAL_MAP, heldKey);
        try {
            Duration bound = WITHIN.multipliedBy(2);
            assertThatThrownBy(() -> within(bound, () -> {
                store.forgetScope("scope-f");
                return null;
            })).isInstanceOf(ExecutionException.class).hasCauseInstanceOf(ClusterLockTimeoutException.class);

            IMap<String, String> approvals = members[0].instance().getMap(PrivacyCluster.APPROVAL_MAP);
            IMap<String, String> claims = members[0].instance().getMap(PrivacyCluster.APPROVAL_ID_MAP);
            IMap<String, String> slots = members[0].instance().getMap(PrivacyCluster.APPROVAL_PENDING_MAP);
            assertThat(approvals.containsKey(heldKey)).isTrue();
            assertThat(ClaimEntries.read(claims.get(TestMembers.SCOPE_KEYS.claim("f-1")))).isPresent();
            assertThat(PendingSet.decode(slots.get(pendingKey()))).containsOnlyKeys("f-1");
            // The entry whose lock was free was forgotten, completely.
            assertThat(approvals.containsKey(TestMembers.SCOPE_KEYS.approval("scope-f", "f-2"))).isFalse();
            assertThat(claims.containsKey(TestMembers.SCOPE_KEYS.claim("f-2"))).isFalse();
            assertThat(slots.containsKey(TestMembers.SCOPE_KEYS.pending(Kind.TOOL_CALL, "carol"))).isFalse();
        } finally {
            release.countDown();
        }
    }

    @Test
    void endScopeBehindAHeldApprovalLockRefusesWithinTheBoundAndLeavesTheApproval() throws Exception {
        store.create(request("f-1", "scope-f", "alice"), 5);
        String heldKey = TestMembers.SCOPE_KEYS.approval("scope-f", "f-1");
        CountDownLatch release = hold(PrivacyCluster.APPROVAL_MAP, heldKey);
        try {
            var index = new ScopeIdentityIndex(members[0], metrics, TIMEOUT);
            assertThatThrownBy(() -> within(WITHIN, () -> {
                index.endScope("scope-f");
                return null;
            })).isInstanceOf(ExecutionException.class).hasCauseInstanceOf(ClusterLockTimeoutException.class);
            assertThat(members[0].instance().getMap(PrivacyCluster.APPROVAL_MAP).containsKey(heldKey)).isTrue();
            assertThat(members[0].instance().getMap(PrivacyCluster.APPROVAL_ID_MAP)
                .containsKey(TestMembers.SCOPE_KEYS.claim("f-1"))).isTrue();
            assertThat(members[0].instance().getMap(PrivacyCluster.APPROVAL_PENDING_MAP).containsKey(pendingKey()))
                    .isTrue();
        } finally {
            release.countDown();
        }
        // Once the lock is free the scope can be ended.
        new ScopeIdentityIndex(members[0], metrics, TIMEOUT).endScope("scope-f");
        assertThat(store.find("f-1")).isEmpty();
    }

    @Test
    void forgetBehindAHeldPendingKeyRemovesTheEntryKeepsTheSlotCountedAndReportsTheSkippedRelease() throws Exception {
        store.create(request("x-1", "scope-a", "alice"), 1);
        CountDownLatch release = hold(PrivacyCluster.APPROVAL_PENDING_MAP, pendingKey());
        try {
            assertThatThrownBy(() -> within(WITHIN, () -> {
                store.forgetScope("scope-a");
                return null;
            })).hasRootCauseInstanceOf(ClusterLockTimeoutException.class);
            assertThat(members[0].instance().getMap(PrivacyCluster.APPROVAL_MAP)
                    .containsKey(TestMembers.SCOPE_KEYS.approval("scope-a", "x-1"))).isFalse();
            assertThat(metrics.countOf(Metric.APPROVAL_RELEASE_SKIPPED)).isEqualTo(1);
        } finally {
            release.countDown();
        }
        awaitSlotFreeLock();
        assertThatThrownBy(() -> store.create(request("x-2", "scope-a", "alice"), 1))
                .isInstanceOf(ApprovalRefusedException.class);
        sweepPendingOnBothMembers(LONG_AFTER);
        // The id and the slot are free again.
        assertThat(store.create(request("x-1", "scope-a", "alice"), 1).approvalId()).isEqualTo("x-1");
    }

    @Test
    void forgetWhoseGuardLockIsRefusedByTheClusterThrowsTheRefusalNotATimeoutAndStillReleasesTheClaim()
            throws Exception {
        StalledInsert injector = StalledInsert.passive().rejecting(PrivacyCluster.APPROVAL_PENDING_MAP, "tryLock");
        var faulty = new HazelcastApprovalStore(injector.over(members[0]), TIMEOUT, metrics);
        faulty.create(request("k-1", "scope-k", "alice"), 5);
        injector.arm();

        assertThatThrownBy(() -> faulty.forgetScope("scope-k"))
                .isInstanceOf(ClusterOperationRefusedException.class);
        assertThat(members[0].instance().getMap(PrivacyCluster.APPROVAL_MAP)
                .containsKey(TestMembers.SCOPE_KEYS.approval("scope-k", "k-1"))).isFalse();
        assertThat(members[0].instance().getMap(PrivacyCluster.APPROVAL_PENDING_MAP)
                .containsKey(pendingKey())).isTrue();
        assertThat(metrics.countOf(Metric.APPROVAL_RELEASE_SKIPPED)).isEqualTo(1);
        assertThat(members[0].instance().getMap(PrivacyCluster.APPROVAL_ID_MAP)
                .containsKey(TestMembers.SCOPE_KEYS.claim("k-1"))).isFalse();
    }

    @Test
    void forgetWhoseSlotReleaseFailsWithAClusterFaultThrowsTheRefusalNotATimeout() {
        StalledInsert injector = StalledInsert.passive().inactive(PrivacyCluster.APPROVAL_PENDING_MAP, "executeOnKey");
        var faulty = new HazelcastApprovalStore(injector.over(members[0]), TIMEOUT, metrics);
        faulty.create(request("e-1", "scope-e", "alice"), 5);
        injector.arm();

        assertThatThrownBy(() -> faulty.forgetScope("scope-e"))
                .isInstanceOf(ClusterOperationRefusedException.class);
        assertThat(metrics.countOf(Metric.APPROVAL_RELEASE_SKIPPED)).isEqualTo(1);
    }

    @Test
    void forgetWhoseGuardLockFailsWithAClusterFaultThrowsTheRefusalNotATimeout() {
        StalledInsert injector = StalledInsert.passive().inactive(PrivacyCluster.APPROVAL_PENDING_MAP, "tryLock");
        var faulty = new HazelcastApprovalStore(injector.over(members[0]), TIMEOUT, metrics);
        faulty.create(request("n-1", "scope-n", "alice"), 5);
        injector.arm();

        assertThatThrownBy(() -> faulty.forgetScope("scope-n"))
                .isInstanceOf(ClusterOperationRefusedException.class);
        assertThat(metrics.countOf(Metric.APPROVAL_RELEASE_SKIPPED)).isEqualTo(1);
    }

    @Test
    void aDecisionWhoseGuardLockIsRefusedByTheClusterStillCommitsCountsTheSkipAndKeepsTheSlot() {
        StalledInsert injector = StalledInsert.passive().rejecting(PrivacyCluster.APPROVAL_PENDING_MAP, "tryLock");
        var faulty = new HazelcastApprovalStore(injector.over(members[0]), TIMEOUT, metrics);
        faulty.create(request("d-1", "scope-d", "alice"), 1);
        injector.arm();

        ApprovalRequest decided = faulty.approve("d-1", "bob", NOW.plusSeconds(1));

        assertThat(decided.status()).isEqualTo(Status.APPROVED);
        assertThat(metrics.countOf(Metric.APPROVAL_RELEASE_SKIPPED)).isEqualTo(1);
        assertThatThrownBy(() -> store.create(request("d-2", "scope-d", "alice"), 1))
                .isInstanceOf(ApprovalRefusedException.class)
                .satisfies(failure -> assertThat(((ApprovalRefusedException) failure).code())
                        .isEqualTo(Code.TOO_MANY_PENDING));
    }

    @Test
    void forgetOfAnUnreadableEntryRemovesItCountsItAndReportsThatItsClaimAndSlotWereLeft() {
        IMap<String, String> approvals = members[0].instance().getMap(PrivacyCluster.APPROVAL_MAP);
        String key = TestMembers.SCOPE_KEYS.approval("scope-u", "u-1");
        approvals.put(key, "garbage");

        assertThatThrownBy(() -> store.forgetScope("scope-u")).isInstanceOf(ClusterLockTimeoutException.class);
        assertThat(approvals.containsKey(key)).isFalse();
        assertThat(metrics.countOf(Metric.APPROVAL_RELEASE_SKIPPED)).isEqualTo(1);
    }

    @Test
    void forgetWithEveryGuardLockHeldFinishesInAboutOneTimeoutAndReportsTheSkips() throws Exception {
        String[] requesters = {"alice", "bob", "carol"};
        List<CountDownLatch> releases = new ArrayList<>();
        for (String requester : requesters) {
            store.create(request("h-" + requester, "scope-h", requester), 5);
            releases.add(hold(PrivacyCluster.APPROVAL_PENDING_MAP, TestMembers.SCOPE_KEYS.pending(Kind.TOOL_CALL,
                    requester)));
            releases.add(hold(PrivacyCluster.APPROVAL_ID_MAP, TestMembers.SCOPE_KEYS.claim("h-" + requester)));
        }
        try {
            // Six held guard locks, each a full timeout if it were waited for on its own: about 1.8 s.
            assertThatThrownBy(() -> within(WITHIN, () -> {
                store.forgetScope("scope-h");
                return null;
            })).hasRootCauseInstanceOf(ClusterLockTimeoutException.class);
            assertThat(metrics.countOf(Metric.APPROVAL_RELEASE_SKIPPED)).isEqualTo(6);
            assertThat(members[0].instance().getMap(PrivacyCluster.APPROVAL_MAP).keySet()).isEmpty();
        } finally {
            releases.forEach(CountDownLatch::countDown);
        }
    }

    @Test
    void forgetPausedAfterItsDeleteLeavesAReCreationInTheSameScopeItsClaimAndItsSlot() throws Exception {
        var other = new HazelcastApprovalStore(members[1], TIMEOUT);
        store.create(request("x-1", "scope-a", "alice"), 5);
        CountDownLatch release = hold(PrivacyCluster.APPROVAL_PENDING_MAP, pendingKey());
        Future<?> forgetting = pool.submit(() -> {
            store.forgetScope("scope-a");
            return null;
        });
        IMap<String, String> approvals = members[0].instance().getMap(PrivacyCluster.APPROVAL_MAP);
        String key = TestMembers.SCOPE_KEYS.approval("scope-a", "x-1");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (approvals.containsKey(key) && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertThat(approvals.containsKey(key)).isFalse();
        // The same id again in the same scope, for a requester whose pending key is free.
        other.create(request("x-1", "scope-a", "carol"), 5);
        release.countDown();
        forgetting.get(10, TimeUnit.SECONDS);

        String token = HazelcastApprovalStore.Codec.tokenOf(approvals.get(key));
        IMap<String, String> claims = members[0].instance().getMap(PrivacyCluster.APPROVAL_ID_MAP);
        assertThat(claims.get(TestMembers.SCOPE_KEYS.claim("x-1"))).isEqualTo(ClaimEntries.value(key, token));
        IMap<String, String> slots = members[0].instance().getMap(PrivacyCluster.APPROVAL_PENDING_MAP);
        assertThat(PendingSet.decode(slots.get(TestMembers.SCOPE_KEYS.pending(Kind.TOOL_CALL, "carol"))))
                .containsOnlyKeys("x-1");
        assertThatThrownBy(() -> other.create(request("x-1", "scope-b", "dave"), 5))
                .isExactlyInstanceOf(IllegalArgumentException.class).hasMessage("duplicate approval id");
        assertThat(TestMembers.keysOfApproval(approvals, "x-1")).hasSize(1);
    }

    // ----- the pending sweep

    @Test
    void thePendingSweepSkipsAKeyWhoseLockIsHeldAndTakesItOnceItIsFree() throws Exception {
        store.create(request("s-1", "scope-a", "alice"), 5);
        CountDownLatch release = hold(PrivacyCluster.APPROVAL_PENDING_MAP, pendingKey());
        try {
            int removed = within(WITHIN, () -> members[0].sweepPendingApprovals(LONG_AFTER)
                    + members[1].sweepPendingApprovals(LONG_AFTER));
            assertThat(removed).isZero();
            assertThat(members[0].instance().getMap(PrivacyCluster.APPROVAL_PENDING_MAP).containsKey(pendingKey()))
                    .isTrue();
        } finally {
            release.countDown();
        }
        awaitSlotFreeLock();
        assertThat(members[0].sweepPendingApprovals(LONG_AFTER) + members[1].sweepPendingApprovals(LONG_AFTER))
                .isEqualTo(1);
    }

    // ----- a decision commits with its expiry, or not at all

    private static long ttlOf(IMap<String, String> map, String key) {
        EntryView<String, String> view = map.getEntryView(key);
        assertThat(view).isNotNull();
        return view.getTtl();
    }

    @ParameterizedTest
    @ValueSource(strings = {"setTtl", "replace", "executeOnKey"})
    void aFailingStepNeverLeavesAStatusChangedWhileTheCallThrew(String failingCall) throws Exception {
        StalledInsert injector = new StalledInsert().failing(PrivacyCluster.APPROVAL_MAP, failingCall);
        var faulty = new HazelcastApprovalStore(injector.over(members[0]), TIMEOUT);
        var healthy = new HazelcastApprovalStore(members[1], TIMEOUT);
        IMap<String, String> approvals = members[1].instance().getMap(PrivacyCluster.APPROVAL_MAP);
        healthy.create(request("t-approve", "scope-t", "alice"));
        healthy.create(request("t-reject", "scope-t", "carol"));
        healthy.create(request("t-consume", "scope-t", "dave"));
        healthy.approve("t-consume", "bob", NOW.plusSeconds(1));
        injector.arm();
        Instant now = NOW.plusSeconds(1);
        long expectedTtl = Duration.between(now, NOW.plus(1, ChronoUnit.HOURS)).toMillis();

        record Attempt(String id, Status before, Status after, Callable<Object> call) {
        }
        for (Attempt attempt : new Attempt[]{
                new Attempt("t-approve", Status.PENDING, Status.APPROVED,
                        () -> faulty.approve("t-approve", "bob", now)),
                new Attempt("t-reject", Status.PENDING, Status.REJECTED, () -> faulty.reject("t-reject", "bob", now)),
                new Attempt("t-consume", Status.APPROVED, Status.CONSUMED, () -> faulty.consumeApproved(
                        Kind.TOOL_CALL, "dave", "scope-t", "search", "fp-t-consume", now).orElseThrow())}) {
            boolean threw = false;
            try {
                attempt.call().call();
            } catch (RuntimeException failure) {
                threw = true;
            }
            Status status = healthy.find(attempt.id()).orElseThrow().status();
            String key = TestMembers.SCOPE_KEYS.approval("scope-t", attempt.id());
            if (threw) {
                assertThat(status).as("%s after a call that threw", attempt.id()).isEqualTo(attempt.before());
            } else {
                assertThat(status).as("%s after a call that returned", attempt.id()).isEqualTo(attempt.after());
                assertThat(ttlOf(approvals, key)).as("%s expiry", attempt.id()).isEqualTo(expectedTtl);
            }
            if (failingCall.equals("setTtl") || failingCall.equals("replace")) {
                // These calls are no longer part of a decision, so the decision goes through with its expiry.
                assertThat(threw).as("%s threw", attempt.id()).isFalse();
            } else {
                assertThat(threw).as("%s threw", attempt.id()).isTrue();
            }
        }
    }

    @Test
    void anExpireSwapThatLosesToAConcurrentDecisionRefusesNotPendingAndReleasesNothing() throws Exception {
        StalledInsert stall = StalledInsert.beforeDecisionCommit();
        var loser = new HazelcastApprovalStore(stall.over(members[0]), TIMEOUT, metrics);
        var winner = new HazelcastApprovalStore(members[1], TIMEOUT);
        loser.create(request("e-1", "scope-e", "alice"), 5);
        Future<?> expiring = pool.submit(() -> loser.approve("e-1", "bob", LONG_AFTER));
        try {
            assertThat(stall.reached.await(10, TimeUnit.SECONDS)).isTrue();
            // The stopped call holds the entry lock only until its lease runs out; then another member decides.
            StalledInsert.awaitFree(members[1], PrivacyCluster.APPROVAL_MAP,
                    TestMembers.SCOPE_KEYS.approval("scope-e", "e-1"));
            assertThat(winner.approve("e-1", "carol", NOW.plusSeconds(1)).status()).isEqualTo(Status.APPROVED);
            stall.resume();
            assertThatThrownBy(() -> expiring.get(10, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
                    .satisfies(failure -> assertThat(((ApprovalRefusedException) failure.getCause()).code())
                            .isEqualTo(Code.NOT_PENDING));
        } finally {
            stall.resume();
        }
        // The loser made one pending-map call, its reserve at create, and released nothing; the winner's release
        // is the only one, so the slot is gone.
        assertThat(stall.callsTo(PrivacyCluster.APPROVAL_PENDING_MAP, "executeOnKey")).isEqualTo(1);
        assertThat(metrics.countOf(Metric.APPROVAL_RELEASE_SKIPPED)).isZero();
        assertThat(winner.find("e-1")).get().extracting(ApprovalRequest::status).isEqualTo(Status.APPROVED);
        assertThat(members[0].instance().<String, String>getMap(PrivacyCluster.APPROVAL_PENDING_MAP)
                .containsKey(pendingKey())).isFalse();
    }

    // ----- retried commits, removes and lookups after a commit

    @ParameterizedTest
    @ValueSource(strings = {"approve", "reject", "expire"})
    void aDecisionWhoseCommitWasRetriedAfterItAppliedReportsSuccess(String decision) throws Exception {
        StalledInsert injector = StalledInsert.passive().repeating(PrivacyCluster.APPROVAL_MAP, "executeOnKey");
        var retried = new HazelcastApprovalStore(injector.over(members[0]), TIMEOUT, metrics);
        retried.create(request("r-1", "scope-r", "alice"), 1);
        injector.arm();
        switch (decision) {
            case "approve" -> assertThat(retried.approve("r-1", "bob", NOW.plusSeconds(1)).status())
                    .isEqualTo(Status.APPROVED);
            case "reject" -> assertThat(retried.reject("r-1", "bob", NOW.plusSeconds(1)).status())
                    .isEqualTo(Status.REJECTED);
            default -> assertThatThrownBy(() -> retried.approve("r-1", "bob", LONG_AFTER))
                    .isInstanceOf(ApprovalRefusedException.class)
                    .satisfies(failure -> assertThat(((ApprovalRefusedException) failure).code())
                            .isEqualTo(Code.EXPIRED));
        }
        // The decision committed, so its slot was released.
        assertThat(members[0].instance().<String, String>getMap(PrivacyCluster.APPROVAL_PENDING_MAP)
                .containsKey(pendingKey())).isFalse();
        assertThat(metrics.countOf(Metric.APPROVAL_RELEASE_SKIPPED)).isZero();
    }

    @Test
    void aRetriedConsumeStillRefusesBecauseTwoConsumersWriteTheSameValue() throws Exception {
        StalledInsert injector = StalledInsert.passive().repeating(PrivacyCluster.APPROVAL_MAP, "executeOnKey");
        var retried = new HazelcastApprovalStore(injector.over(members[0]), TIMEOUT);
        retried.create(request("c-1", "scope-c", "alice"));
        retried.approve("c-1", "bob", NOW.plusSeconds(1));
        injector.arm();
        assertThat(retried.consumeApproved(Kind.TOOL_CALL, "alice", "scope-c", "search", "fp-c-1",
                NOW.plusSeconds(2))).isEmpty();
    }

    @Test
    void aRemoveThatReportsFalseForAnEntryThatIsGoneReleasesItsGuardsAndIsNotATimeout() throws Exception {
        StalledInsert injector = StalledInsert.passive().repeating(PrivacyCluster.APPROVAL_MAP, "remove");
        var faulty = new HazelcastApprovalStore(injector.over(members[0]), TIMEOUT, metrics);
        faulty.create(request("g-1", "scope-g", "alice"), 5);
        injector.arm();
        faulty.forgetScope("scope-g");
        assertThat(members[0].instance().getMap(PrivacyCluster.APPROVAL_ID_MAP)
                .containsKey(TestMembers.SCOPE_KEYS.claim("g-1"))).isFalse();
        assertThat(members[0].instance().getMap(PrivacyCluster.APPROVAL_PENDING_MAP).containsKey(pendingKey()))
                .isFalse();
    }

    @Test
    void aMapLookupThatFailsAfterTheCommitDoesNotFailTheDecision() throws Exception {
        StalledInsert injector = StalledInsert.passive().failing(PrivacyCluster.APPROVAL_PENDING_MAP, "getMap");
        var faulty = new HazelcastApprovalStore(injector.over(members[0]), TIMEOUT, metrics);
        faulty.create(request("m-1", "scope-m", "alice"), 5);
        injector.arm();
        assertThat(faulty.approve("m-1", "bob", NOW.plusSeconds(1)).status()).isEqualTo(Status.APPROVED);
        assertThat(metrics.countOf(Metric.APPROVAL_RELEASE_SKIPPED)).isEqualTo(1);
    }
}

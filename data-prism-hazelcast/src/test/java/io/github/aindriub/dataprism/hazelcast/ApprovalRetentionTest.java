package io.github.aindriub.dataprism.hazelcast;

import com.hazelcast.config.Config;
import com.hazelcast.core.EntryView;
import com.hazelcast.map.IMap;
import io.github.aindriub.dataprism.oversight.ApprovalRefusedException;
import io.github.aindriub.dataprism.oversight.ApprovalRequest;
import io.github.aindriub.dataprism.oversight.ApprovalRequest.Kind;
import io.github.aindriub.dataprism.oversight.ApprovalRequest.Status;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.OptionalInt;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;

import static io.github.aindriub.dataprism.hazelcast.TestMembers.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Every id claim outlives its approval and never loses to a stale caller; a pending entry has no TTL, so the cap can
 * not be under-counted, and the sweep bounds how long one outlives its approvals.
 */
class ApprovalRetentionTest {

    private static final Duration RETENTION = Duration.ofMinutes(1);
    private static final Instant PAST = Instant.parse("2020-01-01T00:00:00Z");

    private PrivacyCluster cluster;
    private HazelcastApprovalStore store;
    private IMap<String, String> approvals;
    private IMap<String, String> claims;
    private IMap<String, String> pendingSets;

    @BeforeEach
    void start() {
        int port = FreePorts.consecutive(1);
        cluster = TestMembers.member(TestMembers.uniqueName("retention"), port, List.of("127.0.0.1:" + port),
                OptionalInt.empty());
        store = new HazelcastApprovalStore(cluster, Duration.ofMillis(300));
        approvals = cluster.instance().getMap(PrivacyCluster.APPROVAL_MAP);
        claims = cluster.instance().getMap(PrivacyCluster.APPROVAL_ID_MAP);
        pendingSets = cluster.instance().getMap(PrivacyCluster.APPROVAL_PENDING_MAP);
    }

    @AfterEach
    void stop() {
        cluster.close();
    }

    private static long ttl(IMap<String, String> map, String key) {
        EntryView<String, String> view = map.getEntryView(key);
        assertThat(view).as("entry %s", key).isNotNull();
        return view.getTtl();
    }

    private static void awaitTrue(BooleanSupplier condition) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) throw new AssertionError("condition never became true");
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
        }
    }

    private static ApprovalRequest pending(String id, String scope, String requester, Instant created, Duration life) {
        return new ApprovalRequest(id, Kind.TOOL_CALL, requester, "client", scope, "search", "fp-" + id,
                "PERSON_NAME", "synthetic", "audit", "CASE-1", created, created.plus(life), Status.PENDING, null, null);
    }

    private String pendingKey(String requester) {
        return HazelcastApprovalStore.pendingKey(Kind.TOOL_CALL, requester);
    }

    // ----- id claims

    @Test
    void theClaimOutlivesItsApprovalAtCreation() {
        store.create(TestMembers.request("r-1", "scope-a", Status.PENDING), 5);
        assertThat(ttl(claims, "r-1")).isGreaterThanOrEqualTo(ttl(approvals, ScopeKeys.approval("scope-a", "r-1"))
                + RETENTION.toMillis());
    }

    @Test
    void theClaimStillOutlivesItsApprovalAfterADecisionWithASkewedClock() {
        for (Duration skew : new Duration[]{Duration.ofHours(-2), Duration.ofMinutes(59), Duration.ofHours(3)}) {
            String id = "r-" + skew.toMinutes();
            store.create(TestMembers.request(id, "scope-a", Status.PENDING));
            try {
                store.reject(id, "bob", NOW.plus(skew));
            } catch (ApprovalRefusedException expired) {
                // A clock three hours ahead finds the approval expired, which is also a change of state.
                assertThat(expired.code()).isEqualTo(ApprovalRefusedException.Code.EXPIRED);
            }
            assertThat(ttl(claims, id)).as("claim after a decision %s from the request", skew)
                    .isGreaterThanOrEqualTo(ttl(approvals, ScopeKeys.approval("scope-a", id)) + RETENTION.toMillis());
        }
    }

    @Test
    void approveThenConsumeInEitherClockOrderLeavesTheClaimOutlivingTheEntry() {
        // The caller whose clock is behind sets the longer entry TTL; the order of the two swaps must not matter.
        for (boolean behindFirst : new boolean[]{true, false}) {
            String id = behindFirst ? "o-1" : "o-2";
            Instant behind = NOW.minus(Duration.ofHours(2));
            Instant ahead = NOW.plus(Duration.ofMinutes(59));
            store.create(TestMembers.request(id, "scope-a", Status.PENDING));
            store.approve(id, "bob", behindFirst ? behind : ahead);
            store.consumeApproved(Kind.TOOL_CALL, "alice", "scope-a", "search", "fp-" + id,
                    behindFirst ? ahead : behind);
            assertThat(ttl(claims, id)).as("order behind-first=%s", behindFirst)
                    .isGreaterThanOrEqualTo(ttl(approvals, ScopeKeys.approval("scope-a", id)) + RETENTION.toMillis());
        }
    }

    @Test
    void aClaimRefreshNeverShortensTheClaimWhateverTheOrderOfArrival() {
        store.create(TestMembers.request("x-1", "scope-a", Status.PENDING));
        String entryKey = ScopeKeys.approval("scope-a", "x-1");
        long now = System.currentTimeMillis();
        long before = ttl(claims, "x-1");
        long longer = before + Duration.ofHours(2).toMillis();
        assertThat(claims.<Boolean>executeOnKey("x-1", new ClaimEntries.Extend(entryKey, 60_000, now))).isFalse();
        assertThat(claims.<Boolean>executeOnKey("x-1", new ClaimEntries.Extend(entryKey, longer, now))).isTrue();
        // A refresh that arrives late, asking for less, changes nothing.
        assertThat(claims.<Boolean>executeOnKey("x-1", new ClaimEntries.Extend(entryKey, 60_000, now))).isFalse();
        assertThat(ttl(claims, "x-1")).isGreaterThanOrEqualTo(longer);
    }

    @Test
    void aStaleRefreshAfterForgetAndRecreateInAnotherScopeLeavesTheNewClaimAlone() {
        store.create(TestMembers.request("s-1", "scope-a", Status.PENDING));
        String staleKey = ScopeKeys.approval("scope-a", "s-1");
        store.forgetScope("scope-a");
        store.create(TestMembers.request("s-1", "scope-b", Status.PENDING));
        String claimBefore = claims.get("s-1");
        long ttlBefore = ttl(claims, "s-1");
        long now = System.currentTimeMillis();

        assertThat(claims.<Boolean>executeOnKey("s-1",
                new ClaimEntries.Extend(staleKey, Duration.ofDays(30).toMillis(), now))).isFalse();
        assertThat(claims.<Boolean>executeOnKey("s-1", new ClaimEntries.Release(staleKey))).isFalse();

        assertThat(claims.get("s-1")).isEqualTo(claimBefore);
        assertThat(ttl(claims, "s-1")).isLessThanOrEqualTo(ttlBefore);
        assertThat(claimBefore).startsWith(ScopeKeys.approval("scope-b", "s-1") + "\0");
    }

    @Test
    void anApprovalThatExpiresTakesItsClaimWithIt() {
        var quick = new HazelcastApprovalStore(cluster, Duration.ofMillis(300), Duration.ofSeconds(2));
        quick.create(pending("q-1", "scope-a", "alice", NOW, Duration.ofSeconds(2)), 5);
        awaitTrue(() -> !claims.containsKey("q-1"));
        assertThat(approvals.containsKey(ScopeKeys.approval("scope-a", "q-1"))).isFalse();
    }

    // ----- pending entries

    @Test
    void aPendingEntryHasNoTtlSoItCanNeverExpireBeforeItsApprovals() {
        store.create(TestMembers.request("p-1", "scope-a", Status.PENDING), 5);
        store.create(pending("p-2", "scope-a", "alice", NOW, Duration.ofHours(5)), 5);
        store.reject("p-1", "bob", NOW);
        assertThat(ttl(pendingSets, pendingKey("alice"))).isEqualTo(Long.MAX_VALUE);
    }

    @Test
    void theSweepKeepsEveryIdUntilItsExpiryPlusTheSlackHasPassed() {
        store.create(pending("w-1", "scope-a", "alice", PAST, Duration.ofHours(1)));
        Instant expiry = PAST.plus(Duration.ofHours(1));
        Instant lastKept = expiry.plus(PendingSweeper.SLACK).minusMillis(1);
        assertThat(cluster.sweepPendingApprovals(PAST)).isZero();
        assertThat(cluster.sweepPendingApprovals(expiry)).isZero();
        assertThat(cluster.sweepPendingApprovals(lastKept)).isZero();
        assertThat(pendingSets.containsKey(pendingKey("alice"))).isTrue();
        assertThat(cluster.sweepPendingApprovals(expiry.plus(PendingSweeper.SLACK))).isEqualTo(1);
        assertThat(pendingSets.containsKey(pendingKey("alice"))).isFalse();
    }

    @Test
    void theSweepRemovesOnlyTheIdsPastTheirSlackAndIsIdempotent() {
        store.create(pending("m-1", "scope-a", "alice", PAST, Duration.ofHours(1)));
        store.create(pending("m-2", "scope-a", "alice", PAST, Duration.ofHours(3)));
        store.create(pending("m-3", "scope-a", "carol", PAST, Duration.ofHours(1)));
        Instant sweepAt = PAST.plus(Duration.ofHours(2));
        assertThat(cluster.sweepPendingApprovals(sweepAt)).isEqualTo(2);
        assertThat(cluster.sweepPendingApprovals(sweepAt)).isZero();
        assertThat(PendingSet.decode(pendingSets.get(pendingKey("alice")))).containsOnlyKeys("m-2");
        assertThat(pendingSets.containsKey(pendingKey("carol"))).isFalse();
    }

    @Test
    void anEntryThatCannotBeReadIsLeftForTheCapToRefuse() {
        pendingSets.put(pendingKey("alice"), "garbage");
        assertThat(cluster.sweepPendingApprovals(PAST.plus(Duration.ofDays(3650)))).isZero();
        assertThat(pendingSets.get(pendingKey("alice"))).isEqualTo("garbage");
        assertThatThrownBy(() -> store.create(TestMembers.request("g-1", "scope-a", Status.PENDING), 5))
                .isInstanceOf(ClusterOperationRefusedException.class);
    }

    @Test
    void aSweepNeverLowersTheCountOfALiveApproval() {
        store.create(TestMembers.request("l-1", "scope-a", Status.PENDING), 1);
        // Real time is long past the 2030 expiry only after 2031; a sweep at the request's own time keeps it.
        cluster.sweepPendingApprovals(NOW.plus(Duration.ofMinutes(30)));
        assertThatThrownBy(() -> store.create(TestMembers.request("l-2", "scope-a", Status.PENDING), 1))
                .isInstanceOf(ApprovalRefusedException.class);
    }

    @Test
    void theScheduledSweepRemovesAnExpiredEntryAndStopsWithTheCluster() {
        int port = FreePorts.consecutive(1);
        Config config = ClusterMembership.tcpIp(TestMembers.uniqueName("sweep-schedule"),
                List.of("127.0.0.1:" + port)).withPort(port).withInterface("127.0.0.1").toConfig();
        config.setProperty(PendingSweeper.INTERVAL_PROPERTY, "1");
        PrivacyCluster scheduled = PrivacyCluster.embedded(config, false, OptionalInt.empty());
        try {
            var scheduledStore = new HazelcastApprovalStore(scheduled, Duration.ofMillis(300));
            IMap<String, String> pendingMap = scheduled.instance().getMap(PrivacyCluster.APPROVAL_PENDING_MAP);
            // Expired years ago, so the wall-clock sweep finds it past its slack.
            scheduledStore.create(pending("t-1", "scope-a", "alice", PAST, Duration.ofHours(1)), 5);
            assertThat(pendingMap.containsKey(pendingKey("alice"))).isTrue();
            awaitTrue(() -> !pendingMap.containsKey(pendingKey("alice")));
            assertThat(scheduled.sweeperStopped()).isFalse();
        } finally {
            scheduled.close();
        }
        assertThat(scheduled.sweeperStopped()).isTrue();
    }

    @Test
    void theSweepIntervalIsConfigurableAndValidated() {
        Config config = new Config();
        assertThat(PendingSweeper.intervalOf(config)).isEqualTo(Duration.ofMinutes(5));
        config.setProperty(PendingSweeper.INTERVAL_PROPERTY, "30");
        assertThat(PendingSweeper.intervalOf(config)).isEqualTo(Duration.ofSeconds(30));
        for (String bad : new String[]{"0", "-5", "abc", "86401", "1.5"}) {
            config.setProperty(PendingSweeper.INTERVAL_PROPERTY, bad);
            assertThatThrownBy(() -> PendingSweeper.intervalOf(config)).isInstanceOf(IllegalArgumentException.class);
        }
    }
}

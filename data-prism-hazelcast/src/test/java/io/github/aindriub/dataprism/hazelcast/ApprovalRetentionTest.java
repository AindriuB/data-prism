package io.github.aindriub.dataprism.hazelcast;

import com.hazelcast.config.Config;
import com.hazelcast.core.EntryView;
import com.hazelcast.map.IMap;
import io.github.aindriub.dataprism.core.refusal.ClusterOperationRefusedException;
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

    // ----- id claims: valid while the entry they name exists

    private String entryKey(String scope, String id) {
        return ScopeKeys.approval(scope, id);
    }

    /** A claim for a creation that is not running: the entry key of {@code scope} and {@code id}, and a token. */
    private static String claim(String scope, String id) {
        return ClaimEntries.value(ScopeKeys.approval(scope, id), TestMembers.token(9));
    }

    /** The entry key a claim names. */
    private static String named(String claim) {
        return ClaimEntries.read(claim).orElseThrow().entryKey();
    }

    @Test
    void aClaimHasNoLifetimeOfItsOwn() {
        store.create(TestMembers.request("r-1", "scope-a", Status.PENDING), 5);
        assertThat(ttl(claims, "r-1")).isEqualTo(Long.MAX_VALUE);
        assertThat(named(claims.get("r-1"))).isEqualTo(entryKey("scope-a", "r-1"));
    }

    @Test
    void aLiveClaimRefusesADuplicateAndIsLeftAlone() {
        store.create(TestMembers.request("r-2", "scope-a", Status.PENDING));
        // Whatever the deciding clock does to the entry's expiry, the id stays claimed while the entry exists.
        store.reject("r-2", "bob", NOW.minus(Duration.ofHours(2)));
        assertThatThrownBy(() -> store.create(TestMembers.request("r-2", "scope-b", Status.PENDING)))
                .isExactlyInstanceOf(IllegalArgumentException.class).hasMessage("duplicate approval id");
        assertThat(named(claims.get("r-2"))).isEqualTo(entryKey("scope-a", "r-2"));
    }

    @Test
    void approveThenConsumeLeavesTheClaimHeldForAsLongAsTheEntryExists() {
        store.create(TestMembers.request("o-1", "scope-a", Status.PENDING));
        store.approve("o-1", "bob", NOW.minus(Duration.ofHours(2)));
        store.consumeApproved(Kind.TOOL_CALL, "alice", "scope-a", "search", "fp-o-1", NOW.plus(Duration.ofMinutes(59)));
        assertThat(approvals.containsKey(entryKey("scope-a", "o-1"))).isTrue();
        assertThatThrownBy(() -> store.create(TestMembers.request("o-1", "scope-b", Status.PENDING)))
                .isExactlyInstanceOf(IllegalArgumentException.class).hasMessage("duplicate approval id");
        assertThat(named(claims.get("o-1"))).isEqualTo(entryKey("scope-a", "o-1"));
    }

    @Test
    void createTakesOverAClaimWhoseEntryIsGone() {
        claims.put("c-1", claim("scope-x", "c-1"));
        assertThat(approvals.containsKey(entryKey("scope-x", "c-1"))).isFalse();
        ApprovalRequest request = TestMembers.request("c-1", "scope-a", Status.PENDING);
        assertThat(store.create(request, 5)).isEqualTo(request);
        assertThat(named(claims.get("c-1"))).isEqualTo(entryKey("scope-a", "c-1"));
        assertThat(store.find("c-1")).contains(request);
    }

    @Test
    void createTakesOverAClaimWhoseEntryExpired() {
        var shortLived = pending("e-1", "scope-a", "alice", NOW, Duration.ofSeconds(2));
        store.create(shortLived);
        awaitTrue(() -> !approvals.containsKey(entryKey("scope-a", "e-1")));
        assertThat(named(claims.get("e-1"))).isEqualTo(entryKey("scope-a", "e-1"));
        ApprovalRequest again = TestMembers.request("e-1", "scope-b", Status.PENDING);
        assertThat(store.create(again)).isEqualTo(again);
        assertThat(named(claims.get("e-1"))).isEqualTo(entryKey("scope-b", "e-1"));
    }

    @Test
    void aStaleClaimOfTheSameScopeCanBeCreatedAgain() {
        claims.put("c-2", claim("scope-a", "c-2"));
        ApprovalRequest request = TestMembers.request("c-2", "scope-a", Status.PENDING);
        assertThat(store.create(request)).isEqualTo(request);
        assertThat(store.find("c-2")).contains(request);
    }

    @Test
    void aMarkedClaimIsHeldByItsTakerAndRefusesEveryoneElse() {
        String marked = claim("scope-x", "c-3") + "\0~\0" + claim("scope-y", "c-3");
        claims.put("c-3", marked);
        assertThatThrownBy(() -> store.create(TestMembers.request("c-3", "scope-a", Status.PENDING)))
                .isExactlyInstanceOf(IllegalArgumentException.class).hasMessage("duplicate approval id");
        assertThat(claims.get("c-3")).isEqualTo(marked);
        assertThat(approvals.keySet()).isEmpty();
    }

    /**
     * The creators share one member and the id lock serialises them, so this checks the outcome (one winner, one
     * entry) and not the compare-and-set interleavings; those are in {@link ClaimInterleavingTest}.
     */
    @Test
    void creatorsOnOneMemberRacingForAStaleClaimAreSerialisedByTheIdLockAndGiveExactlyOneWinner() throws Exception {
        for (int round = 0; round < 5; round++) {
            String id = "race-" + round;
            claims.put(id, claim("scope-gone", id));
            int creators = 6;
            var ready = new java.util.concurrent.CountDownLatch(creators);
            var go = new java.util.concurrent.CountDownLatch(1);
            var wins = new java.util.concurrent.atomic.AtomicInteger();
            var duplicates = new java.util.concurrent.atomic.AtomicInteger();
            try (var pool = java.util.concurrent.Executors.newFixedThreadPool(creators)) {
                var futures = new java.util.ArrayList<java.util.concurrent.Future<?>>();
                for (int i = 0; i < creators; i++) {
                    String scope = "scope-" + i;
                    futures.add(pool.submit(() -> {
                        ready.countDown();
                        go.await();
                        try {
                            store.create(TestMembers.request(id, scope, Status.PENDING));
                            wins.incrementAndGet();
                        } catch (IllegalArgumentException | io.github.aindriub.dataprism.core.refusal.PrivacyRefusedException lost) {
                            // A lost race is a duplicate, or a bounded refusal while the winner holds a lock.
                            duplicates.incrementAndGet();
                        }
                        return null;
                    }));
                }
                assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
                go.countDown();
                for (var future : futures) future.get(30, TimeUnit.SECONDS);
            }
            assertThat(wins.get()).as("round %d", round).isEqualTo(1);
            assertThat(approvals.keySet().stream().filter(key -> ScopeKeys.approvalId(key).equals(id))).hasSize(1);
            assertThat(named(claims.get(id))).isEqualTo(approvals.keySet().stream()
                    .filter(key -> ScopeKeys.approvalId(key).equals(id)).findFirst().orElseThrow());
        }
    }

    @Test
    void theClaimSweepRemovesOnlyClaimsWhoseEntryIsGone() {
        store.create(TestMembers.request("live-1", "scope-a", Status.PENDING));
        claims.put("stale-1", claim("scope-x", "stale-1"));
        claims.put("stale-2", claim("scope-x", "stale-2") + "\0~\0" + claim("scope-y", "stale-2"));
        store.create(TestMembers.request("live-2", "scope-a", Status.PENDING));
        // A marked claim whose named entry exists is put back, never removed.
        claims.put("live-2", claim("scope-a", "live-2") + "\0~\0" + claim("scope-z", "live-2"));

        assertThat(cluster.sweepStaleClaims()).isEqualTo(2);
        assertThat(claims.containsKey("stale-1")).isFalse();
        assertThat(claims.containsKey("stale-2")).isFalse();
        assertThat(named(claims.get("live-1"))).isEqualTo(entryKey("scope-a", "live-1"));
        assertThat(named(claims.get("live-2"))).isEqualTo(entryKey("scope-a", "live-2"));
        assertThat(cluster.sweepStaleClaims()).isZero();
        assertThat(store.find("live-1")).isPresent();
    }

    @Test
    void theClaimSweepSkipsAnIdWhoseLockIsHeldAndTakesItOnceItIsFree() throws Exception {
        claims.put("busy-1", claim("scope-x", "busy-1"));
        var locked = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        Thread holder = new Thread(() -> {
            claims.lock("busy-1");
            locked.countDown();
            try {
                release.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                claims.unlock("busy-1");
            }
        });
        holder.start();
        try {
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(cluster.sweepStaleClaims()).isZero();
            assertThat(named(claims.get("busy-1"))).isEqualTo(entryKey("scope-x", "busy-1"));
        } finally {
            release.countDown();
            holder.join();
        }
        assertThat(cluster.sweepStaleClaims()).isEqualTo(1);
        assertThat(claims.containsKey("busy-1")).isFalse();
    }

    @Test
    void aClaimSweepThatMeetsACreatorMidInsertAfterItsLeaseRanOutMakesTheCreatorRefuseRatherThanDuplicate()
            throws Exception {
        StalledInsert stall = new StalledInsert();
        try (var caller = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var paused = new HazelcastApprovalStore(stall.over(cluster), Duration.ofMillis(300));
            ApprovalRequest request = TestMembers.request("mid-1", "scope-a", Status.PENDING);
            var stalled = caller.submit(() -> paused.create(request));
            try {
                assertThat(stall.reached.await(10, TimeUnit.SECONDS)).isTrue();
                // While the creator holds the id lock the sweep leaves its claim alone.
                assertThat(cluster.sweepStaleClaims()).isZero();
                assertThat(named(claims.get("mid-1"))).isEqualTo(entryKey("scope-a", "mid-1"));
                // Once its lease ran out, the claim names an entry that is not there, so the sweep removes it.
                StalledInsert.awaitFree(cluster, PrivacyCluster.APPROVAL_ID_MAP, "mid-1");
                assertThat(cluster.sweepStaleClaims()).isEqualTo(1);
                stall.resume();
                assertThatThrownBy(() -> stalled.get(10, TimeUnit.SECONDS))
                        .hasCauseInstanceOf(IllegalArgumentException.class);
                // Nothing is left half-created, and the id is free again.
                assertThat(approvals.containsKey(entryKey("scope-a", "mid-1"))).isFalse();
                assertThat(store.create(TestMembers.request("mid-1", "scope-b", Status.PENDING))).isNotNull();
            } finally {
                stall.resume();
            }
        }
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
        PrivacyCluster scheduled = PrivacyCluster.embedded(config, false, OptionalInt.empty(), Duration.ofSeconds(1));
        try {
            assertThat(scheduled.approvalSweepInterval()).isEqualTo(Duration.ofSeconds(1));
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
    void theSweepIntervalIsAFactoryInputWithinItsBounds() {
        assertThat(cluster.approvalSweepInterval()).isEqualTo(Duration.ofMinutes(5));
        for (Duration good : new Duration[]{Duration.ofSeconds(1), Duration.ofDays(1)}) {
            int port = FreePorts.consecutive(1);
            ClusterMembership membership = ClusterMembership.tcpIp(TestMembers.uniqueName("sweep-bounds"),
                    List.of("127.0.0.1:" + port)).withPort(port).withInterface("127.0.0.1");
            try (PrivacyCluster built = PrivacyCluster.embedded(membership, false, OptionalInt.empty(), good)) {
                assertThat(built.approvalSweepInterval()).isEqualTo(good);
            }
            int otherPort = FreePorts.consecutive(1);
            try (PrivacyCluster other = TestMembers.member(TestMembers.uniqueName("sweep-using"), otherPort,
                    List.of("127.0.0.1:" + otherPort), OptionalInt.empty());
                 PrivacyCluster wrapped = PrivacyCluster.using(other.instance(), false, good)) {
                assertThat(wrapped.approvalSweepInterval()).isEqualTo(good);
            }
        }
        for (Duration bad : new Duration[]{Duration.ZERO, Duration.ofSeconds(-1), Duration.ofMillis(500),
                Duration.ofDays(1).plusSeconds(1)}) {
            int port = FreePorts.consecutive(1);
            ClusterMembership membership = ClusterMembership.tcpIp(TestMembers.uniqueName("sweep-bad"),
                    List.of("127.0.0.1:" + port)).withPort(port).withInterface("127.0.0.1");
            assertThatThrownBy(() -> PrivacyCluster.embedded(membership, false, OptionalInt.empty(), bad))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> PrivacyCluster.embedded(membership.toConfig(), false, OptionalInt.empty(), bad))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> PrivacyCluster.using(cluster.instance(), false, bad))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> PrivacyCluster.embedded(
                ClusterMembership.tcpIp(TestMembers.uniqueName("sweep-null"), List.of("127.0.0.1:1")), false,
                OptionalInt.empty(), null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void aConfigCarryingTheOldIntervalPropertyIsNeitherReadNorRefused() {
        int port = FreePorts.consecutive(1);
        Config config = ClusterMembership.tcpIp(TestMembers.uniqueName("sweep-old"), List.of("127.0.0.1:" + port))
                .withPort(port).withInterface("127.0.0.1").toConfig();
        config.setProperty("dataprism.approval.pending.sweep-" + "seconds", "not-a-number");
        try (PrivacyCluster built = PrivacyCluster.embedded(config, false, OptionalInt.empty())) {
            assertThat(built.approvalSweepInterval()).isEqualTo(Duration.ofMinutes(5));
        }
    }
}

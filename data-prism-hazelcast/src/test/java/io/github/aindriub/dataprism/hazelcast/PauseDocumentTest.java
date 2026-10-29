package io.github.aindriub.dataprism.hazelcast;

import com.hazelcast.config.Config;
import com.hazelcast.map.IMap;
import io.github.aindriub.dataprism.core.metrics.Metric;
import io.github.aindriub.dataprism.core.metrics.PrivacyMetrics;
import io.github.aindriub.dataprism.core.refusal.ClusterOperationRefusedException;
import io.github.aindriub.dataprism.hazelcast.ClusterSecuritySettings.UnverifiedMemberAction;
import io.github.aindriub.dataprism.hazelcast.MemberCardTest.Fixture;
import io.github.aindriub.dataprism.hazelcast.boundary.Inputs;
import io.github.aindriub.dataprism.hazelcast.boundary.OversightMapsBoundary;
import io.github.aindriub.dataprism.hazelcast.seal.ClusterKeys;
import io.github.aindriub.dataprism.hazelcast.seal.Sealer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T4: the pause document (docs/plan/design/cluster-security.md 2.3.3, OD-3, OD-4, OD-15). Every instance here is a
 * {@link HazelcastOversightState} with its own monotonic clock and its own wall clock, which a test moves; none ticks
 * on its own and none sleeps. Two states on one cluster stand for two instances, with separate memory.
 */
class PauseDocumentTest {

    /** A monotonic clock that only moves when a test says so. */
    static final class Nanos implements LongSupplier {
        private final AtomicLong now = new AtomicLong(1_000_000_000L);

        @Override
        public long getAsLong() {
            return now.get();
        }

        void advance(Duration by) {
            now.addAndGet(by.toNanos());
        }
    }

    private static final Duration LEASE = ClusterSecuritySettings.DEFAULT_PAUSE_LEASE;
    private static final Duration SKEW = ClusterSecuritySettings.DEFAULT_CLOCK_SKEW_TOLERANCE;

    private final Fixture fixture = new Fixture();
    private final List<HazelcastOversightState> states = new ArrayList<>();
    private PrivacyCluster cluster;
    private final Fixture.Metrics metrics = new Fixture.Metrics();

    @AfterEach
    void shutDown() {
        states.forEach(HazelcastOversightState::close);
        if (cluster != null) {
            cluster.close();
        }
        fixture.closeAll();
    }

    private static Config config(String name) {
        Config config = new Config();
        config.setClusterName(name);
        config.getJetConfig().setEnabled(false);
        config.getNetworkConfig().getJoin().getMulticastConfig().setEnabled(false);
        config.getNetworkConfig().getJoin().getTcpIpConfig().setEnabled(false);
        return config;
    }

    /** A solo member: it seals under keys drawn at random for its process. */
    private PrivacyCluster solo() {
        cluster = PrivacyCluster.embedded(config(TestMembers.uniqueName("pause")), false);
        return cluster;
    }

    private HazelcastOversightState instance(PrivacyCluster on, Nanos nanos, Fixture.TestClock wall) {
        HazelcastOversightState state = new HazelcastOversightState(on, ClusterLocks.DEFAULT_TIMEOUT, metrics,
                new PauseDocument.Environment(nanos, wall, null));
        states.add(state);
        return state;
    }

    private HazelcastOversightState instance(Nanos nanos, Fixture.TestClock wall) {
        return instance(cluster, nanos, wall);
    }

    private IMap<String, Object> map(PrivacyCluster on) {
        return on.instance().getMap(PrivacyCluster.OVERSIGHT_MAP);
    }

    private String key(PrivacyCluster on) {
        return on.keyedNames().mapKey(PrivacyCluster.OVERSIGHT_MAP, PauseDocument.CANONICAL);
    }

    // ---- deletion, replay, restart ------------------------------------------------------------------------

    @Test
    void deletingTheDocumentMakesEveryInstanceRefuseAndPauseFreshReadZero() {
        solo();
        var one = instance(new Nanos(), new Fixture.TestClock());
        var two = instance(new Nanos(), new Fixture.TestClock());
        one.pauseAll();
        assertThat(two.allPaused()).isTrue();
        assertThat(metrics.gauge(Metric.CLUSTER_PAUSE_FRESH)).isEqualTo(1.0);

        map(cluster).delete(key(cluster));

        for (var state : List.of(one, two)) {
            assertThatThrownBy(state::allPaused).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> state.toolPaused("search")).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> state.scopePaused("CASE-A")).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(state::snapshot).isInstanceOf(IllegalStateException.class);
            assertThat(state.securityStatus().pauseFresh()).isFalse();
            assertThat(state.securityStatus().reason()).isEqualTo(ClusterSecurityStatus.Reason.PAUSE_NOT_FRESH);
        }
        assertThat(metrics.gauge(Metric.CLUSTER_PAUSE_FRESH)).isEqualTo(0.0);
    }

    @Test
    void aDocumentOlderThanOneAnInstanceHasSeenIsRejectedByIt() {
        solo();
        var seen = instance(new Nanos(), new Fixture.TestClock());
        seen.pauseTool("alpha");
        String older = (String) map(cluster).get(key(cluster));
        seen.pauseTool("beta");
        long[] before = seen.document().acceptedOrder();

        map(cluster).put(key(cluster), older);

        // The instance neither lowers what it accepted nor answers from the replayed document.
        assertThatThrownBy(() -> seen.toolPaused("beta")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> seen.toolPaused("gamma")).isInstanceOf(IllegalStateException.class);
        assertThat(seen.document().acceptedOrder()).containsExactly(before);
        assertThat(seen.securityStatus().pauseFresh()).isFalse();
    }

    @Test
    void aTickRepairsAReplayedOlderDocumentWithAUnionSoNoPauseOfEitherIsDropped() {
        solo();
        var seen = instance(new Nanos(), new Fixture.TestClock());
        seen.pauseTool("alpha");
        String older = (String) map(cluster).get(key(cluster));
        seen.resumeTool("alpha");
        seen.pauseTool("beta");
        map(cluster).put(key(cluster), older);
        assertThatThrownBy(() -> seen.toolPaused("beta")).isInstanceOf(IllegalStateException.class);

        seen.document().tick();

        // The replayed document is back to the present, and it carries what the replay held as well.
        assertThat(seen.snapshot().pausedTools()).containsExactlyInAnyOrder("alpha", "beta");
        assertThat(seen.securityStatus().pauseFresh()).isTrue();
        assertThat(instance(new Nanos(), new Fixture.TestClock()).snapshot().pausedTools())
                .containsExactlyInAnyOrder("alpha", "beta");
    }

    @Test
    void aRestartedInstanceAcceptsAReplayedOlderDocumentOnlyInsideTheLeaseAndTheColdStartGate_theNamedResidual() {
        solo();
        var first = instance(new Nanos(), new Fixture.TestClock());
        String beforeThePause = (String) map(cluster).get(key(cluster));
        first.pauseAll();
        // The attacker deletes the pause document and puts the captured pre-pause one back.
        map(cluster).put(key(cluster), beforeThePause);

        // A restarted instance (no memory) one second inside the cold-start gate accepts it: the pause is lifted for
        // this instance, and only for as long as the lease lasts from its receipt.
        Fixture.TestClock insideGate = new Fixture.TestClock();
        insideGate.advance(LEASE.plus(SKEW));
        Nanos nanos = new Nanos();
        var restarted = instance(nanos, insideGate);
        assertThat(restarted.allPaused()).as("the residual: a replayed pre-pause document lifts the pause").isFalse();

        // The window is exactly the configured lease from receipt: one nanosecond short is still open, the lease is not.
        nanos.advance(LEASE.minusNanos(1));
        assertThat(restarted.allPaused()).isFalse();
        nanos.advance(Duration.ofNanos(1));
        assertThatThrownBy(restarted::allPaused).isInstanceOf(IllegalStateException.class);

        // One millisecond past the cold-start gate, a restarted instance does not accept the replayed document.
        Fixture.TestClock pastGate = new Fixture.TestClock();
        pastGate.advance(LEASE.plus(SKEW).plusMillis(1));
        var tooLate = instance(new Nanos(), pastGate);
        assertThatThrownBy(tooLate::allPaused).isInstanceOf(IllegalStateException.class);
        assertThat(tooLate.document().acceptedSequence()).isZero();
    }

    @Test
    void aDocumentNobodyRefreshesGoesStaleAfterOneLeaseAndARefreshByAnyInstanceKeepsItFresh() {
        solo();
        Nanos nanosOne = new Nanos();
        Nanos nanosTwo = new Nanos();
        Fixture.TestClock wall = new Fixture.TestClock();
        var one = instance(nanosOne, wall);
        var two = instance(nanosTwo, wall);
        one.pauseTool("search");
        assertThat(two.toolPaused("search")).isTrue();

        nanosOne.advance(LEASE.dividedBy(2));
        nanosTwo.advance(LEASE.dividedBy(2));
        long[] before = two.document().acceptedOrder();
        two.document().tick();
        // The second instance refreshed it: same pauses, refresh counter one higher.
        assertThat(one.toolPaused("search")).isTrue();
        assertThat(one.document().acceptedOrder()[0]).isEqualTo(before[0]);
        assertThat(one.document().acceptedOrder()[1]).isEqualTo(before[1] + 1);

        nanosOne.advance(LEASE.dividedBy(2).plusNanos(1));
        nanosTwo.advance(LEASE.dividedBy(2).plusNanos(1));
        assertThat(one.toolPaused("search")).as("the refresh renewed it from its receipt").isTrue();

        // Nobody refreshes it from here: it goes stale one lease after the last receipt.
        nanosOne.advance(LEASE);
        assertThatThrownBy(() -> one.toolPaused("search")).isInstanceOf(IllegalStateException.class);
        assertThat(one.securityStatus().pauseFresh()).isFalse();
        assertThat(one.document().fresh()).isFalse();
    }

    @Test
    void theAgeGaugeCountsFromLocalReceiptAndTheStatusCarriesNoPauseContent() {
        solo();
        Nanos nanos = new Nanos();
        var state = instance(nanos, new Fixture.TestClock());
        state.pauseTool("secret-tool-name");
        state.pauseScope("CASE-SECRET");
        nanos.advance(Duration.ofSeconds(7));
        assertThat(state.toolPaused("secret-tool-name")).isTrue();

        assertThat(metrics.gauge(Metric.CLUSTER_PAUSE_AGE_SECONDS)).isEqualTo(7.0);
        ClusterSecurityStatus status = state.securityStatus();
        assertThat(status.pauseFresh()).isTrue();
        assertThat(status.pauseAgeSeconds()).isEqualTo(7);
        assertThat(status.serving()).isTrue();
        assertThat(status.text() + status).doesNotContain("secret-tool-name").doesNotContain("CASE-SECRET");
    }

    // ---- concurrent writers ----------------------------------------------------------------------------------

    @Test
    void aWriterThatLosesTheRaceReReadsAndBothPausesSurviveInSequence() {
        solo();
        var one = instance(new Nanos(), new Fixture.TestClock());
        var two = instance(new Nanos(), new Fixture.TestClock());
        long start = one.document().acceptedSequence();
        AtomicInteger commits = new AtomicInteger();
        // The first writer has read and sealed its next document when the second writer completes its own.
        one.document().beforeCommit = () -> {
            if (commits.incrementAndGet() == 1) {
                two.pauseTool("beta");
            }
        };

        one.pauseTool("alpha");

        assertThat(commits.get()).as("the first writer lost once and retried").isEqualTo(2);
        var snapshot = two.snapshot();
        assertThat(snapshot.pausedTools()).containsExactlyInAnyOrder("alpha", "beta");
        assertThat(one.document().acceptedSequence()).isEqualTo(start + 2);
    }

    @Test
    void manyWritersOnManyInstancesLoseNoPause() throws Exception {
        solo();
        int writers = 4;
        int each = 5;
        List<HazelcastOversightState> instances = new ArrayList<>();
        for (int i = 0; i < writers; i++) {
            instances.add(instance(new Nanos(), new Fixture.TestClock()));
        }
        ExecutorService pool = Executors.newFixedThreadPool(writers);
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<?>> done = new ArrayList<>();
            for (int w = 0; w < writers; w++) {
                int writer = w;
                done.add(pool.submit(() -> {
                    go.await();
                    for (int n = 0; n < each; n++) {
                        instances.get(writer).pauseTool("tool-" + writer + "-" + n);
                    }
                    return null;
                }));
            }
            go.countDown();
            for (Future<?> f : done) {
                f.get();
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(instances.get(0).snapshot().pausedTools()).hasSize(writers * each);
    }

    @Test
    void aWallClockSkewedByMoreThanTheToleranceDoesNotMakeAHealthyDocumentStaleAfterTheFirstAcceptance() {
        solo();
        Fixture.TestClock writerWall = new Fixture.TestClock();
        var writer = instance(new Nanos(), writerWall);
        writer.pauseTool("search");

        Fixture.TestClock readerWall = new Fixture.TestClock();
        var reader = instance(new Nanos(), readerWall);
        assertThat(reader.toolPaused("search")).isTrue();

        // The reader's wall clock jumps an hour ahead, then a day behind: only the monotonic clock is consulted.
        readerWall.advance(Duration.ofHours(1));
        assertThat(reader.toolPaused("search")).isTrue();
        readerWall.advance(Duration.ofHours(-25));
        assertThat(reader.toolPaused("search")).isTrue();

        // A writer whose own clock runs ten minutes fast issues a document the reader accepts at once.
        writerWall.advance(Duration.ofMinutes(10));
        writer.pauseTool("lookup");
        assertThat(reader.toolPaused("lookup")).isTrue();
        assertThat(reader.securityStatus().pauseFresh()).isTrue();
    }

    // ---- OD-15 ----------------------------------------------------------------------------------------------

    @Test
    void aMemberThatMissedAPauseDoesNotRewriteAnOlderDocumentWhileAVerifiedMemberReportsAHigherSequence() {
        solo();
        var behind = instance(new Nanos(), new Fixture.TestClock());
        var current = instance(new Nanos(), new Fixture.TestClock());
        // "behind" accepted the first document and never looked again; "current" then paused.
        current.pauseAll();
        long reported = current.document().acceptedSequence();
        assertThat(behind.document().acceptedSequence()).isLessThan(reported);
        map(cluster).delete(key(cluster));

        behind.document().peers = peers(reported, true);
        behind.document().tick();
        assertThat(map(cluster).containsKey(key(cluster))).as("no rewrite from an older document").isFalse();

        current.document().peers = peers(reported, true);
        current.document().tick();
        assertThat(map(cluster).containsKey(key(cluster))).as("rewritten by the member that has the latest").isTrue();
        assertThat(behind.allPaused()).isTrue();
    }

    @Test
    void aMemberThatAcceptedTheLatestDocumentWithinTheLeaseRewritesADeletedOneButNotAfterTheLease() {
        solo();
        Nanos nanos = new Nanos();
        var member = instance(nanos, new Fixture.TestClock());
        member.pauseScope("CASE-A");
        map(cluster).delete(key(cluster));
        member.document().peers = peers(member.document().acceptedSequence(), true);

        member.document().tick();
        assertThat(map(cluster).containsKey(key(cluster))).isTrue();
        assertThat(member.scopePaused("CASE-A")).isTrue();
        assertThat(instance(new Nanos(), new Fixture.TestClock()).scopePaused("CASE-A")).isTrue();

        // Not from a document accepted more than a lease ago.
        map(cluster).delete(key(cluster));
        nanos.advance(LEASE);
        member.document().tick();
        assertThat(map(cluster).containsKey(key(cluster))).as("accepted a lease ago").isFalse();
    }

    @Test
    void aMemberThatFindsNoDocumentAndNoOtherVerifiedMemberWritesTheFirstOneAndOneThatSeesOthersWaits() {
        solo();
        map(cluster).delete(key(cluster));
        var lonely = instance(new Nanos(), new Fixture.TestClock());
        assertThat(map(cluster).containsKey(key(cluster))).isTrue();
        assertThat(lonely.allPaused()).isFalse();

        map(cluster).delete(key(cluster));
        // A cold instance that sees another verified member writes nothing: it waits for that member's document.
        var waiting = new PauseDocument(cluster, metrics, ClusterLocks.DEFAULT_TIMEOUT,
                new PauseDocument.Environment(new Nanos(), new Fixture.TestClock(), null));
        waiting.peers = peers(0, true);
        waiting.tick();
        assertThat(map(cluster).containsKey(key(cluster))).isFalse();
        assertThatThrownBy(waiting::current).isInstanceOf(IllegalStateException.class);
        assertThat(waiting.fresh()).isFalse();
        // The operator's pause re-creates it.
        waiting.update(pauses -> pauses.withAll(true), false);
        assertThat(waiting.current().all()).isTrue();
    }

    private static PauseDocument.Peers peers(long highest, boolean others) {
        return new PauseDocument.Peers() {
            @Override
            public long highestReportedSequence() {
                return highest;
            }

            @Override
            public boolean othersVerified() {
                return others;
            }
        };
    }

    // ---- attacker documents --------------------------------------------------------------------------------

    @Test
    void anAttackerDocumentFromAKeylessMemberOrClientNeverLiftsAPauseAndIsCounted() {
        solo();
        var victim = instance(new Nanos(), new Fixture.TestClock());
        victim.pauseAll();
        long[] accepted = victim.document().acceptedOrder();
        IMap<String, Object> raw = map(cluster);
        String key = key(cluster);

        ClusterKeys foreign = new ClusterKeys("other-cluster-1", Fixture.filled('x'), null, null, null, null,
                "other-name-1", Fixture.filled('y'));
        Sealer attacker = Sealer.builder(foreign, PrivacyMetrics.none()).build();
        String randomEnvelope = "dps1.solo-cluster." + b64(16) + "." + b64(12) + "." + b64(48);
        List<Object> forgeries = List.of(
                "{\"all\":false}",                                                       // plaintext
                Boolean.TRUE,                                                            // the 0.7 format
                randomEnvelope,
                attacker.seal(PrivacyCluster.OVERSIGHT_MAP, key, "not a document".getBytes()),   // another key
                attacker.seal(PrivacyCluster.OVERSIGHT_MAP, "another-key", new byte[]{1, 0, 0}),
                "");
        for (Object forgery : forgeries) {
            int before = metrics.count(Metric.CLUSTER_SEAL_TAMPER) + metrics.count(Metric.CLUSTER_SEAL_UNSUPPORTED_FORMAT);
            raw.put(key, forgery);

            assertThatThrownBy(victim::allPaused).as("never answers not-paused for %s", forgery)
                    .isInstanceOf(IllegalStateException.class);
            assertThat(metrics.count(Metric.CLUSTER_SEAL_TAMPER) + metrics.count(Metric.CLUSTER_SEAL_UNSUPPORTED_FORMAT))
                    .as("counted: %s", forgery).isGreaterThan(before);
            assertThat(victim.document().acceptedOrder()).containsExactly(accepted);
        }

        // The operator's pause replaces the forged entry; a resume is refused until the document is fresh again.
        victim.pauseTool("search");
        assertThat(victim.allPaused()).isTrue();
        assertThat(victim.toolPaused("search")).isTrue();
        assertThat(instance(new Nanos(), new Fixture.TestClock()).allPaused()).isTrue();
    }

    @Test
    void aResumeIsRefusedWhileTheDocumentIsNotFreshAndAPauseIsStillAccepted() {
        solo();
        var state = instance(new Nanos(), new Fixture.TestClock());
        state.pauseTool("search");
        map(cluster).delete(key(cluster));

        assertThatThrownBy(() -> state.resumeTool("search")).isInstanceOf(IllegalStateException.class)
                .isNotInstanceOf(ClusterOperationRefusedException.class);
        state.pauseScope("CASE-A");
        // The pause re-created the document from what the instance had accepted, so nothing it held is lost.
        assertThat(state.toolPaused("search")).isTrue();
        assertThat(state.scopePaused("CASE-A")).isTrue();
        state.resumeTool("search");
        assertThat(state.toolPaused("search")).isFalse();
    }

    private static String b64(int bytes) {
        byte[] random = new byte[bytes];
        new java.security.SecureRandom().nextBytes(random);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(random);
    }

    // ---- the 0.7 fence ------------------------------------------------------------------------------------

    @Test
    void aMemberWithKeysKeepsTheFenceForA07MemberAndAssertsItAgainAtEachLeaseRefresh() {
        int port = FreePorts.consecutive(1);
        cluster = fixture.keyed(TestMembers.uniqueName("fence"), port, List.of("127.0.0.1:" + port), TestMembers.KEYS,
                Fixture.settings(UnverifiedMemberAction.REFUSE), new Fixture.Metrics(), new RecordingLogger(), false);
        Nanos nanos = new Nanos();
        var state = instance(nanos, fixture.clock);
        IMap<String, Object> fence = cluster.instance().getMap(PrivacyCluster.OVERSIGHT_FENCE_MAP);
        state.pauseTool("search");
        assertThat(fence.get("all")).isEqualTo(Boolean.TRUE);

        fence.delete("all");
        nanos.advance(cluster.securitySettings().pauseLease().dividedBy(2));
        state.document().tick();

        assertThat(fence.get("all")).as("re-asserted at the refresh").isEqualTo(Boolean.TRUE);
        assertThat(fence.size()).isEqualTo(1);
    }

    // ---- OD-215-16 (a) -------------------------------------------------------------------------------------

    @Test
    void withTheServingGateClosedAPauseIsWrittenAndSeenByEveryInstanceAndAResumeIsRefusedUntilItReopens() {
        String name = TestMembers.uniqueName("gate");
        int first = FreePorts.consecutive(3);
        List<String> all = List.of("127.0.0.1:" + first, "127.0.0.1:" + (first + 1), "127.0.0.1:" + (first + 2));
        ClusterSecuritySettings settings = Fixture.settings(UnverifiedMemberAction.REFUSE);
        PrivacyCluster a = fixture.keyed(name, first, all, TestMembers.KEYS, settings, new Fixture.Metrics(),
                new RecordingLogger(), false);
        Nanos nanos = new Nanos();
        var stateA = instance(a, nanos, fixture.clock);
        PrivacyCluster b = fixture.keyed(name, first + 1, all, TestMembers.KEYS, settings, new Fixture.Metrics(),
                new RecordingLogger(), false);
        var stateB = instance(b, new Nanos(), fixture.clock);
        com.hazelcast.core.HazelcastInstance outsider = Fixture.plain(name, first + 2, all);
        try {
            assertThat(Fixture.awaitMembers(a, 3)).isTrue();
            assertThat(Fixture.awaitMembers(b, 3)).isTrue();
            a.memberCards().orElseThrow().tick();
            b.memberCards().orElseThrow().tick();
            fixture.clock.advance(MemberCardTest.GRACE);
            a.memberCards().orElseThrow().tick();
            b.memberCards().orElseThrow().tick();
            assertThat(a.securityStatus().serving()).as("the gate is closed").isFalse();
            assertThat(b.securityStatus().serving()).isFalse();

            stateA.pauseTool("search");
            assertThat(stateB.toolPaused("search")).as("seen by every instance").isTrue();
            assertThat(stateA.toolPaused("search")).isTrue();

            assertThatThrownBy(() -> stateA.resumeTool("search")).isInstanceOf(ClusterOperationRefusedException.class)
                    .satisfies(refusal -> assertThat(((ClusterOperationRefusedException) refusal).code())
                            .isEqualTo("CLUSTER_OPERATION_REFUSED"));
            assertThatThrownBy(() -> stateB.resumeTool("search")).isInstanceOf(ClusterOperationRefusedException.class);
            assertThat(stateB.toolPaused("search")).as("the refused resume changed nothing").isTrue();

            outsider.shutdown();
            assertThat(Fixture.awaitMembers(a, 2)).isTrue();
            assertThat(Fixture.awaitMembers(b, 2)).isTrue();
            a.memberCards().orElseThrow().tick();
            b.memberCards().orElseThrow().tick();
            assertThat(a.securityStatus().serving()).as("the gate reopened").isTrue();

            stateA.resumeTool("search");
            assertThat(stateB.toolPaused("search")).isFalse();
        } finally {
            outsider.shutdown();
        }
    }

    // ---- the boundary --------------------------------------------------------------------------------------

    @Test
    void theOversightMapHoldsExactlyTheDocumentAndItOpensUnderTheTestKey() {
        int port = FreePorts.consecutive(1);
        cluster = fixture.keyed(TestMembers.uniqueName("boundary"), port, List.of("127.0.0.1:" + port),
                TestMembers.KEYS, Fixture.settings(UnverifiedMemberAction.REFUSE), new Fixture.Metrics(),
                new RecordingLogger(), false);
        var state = instance(new Nanos(), fixture.clock);
        state.pauseAll();
        state.pauseTool("search");
        state.pauseScope("CASE-42");

        Inputs inputs = new Inputs(cluster.keyedNames(), null, "CASE-42", "subject-42", null, "synthetic", "ap-1",
                "alice", null, "search");
        IMap<Object, Object> contents = cluster.instance().getMap(PrivacyCluster.OVERSIGHT_MAP);
        new OversightMapsBoundary().check(PrivacyCluster.OVERSIGHT_MAP, contents, inputs);
        assertThat(OversightMapsBoundary.opensUnderTestKey(contents, inputs)).isTrue();
        assertThat(contents.size()).isEqualTo(1);
    }
}

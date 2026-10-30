package io.github.aindriub.dataprism.hazelcast;

import com.hazelcast.config.Config;
import com.hazelcast.map.IMap;
import io.github.aindriub.dataprism.core.metrics.Metric;
import io.github.aindriub.dataprism.core.metrics.PrivacyMetrics;
import io.github.aindriub.dataprism.core.refusal.ClusterOperationRefusedException;
import io.github.aindriub.dataprism.hazelcast.ClusterSecuritySettings.UnverifiedMemberAction;
import io.github.aindriub.dataprism.hazelcast.MemberCardTest.Fixture;
import io.github.aindriub.dataprism.hazelcast.PauseDocument.Doc;
import io.github.aindriub.dataprism.hazelcast.PauseDocument.Id;
import io.github.aindriub.dataprism.hazelcast.PauseDocument.Kind;
import io.github.aindriub.dataprism.hazelcast.PauseDocument.Outcome;
import io.github.aindriub.dataprism.hazelcast.PauseDocument.Target;
import io.github.aindriub.dataprism.hazelcast.boundary.Inputs;
import io.github.aindriub.dataprism.hazelcast.boundary.OversightMapsBoundary;
import io.github.aindriub.dataprism.hazelcast.seal.ClusterKeys;
import io.github.aindriub.dataprism.hazelcast.seal.Opened;
import io.github.aindriub.dataprism.hazelcast.seal.Sealer;
import io.github.aindriub.dataprism.hazelcast.seal.ServingGate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
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
 * T4: the pause document (docs/plan/design/cluster-security.md 2.3.3, OD-3, OD-4, OD-15) as an observed-remove set of
 * elements and tombstones. Every instance here is a {@link HazelcastOversightState} (or a bare
 * {@link PauseDocument}) with its own monotonic clock and its own wall clock, which a test moves; none ticks on its
 * own and none sleeps. Two states on one cluster stand for two instances, with separate memory. The merge cases are
 * in {@link PauseSplitBrainTest}.
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

    /** What the cards would say, as a test sets it. */
    static final class StubPeers implements PauseDocument.Peers {
        volatile long highest;
        volatile int unverified;
        volatile boolean oldest = true;

        StubPeers(long highest) {
            this.highest = highest;
        }

        @Override
        public long highestReportedSequence() {
            return highest;
        }

        @Override
        public int unverifiedCount() {
            return unverified;
        }

        @Override
        public boolean isOldest() {
            return oldest;
        }
    }

    private static final Duration LEASE = ClusterSecuritySettings.DEFAULT_PAUSE_LEASE;
    private static final Duration SKEW = ClusterSecuritySettings.DEFAULT_CLOCK_SKEW_TOLERANCE;
    private static final Duration TIMEOUT = ClusterLocks.DEFAULT_TIMEOUT;

    private final Fixture fixture = new Fixture();
    private final List<HazelcastOversightState> states = new ArrayList<>();
    private final List<PauseDocument> documents = new ArrayList<>();
    private PrivacyCluster cluster;
    private final Fixture.Metrics metrics = new Fixture.Metrics();

    @AfterEach
    void shutDown() {
        states.forEach(HazelcastOversightState::close);
        documents.forEach(PauseDocument::close);
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
        HazelcastOversightState state = new HazelcastOversightState(on, TIMEOUT, metrics,
                new PauseDocument.Environment(nanos, wall, null));
        states.add(state);
        return state;
    }

    private HazelcastOversightState instance(Nanos nanos, Fixture.TestClock wall) {
        return instance(cluster, nanos, wall);
    }

    private HazelcastOversightState instance() {
        return instance(new Nanos(), new Fixture.TestClock());
    }

    /** A bare, unstarted document on {@code on}, so a test drives its peers and ticks itself. */
    private PauseDocument bare(PrivacyCluster on, Nanos nanos, Fixture.TestClock wall, PauseDocument.Peers peers) {
        PauseDocument document = new PauseDocument(on, metrics, TIMEOUT, new PauseDocument.Environment(nanos, wall, null));
        document.peers = peers;
        documents.add(document);
        return document;
    }

    private IMap<String, Object> map(PrivacyCluster on) {
        return on.instance().getMap(PrivacyCluster.OVERSIGHT_MAP);
    }

    private String key(PrivacyCluster on) {
        return on.keyedNames().mapKey(PrivacyCluster.OVERSIGHT_MAP, PauseDocument.CANONICAL);
    }

    private Sealer sealer(PrivacyCluster on) {
        return IdentityMaps.sealerFor(on, PrivacyMetrics.none(), ServingGate.open());
    }

    /** The authentic document now in the map of {@code on}. */
    private Doc stored(PrivacyCluster on) {
        Object value = map(on).get(key(on));
        Opened opened = sealer(on).open(PrivacyCluster.OVERSIGHT_MAP, key(on), (String) value);
        assertThat(opened.outcome()).isEqualTo(Opened.Outcome.AUTHENTIC);
        return Doc.decode(opened.plaintext());
    }

    /** Puts a sealed document straight into the map, as a holder of the keys could. */
    static void plant(PrivacyCluster on, Doc doc) {
        IMap<String, Object> map = on.instance().getMap(PrivacyCluster.OVERSIGHT_MAP);
        String key = on.keyedNames().mapKey(PrivacyCluster.OVERSIGHT_MAP, PauseDocument.CANONICAL);
        map.put(key, IdentityMaps.sealerFor(on, PrivacyMetrics.none(), ServingGate.open())
                .seal(PrivacyCluster.OVERSIGHT_MAP, key, doc.encode()));
    }

    static Id id(long n) {
        return new Id(0, n);
    }

    /** A document dated at the default test wall clock. */
    static Doc doc(long sequence, long refresh, Map<Id, Target> elements, Set<Id> tombstones) {
        return new Doc(sequence, refresh, new Fixture.TestClock().millis(), "test-issuer", elements, tombstones);
    }

    static Doc docOfTools(long sequence, String prefix, int count) {
        Map<Id, Target> elements = new HashMap<>();
        for (int i = 0; i < count; i++) {
            elements.put(new Id(prefix.hashCode(), i), Target.tool(prefix + i));
        }
        return doc(sequence, 0, elements, Set.of());
    }

    // ---- deletion, replay, restart ------------------------------------------------------------------------

    @Test
    void deletingTheDocumentMakesEveryInstanceRefuseAndPauseFreshReadZero() {
        solo();
        var one = instance();
        var two = instance();
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
        var seen = instance();
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
    void aTickRepairsAReplayedOlderDocumentWithAUnionSoNoPauseOfEitherIsDropped_andALiftedPauseStaysLifted() {
        solo();
        var seen = instance();
        seen.pauseTool("alpha");
        String older = (String) map(cluster).get(key(cluster));
        Doc replay = stored(cluster);
        seen.resumeTool("alpha");
        seen.pauseTool("beta");
        map(cluster).put(key(cluster), older);
        assertThatThrownBy(() -> seen.toolPaused("beta")).isInstanceOf(IllegalStateException.class);

        seen.document().tick();

        // The replayed document is back to the present: the replay held alpha, but its tombstone is held here.
        assertThat(seen.snapshot().pausedTools()).containsExactly("beta");
        assertThat(seen.securityStatus().pauseFresh()).isTrue();
        assertThat(instance().snapshot().pausedTools()).containsExactly("beta");
        Doc repaired = stored(cluster);
        assertThat(repaired.covers(replay)).isTrue();
        assertThat(seen.document().accepted().covers(repaired)).isTrue();
        assertThat(repaired.sequence()).isGreaterThan(replay.sequence());
    }

    @Test
    void aRestartedInstanceAcceptsAReplayedOlderDocumentOnlyInsideTheLeaseAndTheColdStartGate_theNamedResidual() {
        solo();
        var first = instance();
        first.pauseTool("seed");
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
    void aWriterThatLosesTheRaceReReadsAndBothPausesSurviveInSequenceAtAHigherOrder() {
        solo();
        var one = instance();
        var two = instance();
        one.pauseTool("seed");
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
        assertThat(two.snapshot().pausedTools()).containsExactlyInAnyOrder("seed", "alpha", "beta");
        assertThat(one.document().acceptedSequence()).isEqualTo(start + 2);
    }

    @Test
    void manyWritersOnManyInstancesLoseNoPause() throws Exception {
        solo();
        int writers = 4;
        int each = 5;
        List<HazelcastOversightState> instances = new ArrayList<>();
        for (int i = 0; i < writers; i++) {
            instances.add(instance());
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
        var seeder = instance();
        seeder.pauseTool("seed");
        var behind = instance();
        var current = instance();
        assertThat(behind.snapshot().pausedTools()).containsExactly("seed");
        // "behind" accepted the first document and never looked again; "current" then paused.
        current.pauseAll();
        long reported = current.document().acceptedSequence();
        assertThat(behind.document().acceptedSequence()).isLessThan(reported);
        map(cluster).delete(key(cluster));

        behind.document().peers = new StubPeers(reported);
        behind.document().tick();
        assertThat(map(cluster).containsKey(key(cluster))).as("no rewrite from an older document").isFalse();

        current.document().peers = new StubPeers(reported);
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
        member.document().peers = new StubPeers(member.document().acceptedSequence());

        member.document().tick();
        assertThat(map(cluster).containsKey(key(cluster))).isTrue();
        assertThat(member.scopePaused("CASE-A")).isTrue();
        assertThat(instance().scopePaused("CASE-A")).isTrue();

        // Not from a document accepted more than a lease ago.
        map(cluster).delete(key(cluster));
        nanos.advance(LEASE);
        member.document().tick();
        assertThat(map(cluster).containsKey(key(cluster))).as("accepted a lease ago").isFalse();
    }

    @Test
    void aReplayedOlderDocumentIsRepairedToASupersetOfItAndOfWhatWasHeld_elementsAndTombstones() {
        solo();
        var member = instance();
        member.pauseTool("alpha");
        member.pauseTool("beta");
        Doc replay = stored(cluster);
        member.resumeTool("alpha");
        member.pauseTool("gamma");
        Doc held = member.document().accepted();
        plant(cluster, replay);

        member.document().tick();

        Doc repaired = stored(cluster);
        assertThat(repaired.covers(replay)).isTrue();
        assertThat(repaired.covers(held)).isTrue();
        assertThat(repaired.sequence()).isEqualTo(held.sequence() + 1);
        assertThat(repaired.tombstones()).containsAll(held.tombstones()).isNotEmpty();
        assertThat(member.snapshot().pausedTools()).containsExactlyInAnyOrder("beta", "gamma");
    }

    // ---- first start (amendment 1) -------------------------------------------------------------------------

    @Test
    void theOldestMemberWritesNoFirstDocumentBeforeOneLeaseOfUptimeAndWritesItAfter() {
        solo();
        Nanos nanos = new Nanos();
        var document = bare(cluster, nanos, new Fixture.TestClock(), new StubPeers(0));

        document.tick();
        nanos.advance(LEASE.minusNanos(1));
        document.tick();
        assertThat(map(cluster).containsKey(key(cluster))).as("one nanosecond short of a lease").isFalse();

        nanos.advance(Duration.ofNanos(1));
        document.tick();
        assertThat(map(cluster).containsKey(key(cluster))).isTrue();
        Doc first = stored(cluster);
        assertThat(first.sequence()).isEqualTo(1);
        assertThat(first.refresh()).isZero();
        assertThat(first.elements()).isEmpty();
        assertThat(first.tombstones()).isEmpty();
        assertThat(document.current().all()).isFalse();
    }

    @Test
    void theOldestMemberWithAnyCardAboveZeroDoesNotWriteAndTheWaitRestartsWhenTheCardGoes() {
        solo();
        Nanos nanos = new Nanos();
        var peers = new StubPeers(0);
        var document = bare(cluster, nanos, new Fixture.TestClock(), peers);
        document.tick();
        nanos.advance(LEASE.dividedBy(2));
        peers.highest = 1;
        document.tick();
        nanos.advance(LEASE.multipliedBy(2));
        document.tick();
        assertThat(map(cluster).containsKey(key(cluster))).as("a card above zero at every look").isFalse();

        peers.highest = 0;
        document.tick();
        nanos.advance(LEASE.minusNanos(1));
        document.tick();
        assertThat(map(cluster).containsKey(key(cluster))).as("the wait restarted at the last card").isFalse();
        nanos.advance(Duration.ofNanos(1));
        document.tick();
        assertThat(map(cluster).containsKey(key(cluster))).isTrue();
    }

    @Test
    void aCardAboveZeroThatAppearsBetweenTheFirstLookAndTheWriteStopsTheFirstStart() {
        solo();
        Nanos nanos = new Nanos();
        AtomicInteger looks = new AtomicInteger();
        var document = bare(cluster, nanos, new Fixture.TestClock(), new PauseDocument.Peers() {
            @Override
            public long highestReportedSequence() {
                // The cards are read for the sample, for the pre-check and again under the lock.
                return looks.incrementAndGet() <= 2 ? 0 : 5;
            }

            @Override
            public int unverifiedCount() {
                return 0;
            }

            @Override
            public boolean isOldest() {
                return true;
            }
        });
        nanos.advance(LEASE.multipliedBy(2));

        document.tick();

        assertThat(looks.get()).as("the write was considered under the lock").isGreaterThanOrEqualTo(3);
        assertThat(map(cluster).containsKey(key(cluster))).isFalse();
    }

    @Test
    void aMemberThatIsNotTheOldestAnUnverifiedMemberOrACardLessMemberMeansNoFirstStart() {
        solo();
        Nanos nanos = new Nanos();
        var notOldest = new StubPeers(0);
        notOldest.oldest = false;
        var unverified = new StubPeers(0);
        unverified.unverified = 1;
        var first = bare(cluster, nanos, new Fixture.TestClock(), notOldest);
        var second = bare(cluster, nanos, new Fixture.TestClock(), unverified);

        nanos.advance(LEASE.multipliedBy(3));
        first.tick();
        second.tick();

        assertThat(map(cluster).containsKey(key(cluster))).isFalse();
        assertThatThrownBy(first::current).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(second::current).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void twoEligibleMembersRaceToWriteTheFirstDocumentAndOneDocumentResultsWithNoConflictCounted() {
        solo();
        Nanos nanos = new Nanos();
        var one = bare(cluster, nanos, new Fixture.TestClock(), new StubPeers(0));
        var two = bare(cluster, nanos, new Fixture.TestClock(), new StubPeers(0));
        nanos.advance(LEASE);
        AtomicInteger hooks = new AtomicInteger();
        one.beforeCommit = () -> {
            if (hooks.incrementAndGet() == 1) {
                two.tick();
            }
        };

        one.tick();

        assertThat(hooks.get()).as("the first writer lost its putIfAbsent").isEqualTo(1);
        assertThat(stored(cluster).sequence()).isEqualTo(1);
        assertThat(one.accepted().sequence()).isEqualTo(1);
        assertThat(two.accepted().sequence()).isEqualTo(1);
        assertThat(metrics.count(Metric.CLUSTER_PAUSE_CONFLICT)).isZero();
        assertThat(one.current().all()).isFalse();
    }

    private void threeMembersStartAndTick(String prefix) {
        String name = TestMembers.uniqueName(prefix);
        int first = FreePorts.consecutive(3);
        List<String> all = List.of("127.0.0.1:" + first, "127.0.0.1:" + (first + 1), "127.0.0.1:" + (first + 2));
        ClusterSecuritySettings settings = Fixture.settings(UnverifiedMemberAction.REFUSE);
        List<PrivacyCluster> members = new ArrayList<>();
        List<Nanos> clocks = new ArrayList<>();
        List<HazelcastOversightState> started = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            PrivacyCluster member = fixture.keyed(name, first + i, all, TestMembers.KEYS, settings,
                    new Fixture.Metrics(), new RecordingLogger(), false);
            members.add(member);
            Nanos nanos = new Nanos();
            clocks.add(nanos);
            started.add(instance(member, nanos, fixture.clock));
        }
        for (PrivacyCluster member : members) {
            assertThat(Fixture.awaitMembers(member, 3)).isTrue();
        }
        assertThat(TestMembers.settled(30, members.toArray(new PrivacyCluster[0]))).isTrue();
        for (PrivacyCluster member : members) {
            member.memberCards().orElseThrow().tick();
        }
        for (PrivacyCluster member : members) {
            assertThat(member.memberCards().orElseThrow().unverifiedCount()).isZero();
        }
        for (var state : started) {
            assertThatThrownBy(state::allPaused).as("nothing before the first document").isInstanceOf(IllegalStateException.class);
        }
        for (Nanos clock : clocks) {
            clock.advance(Fixture.settings(UnverifiedMemberAction.REFUSE).pauseLease());
        }
        for (var state : started) {
            state.document().tick();
        }

        for (var state : started) {
            assertThat(state.allPaused()).isFalse();
            assertThat(state.document().acceptedOrder()).as("exactly one first document, never refreshed").containsExactly(1, 0);
        }
        Doc written = stored(members.get(0));
        assertThat(written.sequence()).isEqualTo(1);
        assertThat(written.elements()).isEmpty();
    }

    @Test
    void threeMembersStartedTogetherProduceExactlyOneFirstDocumentAndEveryReadWorks() {
        threeMembersStartAndTick("first-start-3");
    }

    @Test
    void aFullClusterRestartProducesExactlyOneFirstDocument() {
        threeMembersStartAndTick("first-start-restart-a");
        states.forEach(HazelcastOversightState::close);
        states.clear();
        fixture.closeAll();

        threeMembersStartAndTick("first-start-restart-b");
    }

    @Test
    void aCardLessMemberPresentMeansNoFirstStartUntilItLeaves() {
        String name = TestMembers.uniqueName("first-start-outsider");
        int first = FreePorts.consecutive(3);
        List<String> all = List.of("127.0.0.1:" + first, "127.0.0.1:" + (first + 1), "127.0.0.1:" + (first + 2));
        ClusterSecuritySettings settings = Fixture.settings(UnverifiedMemberAction.REFUSE);
        PrivacyCluster a = fixture.keyed(name, first, all, TestMembers.KEYS, settings, new Fixture.Metrics(),
                new RecordingLogger(), false);
        Nanos nanosA = new Nanos();
        var stateA = instance(a, nanosA, fixture.clock);
        PrivacyCluster b = fixture.keyed(name, first + 1, all, TestMembers.KEYS, settings, new Fixture.Metrics(),
                new RecordingLogger(), false);
        Nanos nanosB = new Nanos();
        var stateB = instance(b, nanosB, fixture.clock);
        com.hazelcast.core.HazelcastInstance outsider = Fixture.plain(name, first + 2, all);
        try {
            assertThat(Fixture.awaitMembers(a, 3)).isTrue();
            assertThat(Fixture.awaitMembers(b, 3)).isTrue();
            a.memberCards().orElseThrow().tick();
            b.memberCards().orElseThrow().tick();
            assertThat(a.memberCards().orElseThrow().unverifiedCount()).isEqualTo(1);

            nanosA.advance(settings.pauseLease().multipliedBy(2));
            nanosB.advance(settings.pauseLease().multipliedBy(2));
            stateA.document().tick();
            stateB.document().tick();
            assertThat(a.instance().getMap(PrivacyCluster.OVERSIGHT_MAP).isEmpty()).as("no first document").isTrue();
            assertThatThrownBy(stateA::allPaused).isInstanceOf(IllegalStateException.class);

            outsider.shutdown();
            assertThat(Fixture.awaitMembers(a, 2)).isTrue();
            assertThat(Fixture.awaitMembers(b, 2)).isTrue();
            a.memberCards().orElseThrow().tick();
            b.memberCards().orElseThrow().tick();
            stateA.document().tick();
            stateB.document().tick();

            assertThat(stateA.allPaused()).isFalse();
            assertThat(stateB.allPaused()).isFalse();
            assertThat(stored(a).sequence()).isEqualTo(1);
        } finally {
            outsider.shutdown();
        }
    }

    /** A pause at sequence 2 held by A and B; the document is then deleted, and C (the oldest) restarts with no memory. */
    private record Held(HazelcastOversightState a, HazelcastOversightState b, long sequence) {
    }

    private Held aAndBHoldAPause() {
        var a = instance();
        a.pauseTool("seed");
        a.pauseTool("kept");
        var b = instance();
        assertThat(b.toolPaused("kept")).isTrue();
        long sequence = a.document().acceptedSequence();
        assertThat(sequence).isEqualTo(2);
        return new Held(a, b, sequence);
    }

    @Test
    void whenTheDocumentIsDeletedAndTheRestartedOldestSeesTheCardsItWritesNothingAndTheHoldersRestoreIt() {
        solo();
        Held held = aAndBHoldAPause();
        map(cluster).delete(key(cluster));
        Nanos nanos = new Nanos();
        var peers = new StubPeers(held.sequence());
        var c = bare(cluster, nanos, new Fixture.TestClock(), peers);

        nanos.advance(LEASE.multipliedBy(2));
        c.tick();
        assertThat(map(cluster).containsKey(key(cluster))).as("a card above zero: not a first start").isFalse();
        assertThatThrownBy(c::current).isInstanceOf(IllegalStateException.class);

        held.a().document().peers = new StubPeers(held.sequence());
        held.a().document().tick();
        assertThat(c.current().tools()).containsExactlyInAnyOrder("seed", "kept");
        assertThat(stored(cluster).sequence()).as("the holder's restore, never a first document").isEqualTo(held.sequence());
        assertThat(held.b().toolPaused("kept")).isTrue();
    }

    @Test
    void whenTheCardsAreAlsoDeletedTheRestartedOldestStillWaitsAnUptimeAndTheHoldersRestoreFirst() {
        solo();
        Held held = aAndBHoldAPause();
        map(cluster).delete(key(cluster));
        Nanos nanos = new Nanos();
        var c = bare(cluster, nanos, new Fixture.TestClock(), new StubPeers(0));

        nanos.advance(LEASE.dividedBy(2));
        c.tick();
        assertThat(map(cluster).containsKey(key(cluster))).as("no card, but not a lease of uptime yet").isFalse();
        held.a().document().peers = new StubPeers(0);
        held.a().document().tick();
        nanos.advance(LEASE.dividedBy(2));
        c.tick();

        assertThat(c.current().tools()).containsExactlyInAnyOrder("seed", "kept");
        Doc now = stored(cluster);
        assertThat(now.sequence()).isEqualTo(held.sequence());
        assertThat(now.elements()).hasSize(2);
        assertThat(held.b().toolPaused("kept")).isTrue();
    }

    @Test
    void staleSequenceZeroCardsReplayedWhileTheOldestWaitsAreBeatenByTheRealCardsAndThePauseSurvivesEverywhere() {
        solo();
        Held held = aAndBHoldAPause();
        map(cluster).delete(key(cluster));
        Nanos nanos = new Nanos();
        var peers = new StubPeers(0);
        var c = bare(cluster, nanos, new Fixture.TestClock(), peers);

        nanos.advance(LEASE.dividedBy(2));
        c.tick();
        // The real card of A or B arrives inside the lease: the wait restarts.
        peers.highest = held.sequence();
        c.tick();
        peers.highest = 0;
        nanos.advance(LEASE.dividedBy(2).plusNanos(1));
        c.tick();
        assertThat(map(cluster).containsKey(key(cluster))).as("the wait restarted at the real card").isFalse();

        // A Doc(1) forced in anyway (the commit seam stands for a write the rules did not allow) is merged.
        Doc forced = Doc.empty(1, 0, new Fixture.TestClock().millis(), "forced");
        assertThat(c.commit(null, null, null, forced, null, Set.of())).isEqualTo(Outcome.WRITTEN);
        held.a().document().peers = new StubPeers(held.sequence());
        held.a().document().tick();
        held.b().document().peers = new StubPeers(held.sequence());
        held.b().document().tick();

        for (var state : List.of(held.a(), held.b())) {
            assertThat(state.snapshot().pausedTools()).containsExactlyInAnyOrder("seed", "kept");
        }
        assertThat(c.current().tools()).containsExactlyInAnyOrder("seed", "kept");
    }

    // ---- lapsed-lease recovery (amendment 2) ---------------------------------------------------------------

    private RecordingLogger captureWarnings(PauseDocument document) {
        RecordingLogger recording = new RecordingLogger();
        document.log = recording.logger();
        return recording;
    }

    @Test
    void aSoloMembersLapsedLeaseIsRecoveredByOneTickWithTheSameSequenceAndRefreshPlusOne() {
        solo();
        Nanos nanos = new Nanos();
        var member = instance(nanos, new Fixture.TestClock());
        RecordingLogger warnings = captureWarnings(member.document());
        member.pauseTool("search");
        long[] order = member.document().acceptedOrder();

        nanos.advance(LEASE);
        assertThatThrownBy(() -> member.toolPaused("search")).isInstanceOf(IllegalStateException.class);
        assertThat(metrics.gauge(Metric.CLUSTER_PAUSE_FRESH)).isEqualTo(0.0);

        member.document().tick();

        assertThat(member.toolPaused("search")).isTrue();
        assertThat(metrics.gauge(Metric.CLUSTER_PAUSE_FRESH)).isEqualTo(1.0);
        assertThat(member.document().acceptedOrder()).containsExactly(order[0], order[1] + 1);
        assertThat(warnings.lines()).hasSize(1);
        assertThat(warnings.lines().get(0).message()).doesNotContain("search").doesNotContain("127.0.0.1");

        // Rate limited to one line per lease: a recovery one lease later logs again, and no sooner.
        nanos.advance(LEASE);
        member.document().tick();
        assertThat(warnings.lines()).hasSize(2);
    }

    @Test
    void theIdenticalBytesPutBackDoNotResetFreshness() {
        solo();
        Nanos nanos = new Nanos();
        var member = instance(nanos, new Fixture.TestClock());
        member.pauseTool("search");
        String same = (String) map(cluster).get(key(cluster));
        nanos.advance(LEASE);

        map(cluster).put(key(cluster), same);

        assertThatThrownBy(() -> member.toolPaused("search")).isInstanceOf(IllegalStateException.class);
        assertThat(member.document().fresh()).isFalse();
    }

    @Test
    void aLapsedLeaseIsNotRecoveredWhenAPeerCardIsHigherAMemberIsUnverifiedTheStoredValueIsBehindOrAbsentOrTheMemberRestarted() {
        solo();
        Nanos nanos = new Nanos();
        var member = instance(nanos, new Fixture.TestClock());
        member.pauseTool("a");
        String replay = (String) map(cluster).get(key(cluster));
        member.pauseTool("b");
        long[] order = member.document().acceptedOrder();
        nanos.advance(LEASE);

        member.document().peers = new StubPeers(order[0] + 1);
        member.document().tick();
        assertThat(member.document().acceptedOrder()).as("a peer card is higher").containsExactly(order);

        StubPeers unverified = new StubPeers(order[0]);
        unverified.unverified = 1;
        member.document().peers = unverified;
        member.document().tick();
        assertThat(member.document().acceptedOrder()).as("a member is unverified").containsExactly(order);

        member.document().peers = new StubPeers(order[0]);
        map(cluster).put(key(cluster), replay);
        member.document().tick();
        assertThat(member.document().acceptedOrder()).as("the stored value is a replay (behind)").containsExactly(order);
        assertThat(stored(cluster).sequence()).isLessThan(order[0]);

        map(cluster).delete(key(cluster));
        member.document().tick();
        assertThat(map(cluster).containsKey(key(cluster))).as("absent").isFalse();

        // A restarted member that holds nothing never recovers a lapsed lease: the gate and the lease still apply.
        plant(cluster, member.document().accepted());
        Fixture.TestClock pastGate = new Fixture.TestClock();
        pastGate.advance(LEASE.plus(SKEW).plusMillis(1));
        Nanos restartedNanos = new Nanos();
        var restarted = bare(cluster, restartedNanos, pastGate, new StubPeers(0));
        restarted.tick();
        restartedNanos.advance(LEASE);
        restarted.tick();
        assertThat(restarted.acceptedOrder()).isNull();
        assertThatThrownBy(restarted::current).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aLapsedLeaseOverAnAbsentDocumentIsRecoveredOnlyByAnOperatorPauseWhichKeepsTheHeldPauses() {
        solo();
        Nanos nanos = new Nanos();
        var member = instance(nanos, new Fixture.TestClock());
        member.pauseTool("kept");
        map(cluster).delete(key(cluster));
        nanos.advance(LEASE);

        member.document().tick();
        assertThat(map(cluster).containsKey(key(cluster))).isFalse();

        member.pauseTool("added");

        assertThat(member.snapshot().pausedTools()).containsExactlyInAnyOrder("kept", "added");
        assertThat(instance().snapshot().pausedTools()).containsExactlyInAnyOrder("kept", "added");
    }

    // ---- attacker documents --------------------------------------------------------------------------------

    @Test
    void anAttackerDocumentFromAKeylessMemberOrClientNeverLiftsAPauseAndIsCounted() {
        solo();
        var victim = instance();
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

            // A tick never overwrites it.
            victim.document().tick();
            assertThat(raw.get(key)).as("a tick leaves %s", forgery).isEqualTo(forgery);
        }

        // The operator's pause replaces the forged entry, and keeps what the victim held.
        victim.pauseTool("search");
        assertThat(victim.allPaused()).isTrue();
        assertThat(victim.toolPaused("search")).isTrue();
        assertThat(instance().allPaused()).isTrue();
    }

    @Test
    void aDocumentThatNamesAHeldIdAsAnotherTargetIsNotAcceptedIsCountedAsTamperAndWarnsOnce() {
        solo();
        var member = instance();
        RecordingLogger warnings = captureWarnings(member.document());
        member.pauseTool("search");
        Id held = member.document().accepted().elements().keySet().iterator().next();
        Doc clash = doc(member.document().acceptedSequence() + 5, 0, Map.of(held, Target.tool("rival-name")), Set.of());
        plant(cluster, clash);
        Doc before = member.document().accepted();
        int tamper = metrics.count(Metric.CLUSTER_SEAL_TAMPER);

        assertThatThrownBy(member::allPaused).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(member::allPaused).isInstanceOf(IllegalStateException.class);

        assertThat(member.document().accepted()).isEqualTo(before);
        assertThat(metrics.count(Metric.CLUSTER_SEAL_TAMPER)).isGreaterThan(tamper);
        assertThat(warnings.lines()).as("rate limited").hasSize(1);
        assertThat(warnings.lines().get(0).message()).doesNotContain("search").doesNotContain("rival-name");
        member.document().tick();
        assertThat(stored(cluster).sequence()).as("not repaired by a tick").isEqualTo(clash.sequence());
        member.pauseTool("again");
        assertThat(member.snapshot().pausedTools()).containsExactlyInAnyOrder("search", "again");
    }

    @Test
    void aResumeIsRefusedWhileTheDocumentIsNotFreshAndAPauseIsStillAccepted() {
        solo();
        var state = instance();
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

    @Test
    void plaintextZeroPointSevenRandomAndWrongKeyEnvelopesAreNeverOverwrittenByATickAndAnOperatorPauseKeepsTheHeldPauses() {
        solo();
        var member = instance();
        member.pauseTool("kept");
        ClusterKeys foreign = new ClusterKeys("other-cluster-1", Fixture.filled('x'), null, null, null, null,
                "other-name-1", Fixture.filled('y'));
        String wrongKey = Sealer.builder(foreign, PrivacyMetrics.none()).build()
                .seal(PrivacyCluster.OVERSIGHT_MAP, key(cluster), "x".getBytes());
        for (Object forged : List.of("plaintext", Boolean.TRUE, "dps1.solo-cluster." + b64(16) + "." + b64(12) + "." + b64(48), wrongKey)) {
            int before = metrics.count(Metric.CLUSTER_SEAL_TAMPER) + metrics.count(Metric.CLUSTER_SEAL_UNSUPPORTED_FORMAT);
            map(cluster).put(key(cluster), forged);
            member.document().tick();
            assertThat(map(cluster).get(key(cluster))).isEqualTo(forged);
            assertThat(metrics.count(Metric.CLUSTER_SEAL_TAMPER) + metrics.count(Metric.CLUSTER_SEAL_UNSUPPORTED_FORMAT))
                    .isGreaterThan(before);
            member.pauseTool("added-" + forged.hashCode());
            assertThat(member.snapshot().pausedTools()).contains("kept");
        }
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

    @Test
    void anUnverifiedMemberRefusesAResumeEvenWhileTheMemberStillServes() {
        solo();
        var member = instance();
        member.pauseTool("search");
        StubPeers peers = new StubPeers(0);
        peers.unverified = 1;
        member.document().peers = peers;

        assertThatThrownBy(() -> member.resumeTool("search")).isInstanceOf(ClusterOperationRefusedException.class);
        assertThat(member.toolPaused("search")).isTrue();
    }

    @Test
    void aResumeIsRefusedAsUnavailableWhenAPeerCardIsAboveTheDocumentOrNoDocumentWasAccepted() {
        solo();
        var member = instance();
        member.pauseTool("search");
        member.document().peers = new StubPeers(member.document().acceptedSequence() + 1);
        assertThatThrownBy(() -> member.resumeTool("search")).isInstanceOf(IllegalStateException.class)
                .isNotInstanceOf(ClusterOperationRefusedException.class);
        assertThat(member.toolPaused("search")).isTrue();

        map(cluster).delete(key(cluster));
        var cold = instance();
        assertThatThrownBy(() -> cold.resumeTool("search")).isInstanceOf(IllegalStateException.class)
                .isNotInstanceOf(ClusterOperationRefusedException.class);
        assertThat(map(cluster).containsKey(key(cluster))).as("a refused resume writes nothing").isFalse();
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
        OversightMapsBoundary.checkMapConfigs(cluster.instance());
    }

    @Test
    void theBoundaryFailsRatherThanSkipsWhenTheNamesAreTheTestNamesAndTheEnvelopeDoesNotOpen() {
        int port = FreePorts.consecutive(1);
        cluster = fixture.keyed(TestMembers.uniqueName("boundary-fail"), port, List.of("127.0.0.1:" + port),
                TestMembers.KEYS, Fixture.settings(UnverifiedMemberAction.REFUSE), new Fixture.Metrics(),
                new RecordingLogger(), false);
        var state = instance(new Nanos(), fixture.clock);
        state.pauseTool("search");
        ClusterKeys foreign = new ClusterKeys("other-cluster-1", Fixture.filled('x'), null, null, null, null,
                "test-name-1", Fixture.filled('n'));
        String wrongKey = Sealer.builder(foreign, PrivacyMetrics.none()).build()
                .seal(PrivacyCluster.OVERSIGHT_MAP, key(cluster), "x".getBytes());
        IMap<Object, Object> contents = cluster.instance().getMap(PrivacyCluster.OVERSIGHT_MAP);
        contents.put(key(cluster), wrongKey);
        Inputs inputs = new Inputs(cluster.keyedNames(), null, "CASE-42", "subject-42", null, "synthetic", "ap-1",
                "alice", null, "search");

        assertThatThrownBy(() -> new OversightMapsBoundary().check(PrivacyCluster.OVERSIGHT_MAP, contents, inputs))
                .isInstanceOf(AssertionError.class);
    }

    // ---- the guard clauses of the commit (amendment 4) -----------------------------------------------------

    private record Setup(HazelcastOversightState state, Object raw, Doc stored) {
    }

    private Setup pausedOnce() {
        solo();
        var state = instance();
        state.pauseTool("kept");
        return new Setup(state, map(cluster).get(key(cluster)), stored(cluster));
    }

    private void assertRefusedAndUntouched(Setup setup, Outcome expected, Doc next, Id fresh, Set<Id> tombstones) {
        Outcome outcome = setup.state().document().commit(setup.raw(), setup.stored(), setup.stored(), next, fresh,
                tombstones);
        assertThat(outcome).isEqualTo(expected);
        assertThat(map(cluster).get(key(cluster))).as("nothing was written").isEqualTo(setup.raw());
    }

    @Test
    void aWriteThatWouldDropAStoredElementOrTombstoneIsRefusedWithNothingWritten() {
        Setup setup = pausedOnce();
        Doc stored = setup.stored();
        assertRefusedAndUntouched(setup, Outcome.DROPS_STORED,
                doc(stored.sequence() + 1, 0, Map.of(), Set.of()), null, Set.of());

        // With a tombstone stored, a next that keeps the element but not the tombstone drops it.
        setup.state().resumeTool("kept");
        Setup resumed = new Setup(setup.state(), map(cluster).get(key(cluster)), stored(cluster));
        assertThat(resumed.stored().tombstones()).hasSize(1);
        assertRefusedAndUntouched(resumed, Outcome.DROPS_STORED,
                doc(resumed.stored().sequence() + 1, 0, resumed.stored().elements(), Set.of()), null, Set.of());
    }

    @Test
    void aWriteThatHoldsAnElementOutsideTheBaseAndTheOneFreshPauseIsRefused() {
        Setup setup = pausedOnce();
        Map<Id, Target> elements = new HashMap<>(setup.stored().elements());
        elements.put(id(99), Target.tool("rogue"));
        Doc next = doc(setup.stored().sequence() + 1, 0, elements, Set.of());
        assertRefusedAndUntouched(setup, Outcome.ADDS_ELEMENT, next, null, Set.of());
        assertRefusedAndUntouched(setup, Outcome.ADDS_ELEMENT, next, id(100), Set.of());
        // The fresh element itself is allowed.
        Outcome allowed = setup.state().document().commit(setup.raw(), setup.stored(), setup.stored(), next, id(99),
                Set.of());
        assertThat(allowed).isEqualTo(Outcome.WRITTEN);
    }

    @Test
    void aWriteThatAddsATombstoneTheChangeMayNotAddIsRefused_aPauseAddsNoneAndAResumeOnlyItsOwn() {
        Setup setup = pausedOnce();
        Id live = setup.stored().elements().keySet().iterator().next();
        Doc tombstoned = doc(setup.stored().sequence() + 1, 0, setup.stored().elements(), Set.of(live));
        // A pause (no tombstones allowed) that tombstones.
        assertRefusedAndUntouched(setup, Outcome.ADDS_TOMBSTONE, tombstoned, null, Set.of());
        // A resume of another target, whose allowed ids do not include this one.
        assertRefusedAndUntouched(setup, Outcome.ADDS_TOMBSTONE, tombstoned, null, Set.of(id(7)));
        // A tombstone for an id that is in no element is outside the base too.
        Doc unknown = doc(setup.stored().sequence() + 1, 0, setup.stored().elements(), Set.of(id(12345)));
        assertRefusedAndUntouched(setup, Outcome.ADDS_TOMBSTONE, unknown, null, Set.of(live));
        // Its own is allowed.
        assertThat(setup.state().document().commit(setup.raw(), setup.stored(), setup.stored(), tombstoned, null,
                Set.of(live))).isEqualTo(Outcome.WRITTEN);
    }

    @Test
    void aWriteThatIsNotAboveTheStoredOrderAndNotItsRefreshIsRefused() {
        Setup setup = pausedOnce();
        Doc stored = setup.stored();
        // Same order, a new element: not above, not a refresh (the sets differ).
        assertRefusedAndUntouched(setup, Outcome.ORDER,
                doc(stored.sequence(), stored.refresh(), mapPlus(stored.elements(), id(5), Target.scope("s")),
                        Set.of()), id(5), Set.of());
        // A lower order.
        assertRefusedAndUntouched(setup, Outcome.ORDER,
                doc(stored.sequence() - 1, 0, stored.elements(), Set.of()), null, Set.of());
        // The very same order again, with the same sets, is neither above nor a refresh successor.
        assertRefusedAndUntouched(setup, Outcome.ORDER,
                doc(stored.sequence(), stored.refresh(), stored.elements(), Set.of()), null, Set.of());
        // A genuine refresh successor is written.
        assertThat(setup.state().document().commit(setup.raw(), stored, stored,
                doc(stored.sequence(), stored.refresh() + 1, stored.elements(), Set.of()), null, Set.of()))
                .isEqualTo(Outcome.WRITTEN);
    }

    @Test
    void aWriteOverAnEntryAnotherWriterChangedLosesItsRaceAndWritesNothing() {
        Setup setup = pausedOnce();
        instance().pauseTool("rival");
        Doc next = doc(setup.stored().sequence() + 1, 0, setup.stored().elements(), Set.of());

        Outcome outcome = setup.state().document().commit(setup.raw(), setup.stored(), setup.stored(), next, null,
                Set.of());

        assertThat(outcome).isEqualTo(Outcome.LOST_RACE);
        assertThat(stored(cluster).elements()).hasSize(2);
    }

    @Test
    void aRivalThatWritesAHigherDocumentWithAnExtraPauseBetweenReadAndCommitLeavesBothInTheFinalDocumentAtAHigherOrder() {
        solo();
        var writer = instance();
        var rival = instance();
        writer.pauseTool("seed");
        AtomicInteger hooks = new AtomicInteger();
        writer.document().beforeCommit = () -> {
            if (hooks.incrementAndGet() == 1) {
                rival.pauseTool("rival-1");
                rival.pauseTool("rival-2");
            }
        };
        long before = writer.document().acceptedSequence();

        writer.pauseTool("writer");

        Doc finalDoc = stored(cluster);
        assertThat(writer.snapshot().pausedTools()).containsExactlyInAnyOrder("seed", "rival-1", "rival-2", "writer");
        assertThat(finalDoc.sequence()).isGreaterThan(before + 2);
    }

    @Test
    void aTickNeverWritesOverAStoredDocumentThatAlreadyCoversWhatItHolds() {
        solo();
        var member = instance();
        member.pauseTool("a");
        Doc held = member.document().accepted();
        Doc superset = doc(held.sequence() + 3, 0, mapPlus(held.elements(), id(50), Target.tool("extra")),
                held.tombstones());
        plant(cluster, superset);
        Object raw = map(cluster).get(key(cluster));

        member.document().tick();

        assertThat(map(cluster).get(key(cluster))).as("the superset is left alone").isEqualTo(raw);
        assertThat(member.snapshot().pausedTools()).containsExactlyInAnyOrder("a", "extra");
    }

    private static Map<Id, Target> mapPlus(Map<Id, Target> elements, Id id, Target target) {
        Map<Id, Target> more = new HashMap<>(elements);
        more.put(id, target);
        return more;
    }

    // ---- pauses are never deduped; a resume lifts what it saw ----------------------------------------------

    @Test
    void aPauseAlwaysMintsAFreshElementAndOneResumeLiftsEveryPauseOfTheTarget() {
        solo();
        var state = instance();
        state.pauseTool("search");
        state.pauseTool("search");
        state.pauseTool("search");
        assertThat(state.document().accepted().elements()).hasSize(3);

        state.resumeTool("search");

        assertThat(state.toolPaused("search")).isFalse();
        assertThat(state.document().accepted().tombstones()).hasSize(3);
    }

    @Test
    void aResumeLiftsOnlyThePausesOfItsOwnTarget_notOtherToolsOtherKindsOrTheGlobalPause() {
        solo();
        var state = instance();
        state.pauseTool("search");
        state.pauseTool("lookup");
        state.pauseScope("search");
        state.pauseAll();

        state.resumeTool("search");

        assertThat(state.toolPaused("search")).isFalse();
        assertThat(state.toolPaused("lookup")).isTrue();
        assertThat(state.scopePaused("search")).as("a scope of the same name is another target").isTrue();
        assertThat(state.allPaused()).isTrue();
        assertThat(state.document().accepted().tombstones()).hasSize(1);

        state.resumeAll();
        assertThat(state.allPaused()).isFalse();
        assertThat(state.toolPaused("lookup")).isTrue();
        assertThat(state.scopePaused("search")).isTrue();
        assertThat(state.document().accepted().tombstones()).hasSize(2);
    }

    @Test
    void aConcurrentPauseOfTheSameTargetBetweenAResumesReadAndCommitStaysInForceAndASecondResumeClearsIt() {
        solo();
        var state = instance();
        var rival = instance();
        state.pauseTool("X");
        Set<Id> observed = new HashSet<>(state.document().accepted().live(Target.tool("X")));
        AtomicInteger hooks = new AtomicInteger();
        state.document().beforeCommit = () -> {
            if (hooks.incrementAndGet() == 1) {
                rival.pauseTool("X");
            }
        };

        state.resumeTool("X");

        assertThat(hooks.get()).as("the resume lost its race and retried").isEqualTo(2);
        assertThat(state.toolPaused("X")).as("the pause that landed mid-resume stays in force").isTrue();
        assertThat(state.document().accepted().tombstones()).isEqualTo(observed);
        assertThat(state.snapshot().pausedTools()).containsExactly("X");

        state.document().beforeCommit = () -> { };
        state.resumeTool("X");
        assertThat(state.toolPaused("X")).isFalse();
    }

    @Test
    void aResumeWhoseObservedIdsWereAllTombstonedElsewhereBeforeItsRetryWritesNothingAndSucceeds() {
        solo();
        var state = instance();
        var rival = instance();
        state.pauseTool("X");
        AtomicInteger hooks = new AtomicInteger();
        AtomicLong sequenceAfterRival = new AtomicLong();
        state.document().beforeCommit = () -> {
            if (hooks.incrementAndGet() == 1) {
                rival.resumeTool("X");
                sequenceAfterRival.set(rival.document().acceptedSequence());
            }
        };

        state.resumeTool("X");

        assertThat(state.toolPaused("X")).isFalse();
        assertThat(state.document().acceptedSequence()).as("no write of its own").isEqualTo(sequenceAfterRival.get());
        assertThat(hooks.get()).isEqualTo(1);
    }

    // ---- the cap and the format bound (amendment 4) --------------------------------------------------------

    @Test
    void eightThousandOneHundredSeventySixPausesFitAndTheNextOneIsRefusedWithNothingWritten_aResumeAtTheCapIsAccepted() {
        solo();
        var state = instance();
        plant(cluster, docOfTools(1000, "t", PauseDocument.PAUSE_CAP - 1));
        assertThat(state.snapshot().pausedTools()).hasSize(PauseDocument.PAUSE_CAP - 1);

        state.pauseTool("the-8176th");
        Object raw = map(cluster).get(key(cluster));

        assertThatThrownBy(() -> state.pauseTool("the-8177th")).isInstanceOf(ClusterOperationRefusedException.class)
                .satisfies(refusal -> assertThat(((ClusterOperationRefusedException) refusal).code())
                        .isEqualTo("CLUSTER_OPERATION_REFUSED"));
        assertThat(map(cluster).get(key(cluster))).as("refused, never truncated").isEqualTo(raw);
        assertThat(state.document().accepted().elements()).hasSize(PauseDocument.PAUSE_CAP);

        state.resumeTool("the-8176th");
        assertThat(state.toolPaused("the-8176th")).isFalse();
    }

    @Test
    void theCapCountsElementsOnly_manyResumedPausesDoNotBlockANonAllPause() {
        solo();
        var state = instance();
        // 4,200 elements, every one tombstoned: elements plus tombstones is above 8,176, elements alone are not.
        Doc doc = docOfTools(1000, "t", 4_200);
        plant(cluster, new Doc(doc.sequence(), 0, doc.issuedAtMillis(), "test", doc.elements(),
                new HashSet<>(doc.elements().keySet())));
        assertThat(state.snapshot().pausedTools()).isEmpty();

        state.pauseTool("fits");

        assertThat(state.toolPaused("fits")).isTrue();
    }

    @Test
    void pauseAllCyclesAtTheCapAreNeverRefused_theReserveIsTheWholeGapToTheFormatBound() {
        solo();
        var state = instance();
        plant(cluster, docOfTools(1000, "t", PauseDocument.PAUSE_CAP));
        assertThat(state.snapshot().pausedTools()).hasSize(PauseDocument.PAUSE_CAP);
        assertThatThrownBy(() -> state.pauseTool("over")).isInstanceOf(ClusterOperationRefusedException.class);

        for (int cycle = 0; cycle < 20; cycle++) {
            state.pauseAll();
            assertThat(state.allPaused()).isTrue();
            state.resumeAll();
            assertThat(state.allPaused()).isFalse();
        }
        state.pauseAll();

        assertThat(state.allPaused()).isTrue();
        assertThat(state.document().accepted().elements()).hasSize(PauseDocument.PAUSE_CAP + 21);
        assertThatThrownBy(() -> state.pauseTool("still-over")).isInstanceOf(ClusterOperationRefusedException.class);
    }

    @Test
    void pauseAllAtTheFormatBoundIsRefusedWithCodeAndNothingIsWritten() {
        solo();
        var state = instance();
        plant(cluster, docOfTools(1000, "t", PauseDocument.FORMAT_BOUND));
        assertThat(state.snapshot().pausedTools()).hasSize(PauseDocument.FORMAT_BOUND);
        Object raw = map(cluster).get(key(cluster));

        assertThatThrownBy(state::pauseAll).isInstanceOf(ClusterOperationRefusedException.class);
        assertThat(map(cluster).get(key(cluster))).isEqualTo(raw);
    }

    @Test
    void anEncodeOverTheFormatBoundIsRefusedNeverTruncated() {
        Doc tooManyElements = docOfTools(1, "t", PauseDocument.FORMAT_BOUND + 1);
        assertThatThrownBy(tooManyElements::encode).isInstanceOf(IllegalStateException.class);
        Set<Id> tombstones = new HashSet<>();
        for (int i = 0; i <= PauseDocument.FORMAT_BOUND; i++) {
            tombstones.add(id(i));
        }
        Doc tooManyTombstones = doc(1, 0, Map.of(), tombstones);
        assertThatThrownBy(tooManyTombstones::encode).isInstanceOf(IllegalStateException.class);

        Setup setup = pausedOnce();
        Doc next = docOfTools(setup.stored().sequence() + 1, "u", PauseDocument.FORMAT_BOUND + 1);
        Map<Id, Target> elements = new HashMap<>(next.elements());
        elements.putAll(setup.stored().elements());
        assertRefusedAndUntouched(setup, Outcome.OVERSIZE,
                doc(setup.stored().sequence() + 1, 0, elements, Set.of()), null, Set.of());
    }

    /** The plaintext of a document with {@code elements} elements and {@code tombstones} tombstones, unchecked. */
    private static byte[] plaintext(int elements, int tombstones) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeByte(PauseDocument.FORMAT);
        out.writeLong(1000);
        out.writeLong(0);
        out.writeLong(new Fixture.TestClock().millis());
        out.writeInt(1);
        out.write('i');
        out.writeInt(elements);
        for (int i = 0; i < elements; i++) {
            out.writeLong(7);
            out.writeLong(i);
            out.writeByte(Kind.TOOL.ordinal());
            out.writeInt(1);
            out.write('t');
        }
        out.writeInt(tombstones);
        for (int i = 0; i < tombstones; i++) {
            out.writeLong(9);
            out.writeLong(i);
        }
        return bytes.toByteArray();
    }

    @Test
    void aDocumentAboveTheFormatBoundDecodesAsUnreadableAndCountedAsTamper_oneAtTheBoundOrWellBelowItOpens() throws Exception {
        solo();
        var state = instance();
        state.pauseTool("seed");
        Sealer sealer = sealer(cluster);
        for (byte[] oversize : List.of(plaintext(PauseDocument.FORMAT_BOUND + 1, 0), plaintext(0, PauseDocument.FORMAT_BOUND + 1))) {
            assertThat(Doc.decode(oversize)).isNull();
            int before = metrics.count(Metric.CLUSTER_SEAL_TAMPER);
            map(cluster).put(key(cluster), sealer.seal(PrivacyCluster.OVERSIGHT_MAP, key(cluster), oversize));
            assertThatThrownBy(state::allPaused).isInstanceOf(IllegalStateException.class);
            assertThat(metrics.count(Metric.CLUSTER_SEAL_TAMPER)).isGreaterThan(before);
        }
        assertThat(Doc.decode(plaintext(PauseDocument.FORMAT_BOUND, PauseDocument.FORMAT_BOUND))).isNotNull();
        assertThat(Doc.decode(plaintext(16_000, 0))).isNotNull();
        map(cluster).put(key(cluster), sealer.seal(PrivacyCluster.OVERSIGHT_MAP, key(cluster),
                plaintext(16_000, 0)));
        assertThat(instance().snapshot().pausedTools()).containsExactly("t");
    }

    @Test
    void decodeRejectsADuplicateIdWithADifferentTargetAnUnknownFormatTagAndKeepsATombstoneForAnUnknownId() throws Exception {
        Id same = id(5);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeByte(PauseDocument.FORMAT);
        out.writeLong(1);
        out.writeLong(0);
        out.writeLong(0);
        out.writeInt(0);
        out.writeInt(2);
        for (String tool : List.of("a", "b")) {
            out.writeLong(same.high());
            out.writeLong(same.low());
            out.writeByte(Kind.TOOL.ordinal());
            out.writeInt(1);
            out.write(tool.charAt(0));
        }
        out.writeInt(0);
        assertThat(Doc.decode(bytes.toByteArray())).as("a duplicate id with a different target").isNull();

        byte[] unknown = plaintext(1, 0);
        unknown[0] = (byte) (PauseDocument.FORMAT + 1);
        assertThat(Doc.decode(unknown)).as("an unknown format tag").isNull();
        unknown[0] = 1;
        assertThat(Doc.decode(unknown)).as("the previous format is unknown too").isNull();

        Doc withOrphan = doc(3, 0, Map.of(id(1), Target.tool("a")), Set.of(id(1), id(777)));
        Doc round = Doc.decode(withOrphan.encode());
        assertThat(round).isNotNull();
        assertThat(round.tombstones()).containsExactlyInAnyOrder(id(1), id(777));
        assertThat(round.elements()).isEqualTo(withOrphan.elements());
    }

    // ---- memoisation --------------------------------------------------------------------------------------

    @Test
    void identicalSealedBytesAreNotDecodedTwiceAndDifferentBytesAre() {
        solo();
        var writer = instance();
        var reader = instance();
        AtomicInteger decodes = new AtomicInteger();
        reader.document().decoder = plaintext -> {
            decodes.incrementAndGet();
            return Doc.decode(plaintext);
        };
        writer.pauseTool("alpha");

        for (int i = 0; i < 5; i++) {
            assertThat(reader.toolPaused("alpha")).isTrue();
        }
        assertThat(decodes.get()).isEqualTo(1);

        writer.pauseTool("beta");
        for (int i = 0; i < 5; i++) {
            assertThat(reader.toolPaused("beta")).isTrue();
        }
        assertThat(decodes.get()).isEqualTo(2);
    }

    @Test
    void twoDocumentsOfTheSameOrderButDifferentBytesAreNeverMistakenForOne() {
        solo();
        var reader = instance();
        Doc one = doc(10, 0, Map.of(id(1), Target.tool("one")), Set.of());
        Doc two = doc(10, 0, Map.of(id(2), Target.tool("two")), Set.of());
        plant(cluster, one);
        assertThat(reader.snapshot().pausedTools()).containsExactly("one");

        plant(cluster, two);
        assertThatThrownBy(reader::snapshot).isInstanceOf(IllegalStateException.class);
        assertThat(reader.document().accepted().live(Target.tool("two"))).hasSize(1);
        assertThat(reader.document().accepted().live(Target.tool("one"))).hasSize(1);
    }

    // ---- pause.conflict (amendment 7) ----------------------------------------------------------------------

    @Test
    void conflictIsCountedOnceForARolledBackStoredDocumentAndOnceForALowerOrderDocumentCarryingAnExtraElement() {
        solo();
        var member = instance();
        member.pauseTool("a");
        String rolledBack = (String) map(cluster).get(key(cluster));
        member.pauseTool("b");
        member.pauseTool("c");
        int baseline = metrics.count(Metric.CLUSTER_PAUSE_CONFLICT);
        assertThat(baseline).as("ordinary writes are no conflict").isZero();

        map(cluster).put(key(cluster), rolledBack);
        assertThatThrownBy(() -> member.toolPaused("a")).isInstanceOf(IllegalStateException.class);
        assertThat(metrics.count(Metric.CLUSTER_PAUSE_CONFLICT)).isEqualTo(1);
        // The same bytes again, and a tick that looks at them, count nothing more.
        assertThatThrownBy(() -> member.toolPaused("a")).isInstanceOf(IllegalStateException.class);
        assertThat(metrics.count(Metric.CLUSTER_PAUSE_CONFLICT)).isEqualTo(1);

        // A lower-order document that holds everything held and one more: it brings an element held lacks.
        Doc held = member.document().accepted();
        Doc lowerWithExtra = doc(1, 0, mapPlus(held.elements(), id(900), Target.tool("extra")), held.tombstones());
        plant(cluster, lowerWithExtra);
        assertThatThrownBy(() -> member.toolPaused("a")).isInstanceOf(IllegalStateException.class);
        assertThat(metrics.count(Metric.CLUSTER_PAUSE_CONFLICT)).isEqualTo(2);
        assertThatThrownBy(() -> member.toolPaused("a")).isInstanceOf(IllegalStateException.class);
        member.document().tick();
        assertThat(metrics.count(Metric.CLUSTER_PAUSE_CONFLICT)).as("the same bytes seen on a tick").isEqualTo(2);
        assertThat(member.snapshot().pausedTools()).containsExactlyInAnyOrder("a", "b", "c", "extra");
    }

    // ---- properties of the merge --------------------------------------------------------------------------

    private static Doc randomDoc(Random random, int pool) {
        Map<Id, Target> elements = new HashMap<>();
        Set<Id> tombstones = new HashSet<>();
        for (int i = 0; i < pool; i++) {
            // The target of an id is fixed by the id, so two random documents never name one id as two targets.
            Target target = switch (i % 3) {
                case 0 -> Target.ALL;
                case 1 -> Target.tool("tool-" + (i % 4));
                default -> Target.scope("scope-" + (i % 5));
            };
            if (random.nextInt(3) == 0) {
                elements.put(id(i), target);
            }
            if (random.nextInt(4) == 0) {
                tombstones.add(id(i));
            }
        }
        return doc(random.nextInt(10), random.nextInt(3), elements, tombstones);
    }

    private static void assertSameContent(Doc left, Doc right, String message) {
        assertThat(left.elements()).as(message + " elements").isEqualTo(right.elements());
        assertThat(left.tombstones()).as(message + " tombstones").isEqualTo(right.tombstones());
    }

    @Test
    void mergeIsCommutativeAssociativeAndIdempotentAndAMergedTargetIsPausedExactlyWhenSomeElementHasNoTombstone() {
        Random random = new Random(228);
        for (int run = 0; run < 400; run++) {
            Doc a = randomDoc(random, 24);
            Doc b = randomDoc(random, 24);
            Doc c = randomDoc(random, 24);
            assertSameContent(a.union(b), b.union(a), "commutative");
            assertSameContent(a.union(b).union(c), a.union(b.union(c)), "associative");
            assertSameContent(a.union(a), a, "idempotent");
            assertSameContent(a.union(b).union(b), a.union(b), "idempotent over a merge");

            Doc merged = a.union(b);
            Set<Id> tombstones = new HashSet<>(a.tombstones());
            tombstones.addAll(b.tombstones());
            Map<Id, Target> elements = new HashMap<>(a.elements());
            elements.putAll(b.elements());
            // No merge removes an element, and none lifts one unless its tombstone is in the merged state.
            assertThat(merged.elements()).isEqualTo(elements);
            PauseDocument.Pauses view = merged.pauses();
            boolean all = false;
            Set<String> tools = new HashSet<>();
            Set<String> scopes = new HashSet<>();
            for (Map.Entry<Id, Target> element : elements.entrySet()) {
                if (!tombstones.contains(element.getKey())) {
                    switch (element.getValue().kind()) {
                        case ALL -> all = true;
                        case TOOL -> tools.add(element.getValue().name());
                        case SCOPE -> scopes.add(element.getValue().name());
                    }
                }
            }
            assertThat(view.all()).isEqualTo(all);
            assertThat(view.tools()).isEqualTo(tools);
            assertThat(view.scopes()).isEqualTo(scopes);
            // A target that is paused in either input without a tombstone in the merged state is paused in it.
            for (Doc input : List.of(a, b)) {
                for (Map.Entry<Id, Target> element : input.elements().entrySet()) {
                    if (!tombstones.contains(element.getKey())) {
                        assertThat(merged.live(element.getValue())).contains(element.getKey());
                    }
                }
            }
        }
    }
}

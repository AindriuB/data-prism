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
import io.github.aindriub.dataprism.hazelcast.PauseDocument.Target;
import io.github.aindriub.dataprism.hazelcast.PauseDocumentTest.Nanos;
import io.github.aindriub.dataprism.hazelcast.seal.Opened;
import io.github.aindriub.dataprism.hazelcast.seal.ServingGate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T4, the merge cases (docs/plan/design/cluster-security.md 2.3.3, OD-14). The pause state is an observed-remove set,
 * so two sides of a split, a rolled-back store and a replayed document all merge by union: a pause on one side and a
 * resume on the other both hold after the heal, and a pause is never lost to a document that is merely newer.
 *
 * <p>Two clusters of the same keys stand for the two sides of a split: the document of one side is copied into the
 * other's map, which is what the map's merge policy leaves behind, and the member that rejoined reconciles. Single
 * keyless clusters with several states stand for several instances with separate memory. One test at the end splits
 * and heals real clusters, to show that the lifecycle event drives the same reconciliation.
 */
class PauseSplitBrainTest {

    private final Fixture fixture = new Fixture();
    private final Fixture.Metrics metrics = new Fixture.Metrics();
    private final List<HazelcastOversightState> states = new ArrayList<>();
    private PrivacyCluster side;
    private PrivacyCluster other;
    private PrivacyCluster lone;

    @AfterEach
    void shutDown() {
        states.forEach(HazelcastOversightState::close);
        if (lone != null) {
            lone.close();
        }
        fixture.closeAll();
    }

    private PrivacyCluster member() {
        int port = FreePorts.consecutive(1);
        return fixture.keyed(TestMembers.uniqueName("split"), port, List.of("127.0.0.1:" + port), TestMembers.KEYS,
                Fixture.settings(UnverifiedMemberAction.REFUSE), new Fixture.Metrics(), new RecordingLogger(), false);
    }

    private HazelcastOversightState state(PrivacyCluster on, Fixture.Metrics counting) {
        HazelcastOversightState state = new HazelcastOversightState(on, ClusterLocks.DEFAULT_TIMEOUT, counting,
                new PauseDocument.Environment(new Nanos(), fixture.clock, null));
        states.add(state);
        return state;
    }

    /** A solo member of no keys: several states on it stand for several instances. */
    private PrivacyCluster solo() {
        Config config = new Config();
        config.setClusterName(TestMembers.uniqueName("split-solo"));
        config.getJetConfig().setEnabled(false);
        config.getNetworkConfig().getJoin().getMulticastConfig().setEnabled(false);
        config.getNetworkConfig().getJoin().getTcpIpConfig().setEnabled(false);
        lone = PrivacyCluster.embedded(config, false);
        return lone;
    }

    private HazelcastOversightState instance() {
        return state(lone, new Fixture.Metrics());
    }

    private static String key(PrivacyCluster on) {
        return on.keyedNames().mapKey(PrivacyCluster.OVERSIGHT_MAP, PauseDocument.CANONICAL);
    }

    private static IMap<String, Object> map(PrivacyCluster on) {
        return on.instance().getMap(PrivacyCluster.OVERSIGHT_MAP);
    }

    private static Doc stored(PrivacyCluster on) {
        Opened opened = IdentityMaps.sealerFor(on, PrivacyMetrics.none(), ServingGate.open())
                .open(PrivacyCluster.OVERSIGHT_MAP, key(on), (String) map(on).get(key(on)));
        assertThat(opened.outcome()).isEqualTo(Opened.Outcome.AUTHENTIC);
        return Doc.decode(opened.plaintext());
    }

    /** What the merge policy leaves in {@code into}: the other side's document, in place of its own. */
    private static void copyDocument(PrivacyCluster from, PrivacyCluster into) {
        map(into).put(key(into), map(from).get(key(from)));
    }

    // ---- the two sides of a split ------------------------------------------------------------------------

    @Test
    void aPauseIssuedOnTheSmallerSideOfASplitIsPresentAfterTheHeal() {
        side = member();
        other = member();
        var smaller = state(side, metrics);
        var larger = state(other, new Fixture.Metrics());
        smaller.pauseTool("issued-on-the-smaller-side");
        larger.pauseScope("CASE-A");
        larger.pauseScope("CASE-B");
        // The larger side's document is the one that survives the merge.
        smaller.document().beginMerge();
        copyDocument(other, side);

        smaller.document().reconcileAfterMerge();

        assertThat(smaller.toolPaused("issued-on-the-smaller-side")).isTrue();
        assertThat(smaller.scopePaused("CASE-A")).as("what the larger side paused is kept").isTrue();
        assertThat(smaller.scopePaused("CASE-B")).isTrue();
        long healed = smaller.document().acceptedSequence();
        assertThat(healed).as("one above both sides").isGreaterThan(larger.document().acceptedSequence());
        // An instance with no memory of either side reads the same union from the cluster.
        var fresh = state(side, new Fixture.Metrics());
        assertThat(fresh.snapshot().pausedTools()).containsExactly("issued-on-the-smaller-side");
        assertThat(fresh.snapshot().pausedScopes()).containsExactlyInAnyOrder("CASE-A", "CASE-B");
    }

    @Test
    void equalOrderDocumentsWithDifferentContentMergeByUnionAndCountAConflict() {
        side = member();
        other = member();
        var one = state(side, metrics);
        var two = state(other, new Fixture.Metrics());
        one.pauseTool("alpha");
        two.pauseTool("beta");
        assertThat(one.document().acceptedOrder()).containsExactly(two.document().acceptedOrder());
        long sequence = one.document().acceptedSequence();
        copyDocument(other, side);

        // Equal order, and the stored document lacks alpha: not fresh until the union is written back.
        assertThatThrownBy(one::snapshot).isInstanceOf(IllegalStateException.class);
        one.document().tick();

        assertThat(one.snapshot().pausedTools()).containsExactlyInAnyOrder("alpha", "beta");
        assertThat(metrics.count(Metric.CLUSTER_PAUSE_CONFLICT)).isEqualTo(1);
        assertThat(one.document().acceptedSequence()).isEqualTo(sequence + 1);
        assertThat(state(side, new Fixture.Metrics()).snapshot().pausedTools())
                .as("the union was written back to the cluster").containsExactlyInAnyOrder("alpha", "beta");
    }

    @Test
    void aResumeOnOneSideAndAPauseOnTheOtherBothHoldAfterTheHeal() {
        side = member();
        other = member();
        var resumedSide = state(side, new Fixture.Metrics());
        var pausedSide = state(other, new Fixture.Metrics());
        resumedSide.pauseTool("p");
        // Before the split both sides hold the pause of p.
        copyDocument(side, other);
        assertThat(pausedSide.toolPaused("p")).isTrue();

        // The split: one side resumes p, the other pauses y.
        resumedSide.resumeTool("p");
        pausedSide.pauseTool("y");
        assertThat(resumedSide.toolPaused("p")).isFalse();

        resumedSide.document().beginMerge();
        copyDocument(other, side);
        resumedSide.document().reconcileAfterMerge();

        assertThat(resumedSide.toolPaused("p")).as("the resume holds after the heal").isFalse();
        assertThat(resumedSide.toolPaused("y")).as("the pause holds after the heal").isTrue();
        // The other side takes the healed document and agrees.
        copyDocument(side, other);
        assertThat(pausedSide.toolPaused("p")).isFalse();
        assertThat(pausedSide.toolPaused("y")).isTrue();
    }

    @Test
    void aHealThatFindsTheClusterDocumentAlreadyCarryingEverythingWritesNothing() {
        side = member();
        var only = state(side, metrics);
        only.pauseAll();
        long[] before = only.document().acceptedOrder();
        only.document().beginMerge();

        only.document().reconcileAfterMerge();

        assertThat(only.document().acceptedOrder()).containsExactly(before);
    }

    @Test
    void reconcileWritesWhatTheMemberHeldWhenTheMergeBeganEvenIfNothingElseHoldsIt() {
        side = member();
        other = member();
        var member = state(side, new Fixture.Metrics());
        var theirs = state(other, new Fixture.Metrics());
        member.pauseTool("kept");
        theirs.pauseTool("theirs");
        member.document().beginMerge();
        // The in-memory view is lost between MERGING and MERGED: only the note taken at MERGING still holds "kept".
        member.document().forgetContentForTest();
        copyDocument(other, side);

        member.document().reconcileAfterMerge();

        Doc healed = stored(side);
        assertThat(healed.elements().values()).extracting(Target::name).containsExactlyInAnyOrder("kept", "theirs");
        assertThat(member.snapshot().pausedTools()).containsExactlyInAnyOrder("kept", "theirs");
    }

    // ---- the reviewer's shape: repeated pauses overtaking missing state -----------------------------------------

    private enum Rollback { ROLL_BACK, DELETE, GARBAGE }

    /**
     * A, B and C share a cluster. A and B hold {s1, s2, s3, p, q}; C holds only {s1, s2, s3} (seq 3). Storage is
     * then rolled back, deleted or replaced by garbage, and C pauses x three times, so its sequence climbs above
     * A's and B's.
     */
    private record Shape(HazelcastOversightState a, HazelcastOversightState b, HazelcastOversightState c,
                          long sequenceAB) {
    }

    private Shape shape(Rollback rollback, boolean coldC) {
        solo();
        var a = instance();
        a.pauseTool("s1");
        a.pauseTool("s2");
        a.pauseTool("s3");
        HazelcastOversightState c = coldC ? null : instance();
        if (c != null) {
            assertThat(c.snapshot().pausedTools()).hasSize(3);
        }
        Object atSeq3 = map(lone).get(key(lone));
        a.pauseTool("p");
        a.pauseTool("q");
        var b = instance();
        assertThat(b.snapshot().pausedTools()).containsExactlyInAnyOrder("s1", "s2", "s3", "p", "q");
        long sequenceAB = a.document().acceptedSequence();
        rollBack(rollback, atSeq3);
        return new Shape(a, b, coldC ? instance() : c, sequenceAB);
    }

    private void rollBack(Rollback rollback, Object atSeq3) {
        switch (rollback) {
            case ROLL_BACK -> map(lone).put(key(lone), atSeq3);
            case DELETE -> map(lone).delete(key(lone));
            case GARBAGE -> map(lone).put(key(lone), "garbage that is not an envelope");
        }
    }

    private void assertEverythingSurvives(Shape shape, boolean exactRepairSequence) {
        shape.a().document().tick();
        shape.b().document().tick();
        shape.c().document().tick();
        Doc healed = stored(lone);
        for (var state : List.of(shape.a(), shape.b(), shape.c())) {
            assertThat(state.snapshot().pausedTools()).contains("p", "q", "x");
        }
        assertThat(healed.live(Target.tool("p"))).hasSize(1);
        assertThat(healed.live(Target.tool("q"))).hasSize(1);
        assertThat(healed.live(Target.tool("x"))).hasSize(3);
        assertThat(healed.tombstones()).as("no tombstone exists for any id of p or q").isEmpty();
        if (exactRepairSequence) {
            assertThat(healed.sequence()).as("a repair write above C's climb to seq 6").isEqualTo(shape.sequenceAB() + 2);
        }
    }

    private void cPausesXThreeTimes(Shape shape) {
        for (int i = 0; i < 3; i++) {
            shape.c().pauseTool("x");
        }
    }

    @Test
    void repeatedPausesFromABehindMemberNeverLiftThePausesOfTheMembersAheadOfIt_rolledBackStorage() {
        Shape shape = shape(Rollback.ROLL_BACK, false);
        cPausesXThreeTimes(shape);
        assertThat(shape.c().document().acceptedSequence()).as("C climbed above A and B").isEqualTo(shape.sequenceAB() + 1);

        assertEverythingSurvives(shape, true);
    }

    @Test
    void repeatedPausesFromABehindMemberNeverLiftThePausesOfTheMembersAheadOfIt_deletedStorage() {
        Shape shape = shape(Rollback.DELETE, false);
        cPausesXThreeTimes(shape);

        assertEverythingSurvives(shape, true);
    }

    @Test
    void repeatedPausesFromABehindMemberNeverLiftThePausesOfTheMembersAheadOfIt_garbageInStorage() {
        Shape shape = shape(Rollback.GARBAGE, false);
        cPausesXThreeTimes(shape);

        assertEverythingSurvives(shape, true);
    }

    @Test
    void theSameShapeWithAColdMemberThatHoldsNothing() {
        for (Rollback rollback : Rollback.values()) {
            if (lone != null) {
                states.forEach(HazelcastOversightState::close);
                states.clear();
                lone.close();
            }
            Shape shape = shape(rollback, true);
            cPausesXThreeTimes(shape);

            assertEverythingSurvives(shape, false);
        }
    }

    @Test
    void theSameShapeWithAMemberWhoseCardsAreStalledAndWhoseWriteConsultsNoPeer() {
        Shape shape = shape(Rollback.ROLL_BACK, false);
        shape.c().document().peers = new PauseDocument.Peers() {
            @Override
            public long highestReportedSequence() {
                throw new IllegalStateException("the cards look is old");
            }

            @Override
            public int unverifiedCount() {
                throw new IllegalStateException("the cards look is old");
            }

            @Override
            public boolean isOldest() {
                throw new IllegalStateException("the cards look is old");
            }
        };

        cPausesXThreeTimes(shape);

        // A peer-derived term in a sequence would have thrown above; and nothing is lifted.
        shape.a().document().tick();
        assertThat(stored(lone).live(Target.tool("p"))).hasSize(1);
        assertThat(shape.c().document().acceptedSequence()).isEqualTo(shape.sequenceAB() + 1);
    }

    @Test
    void theSameShapeWithTheRollbackTakenAfterAResumeOfPKeepsPLiftedOnAAndBAndOnCOnceItMerges() {
        solo();
        var a = instance();
        a.pauseTool("s1");
        a.pauseTool("s2");
        a.pauseTool("s3");
        var c = instance();
        assertThat(c.snapshot().pausedTools()).hasSize(3);
        Object atSeq3 = map(lone).get(key(lone));
        a.pauseTool("p");
        a.pauseTool("q");
        a.resumeTool("p");
        var b = instance();
        assertThat(b.snapshot().pausedTools()).containsExactlyInAnyOrder("s1", "s2", "s3", "q");
        long sequence = a.document().acceptedSequence();
        map(lone).put(key(lone), atSeq3);

        for (int i = 0; i < 3; i++) {
            c.pauseTool("x");
        }
        assertThat(c.document().acceptedSequence()).as("equal to A and B, with other bytes").isEqualTo(sequence);
        a.document().tick();
        b.document().tick();
        c.document().tick();

        for (var state : List.of(a, b, c)) {
            assertThat(state.snapshot().pausedTools()).containsExactlyInAnyOrder("s1", "s2", "s3", "q", "x");
        }
        assertThat(stored(lone).sequence()).isEqualTo(sequence + 1);
    }

    @Test
    void aReplayOfAnOldDocumentHoldingAnElementThatIsNowTombstonedLeavesItLiftedOnEveryWarmMember() {
        solo();
        var a = instance();
        a.pauseTool("p");
        Object replay = map(lone).get(key(lone));
        a.pauseTool("q");
        a.resumeTool("p");
        var b = instance();
        assertThat(b.snapshot().pausedTools()).containsExactly("q");

        map(lone).put(key(lone), replay);
        a.document().tick();

        for (var state : List.of(a, b)) {
            assertThat(state.toolPaused("p")).as("p stays lifted").isFalse();
            assertThat(state.toolPaused("q")).isTrue();
        }
    }

    @Test
    void aColdMemberGivenAReplayShowsTheReplaysStateUntilAWarmMembersRepairTickThenTheUnion() {
        solo();
        var a = instance();
        a.pauseTool("p");
        Object replay = map(lone).get(key(lone));
        a.pauseTool("q");
        a.resumeTool("p");
        map(lone).put(key(lone), replay);

        var cold = instance();
        assertThat(cold.snapshot().pausedTools()).as("the replay's state, within the cold-start gate").containsExactly("p");

        a.document().tick();

        assertThat(cold.snapshot().pausedTools()).as("the union, once a warm member repaired").containsExactly("q");
    }

    @Test
    void twoWritersRacingOnTheCompareAndSetEachKeepTheirPause() {
        solo();
        var one = instance();
        var two = instance();
        one.pauseTool("seed");
        assertThat(two.snapshot().pausedTools()).containsExactly("seed");
        java.util.concurrent.atomic.AtomicInteger hooks = new java.util.concurrent.atomic.AtomicInteger();
        one.document().beforeCommit = () -> {
            if (hooks.incrementAndGet() == 1) {
                two.pauseTool("two");
            }
        };

        one.pauseTool("one");

        assertThat(one.snapshot().pausedTools()).containsExactlyInAnyOrder("seed", "one", "two");
        assertThat(two.snapshot().pausedTools()).containsExactlyInAnyOrder("seed", "one", "two");
    }

    // ---- over the operating cap ---------------------------------------------------------------------------

    @Test
    void eachSideOfASplitAddingEightThousandPausesMergesToSixteenThousandAndOnlyPauseAllIsAcceptedAfterwards() {
        side = member();
        other = member();
        var left = state(side, new Fixture.Metrics());
        var right = state(other, new Fixture.Metrics());
        PauseDocumentTest.plant(side, PauseDocumentTest.docOfTools(100, "a", 8_000));
        PauseDocumentTest.plant(other, PauseDocumentTest.docOfTools(100, "b", 8_000));
        assertThat(left.snapshot().pausedTools()).hasSize(8_000);
        assertThat(right.snapshot().pausedTools()).hasSize(8_000);

        left.document().beginMerge();
        copyDocument(other, side);
        left.document().reconcileAfterMerge();

        assertThat(stored(side).elements()).as("the merged document encoded and was written").hasSize(16_000);
        assertThat(left.snapshot().pausedTools()).hasSize(16_000);
        copyDocument(side, other);
        assertThat(right.snapshot().pausedTools()).as("every member holds all 16,000").hasSize(16_000);
        assertThat(state(side, new Fixture.Metrics()).snapshot().pausedTools()).hasSize(16_000);

        assertThatThrownBy(() -> left.pauseTool("late")).isInstanceOf(ClusterOperationRefusedException.class)
                .satisfies(refusal -> assertThat(((ClusterOperationRefusedException) refusal).code())
                        .isEqualTo("CLUSTER_OPERATION_REFUSED"));
        left.pauseAll();

        assertThat(left.allPaused()).isTrue();
        assertThat(stored(side).elements()).hasSize(16_001);
    }

    @Test
    void anAuthenticUnionOfAnySizeWithinTheFormatBoundIsWrittenByARepairWhateverTheOperatingCap() {
        side = member();
        var only = state(side, new Fixture.Metrics());
        PauseDocumentTest.plant(side, PauseDocumentTest.docOfTools(100, "a", PauseDocument.PAUSE_CAP + 500));
        assertThat(only.snapshot().pausedTools()).hasSize(PauseDocument.PAUSE_CAP + 500);
        // A rolled-back store: the repair writes the whole thing back though it is above the operating cap.
        PauseDocumentTest.plant(side, PauseDocumentTest.doc(50, 0, java.util.Map.of(), Set.<Id>of()));

        only.document().tick();

        assertThat(stored(side).elements()).hasSize(PauseDocument.PAUSE_CAP + 500);
    }

    // ---- real clusters -------------------------------------------------------------------------------------

    @Test
    void realClustersThatSplitAndHealKeepThePausesOfBothSides() throws Exception {
        String name = TestMembers.uniqueName("heal-pause");
        int port = FreePorts.consecutive(2);
        String a = "127.0.0.1:" + port;
        String b = "127.0.0.1:" + (port + 1);
        side = PrivacyCluster.embedded(shortMergeDelays(ClusterMembership.tcpIp(name, List.of(a, b))
                .withPort(port).withInterface("127.0.0.1").toConfig()), false, OptionalInt.empty(), TestMembers.KEYS);
        other = PrivacyCluster.embedded(shortMergeDelays(ClusterMembership.tcpIp(name, List.of(b))
                .withPort(port + 1).withInterface("127.0.0.1").toConfig()), false, OptionalInt.empty(),
                TestMembers.KEYS);
        try {
            assertThat(side.instance().getCluster().getMembers()).hasSize(1);
            assertThat(other.instance().getCluster().getMembers()).hasSize(1);
            var onFirst = new HazelcastOversightState(side);
            var onSecond = new HazelcastOversightState(other);
            states.add(onFirst);
            states.add(onSecond);
            onFirst.pauseTool("alpha");
            onSecond.pauseTool("beta");
            onSecond.pauseTool("gamma");

            assertThat(Fixture.await(60, () -> side.instance().getCluster().getMembers().size() == 2))
                    .as("the clusters merged within 60 s").isTrue();
            // Reconciliation follows the lifecycle event, which follows the membership change.
            boolean healed = Fixture.await(60, () -> pausedEverywhere(onFirst, onSecond));
            assertThat(healed).as("every pause of both sides is present on both; first %s, second %s",
                    describe(onFirst), describe(onSecond)).isTrue();
        } finally {
            side.close();
            other.close();
        }
    }

    private static boolean pausedEverywhere(HazelcastOversightState first, HazelcastOversightState second) {
        try {
            return first.snapshot().pausedTools().containsAll(List.of("alpha", "beta", "gamma"))
                    && second.snapshot().pausedTools().containsAll(List.of("alpha", "beta", "gamma"));
        } catch (RuntimeException notYet) {
            return false;
        }
    }

    private static String describe(HazelcastOversightState state) {
        String tools;
        try {
            tools = state.snapshot().pausedTools().toString();
        } catch (RuntimeException unavailable) {
            tools = unavailable.getClass().getSimpleName();
        }
        long[] order = state.document().acceptedOrder();
        return "order " + (order == null ? "none" : order[0] + "." + order[1]) + ", fresh "
                + state.document().fresh() + ", tools " + tools;
    }

    private static Config shortMergeDelays(Config config) {
        config.setProperty("hazelcast.merge.first.run.delay.seconds", "5");
        config.setProperty("hazelcast.merge.next.run.delay.seconds", "5");
        return config;
    }
}

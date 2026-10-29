package io.github.aindriub.dataprism.hazelcast;

import com.hazelcast.config.Config;
import com.hazelcast.map.IMap;
import io.github.aindriub.dataprism.core.metrics.Metric;
import io.github.aindriub.dataprism.hazelcast.ClusterSecuritySettings.UnverifiedMemberAction;
import io.github.aindriub.dataprism.hazelcast.MemberCardTest.Fixture;
import io.github.aindriub.dataprism.hazelcast.PauseDocumentTest.Nanos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T4, the merge cases (docs/plan/design/cluster-security.md 2.3.3, OD-14). Two clusters of the same keys stand for the
 * two sides of a split: the document of one side is copied into the other's map, which is what the map's merge policy
 * leaves behind, and the member that rejoined reconciles. One test at the end splits and heals real clusters, to
 * show that the lifecycle event drives the same reconciliation.
 */
class PauseSplitBrainTest {

    private final Fixture fixture = new Fixture();
    private final Fixture.Metrics metrics = new Fixture.Metrics();
    private final List<HazelcastOversightState> states = new ArrayList<>();
    private PrivacyCluster side;
    private PrivacyCluster other;

    @AfterEach
    void shutDown() {
        states.forEach(HazelcastOversightState::close);
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

    private static String key(PrivacyCluster on) {
        return on.keyedNames().mapKey(PrivacyCluster.OVERSIGHT_MAP, PauseDocument.CANONICAL);
    }

    /** What the merge policy leaves in {@code into}: the other side's document, in place of its own. */
    private static void copyDocument(PrivacyCluster from, PrivacyCluster into) {
        IMap<String, Object> source = from.instance().getMap(PrivacyCluster.OVERSIGHT_MAP);
        IMap<String, Object> target = into.instance().getMap(PrivacyCluster.OVERSIGHT_MAP);
        target.put(key(into), source.get(key(from)));
    }

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
        assertThat(state(side, new Fixture.Metrics()).snapshot().pausedTools())
                .containsExactly("issued-on-the-smaller-side");
        assertThat(metrics.count(Metric.CLUSTER_PAUSE_CONFLICT)).as("not an equal-order conflict").isZero();
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

        // Whichever way the instance meets the other document, it adopts the union one sequence up.
        assertThat(one.snapshot().pausedTools()).containsExactlyInAnyOrder("alpha", "beta");

        assertThat(metrics.count(Metric.CLUSTER_PAUSE_CONFLICT)).isEqualTo(1);
        assertThat(one.document().acceptedSequence()).isEqualTo(sequence + 1);
        assertThat(state(side, new Fixture.Metrics()).snapshot().pausedTools())
                .as("the union was written back to the cluster").containsExactlyInAnyOrder("alpha", "beta");
    }

    @Test
    void aResumeOnOneSideMayBeUndoneByTheHeal_theCostOfTheUnion() {
        side = member();
        other = member();
        var resumedSide = state(other, new Fixture.Metrics());
        var pausedSide = state(side, metrics);
        pausedSide.pauseTool("search");
        resumedSide.pauseTool("search");
        resumedSide.resumeTool("search");
        assertThat(resumedSide.toolPaused("search")).isFalse();
        pausedSide.document().beginMerge();
        copyDocument(other, side);

        pausedSide.document().reconcileAfterMerge();

        assertThat(pausedSide.toolPaused("search"))
                .as("the pause the other side lifted is back: the operator resumes again (OD-14)").isTrue();
        pausedSide.resumeTool("search");
        assertThat(pausedSide.toolPaused("search")).isFalse();
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

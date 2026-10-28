package io.github.aindriub.dataprism.hazelcast;

import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;
import io.github.aindriub.dataprism.core.refusal.ClusterOperationRefusedException;
import io.github.aindriub.dataprism.hazelcast.ClusterSecuritySettings.UnverifiedMemberAction;
import io.github.aindriub.dataprism.hazelcast.MemberCardTest.Fixture;
import io.github.aindriub.dataprism.hazelcast.PrivacyClusterRefusal.Code;
import io.github.aindriub.dataprism.hazelcast.seal.ClusterKeys;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * T9: a member that cannot read the cluster refuses to join, instead of producing a stream of tamper counts
 * (docs/plan/design/cluster-security.md 4.6 and 4.7). The sentinel decides, and the rows of the 4.6 table each have a
 * test.
 */
class ClusterKeyMismatchTest {

    private final Fixture fixture = new Fixture();
    private final String name = TestMembers.uniqueName("mismatch");
    private final int first = FreePorts.consecutive(2);
    private final List<String> both = List.of("127.0.0.1:" + first, "127.0.0.1:" + (first + 1));

    @AfterEach
    void shutDown() {
        fixture.closeAll();
    }

    private PrivacyCluster start(int index, ClusterKeys keys) {
        return fixture.keyed(name, first + index, both, keys, Fixture.settings(UnverifiedMemberAction.REFUSE),
                new Fixture.Metrics(), new RecordingLogger(), false);
    }

    private String sentinelOf(PrivacyCluster cluster) {
        return Fixture.cardMap(cluster).get(ClusterSentinel.KEY);
    }

    private static String keyIdOf(String envelope) {
        return envelope.split("\\.", -1)[1];
    }

    // ---- none of the held keys opens the sentinel ----------------------------------------------------------

    private void assertRefusedAndGone(PrivacyCluster holder, ClusterKeys keys) {
        Throwable refused = catchThrowable(() -> start(1, keys));
        assertThat(refused).isInstanceOf(PrivacyClusterRefusal.class);
        assertThat(((PrivacyClusterRefusal) refused).code()).isEqualTo(Code.CLUSTER_KEY_MISMATCH);
        assertThat(refused.getMessage()).doesNotContain("127.0.0.1").doesNotContain("test-cluster-1");
        assertThat(Hazelcast.getAllHazelcastInstances()).as("the refused member left").hasSize(1);
        assertThat(Fixture.awaitMembers(holder, 1)).as("the holder is alone again").isTrue();
        // The refused member published no card: the holder's card and the sentinel are all there is.
        assertThat(Fixture.cardMap(holder).keySet()).hasSize(2).contains(ClusterSentinel.KEY);
    }

    @Test
    void aMemberWithADifferentClusterKeyIsRefusedBeforeItServesAndLeavesNoCard() {
        PrivacyCluster holder = start(0, TestMembers.KEYS);
        // The same key id with other material: the sentinel does not authenticate.
        assertRefusedAndGone(holder, Fixture.keys("test-cluster-1", 'z', null, ' '));
    }

    @Test
    void aMemberWhoseKeyIdIsUnknownToTheSentinelIsRefusedToo() {
        PrivacyCluster holder = start(0, TestMembers.KEYS);
        assertRefusedAndGone(holder, Fixture.keys("another-cluster-key", 'c', null, ' '));
    }

    @Test
    void aMemberHoldingOnlyTheOldKeyAfterTheSentinelMovedCannotRead() {
        // The cluster has moved to k2 (k1 is only its previous key); a newcomer with k1 alone cannot read it.
        PrivacyCluster holder = start(0, Fixture.keys("k2", 'd', "k1", 'c'));
        assertThat(keyIdOf(sentinelOf(holder))).isEqualTo("k2");
        assertRefusedAndGone(holder, Fixture.keys("k1", 'c', null, ' '));
    }

    @Test
    void aSentinelPlantedByAKeylessMemberBeforeTheFirstLegitimateMemberCausesTheRefusalAndNoDataAccess() {
        HazelcastInstance planter = Fixture.plain(name, first + 1, both);
        planter.getMap(PrivacyCluster.MEMBER_CARD_MAP).put(ClusterSentinel.KEY, "planted by a keyless member");

        Throwable refused = catchThrowable(() -> start(0, TestMembers.KEYS));
        assertThat(refused).isInstanceOf(PrivacyClusterRefusal.class);
        assertThat(((PrivacyClusterRefusal) refused).code()).isEqualTo(Code.CLUSTER_KEY_MISMATCH);
        assertThat(Fixture.await(30, () -> planter.getCluster().getMembers().size() == 1))
                .as("the refused member left").isTrue();
        assertThat(planter.getMap(PrivacyCluster.MEMBER_CARD_MAP).keySet()).as("no card was published")
                .containsExactly(ClusterSentinel.KEY);
        // Nothing of Data Prism's was read or written: the only distributed object is the one the planter made.
        Set<String> maps = new TreeSet<>();
        planter.getDistributedObjects().forEach(object -> maps.add(object.getName()));
        assertThat(maps).as("no sealed map was touched").doesNotContain(PrivacyCluster.IDENTITY_MAP,
                PrivacyCluster.REIDENTIFICATION_MAP, PrivacyCluster.BUDGET_MAP, PrivacyCluster.APPROVAL_MAP,
                PrivacyCluster.CALLER_RATE_MAP, PrivacyCluster.OVERSIGHT_MAP);
    }

    @Test
    void aSentinelThatIsNotAStringIsAMismatchNotAClassCastException() {
        HazelcastInstance planter = Fixture.plain(name, first + 1, both);
        planter.getMap(PrivacyCluster.MEMBER_CARD_MAP).put(ClusterSentinel.KEY, 42);

        Throwable refused = catchThrowable(() -> start(0, TestMembers.KEYS));
        assertThat(refused).isInstanceOf(PrivacyClusterRefusal.class);
        assertThat(((PrivacyClusterRefusal) refused).code()).isEqualTo(Code.CLUSTER_KEY_MISMATCH);
    }

    // ---- the rows of the 4.6 table ---------------------------------------------------------------------------

    @Test
    void currentRowAMemberInStepJoinsNormally() {
        PrivacyCluster holder = start(0, TestMembers.KEYS);
        String before = sentinelOf(holder);
        PrivacyCluster joiner = start(1, TestMembers.KEYS);
        assertThat(Fixture.awaitMembers(holder, 2)).isTrue();
        assertThat(joiner.memberCards()).isPresent();
        assertThat(sentinelOf(joiner)).as("the sentinel is not rewritten").isEqualTo(before);
        assertThat(Fixture.cardMap(holder).keySet()).hasSize(3);
    }

    @Test
    void previousRowAMemberAheadOfTheSentinelJoinsAndLeavesItAlone() {
        // The cluster still seals under k1; the newcomer's current is k2 and its previous is k1: it is ahead.
        PrivacyCluster holder = start(0, Fixture.keys("k1", 'c', null, ' '));
        String before = sentinelOf(holder);
        assertThat(keyIdOf(before)).isEqualTo("k1");
        PrivacyCluster ahead = start(1, Fixture.keys("k2", 'd', "k1", 'c'));
        assertThat(Fixture.awaitMembers(holder, 2)).isTrue();
        assertThat(ahead.memberCards()).isPresent();
        assertThat(sentinelOf(ahead)).as("re-sealing the sentinel is not this task's").isEqualTo(before);
        assertThat(Fixture.cardMap(holder).keySet()).as("it published its card").hasSize(3);
    }

    @Test
    void nextRowAMemberBehindTheSentinelJoinsAndCanRead() {
        // The cluster already seals under k2; the newcomer's current is k1 and it accepts k2 as next: it is behind.
        PrivacyCluster holder = start(0, Fixture.keys("k2", 'd', null, ' '));
        String before = sentinelOf(holder);
        assertThat(keyIdOf(before)).isEqualTo("k2");
        PrivacyCluster behind = start(1, Fixture.keysWithNext("k1", 'c', "k2", 'd'));
        assertThat(Fixture.awaitMembers(holder, 2)).isTrue();
        assertThat(behind.memberCards()).isPresent();
        assertThat(sentinelOf(behind)).isEqualTo(before);
        assertThat(Fixture.cardMap(holder).keySet()).as("it published its card").hasSize(3);
    }

    @Test
    void aNextKeyIsNeverWritten() {
        PrivacyCluster only = start(0, Fixture.keysWithNext("k1", 'c', "k2", 'd'));
        assertThat(keyIdOf(sentinelOf(only))).as("the sentinel").isEqualTo("k1");
        Fixture.cardMap(only).entrySet().forEach(entry ->
                assertThat(keyIdOf(entry.getValue())).as("an entry of the card map").isEqualTo("k1"));
    }

    // ---- after the join --------------------------------------------------------------------------------------

    @Test
    void aSentinelReplacedAfterTheJoinStopsEveryMemberServing() {
        PrivacyCluster holder = start(0, TestMembers.KEYS);
        PrivacyCluster joiner = start(1, TestMembers.KEYS);
        assertThat(Fixture.awaitMembers(holder, 2)).isTrue();
        Fixture.cardMap(holder).put(ClusterSentinel.KEY, "replaced by a member without the key");

        for (PrivacyCluster member : List.of(holder, joiner)) {
            member.memberCards().orElseThrow().tick();
            assertThat(member.securityStatus().reason()).isEqualTo(ClusterSecurityStatus.Reason.SENTINEL_MISMATCH);
            assertThatThrownBy(member::servingInstance).isInstanceOf(ClusterOperationRefusedException.class);
            assertThat(member.instance()).as("the ungated accessor is unchanged").isNotNull();
        }
    }
}

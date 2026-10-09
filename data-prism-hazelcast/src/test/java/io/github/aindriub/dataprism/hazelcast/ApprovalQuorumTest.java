package io.github.aindriub.dataprism.hazelcast;

import com.hazelcast.splitbrainprotection.SplitBrainProtectionException;
import io.github.aindriub.dataprism.oversight.ApprovalRequest.Kind;
import io.github.aindriub.dataprism.oversight.ApprovalRequest.Status;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.OptionalInt;

import static io.github.aindriub.dataprism.hazelcast.TestMembers.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The side of a partition that cannot see enough members refuses to decide, consume or create approvals. */
class ApprovalQuorumTest {

    private PrivacyCluster[] members = new PrivacyCluster[0];

    @AfterEach
    void stop() {
        for (PrivacyCluster m : members) {
            if (m.instance().getLifecycleService().isRunning()) {
                m.close();
            }
        }
    }

    @Test
    void withEnoughMembersTheCallsSucceed() {
        members = TestMembers.pair(TestMembers.uniqueName("quorum-ok"), OptionalInt.of(2));
        var approvals = new HazelcastApprovalStore(members[0]);
        approvals.create(TestMembers.request("a-1", "scope-1", Status.PENDING));
        approvals.create(TestMembers.request("a-2", "scope-1", Status.PENDING));
        approvals.approve("a-1", "bob", NOW);
        approvals.reject("a-2", "bob", NOW);
        assertThat(approvals.consumeApproved(Kind.TOOL_CALL, "alice", "scope-1", "search", "fp-a-1", NOW)).isPresent();
    }

    @Test
    void withFewerVisibleMembersThanTheMinimumEveryWriteThrows() {
        members = TestMembers.pair(TestMembers.uniqueName("quorum-lost"), OptionalInt.of(2));
        var approvals = new HazelcastApprovalStore(members[0]);
        approvals.create(TestMembers.request("b-1", "scope-1", Status.PENDING));
        approvals.create(TestMembers.request("b-2", "scope-1", Status.PENDING));
        approvals.create(TestMembers.request("b-3", "scope-1", Status.PENDING));
        approvals.approve("b-3", "bob", NOW);
        members[1].close();

        assertThatThrownBy(() -> approvals.consumeApproved(Kind.TOOL_CALL, "alice", "scope-1", "search", "fp-b-3", NOW))
                .isInstanceOf(SplitBrainProtectionException.class);
        assertThatThrownBy(() -> approvals.approve("b-1", "bob", NOW))
                .isInstanceOf(SplitBrainProtectionException.class);
        assertThatThrownBy(() -> approvals.reject("b-2", "bob", NOW))
                .isInstanceOf(SplitBrainProtectionException.class);
        assertThatThrownBy(() -> approvals.create(TestMembers.request("b-4", "scope-1", Status.PENDING)))
                .isInstanceOf(SplitBrainProtectionException.class);
        // Reading is not protected, so a pending request can still be seen.
        assertThat(approvals.find("b-1")).get().extracting(r -> r.status()).isEqualTo(Status.PENDING);
        assertThat(approvals.find("b-3")).get().extracting(r -> r.status()).isEqualTo(Status.APPROVED);
    }

    @Test
    void aMinimumAboveTheMemberCountRefusesFromTheStart() {
        members = TestMembers.pair(TestMembers.uniqueName("quorum-high"), OptionalInt.of(3));
        var approvals = new HazelcastApprovalStore(members[0]);
        assertThatThrownBy(() -> approvals.create(TestMembers.request("c-1", "scope-1", Status.PENDING)))
                .isInstanceOf(SplitBrainProtectionException.class);
    }

    @Test
    void theDefaultMinimumIsAMajorityOfTheListedMembersForTcpIpAndOneForNone() {
        assertThat(ClusterMembership.tcpIp("c", List.of("a", "b")).defaultApprovalMinimumMembers())
                .isEqualTo(OptionalInt.of(2));
        assertThat(ClusterMembership.tcpIp("c", List.of("a", "b", "c")).defaultApprovalMinimumMembers())
                .isEqualTo(OptionalInt.of(2));
        assertThat(ClusterMembership.tcpIp("c", List.of("a", "b", "c", "d")).defaultApprovalMinimumMembers())
                .isEqualTo(OptionalInt.of(3));
        assertThat(ClusterMembership.tcpIp("c", List.of("a:5701", "a:5701", " a:5701 ")).defaultApprovalMinimumMembers())
                .isEqualTo(OptionalInt.of(1));
        assertThat(ClusterMembership.none("c").defaultApprovalMinimumMembers()).isEqualTo(OptionalInt.of(1));
        assertThat(ClusterMembership.kubernetes("c", "ns", "svc", null).defaultApprovalMinimumMembers()).isEmpty();
    }
}

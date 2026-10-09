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

    private static OptionalInt defaultFor(String... members) {
        return PrivacyCluster.defaultApprovalMinimumMembers(ClusterMembership.tcpIp("c", List.of(members)));
    }

    @Test
    void aPortlessEntryIsTheConfiguredMemberPortNotTheDefault() {
        assertThat(PrivacyCluster.countTcpIpMembers(List.of("10.0.0.1", "10.0.0.1:5702"), 5702)).isEqualTo(1);
        assertThat(PrivacyCluster.countTcpIpMembers(List.of("10.0.0.1", "10.0.0.1:5701"), 5702)).isEqualTo(2);
        var membership = ClusterMembership.tcpIp("c", List.of("10.0.0.1", "10.0.0.1:5702", "10.0.0.2"))
                .withPort(5702);
        assertThat(PrivacyCluster.defaultApprovalMinimumMembers(membership)).isEqualTo(OptionalInt.of(2));
    }

    @Test
    void aHostnameMakesTheCountUnknownSoTheDefaultIsRefusedWithoutNamingIt() {
        assertThat(PrivacyCluster.hasHostname(List.of("10.0.0.1", "db-1.internal:5701"))).isTrue();
        assertThat(PrivacyCluster.hasHostname(List.of("10.0.0.1-3", "[::1]:5701", "fe80::1"))).isFalse();
        var membership = ClusterMembership.tcpIp("c", List.of("10.0.0.1", "secret-host.internal"));
        var refused = org.junit.jupiter.api.Assertions.assertThrows(PrivacyClusterRefusal.class,
                () -> PrivacyCluster.defaultApprovalMinimumMembers(membership));
        assertThat(refused.code().name()).isEqualTo("MISSING_APPROVAL_QUORUM");
        assertThat(refused.getMessage()).doesNotContain("secret-host");
    }

    @Test
    void anUncountableEntryIsRefusedWithTheStableCodeAndNoValue() {
        var membership = ClusterMembership.tcpIp("c", List.of("10.0.0.*"));
        var refused = org.junit.jupiter.api.Assertions.assertThrows(PrivacyClusterRefusal.class,
                () -> PrivacyCluster.defaultApprovalMinimumMembers(membership));
        assertThat(refused.code().name()).isEqualTo("INVALID_APPROVAL_QUORUM");
        assertThat(refused.getMessage()).doesNotContain("10.0.0");
    }

    @Test
    void theDefaultMinimumIsAMajorityOfTheListedMembersForTcpIpAndOneForNone() {
        assertThat(defaultFor("10.0.0.1", "10.0.0.2")).isEqualTo(OptionalInt.of(2));
        assertThat(defaultFor("10.0.0.1", "10.0.0.2", "10.0.0.3")).isEqualTo(OptionalInt.of(2));
        assertThat(defaultFor("10.0.0.1", "10.0.0.2", "10.0.0.3", "10.0.0.4")).isEqualTo(OptionalInt.of(3));
        assertThat(PrivacyCluster.defaultApprovalMinimumMembers(ClusterMembership.none("c")))
                .isEqualTo(OptionalInt.of(1));
        assertThat(PrivacyCluster.defaultApprovalMinimumMembers(
                ClusterMembership.kubernetes("c", "ns", "svc", null))).isEmpty();
    }

    @Test
    void membersAreCountedAfterExpandingWhatHazelcastAccepts() {
        assertThat(PrivacyCluster.countTcpIpMembers(List.of("10.0.0.1-3"), 5701)).isEqualTo(3);
        assertThat(defaultFor("10.0.0.1-3")).isEqualTo(OptionalInt.of(2));
        assertThat(PrivacyCluster.countTcpIpMembers(List.of("10.0.0.1,10.0.0.2,10.0.0.3"), 5701)).isEqualTo(3);
        assertThat(PrivacyCluster.countTcpIpMembers(List.of("10.0.0.1, 10.0.0.2;10.0.0.3 10.0.0.4"), 5701)).isEqualTo(4);
        assertThat(defaultFor("10.0.0.1,10.0.0.2,10.0.0.3")).isEqualTo(OptionalInt.of(2));
        assertThat(PrivacyCluster.countTcpIpMembers(List.of("10.0.0.1-3:5702", "10.0.0.2:5702"), 5701)).isEqualTo(3);
    }

    @Test
    void aHostAndTheSameHostOnTheDefaultPortAreOneMember() {
        assertThat(PrivacyCluster.countTcpIpMembers(List.of("10.0.0.1", "10.0.0.1:5701"), 5701)).isEqualTo(1);
        assertThat(PrivacyCluster.countTcpIpMembers(List.of("10.0.0.1:5701", " 10.0.0.1 "), 5701)).isEqualTo(1);
        assertThat(PrivacyCluster.countTcpIpMembers(List.of("10.0.0.1", "10.0.0.1:5702"), 5701)).isEqualTo(2);
        assertThat(defaultFor("10.0.0.1", "10.0.0.1:5701")).isEqualTo(OptionalInt.of(1));
    }

    @Test
    void aFormThatCannotBeExpandedReliablyIsRefusedRatherThanUndercounted() {
        for (String form : new String[]{"host-1-3", "10.0-2.0.1", "10.0.0.*", "10.0.0.3-1", "host[1-3]"}) {
            if (form.equals("host-1-3")) {
                // A hyphenated hostname is a plain hostname; only unambiguous ranges are expanded.
                assertThat(PrivacyCluster.countTcpIpMembers(List.of(form), 5701)).isEqualTo(1);
                continue;
            }
            org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                    () -> PrivacyCluster.countTcpIpMembers(List.of(form), 5701), form);
        }
    }
}

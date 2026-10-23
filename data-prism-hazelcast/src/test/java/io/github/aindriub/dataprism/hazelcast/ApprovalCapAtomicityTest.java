package io.github.aindriub.dataprism.hazelcast;

import com.hazelcast.config.Config;
import io.github.aindriub.dataprism.core.refusal.ClusterOperationRefusedException;
import io.github.aindriub.dataprism.oversight.ApprovalRefusedException;
import io.github.aindriub.dataprism.oversight.ApprovalRequest;
import io.github.aindriub.dataprism.oversight.ApprovalRequest.Kind;
import io.github.aindriub.dataprism.oversight.ApprovalRequest.Status;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.OptionalInt;

import static io.github.aindriub.dataprism.hazelcast.TestMembers.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The pending count follows each approval from creation until it leaves {@code PENDING} or its scope ends. */
class ApprovalCapAtomicityTest {

    private PrivacyCluster cluster;
    private HazelcastApprovalStore store;

    @BeforeEach
    void start() {
        int port = FreePorts.consecutive(1);
        cluster = TestMembers.member(TestMembers.uniqueName("cap-atomic"), port, List.of("127.0.0.1:" + port),
                OptionalInt.empty());
        store = new HazelcastApprovalStore(cluster, Duration.ofMillis(300));
    }

    @AfterEach
    void stop() {
        cluster.close();
    }

    private ApprovalRequest pending(String id, String scope) {
        return TestMembers.request(id, scope, Status.PENDING);
    }

    private void assertAtCap(String id, String scope) {
        assertThatThrownBy(() -> store.create(pending(id, scope), 1)).isInstanceOf(ApprovalRefusedException.class)
                .satisfies(failure -> assertThat(((ApprovalRefusedException) failure).code())
                        .isEqualTo(ApprovalRefusedException.Code.TOO_MANY_PENDING));
        // A refused create leaves neither the approval nor the id claim behind.
        assertThat(store.find(id)).isEmpty();
        assertThat(cluster.instance().getMap(PrivacyCluster.APPROVAL_ID_MAP).containsKey(TestMembers.SCOPE_KEYS.claim(id))).isFalse();
    }

    @Test
    void approvingFreesTheRequestersSlot() {
        store.create(pending("p-1", "scope-a"), 1);
        assertAtCap("p-2", "scope-a");
        store.approve("p-1", "bob", NOW);
        store.create(pending("p-2", "scope-a"), 1);
    }

    @Test
    void rejectingFreesTheRequestersSlot() {
        store.create(pending("p-1", "scope-a"), 1);
        store.reject("p-1", "bob", NOW);
        store.create(pending("p-2", "scope-a"), 1);
    }

    @Test
    void anApprovalThatExpiresUnderADecisionFreesTheSlot() {
        store.create(pending("p-1", "scope-a"), 1);
        assertThatThrownBy(() -> store.approve("p-1", "bob", NOW.plus(Duration.ofHours(2))))
                .isInstanceOf(ApprovalRefusedException.class);
        store.create(pending("p-2", "scope-a"), 1);
    }

    @Test
    void anExpiredApprovalNoLongerCountsForALaterRequest() {
        store.create(pending("p-1", "scope-a"), 1);
        ApprovalRequest later = new ApprovalRequest("p-2", Kind.TOOL_CALL, "alice", "client", "scope-a", "search",
                "fp-p-2", "PERSON_NAME", "synthetic", "audit", "CASE-1", NOW.plus(Duration.ofHours(2)),
                NOW.plus(Duration.ofHours(3)), Status.PENDING, null, null);
        assertThat(store.create(later, 1)).isEqualTo(later);
    }

    @Test
    void consumingAnApprovedApprovalLeavesTheCountAsItWas() {
        store.create(pending("p-1", "scope-a"), 1);
        store.approve("p-1", "bob", NOW);
        store.create(pending("p-2", "scope-a"), 1);
        assertThat(store.consumeApproved(Kind.TOOL_CALL, "alice", "scope-a", "search", "fp-p-1", NOW)).isPresent();
        // p-2 still holds the only slot; consuming p-1 neither frees one nor takes another.
        assertAtCap("p-3", "scope-a");
    }

    @Test
    void forgettingTheScopeFreesTheSlotAndTheId() {
        store.create(pending("p-1", "scope-a"), 1);
        assertAtCap("p-2", "scope-b");
        store.forgetScope("scope-a");
        store.create(pending("p-2", "scope-b"), 1);
        store.forgetScope("scope-b");
        assertThat(store.create(pending("p-1", "scope-c"))).isNotNull();
    }

    @Test
    void endingTheScopeThroughTheIdentityIndexFreesTheSlot() {
        store.create(pending("p-1", "scope-a"), 1);
        new ScopeIdentityIndex(cluster).endScope("scope-a");
        store.create(pending("p-2", "scope-b"), 1);
    }

    @Test
    void theUncappedOverloadCountsTowardsTheCap() {
        store.create(pending("p-1", "scope-a"));
        assertAtCap("p-2", "scope-a");
    }

    @Test
    void aRefusedLeaseIsReportedAsItsOwnCodeAndNeverAsADuplicate() {
        int port = FreePorts.consecutive(1);
        Config config = ClusterMembership.tcpIp(TestMembers.uniqueName("lease-max"), List.of("127.0.0.1:" + port))
                .withPort(port).withInterface("127.0.0.1").toConfig();
        // Below the 10 s lease of a two-second timeout; above the 500 ms lease of the shortest.
        config.setProperty("hazelcast.lock.max.lease.time.seconds", "1");
        try (PrivacyCluster limited = PrivacyCluster.embedded(config, false, OptionalInt.empty(),
                TestMembers.KEYS)) {
            var refused = new HazelcastApprovalStore(limited, Duration.ofSeconds(2));
            var accepted = new HazelcastApprovalStore(limited, Duration.ofMillis(100));
            for (boolean capped : new boolean[]{false, true}) {
                assertThatThrownBy(() -> {
                    if (capped) refused.create(pending("lease-1", "scope-a"), 5);
                    else refused.create(pending("lease-1", "scope-a"));
                }).isInstanceOfSatisfying(ClusterOperationRefusedException.class, failure -> {
                    assertThat(failure.code()).isEqualTo("CLUSTER_OPERATION_REFUSED");
                    assertThat(failure.getMessage()).doesNotContain("lease-1", "scope-a", "alice");
                });
            }
            assertThat(accepted.find("lease-1")).isEmpty();
            accepted.create(pending("lease-1", "scope-a"));
            assertThatThrownBy(() -> accepted.create(pending("lease-1", "scope-b")))
                    .isExactlyInstanceOf(IllegalArgumentException.class).hasMessage("duplicate approval id");
            assertThatThrownBy(() -> accepted.create(pending("lease-1", "scope-b"), 5))
                    .isExactlyInstanceOf(IllegalArgumentException.class).hasMessage("duplicate approval id");
        }
    }
}

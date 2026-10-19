package io.github.aindriub.dataprism.hazelcast;

import com.hazelcast.map.IMap;
import io.github.aindriub.dataprism.oversight.ApprovalRequest;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.OptionalInt;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static io.github.aindriub.dataprism.hazelcast.TestMembers.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApprovalIdLockTest {
    private static final Duration TIMEOUT = Duration.ofMillis(100);

    @Test void uncappedCreationKeepsItsIdClaimAfterItsLeaseExpires() throws Exception {
        pausedCreationCannotDuplicateId(false);
    }

    @Test void cappedCreationKeepsItsIdClaimAfterItsLeaseExpires() throws Exception {
        pausedCreationCannotDuplicateId(true);
    }

    private void pausedCreationCannotDuplicateId(boolean capped) throws Exception {
        PrivacyCluster[] members = TestMembers.pair(TestMembers.uniqueName("approval-id-lock"), OptionalInt.empty());
        StalledInsert stall = new StalledInsert();
        try (var caller = Executors.newSingleThreadExecutor()) {
            IMap<String, String> map = members[0].instance().getMap(PrivacyCluster.APPROVAL_MAP);
            var delayed = new HazelcastApprovalStore(stall.over(members[0]), TIMEOUT);
            var other = new HazelcastApprovalStore(members[1], TIMEOUT);
            ApprovalRequest a = TestMembers.request("shared-id", "scope-a", ApprovalRequest.Status.PENDING);
            ApprovalRequest b = TestMembers.request("shared-id", "scope-b", ApprovalRequest.Status.PENDING);
            var stalled = caller.submit(() -> capped ? delayed.create(a, 1) : delayed.create(a));
            try {
                assertThat(stall.reached.await(10, TimeUnit.SECONDS)).isTrue();
                // Wait for every lock the paused caller may hold to expire; it is still stopped.
                StalledInsert.awaitFree(members[1], PrivacyCluster.APPROVAL_ID_MAP, a.approvalId());
                StalledInsert.awaitFree(members[1], PrivacyCluster.APPROVAL_PENDING_MAP,
                        HazelcastApprovalStore.pendingKey(a.kind(), a.requesterPrincipalId()));
                StalledInsert.awaitFree(members[1], PrivacyCluster.APPROVAL_MAP, a.approvalId());
                StalledInsert.awaitFree(members[1], PrivacyCluster.APPROVAL_MAP,
                        "\0requester\0" + a.kind().name() + "\0=" + a.requesterPrincipalId());
                StalledInsert.awaitFree(members[1], PrivacyCluster.APPROVAL_MAP,
                        ScopeKeys.approval(a.scopeId(), a.approvalId()));
                assertThat(stalled.isDone()).isFalse();

                assertThatThrownBy(() -> {
                    if (capped) other.create(b, 1); else other.create(b);
                }).isInstanceOf(IllegalArgumentException.class).hasMessage("duplicate approval id");

                stall.resume();
                assertThat(stalled.get(10, TimeUnit.SECONDS)).isEqualTo(a);
                assertThat(other.pending(NOW)).containsExactly(a);
                assertThat(other.find(a.approvalId())).contains(a);
                assertThat(map.keySet().stream().filter(key -> ScopeKeys.approvalId(key).equals(a.approvalId())))
                        .containsExactly(ScopeKeys.approval("scope-a", a.approvalId()));
                assertThatThrownBy(() -> other.create(b)).isInstanceOf(IllegalArgumentException.class)
                        .hasMessage("duplicate approval id");
                assertThat(other.approve(a.approvalId(), "bob", NOW).status())
                        .isEqualTo(ApprovalRequest.Status.APPROVED);
                assertThat(other.find(a.approvalId())).get().extracting(ApprovalRequest::scopeId)
                        .isEqualTo("scope-a");
            } finally {
                stall.resume();
            }
        } finally {
            stall.resume();
            for (PrivacyCluster member : members) member.close();
        }
    }
}

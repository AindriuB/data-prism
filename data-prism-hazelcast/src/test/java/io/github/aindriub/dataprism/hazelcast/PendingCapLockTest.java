package io.github.aindriub.dataprism.hazelcast;

import com.hazelcast.map.IMap;
import io.github.aindriub.dataprism.oversight.ApprovalRefusedException;
import io.github.aindriub.dataprism.oversight.ApprovalRequest;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.OptionalInt;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static io.github.aindriub.dataprism.hazelcast.TestMembers.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The cap holds when the creating caller is paused past its lock lease, because it rests on an atomic step. */
class PendingCapLockTest {
    private static final Duration TIMEOUT = Duration.ofMillis(100);

    @Test void aCallerPausedBeforeInsertingStillCountsAgainstTheCapAfterItsLeaseExpires() throws Exception {
        PrivacyCluster[] members = TestMembers.pair(TestMembers.uniqueName("pending-cap-lock"), OptionalInt.empty());
        StalledInsert stall = new StalledInsert();
        try (var caller = Executors.newSingleThreadExecutor()) {
            var paused = new HazelcastApprovalStore(stall.over(members[0]), TIMEOUT);
            var other = new HazelcastApprovalStore(members[1], TIMEOUT);
            ApprovalRequest a = TestMembers.request("request-a", "scope-a", ApprovalRequest.Status.PENDING);
            ApprovalRequest b = TestMembers.request("request-b", "scope-b", ApprovalRequest.Status.PENDING);
            var stalled = caller.submit(() -> paused.create(a, 1));
            try {
                assertThat(stall.reached.await(10, TimeUnit.SECONDS)).isTrue();
                // Wait for every lock the paused caller may hold to expire; it is still stopped.
                String pendingKey = HazelcastApprovalStore.pendingKey(a.kind(), a.requesterPrincipalId());
                StalledInsert.awaitFree(members[1], PrivacyCluster.APPROVAL_PENDING_MAP, pendingKey);
                StalledInsert.awaitFree(members[1], PrivacyCluster.APPROVAL_ID_MAP, a.approvalId());
                StalledInsert.awaitFree(members[1], PrivacyCluster.APPROVAL_MAP, a.approvalId());
                StalledInsert.awaitFree(members[1], PrivacyCluster.APPROVAL_MAP,
                        "\0requester\0" + a.kind().name() + "\0=" + a.requesterPrincipalId());
                StalledInsert.awaitFree(members[1], PrivacyCluster.APPROVAL_MAP,
                        ScopeKeys.approval(a.scopeId(), a.approvalId()));
                assertThat(stalled.isDone()).isFalse();

                assertThatThrownBy(() -> other.create(b, 1)).isInstanceOf(ApprovalRefusedException.class)
                        .satisfies(failure -> assertThat(((ApprovalRefusedException) failure).code())
                                .isEqualTo(ApprovalRefusedException.Code.TOO_MANY_PENDING));

                stall.resume();
                assertThat(stalled.get(10, TimeUnit.SECONDS)).isEqualTo(a);
                assertThat(other.pending(NOW)).containsExactly(a);
                assertThat(other.find(b.approvalId())).isEmpty();
                other.approve(a.approvalId(), "bob", NOW);
                assertThat(other.create(b, 1)).isEqualTo(b);
                assertThat(other.pending(NOW)).containsExactly(b);
                IMap<String, String> map = members[0].instance().getMap(PrivacyCluster.APPROVAL_MAP);
                assertThat(map.keySet()).hasSize(2);
            } finally {
                stall.resume();
            }
        } finally {
            stall.resume();
            for (PrivacyCluster member : members) member.close();
        }
    }
}

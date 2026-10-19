package io.github.aindriub.dataprism.hazelcast;

import io.github.aindriub.dataprism.oversight.ApprovalRefusedException;
import io.github.aindriub.dataprism.oversight.ApprovalRequest;
import io.github.aindriub.dataprism.oversight.ApprovalRequest.Status;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.AbstractMap;
import java.util.Map;
import java.util.OptionalInt;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static io.github.aindriub.dataprism.hazelcast.TestMembers.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** A pending slot belongs to the entry that reserved it, so a creator that lost its claim cannot release it. */
class PendingOwnershipTest {
    private static final Duration TIMEOUT = Duration.ofMillis(300);
    private static final Instant LATER = NOW.plus(Duration.ofHours(1));

    private static String reserve(String current, String id, String owner, int cap) {
        Map.Entry<String, String> entry = new AbstractMap.SimpleEntry<>("k", current);
        new PendingSet.Reserve(id, owner, NOW, LATER, cap).process(entry);
        return entry.getValue();
    }

    private static String release(String current, String id, String owner) {
        Map.Entry<String, String> entry = new AbstractMap.SimpleEntry<>("k", current);
        new PendingSet.Release(id, owner).process(entry);
        return entry.getValue();
    }

    @Test
    void aReserveByAnotherOwnerTakesTheSlotOverWithoutChangingTheCount() {
        String one = reserve(null, "r-1", "scope-a", 1);
        String taken = reserve(one, "r-1", "scope-b", 1);
        assertThat(PendingSet.decode(taken)).containsOnlyKeys("r-1");
        assertThat(PendingSet.decode(taken).get("r-1").owner()).isEqualTo("scope-b");
        // The same owner again is idempotent.
        assertThat(reserve(taken, "r-1", "scope-b", 1)).isEqualTo(taken);
    }

    @Test
    void aReleaseByAFormerOwnerLeavesTheSlotAlone() {
        String taken = reserve(reserve(null, "r-1", "scope-a", 1), "r-1", "scope-b", 1);
        assertThat(release(taken, "r-1", "scope-a")).isEqualTo(taken);
        assertThat(release(taken, "r-1", "scope-b")).isNull();
        assertThat(release(null, "r-1", "scope-b")).isNull();
    }

    @Test
    void aPausedCreatorWhoseInsertFailsAfterATakeoverDoesNotEmptyTheNewOwnersSlot() throws Exception {
        PrivacyCluster[] members = TestMembers.pair(TestMembers.uniqueName("pending-owner"), OptionalInt.empty());
        StalledInsert stall = new StalledInsert().failingOnResume();
        try (var caller = Executors.newSingleThreadExecutor()) {
            var paused = new HazelcastApprovalStore(stall.over(members[0]), TIMEOUT);
            var other = new HazelcastApprovalStore(members[1], TIMEOUT);
            ApprovalRequest a = TestMembers.request("r-1", "scope-a", Status.PENDING);
            ApprovalRequest b = TestMembers.request("r-1", "scope-b", Status.PENDING);
            ApprovalRequest c = TestMembers.request("r-2", "scope-c", Status.PENDING);
            var stalled = caller.submit(() -> paused.create(a, 1));
            try {
                assertThat(stall.reached.await(10, TimeUnit.SECONDS)).isTrue();
                String pendingKey = HazelcastApprovalStore.pendingKey(a.kind(), a.requesterPrincipalId());
                StalledInsert.awaitFree(members[1], PrivacyCluster.APPROVAL_ID_MAP, "r-1");
                StalledInsert.awaitFree(members[1], PrivacyCluster.APPROVAL_PENDING_MAP, pendingKey);
                StalledInsert.awaitFree(members[1], PrivacyCluster.APPROVAL_MAP, ScopeKeys.approval("scope-a", "r-1"));

                // The new owner takes the claim over and finds the id already reserved, which it adopts.
                assertThat(other.create(b, 1)).isEqualTo(b);

                // The paused insert now fails; its undo must not release the slot that scope-b relies on.
                stall.resume();
                assertThatThrownBy(() -> stalled.get(10, TimeUnit.SECONDS))
                        .hasCauseInstanceOf(ClusterOperationRefusedException.class);
                assertThat(other.find("r-1")).contains(b);
                assertThatThrownBy(() -> other.create(c, 1)).isInstanceOf(ApprovalRefusedException.class)
                        .satisfies(failure -> assertThat(((ApprovalRefusedException) failure).code())
                                .isEqualTo(ApprovalRefusedException.Code.TOO_MANY_PENDING));
            } finally {
                stall.resume();
            }
        } finally {
            stall.resume();
            for (PrivacyCluster member : members) member.close();
        }
    }
}

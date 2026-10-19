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
    void aLateReserveOfAShorterApprovalKeepsTheLongerExpiryAndTheLatestWriterOwns() {
        String longer = reserve(null, "r-1", "scope-b", 1);
        Map.Entry<String, String> entry = new AbstractMap.SimpleEntry<>("k", longer);
        new PendingSet.Reserve("r-1", "scope-a", NOW, NOW.plus(Duration.ofMinutes(10)), 1).process(entry);
        PendingSet.Member member = PendingSet.decode(entry.getValue()).get("r-1");
        assertThat(member.expiresAt()).isEqualTo(LATER);
        assertThat(member.owner()).isEqualTo("scope-a");
        assertThat(PendingSet.decode(entry.getValue())).hasSize(1);
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

    @Test
    void aCreatorStoppedBetweenClaimAndReserveCannotShortenTheTakersSlot() throws Exception {
        PrivacyCluster[] members = TestMembers.pair(TestMembers.uniqueName("pending-late"), OptionalInt.empty());
        StalledInsert stall = StalledInsert.beforeReserve();
        try (var caller = Executors.newSingleThreadExecutor()) {
            var paused = new HazelcastApprovalStore(stall.over(members[1]), TIMEOUT);
            var other = new HazelcastApprovalStore(members[0], TIMEOUT);
            ApprovalRequest a = withLife("r-1", "scope-a", NOW, Duration.ofMinutes(10));
            ApprovalRequest b = withLife("r-1", "scope-b", NOW, Duration.ofHours(1));
            var stalled = caller.submit(() -> paused.create(a, 1));
            try {
                assertThat(stall.reached.await(10, TimeUnit.SECONDS)).isTrue();
                String pendingKey = HazelcastApprovalStore.pendingKey(a.kind(), a.requesterPrincipalId());
                StalledInsert.awaitFree(members[0], PrivacyCluster.APPROVAL_ID_MAP, "r-1");
                StalledInsert.awaitFree(members[0], PrivacyCluster.APPROVAL_PENDING_MAP, pendingKey);
                StalledInsert.awaitFree(members[0], PrivacyCluster.APPROVAL_MAP, ScopeKeys.approval("scope-a", "r-1"));
                // The claim names an entry that does not exist yet, so scope-b takes it over and reserves +1 hour.
                assertThat(other.create(b, 1)).isEqualTo(b);

                // The stopped creator now reserves its shorter approval, then finds it lost the claim.
                stall.resume();
                assertThatThrownBy(() -> stalled.get(10, TimeUnit.SECONDS))
                        .hasCauseInstanceOf(IllegalArgumentException.class);

                // Eleven minutes on, the ten-minute expiry has passed but scope-b's approval is still pending.
                ApprovalRequest c = withLife("r-2", "scope-c", NOW.plus(Duration.ofMinutes(11)), Duration.ofHours(2));
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

    private static ApprovalRequest withLife(String id, String scope, Instant created, Duration life) {
        return new ApprovalRequest(id, ApprovalRequest.Kind.TOOL_CALL, "alice", "client", scope, "search",
                "fp-" + id, "PERSON_NAME", "synthetic", "audit", "CASE-1", created, created.plus(life),
                Status.PENDING, null, null);
    }
}

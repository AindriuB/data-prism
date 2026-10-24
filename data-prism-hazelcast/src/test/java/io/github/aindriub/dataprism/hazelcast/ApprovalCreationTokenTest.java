package io.github.aindriub.dataprism.hazelcast;

import com.hazelcast.map.IMap;
import io.github.aindriub.dataprism.core.refusal.ClusterOperationRefusedException;
import io.github.aindriub.dataprism.oversight.ApprovalRefusedException;
import io.github.aindriub.dataprism.oversight.ApprovalRefusedException.Code;
import io.github.aindriub.dataprism.oversight.ApprovalRequest;
import io.github.aindriub.dataprism.oversight.ApprovalRequest.Kind;
import io.github.aindriub.dataprism.oversight.ApprovalRequest.Status;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.OptionalInt;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static io.github.aindriub.dataprism.hazelcast.TestMembers.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Each creation of an approval carries a random token, kept in its entry, its id claim and its pending slot. A
 * clean-up, undo or withdrawal that belongs to one creation never touches a later creation of the same id, and an
 * entry the claim does not name is unknown.
 */
class ApprovalCreationTokenTest {

    private static final Duration TIMEOUT = Duration.ofMillis(300);

    private PrivacyCluster[] members;
    private ExecutorService pool;
    private IMap<String, String> approvals;
    private IMap<String, String> claims;
    private IMap<String, String> slots;

    @BeforeEach
    void start() {
        members = TestMembers.pair(TestMembers.uniqueName("tokens"), OptionalInt.empty());
        pool = Executors.newCachedThreadPool();
        approvals = members[1].instance().getMap(PrivacyCluster.APPROVAL_MAP);
        claims = members[1].instance().getMap(PrivacyCluster.APPROVAL_ID_MAP);
        slots = members[1].instance().getMap(PrivacyCluster.APPROVAL_PENDING_MAP);
    }

    @AfterEach
    void stop() {
        pool.shutdownNow();
        for (PrivacyCluster member : members) {
            member.close();
        }
    }

    private static ApprovalRequest request(String id, String scope, String requester) {
        return new ApprovalRequest(id, Kind.TOOL_CALL, requester, "client", scope, "search", "fp-" + id,
                "PERSON_NAME", "synthetic", "audit", "CASE-1", NOW, NOW.plus(1, ChronoUnit.HOURS), Status.PENDING,
                null, null);
    }

    private static String pendingKey(String requester) {
        return TestMembers.SCOPE_KEYS.pending(Kind.TOOL_CALL, requester);
    }

    /** Waits until the locks a stopped creator took have run out their lease, so another creator can run. */
    private void awaitFree(String scope, String id, String requester) throws InterruptedException {
        StalledInsert.awaitFree(members[1], PrivacyCluster.APPROVAL_ID_MAP, TestMembers.SCOPE_KEYS.claim(id));
        StalledInsert.awaitFree(members[1], PrivacyCluster.APPROVAL_PENDING_MAP, pendingKey(requester));
        StalledInsert.awaitFree(members[1], PrivacyCluster.APPROVAL_MAP, TestMembers.SCOPE_KEYS.approval(scope, id));
    }

    private String tokenOfEntry(String scope, String id) {
        return HazelcastApprovalStore.Codec.tokenOf(approvals.get(TestMembers.SCOPE_KEYS.approval(scope, id)));
    }

    private static void assertDuplicate(Future<?> creation) {
        assertThatThrownBy(() -> creation.get(10, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(IllegalArgumentException.class);
    }

    // ----- stale undos of a creation that lost its claim

    @Test
    void aStaleUndoAfterTheCapRefusedItLeavesTheSameScopeReCreationItsClaimAndSlot() throws Exception {
        StalledInsert stall = StalledInsert.beforeReserve();
        var paused = new HazelcastApprovalStore(stall.over(members[0]), TIMEOUT);
        var other = new HazelcastApprovalStore(members[1], TIMEOUT);
        other.create(request("y-1", "scope-y", "alice"), 1);
        Future<?> stalled = pool.submit(() -> paused.create(request("x-1", "scope-a", "alice"), 1));
        try {
            assertThat(stall.reached.await(10, TimeUnit.SECONDS)).isTrue();
            awaitFree("scope-a", "x-1", "alice");
            // The claim names an entry that does not exist yet, so another requester takes it over in the same
            // scope and inserts the same id.
            other.create(request("x-1", "scope-a", "carol"), 1);
            stall.resume();
            // The stopped creator now finds alice at the cap and undoes its claim; the undo is not carol's.
            assertThatThrownBy(() -> stalled.get(10, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
                    .satisfies(failure -> assertThat(((ApprovalRefusedException) failure.getCause()).code())
                            .isEqualTo(Code.TOO_MANY_PENDING));
        } finally {
            stall.resume();
        }
        assertThat(claims.get(TestMembers.SCOPE_KEYS.claim("x-1"))).isEqualTo(
                ClaimEntries.value(TestMembers.SCOPE_KEYS.approval("scope-a", "x-1"), tokenOfEntry("scope-a", "x-1")));
        assertThat(PendingSet.decode(slots.get(pendingKey("carol")))).containsOnlyKeys("x-1");
        assertThatThrownBy(() -> other.create(request("x-1", "scope-b", "dave"), 5))
                .isExactlyInstanceOf(IllegalArgumentException.class).hasMessage("duplicate approval id");
        assertThat(TestMembers.keysOfApproval(approvals, "x-1")).hasSize(1);
    }

    @Test
    void aStaleUndoAfterTheInsertFailedLeavesTheSameScopeReCreationItsClaimAndSlot() throws Exception {
        StalledInsert stall = new StalledInsert().failingOnResume();
        var paused = new HazelcastApprovalStore(stall.over(members[0]), TIMEOUT);
        var other = new HazelcastApprovalStore(members[1], TIMEOUT);
        Future<?> stalled = pool.submit(() -> paused.create(request("x-1", "scope-a", "alice"), 5));
        try {
            assertThat(stall.reached.await(10, TimeUnit.SECONDS)).isTrue();
            awaitFree("scope-a", "x-1", "alice");
            other.create(request("x-1", "scope-a", "alice"), 5);
            String key = TestMembers.SCOPE_KEYS.approval("scope-a", "x-1");
            String token = tokenOfEntry("scope-a", "x-1");
            // The re-creation's entry is gone (for example it expired) while its claim and slot remain.
            approvals.delete(key);
            stall.resume();
            // The paused insert fails; the entry is absent, so the undo runs, and it is not the re-creation's.
            assertThatThrownBy(() -> stalled.get(10, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(ClusterOperationRefusedException.class);
            assertThat(claims.get(TestMembers.SCOPE_KEYS.claim("x-1"))).isEqualTo(ClaimEntries.value(key, token));
            assertThat(PendingSet.decode(slots.get(pendingKey("alice"))).get("x-1").owner())
                    .isEqualTo(PendingSet.ownerOf("scope-a", token));
        } finally {
            stall.resume();
        }
    }

    @Test
    void aStaleWithdrawalAfterTheClaimWasLostLeavesTheSameScopeReCreationItsEntry() throws Exception {
        StalledInsert stall = StalledInsert.beforeSecondClaimCheck();
        var paused = new HazelcastApprovalStore(stall.over(members[0]), TIMEOUT);
        var other = new HazelcastApprovalStore(members[1], TIMEOUT);
        ApprovalRequest same = request("x-1", "scope-a", "alice");
        Future<?> stalled = pool.submit(() -> paused.create(same, 5));
        try {
            assertThat(stall.reached.await(10, TimeUnit.SECONDS)).isTrue();
            awaitFree("scope-a", "x-1", "alice");
            String key = TestMembers.SCOPE_KEYS.approval("scope-a", "x-1");
            // The first creation's entry is gone (it expired, or its scope ended), so the same request is created
            // again in the same scope and has an identical encoding apart from its token.
            approvals.delete(key);
            other.create(same, 5);
            stall.resume();
            assertDuplicate(stalled);
        } finally {
            stall.resume();
        }
        assertThat(other.find("x-1")).contains(same);
        assertThat(claims.get(TestMembers.SCOPE_KEYS.claim("x-1"))).isEqualTo(
                ClaimEntries.value(TestMembers.SCOPE_KEYS.approval("scope-a", "x-1"), tokenOfEntry("scope-a", "x-1")));
    }

    // ----- the claim cannot be read back after the insert

    @Test
    void anUnreadableClaimAfterTheInsertWithdrawsTheEntryAndRefuses() throws Exception {
        StalledInsert stall = StalledInsert.beforeSecondClaimCheck().failingOnResume();
        var faulty = new HazelcastApprovalStore(stall.over(members[0]), TIMEOUT);
        Future<?> creating = pool.submit(() -> faulty.create(request("x-1", "scope-a", "alice"), 5));
        assertThat(stall.reached.await(10, TimeUnit.SECONDS)).isTrue();
        stall.resume();
        assertThatThrownBy(() -> creating.get(10, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(ClusterOperationRefusedException.class);
        assertThat(TestMembers.keysOfApproval(approvals, "x-1")).isEmpty();
    }

    @Test
    void anUnreadableClaimAfterTheInsertWithAFailedWithdrawalLeavesAnEntryThatIsUsableOnlyWhileItsClaimNamesIt()
            throws Exception {
        StalledInsert stall = StalledInsert.beforeSecondClaimCheck().failingOnResume()
                .failing(PrivacyCluster.APPROVAL_MAP, "remove");
        stall.arm();
        var faulty = new HazelcastApprovalStore(stall.over(members[0]), TIMEOUT);
        var other = new HazelcastApprovalStore(members[1], TIMEOUT);
        Future<?> creating = pool.submit(() -> faulty.create(request("x-1", "scope-a", "alice"), 5));
        assertThat(stall.reached.await(10, TimeUnit.SECONDS)).isTrue();
        stall.resume();
        assertThatThrownBy(() -> creating.get(10, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(ClusterOperationRefusedException.class);

        // The entry stayed. Its claim still names it, so it is a PENDING approval and the id cannot be reused.
        String key = TestMembers.SCOPE_KEYS.approval("scope-a", "x-1");
        assertThat(approvals.containsKey(key)).isTrue();
        assertThat(other.find("x-1")).get().extracting(ApprovalRequest::status).isEqualTo(Status.PENDING);
        assertThatThrownBy(() -> other.create(request("x-1", "scope-b", "dave"), 5))
                .isExactlyInstanceOf(IllegalArgumentException.class).hasMessage("duplicate approval id");

        // Once the claim does not name its token, the entry is unknown.
        claims.put(TestMembers.SCOPE_KEYS.claim("x-1"), ClaimEntries.value(key, TestMembers.token(7)));
        assertThat(other.find("x-1")).isEmpty();
        assertThatThrownBy(() -> other.approve("x-1", "bob", NOW.plusSeconds(1)))
                .isInstanceOf(ApprovalRefusedException.class)
                .satisfies(failure -> assertThat(((ApprovalRefusedException) failure).code())
                        .isEqualTo(Code.UNKNOWN_APPROVAL));
    }

    // ----- the validity rule

    @Test
    void anEntryWhoseClaimIsGoneOrIsAnotherCreationsIsUnknownToEveryRead() {
        var store = new HazelcastApprovalStore(members[0], TIMEOUT);
        store.create(request("v-1", "scope-a", "alice"), 5);
        String key = TestMembers.SCOPE_KEYS.approval("scope-a", "v-1");
        assertThat(store.find("v-1")).isPresent();
        assertThat(store.findPending(Kind.TOOL_CALL, "alice", "scope-a", "search", "fp-v-1", NOW)).isPresent();
        assertThat(store.pending(NOW)).hasSize(1);

        for (String claim : new String[]{null, ClaimEntries.value(key, TestMembers.token(5)),
                ClaimEntries.value(TestMembers.SCOPE_KEYS.approval("scope-b", "v-1"), tokenOfEntry("scope-a", "v-1")), "garbage"}) {
            if (claim == null) {
                claims.remove(TestMembers.SCOPE_KEYS.claim("v-1"));
            } else {
                claims.put(TestMembers.SCOPE_KEYS.claim("v-1"), claim);
            }
            assertThat(store.find("v-1")).isEmpty();
            assertThat(store.findPending(Kind.TOOL_CALL, "alice", "scope-a", "search", "fp-v-1", NOW)).isEmpty();
            assertThat(store.pending(NOW)).isEmpty();
            assertThatThrownBy(() -> store.approve("v-1", "bob", NOW.plusSeconds(1)))
                    .isInstanceOf(ApprovalRefusedException.class)
                    .satisfies(failure -> assertThat(((ApprovalRefusedException) failure).code())
                            .isEqualTo(Code.UNKNOWN_APPROVAL));
            assertThatThrownBy(() -> store.reject("v-1", "bob", NOW.plusSeconds(1)))
                    .isInstanceOf(ApprovalRefusedException.class);
            assertThat(approvals.get(key)).isNotNull();
        }
    }

    @Test
    void anApprovedEntryWhoseClaimIsAnotherCreationsCannotBeConsumed() {
        var store = new HazelcastApprovalStore(members[0], TIMEOUT);
        store.create(request("v-2", "scope-a", "alice"), 5);
        store.approve("v-2", "bob", NOW.plusSeconds(1));
        claims.put(TestMembers.SCOPE_KEYS.claim("v-2"), ClaimEntries.value(TestMembers.SCOPE_KEYS.approval("scope-a", "v-2"), TestMembers.token(5)));
        assertThat(store.consumeApproved(Kind.TOOL_CALL, "alice", "scope-a", "search", "fp-v-2",
                NOW.plusSeconds(2))).isEmpty();
        assertThat(HazelcastApprovalStore.Codec.decode(approvals.get(TestMembers.SCOPE_KEYS.approval("scope-a", "v-2"))).status())
                .isEqualTo(Status.APPROVED);
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void aHealThatPairsAnEntryWithAnotherCreationsClaimLeavesItUnusable() throws Exception {
        // Two sides each hold their own creation of x-1 in one scope.
        String key = TestMembers.SCOPE_KEYS.approval("scope-a", "x-1");
        ApprovalRequest request = request("x-1", "scope-a", "alice");
        String sideA = HazelcastApprovalStore.Codec.encode(request, TestMembers.token(1));
        String sideB = HazelcastApprovalStore.Codec.encode(request, TestMembers.token(2));
        var approvalPolicy = new ApprovalMergePolicy();
        var claimPolicy = new com.hazelcast.spi.merge.PutIfAbsentMergePolicy();
        // The approval map keeps A's entry; the claim map, healing apart from it, keeps B's claim.
        Object keptEntry = approvalPolicy.merge(entry(sideB), entry(sideA));
        Object keptClaim = claimPolicy.merge(entry(ClaimEntries.value(key, TestMembers.token(1))),
                entry(ClaimEntries.value(key, TestMembers.token(2))));
        approvals.put(key, (String) keptEntry);
        claims.put(TestMembers.SCOPE_KEYS.claim("x-1"), (String) keptClaim);
        slots.put(pendingKey("alice"), "x-1\0" + request.expiresAt() + "\0" + TestMembers.owner("scope-a", 1));

        var store = new HazelcastApprovalStore(members[0], TIMEOUT);
        assertThat(store.find("x-1")).isEmpty();
        assertThatThrownBy(() -> store.approve("x-1", "bob", NOW.plusSeconds(1)))
                .isInstanceOf(ApprovalRefusedException.class)
                .satisfies(failure -> assertThat(((ApprovalRefusedException) failure).code())
                        .isEqualTo(Code.UNKNOWN_APPROVAL));
        assertThat(store.consumeApproved(Kind.TOOL_CALL, "alice", "scope-a", "search", "fp-x-1",
                NOW.plusSeconds(2))).isEmpty();
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static com.hazelcast.spi.merge.MergingValue entry(String value) {
        return (com.hazelcast.spi.merge.MergingValue) java.lang.reflect.Proxy.newProxyInstance(
                ApprovalCreationTokenTest.class.getClassLoader(),
                new Class<?>[]{com.hazelcast.spi.merge.SplitBrainMergeTypes.MapMergeTypes.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getValue", "getDeserializedValue", "getRawValue" -> value;
                    case "toString" -> "entry";
                    case "hashCode" -> 1;
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    // ----- values from an earlier version are unreadable and refused

    @Test
    void valuesInTheOldFormsAreUnreadableAndRefused() {
        var store = new HazelcastApprovalStore(members[0], TIMEOUT);
        String key = TestMembers.SCOPE_KEYS.approval("scope-a", "old-1");
        ApprovalRequest request = request("old-1", "scope-a", "alice");
        String sixteen = HazelcastApprovalStore.Codec.encode(request, TestMembers.token(1));
        sixteen = sixteen.substring(0, sixteen.lastIndexOf('\0'));

        // An entry with 16 fields, even with a bare claim naming its key, is unknown.
        approvals.put(key, sixteen);
        claims.put(TestMembers.SCOPE_KEYS.claim("old-1"), key);
        assertThat(store.find("old-1")).isEmpty();
        assertThatThrownBy(() -> store.approve("old-1", "bob", NOW.plusSeconds(1)))
                .isInstanceOf(ApprovalRefusedException.class);

        // A bare claim is never taken over, so its id cannot be created again.
        approvals.delete(key);
        assertThatThrownBy(() -> store.create(request("old-1", "scope-a", "alice")))
                .isExactlyInstanceOf(IllegalArgumentException.class).hasMessage("duplicate approval id");

        // A pending entry with a scope-only owner is unreadable, so the cap refuses.
        slots.put(pendingKey("carol"), "c-1\0" + NOW.plus(1, ChronoUnit.HOURS) + "\0scope-a");
        assertThatThrownBy(() -> store.create(request("c-2", "scope-a", "carol"), 5))
                .isInstanceOf(ClusterOperationRefusedException.class);
    }

    @Test
    void theTokenIsRandomPerCreationAndHoldsNoPersonalData() {
        var store = new HazelcastApprovalStore(members[0], TIMEOUT);
        store.create(request("r-1", "scope-a", "alice"), 5);
        String first = tokenOfEntry("scope-a", "r-1");
        approvals.delete(TestMembers.SCOPE_KEYS.approval("scope-a", "r-1"));
        store.create(request("r-1", "scope-a", "alice"), 5);
        String second = tokenOfEntry("scope-a", "r-1");
        assertThat(first).isNotEqualTo(second);
        assertThat(java.util.UUID.fromString(first).toString()).isEqualTo(first);
        assertThat(first).doesNotContain("alice").doesNotContain("scope-a").doesNotContain("r-1");
    }

    @Test
    void theClaimSweepRemovesOldFormClaimsOnceTheirEntryIsAbsentAndLeavesOtherUnreadableOnes() {
        String present = TestMembers.SCOPE_KEYS.approval("scope-a", "p-1");
        approvals.put(present, "old entry");
        claims.put(TestMembers.SCOPE_KEYS.claim("p-1"), present);
        claims.put(TestMembers.SCOPE_KEYS.claim("a-1"), TestMembers.SCOPE_KEYS.approval("scope-a", "a-1"));
        claims.put(TestMembers.SCOPE_KEYS.claim("a-2"), TestMembers.SCOPE_KEYS.approval("scope-a", "a-2") + "\0~\0" + TestMembers.SCOPE_KEYS.approval("scope-b", "a-2"));
        claims.put(TestMembers.SCOPE_KEYS.claim("a-3"), "garbage");
        int removed = members[0].sweepStaleClaims() + members[1].sweepStaleClaims();
        assertThat(removed).isEqualTo(2);
        assertThat(claims.containsKey(TestMembers.SCOPE_KEYS.claim("a-1"))).isFalse();
        assertThat(claims.containsKey(TestMembers.SCOPE_KEYS.claim("a-2"))).isFalse();
        assertThat(claims.containsKey(TestMembers.SCOPE_KEYS.claim("p-1"))).isTrue();
        assertThat(claims.containsKey(TestMembers.SCOPE_KEYS.claim("a-3"))).isTrue();
    }
}

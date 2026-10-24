package io.github.aindriub.dataprism.hazelcast;

import com.hazelcast.map.IMap;
import io.github.aindriub.dataprism.oversight.ApprovalRequest;
import io.github.aindriub.dataprism.oversight.ApprovalRequest.Status;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.List;
import java.util.OptionalInt;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The two orders of the claim protocol, each forced: the previous creator reads its claim before the taker marks it
 * (r before m), and after (m before r). Exactly one creator succeeds in each, and the claim names the survivor.
 */
class ClaimInterleavingTest {

    private static final String ID = "inter-1";
    private static final String KEY_A = TestMembers.SCOPE_KEYS.approval("scope-a", ID);
    private static final String KEY_B = TestMembers.SCOPE_KEYS.approval("scope-b", ID);
    private static final String TOKEN_A = "00000000-0000-0000-0000-00000000000a";
    private static final String TOKEN_B = "00000000-0000-0000-0000-00000000000b";
    private static final String CLAIM_A = ClaimEntries.value(KEY_A, TOKEN_A);
    private static final String CLAIM_B = ClaimEntries.value(KEY_B, TOKEN_B);

    private PrivacyCluster cluster;
    private IMap<String, String> approvals;
    private IMap<String, String> claims;

    @BeforeEach
    void start() {
        int port = FreePorts.consecutive(1);
        cluster = TestMembers.member(TestMembers.uniqueName("interleave"), port, List.of("127.0.0.1:" + port),
                OptionalInt.empty());
        approvals = cluster.instance().getMap(PrivacyCluster.APPROVAL_MAP);
        claims = cluster.instance().getMap(PrivacyCluster.APPROVAL_ID_MAP);
    }

    @AfterEach
    void stop() {
        cluster.close();
    }

    /** An approvals map that runs {@code between} just before the taker's second look at the old entry. */
    @SuppressWarnings("unchecked")
    private IMap<String, String> approvalsThatRun(Runnable between) {
        AtomicInteger lookups = new AtomicInteger();
        return (IMap<String, String>) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{IMap.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("containsKey") && KEY_A.equals(args[0])
                            && lookups.incrementAndGet() == 2) {
                        between.run();
                    }
                    return invoke(method, approvals, args);
                });
    }

    private void ownerClaimsButHasNotInserted() {
        assertThat(claims.putIfAbsent(TestMembers.SCOPE_KEYS.claim(ID), CLAIM_A)).isNull();
    }

    @Test
    void theOwnerInsertsButReadsItsClaimAfterTheTakerMarkedItAndOnlyTheTakerSucceeds() {
        ownerClaimsButHasNotInserted();
        boolean[] ownerKept = new boolean[1];
        IMap<String, String> seenByTaker = approvalsThatRun(() -> {
            // The owner inserts, then reads its claim: it is marked, so the owner withdraws.
            approvals.put(KEY_A, "owner-entry");
            ownerKept[0] = CLAIM_A.equals(claims.get(TestMembers.SCOPE_KEYS.claim(ID)));
            if (!ownerKept[0]) approvals.remove(KEY_A, "owner-entry");
        });

        assertThat(ClaimEntries.claim(claims, seenByTaker, TestMembers.SCOPE_KEYS.claim(ID), KEY_B, TOKEN_B)).isTrue();

        assertThat(ownerKept[0]).isFalse();
        assertThat(claims.get(TestMembers.SCOPE_KEYS.claim(ID))).isEqualTo(CLAIM_B);
        assertThat(approvals.containsKey(KEY_A)).isFalse();
    }

    @Test
    void theOwnerInsertsBeforeTheTakersCheckAndOnlyTheOwnerSucceeds() {
        ownerClaimsButHasNotInserted();
        IMap<String, String> seenByTaker = approvalsThatRun(() -> approvals.put(KEY_A, "owner-entry"));

        // The taker marked the claim, then finds the owner's entry, puts the claim back and refuses.
        assertThat(ClaimEntries.claim(claims, seenByTaker, TestMembers.SCOPE_KEYS.claim(ID), KEY_B, TOKEN_B)).isFalse();

        // The owner's read of its claim now succeeds, so its entry stays.
        assertThat(claims.get(TestMembers.SCOPE_KEYS.claim(ID))).isEqualTo(CLAIM_A);
        assertThat(approvals.containsKey(KEY_A)).isTrue();
        assertThat(approvals.containsKey(KEY_B)).isFalse();
    }

    @Test
    void aCreatorStoppedJustBeforeItsClaimCheckWinsAgainstATakerWhoSeesItsEntry() throws Exception {
        PrivacyCluster[] members = TestMembers.pair(TestMembers.uniqueName("interleave-e2e"), OptionalInt.empty());
        StalledInsert stall = StalledInsert.beforeSecondClaimCheck();
        try (var caller = Executors.newSingleThreadExecutor()) {
            var owner = new HazelcastApprovalStore(stall.over(members[0]), Duration.ofMillis(300));
            var taker = new HazelcastApprovalStore(members[1], Duration.ofMillis(300));
            ApprovalRequest a = TestMembers.request(ID, "scope-a", Status.PENDING);
            ApprovalRequest b = TestMembers.request(ID, "scope-b", Status.PENDING);
            var stalled = caller.submit(() -> owner.create(a));
            try {
                assertThat(stall.reached.await(10, TimeUnit.SECONDS)).isTrue();
                // Its entry is inserted; wait for the locks it still holds to run out, then the taker runs.
                StalledInsert.awaitFree(members[1], PrivacyCluster.APPROVAL_ID_MAP, TestMembers.SCOPE_KEYS.claim(ID));
                StalledInsert.awaitFree(members[1], PrivacyCluster.APPROVAL_PENDING_MAP,
                        TestMembers.SCOPE_KEYS.pending(a.kind(), a.requesterPrincipalId()));
                StalledInsert.awaitFree(members[1], PrivacyCluster.APPROVAL_MAP, KEY_A);
                assertThatThrownBy(() -> taker.create(b)).isExactlyInstanceOf(IllegalArgumentException.class)
                        .hasMessage("duplicate approval id");

                stall.resume();
                assertThat(stalled.get(10, TimeUnit.SECONDS)).isEqualTo(a);
                IMap<String, String> claimMap = members[1].instance().getMap(PrivacyCluster.APPROVAL_ID_MAP);
                IMap<String, String> entries = members[1].instance().getMap(PrivacyCluster.APPROVAL_MAP);
                assertThat(ClaimEntries.read(claimMap.get(TestMembers.SCOPE_KEYS.claim(ID)))).hasValueSatisfying(
                        named -> assertThat(named.entryKey()).isEqualTo(KEY_A));
                assertThat(TestMembers.keysOfApproval(entries, ID))
                        .containsExactly(KEY_A);
            } finally {
                stall.resume();
            }
        } finally {
            stall.resume();
            for (PrivacyCluster member : members) member.close();
        }
    }

    private static Object invoke(Method method, Object target, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException failure) {
            throw failure.getCause();
        }
    }
}

package io.github.aindriub.dataprism.hazelcast;

import com.hazelcast.core.EntryView;
import com.hazelcast.map.IMap;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;

import static io.github.aindriub.dataprism.hazelcast.TestMembers.NOW;
import static org.assertj.core.api.Assertions.assertThat;

/** Every id claim and pending entry outlives the approvals it guards, and none outlives them for long. */
class ApprovalRetentionTest {

    private static final Duration RETENTION = Duration.ofMinutes(1);

    private PrivacyCluster cluster;
    private HazelcastApprovalStore store;
    private IMap<String, String> approvals;
    private IMap<String, String> claims;
    private IMap<String, String> pendingSets;

    @BeforeEach
    void start() {
        int port = FreePorts.consecutive(1);
        cluster = TestMembers.member(TestMembers.uniqueName("retention"), port, List.of("127.0.0.1:" + port),
                OptionalInt.empty());
        store = new HazelcastApprovalStore(cluster, Duration.ofMillis(300));
        approvals = cluster.instance().getMap(PrivacyCluster.APPROVAL_MAP);
        claims = cluster.instance().getMap(PrivacyCluster.APPROVAL_ID_MAP);
        pendingSets = cluster.instance().getMap(PrivacyCluster.APPROVAL_PENDING_MAP);
    }

    @AfterEach
    void stop() {
        cluster.close();
    }

    private static long ttl(IMap<String, String> map, String key) {
        EntryView<String, String> view = map.getEntryView(key);
        assertThat(view).as("entry %s", key).isNotNull();
        return view.getTtl();
    }

    private static void awaitTrue(BooleanSupplier condition) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) throw new AssertionError("condition never became true");
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
        }
    }

    @Test
    void theClaimOutlivesItsApprovalAtCreation() {
        ApprovalRequest request = TestMembers.request("r-1", "scope-a", Status.PENDING);
        store.create(request, 5);
        assertThat(ttl(claims, "r-1")).isGreaterThanOrEqualTo(ttl(approvals, ScopeKeys.approval("scope-a", "r-1"))
                + RETENTION.toMillis());
    }

    @Test
    void theClaimStillOutlivesItsApprovalAfterADecisionWithASkewedClock() {
        for (Duration skew : new Duration[]{Duration.ofHours(-2), Duration.ofMinutes(59), Duration.ofHours(3)}) {
            String id = "r-" + skew.toMinutes();
            store.create(TestMembers.request(id, "scope-a", Status.PENDING));
            try {
                store.reject(id, "bob", NOW.plus(skew));
            } catch (ApprovalRefusedException expired) {
                // A clock three hours ahead finds the approval expired, which is also a change of state.
                assertThat(expired.code()).isEqualTo(ApprovalRefusedException.Code.EXPIRED);
            }
            assertThat(ttl(claims, id)).as("claim after a decision %s from the request", skew)
                    .isGreaterThanOrEqualTo(ttl(approvals, ScopeKeys.approval("scope-a", id)) + RETENTION.toMillis());
        }
    }

    @Test
    void theClaimStillOutlivesItsApprovalAfterConsume() {
        store.create(TestMembers.request("r-9", "scope-a", Status.PENDING));
        store.approve("r-9", "bob", NOW);
        store.consumeApproved(Kind.TOOL_CALL, "alice", "scope-a", "search", "fp-r-9", NOW.minus(Duration.ofHours(2)));
        assertThat(ttl(claims, "r-9")).isGreaterThanOrEqualTo(ttl(approvals, ScopeKeys.approval("scope-a", "r-9"))
                + RETENTION.toMillis());
    }

    @Test
    void thePendingEntryOutlivesTheLongestApprovalByTheRetentionAndIsRefreshedOnEachReserve() {
        String key = HazelcastApprovalStore.pendingKey(Kind.TOOL_CALL, "alice");
        store.create(TestMembers.request("p-1", "scope-a", Status.PENDING), 5);
        long first = ttl(pendingSets, key);
        assertThat(first).isBetween(Duration.ofHours(1).plus(RETENTION).toMillis() - 5_000,
                Duration.ofHours(1).plus(RETENTION).toMillis());
        ApprovalRequest longer = new ApprovalRequest("p-2", Kind.TOOL_CALL, "alice", "client", "scope-a", "search",
                "fp-p-2", "PERSON_NAME", "synthetic", "audit", "CASE-1", NOW, NOW.plus(Duration.ofHours(5)),
                Status.PENDING, null, null);
        store.create(longer, 5);
        assertThat(ttl(pendingSets, key)).isBetween(Duration.ofHours(5).plus(RETENTION).toMillis() - 5_000,
                Duration.ofHours(5).plus(RETENTION).toMillis());
        // Leaving the set keeps a lifetime that still covers the ids that remain.
        store.reject("p-1", "bob", NOW);
        assertThat(ttl(pendingSets, key)).isBetween(Duration.ofHours(5).plus(RETENTION).toMillis() - 5_000,
                Duration.ofHours(5).plus(RETENTION).toMillis());
    }

    /** Hazelcast truncates entry times to whole seconds, so this uses seconds, not milliseconds. */
    @Test
    void anApprovalThatExpiresUndecidedTakesItsPendingEntryAndClaimWithIt() {
        var quick = new HazelcastApprovalStore(cluster, Duration.ofMillis(300), Duration.ofSeconds(2));
        ApprovalRequest request = new ApprovalRequest("q-1", Kind.TOOL_CALL, "alice", "client", "scope-a", "search",
                "fp-q-1", "PERSON_NAME", "synthetic", "audit", "CASE-1", NOW, NOW.plus(Duration.ofSeconds(2)),
                Status.PENDING, null, null);
        quick.create(request, 5);
        String key = HazelcastApprovalStore.pendingKey(Kind.TOOL_CALL, "alice");
        assertThat(pendingSets.containsKey(key)).isTrue();
        awaitTrue(() -> !pendingSets.containsKey(key));
        awaitTrue(() -> !claims.containsKey("q-1"));
        assertThat(approvals.containsKey(ScopeKeys.approval("scope-a", "q-1"))).isFalse();
    }
}

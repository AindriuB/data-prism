package io.github.aindriub.dataprism.hazelcast;

import com.hazelcast.config.Config;
import com.hazelcast.core.Hazelcast;
import io.github.aindriub.dataprism.oversight.ApprovalRefusedException;
import io.github.aindriub.dataprism.oversight.ApprovalRefusedException.Code;
import io.github.aindriub.dataprism.oversight.ApprovalRequest;
import io.github.aindriub.dataprism.oversight.ApprovalRequest.Kind;
import io.github.aindriub.dataprism.oversight.ApprovalRequest.Status;
import io.github.aindriub.dataprism.oversight.ApprovalStore;
import io.github.aindriub.dataprism.oversight.InMemoryApprovalStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** One set of rules, run against every {@link ApprovalStore} implementation. */
class ApprovalStoreContractTest {

    private static final Instant NOW = Instant.parse("2030-01-01T00:00:00Z");
    private static final String MEMORY = "memory";
    private static final String HAZELCAST = "hazelcast";

    private PrivacyCluster cluster;
    private PrivacyCluster second;

    @AfterEach
    void shutDown() {
        for (PrivacyCluster c : new PrivacyCluster[]{cluster, second}) {
            if (c != null) {
                c.close();
            }
        }
    }

    private ApprovalStore store(String kind) {
        if (kind.equals(MEMORY)) {
            return new InMemoryApprovalStore();
        }
        Config config = new Config();
        config.setClusterName("dataprism-approval-contract-" + System.nanoTime());
        config.getJetConfig().setEnabled(false);
        config.getNetworkConfig().getJoin().getMulticastConfig().setEnabled(false);
        config.getNetworkConfig().getJoin().getTcpIpConfig().setEnabled(false);
        cluster = PrivacyCluster.embedded(config, false);
        return new HazelcastApprovalStore(cluster);
    }

    private static ApprovalRequest request(String id, String scope, Instant expiresAt, Status status) {
        return new ApprovalRequest(id, Kind.TOOL_CALL, "alice", "client", scope, "search", "fp-" + id,
                "PERSON_NAME", "synthetic", "audit", "CASE-1", NOW, expiresAt, status, null, null);
    }

    private static ApprovalRequest request(String id, String scope) {
        return request(id, scope, NOW.plus(1, ChronoUnit.HOURS), Status.PENDING);
    }

    private static Code codeOf(Runnable action) {
        try {
            action.run();
        } catch (ApprovalRefusedException refused) {
            return refused.code();
        }
        throw new AssertionError("expected a refusal");
    }

    @ParameterizedTest
    @ValueSource(strings = {MEMORY, HAZELCAST})
    void createFindAndPendingListing(String kind) {
        ApprovalStore store = store(kind);
        ApprovalRequest created = request("a1", "CASE-A");

        assertThat(store.create(created)).isEqualTo(created);

        assertThat(store.find("a1")).contains(created);
        assertThat(store.find("missing")).isEmpty();
        assertThat(store.pending(NOW)).containsExactly(created);
        assertThat(store.pending(NOW.plus(2, ChronoUnit.HOURS))).isEmpty();
        assertThat(store.findPending(Kind.TOOL_CALL, "alice", "CASE-A", "search", "fp-a1", NOW))
                .contains(created);
        assertThat(store.findPending(Kind.TOOL_CALL, "bob", "CASE-A", "search", "fp-a1", NOW)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {MEMORY, HAZELCAST})
    void aNonPendingRequestIsRefusedAndNothingIsStored(String kind) {
        ApprovalStore store = store(kind);
        for (Status status : new Status[]{Status.APPROVED, Status.REJECTED, Status.CONSUMED, Status.EXPIRED}) {
            ApprovalRequest decided = request("n-" + status, "CASE-A", NOW.plus(1, ChronoUnit.HOURS), status);
            assertThatThrownBy(() -> store.create(decided)).isInstanceOf(IllegalArgumentException.class);
            assertThat(store.find(decided.approvalId())).isEmpty();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {MEMORY, HAZELCAST})
    void aDuplicateIdIsRefusedAndTheFirstIsKept(String kind) {
        ApprovalStore store = store(kind);
        ApprovalRequest first = request("dup", "CASE-A");
        store.create(first);

        assertThatThrownBy(() -> store.create(request("dup", "CASE-A")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.create(request("dup", "CASE-B")))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(store.find("dup")).contains(first);
        assertThat(store.pending(NOW)).containsExactly(first);
    }

    @ParameterizedTest
    @ValueSource(strings = {MEMORY, HAZELCAST})
    void aDuplicateOfADecidedRequestIsRefused(String kind) {
        ApprovalStore store = store(kind);
        store.create(request("done", "CASE-A"));
        ApprovalRequest approved = store.approve("done", "bob", NOW);

        assertThatThrownBy(() -> store.create(request("done", "CASE-A")))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(store.find("done")).contains(approved);
    }

    @ParameterizedTest
    @ValueSource(strings = {MEMORY, HAZELCAST})
    void aNullApproverIsRefusedAndStateIsUnchanged(String kind) {
        ApprovalStore store = store(kind);
        ApprovalRequest pending = request("a1", "CASE-A");
        store.create(pending);

        assertThatThrownBy(() -> store.approve("a1", null, NOW)).isInstanceOf(NullPointerException.class);

        assertThat(store.find("a1")).contains(pending);
    }

    @ParameterizedTest
    @ValueSource(strings = {MEMORY, HAZELCAST})
    void refusalCodes(String kind) {
        ApprovalStore store = store(kind);
        store.create(request("self", "CASE-A"));
        store.create(request("once", "CASE-A"));
        store.create(request("short", "CASE-A", NOW.plus(1, ChronoUnit.MINUTES), Status.PENDING));

        assertThat(codeOf(() -> store.approve("self", "alice", NOW))).isEqualTo(Code.SELF_APPROVAL);
        assertThat(codeOf(() -> store.approve("nope", "bob", NOW))).isEqualTo(Code.UNKNOWN_APPROVAL);
        store.approve("once", "bob", NOW);
        assertThat(codeOf(() -> store.approve("once", "bob", NOW))).isEqualTo(Code.NOT_PENDING);
        assertThat(codeOf(() -> store.approve("short", "bob", NOW.plus(2, ChronoUnit.MINUTES))))
                .isEqualTo(Code.EXPIRED);
        assertThat(codeOf(() -> store.approve("short", "bob", NOW))).isEqualTo(Code.NOT_PENDING);
    }

    @ParameterizedTest
    @ValueSource(strings = {MEMORY, HAZELCAST})
    void rejectingAnUnknownIdIsRefused(String kind) {
        ApprovalStore store = store(kind);

        assertThat(codeOf(() -> store.reject("nope", "bob", NOW))).isEqualTo(Code.UNKNOWN_APPROVAL);
    }

    @ParameterizedTest
    @ValueSource(strings = {MEMORY, HAZELCAST})
    void anApprovalIsConsumedOnce(String kind) {
        ApprovalStore store = store(kind);
        store.create(request("a1", "CASE-A"));
        store.approve("a1", "bob", NOW);

        assertThat(store.consumeApproved(Kind.TOOL_CALL, "alice", "CASE-A", "search", "fp-a1", NOW))
                .hasValueSatisfying(r -> assertThat(r.status()).isEqualTo(Status.CONSUMED));
        assertThat(store.consumeApproved(Kind.TOOL_CALL, "alice", "CASE-A", "search", "fp-a1", NOW)).isEmpty();
        assertThat(store.find("a1")).hasValueSatisfying(r -> assertThat(r.status()).isEqualTo(Status.CONSUMED));
    }

    @ParameterizedTest
    @ValueSource(strings = {MEMORY, HAZELCAST})
    void forgettingAScopeRemovesOnlyThatScope(String kind) {
        ApprovalStore store = store(kind);
        store.create(request("a1", "CASE-A"));
        ApprovalRequest other = request("b1", "CASE-B");
        store.create(other);

        store.forgetScope("CASE-A");

        assertThat(store.find("a1")).isEmpty();
        assertThat(store.find("b1")).contains(other);
        assertThat(store.pending(NOW)).containsExactly(other);
    }

    private static ApprovalRequest request(String id, Kind kind, String requester, Instant createdAt,
                                           Instant expiresAt) {
        return new ApprovalRequest(id, kind, requester, "client", "CASE-A", "search", "fp-" + id,
                "PERSON_NAME", "synthetic", "audit", "CASE-1", createdAt, expiresAt, Status.PENDING, null, null);
    }

    private static ApprovalRequest live(String id, Kind kind, String requester) {
        return request(id, kind, requester, NOW, NOW.plus(1, ChronoUnit.HOURS));
    }

    @ParameterizedTest
    @ValueSource(strings = {MEMORY, HAZELCAST})
    void atTheCapCreateIsRefusedAndNothingIsStored(String kind) {
        ApprovalStore store = store(kind);
        store.create(live("c1", Kind.TOOL_CALL, "alice"), 2);
        store.create(live("c2", Kind.TOOL_CALL, "alice"), 2);

        assertThat(codeOf(() -> store.create(live("c3", Kind.TOOL_CALL, "alice"), 2)))
                .isEqualTo(Code.TOO_MANY_PENDING);

        assertThat(store.find("c3")).isEmpty();
        assertThat(store.pending(NOW)).hasSize(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {MEMORY, HAZELCAST})
    void aNonPositiveCapIsRefusedAndNothingIsStored(String kind) {
        ApprovalStore store = store(kind);

        assertThatThrownBy(() -> store.create(live("z0", Kind.TOOL_CALL, "alice"), 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.create(live("z1", Kind.TOOL_CALL, "alice"), -1))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(store.pending(NOW)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {MEMORY, HAZELCAST})
    void boundedCreateStillRefusesNonPendingAndDuplicates(String kind) {
        ApprovalStore store = store(kind);
        ApprovalRequest approved = request("n1", "CASE-A", NOW.plus(1, ChronoUnit.HOURS), Status.APPROVED);
        store.create(live("d1", Kind.TOOL_CALL, "alice"), 5);

        assertThatThrownBy(() -> store.create(approved, 5)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.create(live("d1", Kind.TOOL_CALL, "alice"), 5))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {MEMORY, HAZELCAST})
    void requestsThatAreNotLivePendingDoNotCount(String kind) {
        ApprovalStore store = store(kind);
        Instant later = NOW.plus(2, ChronoUnit.MINUTES);
        store.create(request("e1", Kind.TOOL_CALL, "alice", NOW, NOW.plus(1, ChronoUnit.MINUTES)), 1);
        // The first has expired by `later`, so it does not count.
        store.create(request("e2", Kind.TOOL_CALL, "alice", later, later.plus(1, ChronoUnit.HOURS)), 1);
        // Approved, rejected, consumed: none count.
        Instant end = later.plus(1, ChronoUnit.HOURS);
        store.create(request("a", Kind.TOOL_CALL, "carol", later, end), 1);
        store.approve("a", "bob", later);
        store.create(request("r", Kind.TOOL_CALL, "carol", later, end), 1);
        store.reject("r", "bob", later);
        store.create(request("k", Kind.TOOL_CALL, "carol", later, end), 1);
        store.approve("k", "bob", later);
        assertThat(store.consumeApproved(Kind.TOOL_CALL, "carol", "CASE-A", "search", "fp-k", later)).isPresent();
        store.create(request("c-new", Kind.TOOL_CALL, "carol", later, later.plus(1, ChronoUnit.HOURS)), 1);

        assertThat(store.find("e2")).isPresent();
        assertThat(store.find("c-new")).isPresent();
        assertThat(codeOf(() -> store.create(
                request("c-over", Kind.TOOL_CALL, "carol", later, later.plus(1, ChronoUnit.HOURS)), 1)))
                .isEqualTo(Code.TOO_MANY_PENDING);
    }

    @ParameterizedTest
    @ValueSource(strings = {MEMORY, HAZELCAST})
    void anotherRequesterOrTheOtherKindIsUnaffected(String kind) {
        ApprovalStore store = store(kind);
        store.create(live("t1", Kind.TOOL_CALL, "alice"), 1);

        store.create(live("t2", Kind.TOOL_CALL, "bob"), 1);
        store.create(live("r1", Kind.REIDENTIFICATION, "alice"), 1);

        assertThat(codeOf(() -> store.create(live("t3", Kind.TOOL_CALL, "alice"), 1)))
                .isEqualTo(Code.TOO_MANY_PENDING);
        assertThat(codeOf(() -> store.create(live("r2", Kind.REIDENTIFICATION, "alice"), 1)))
                .isEqualTo(Code.TOO_MANY_PENDING);
        assertThat(store.pending(NOW)).hasSize(3);
    }

    @ParameterizedTest
    @ValueSource(strings = {MEMORY, HAZELCAST})
    void consumeApprovedMatchesTheExactBindingOnly(String kind) {
        ApprovalStore store = store(kind);
        store.create(request("b1", "CASE-A"), 5);
        store.approve("b1", "bob", NOW);

        assertThat(store.consumeApproved(Kind.TOOL_CALL, "alice", "CASE-A", "search", "fp-other", NOW)).isEmpty();
        assertThat(store.consumeApproved(Kind.TOOL_CALL, "alice", "CASE-B", "search", "fp-b1", NOW)).isEmpty();
        assertThat(store.consumeApproved(Kind.TOOL_CALL, "alice", "CASE-A", "other-tool", "fp-b1", NOW)).isEmpty();
        assertThat(store.consumeApproved(Kind.TOOL_CALL, "mallory", "CASE-A", "search", "fp-b1", NOW)).isEmpty();
        assertThat(store.consumeApproved(Kind.REIDENTIFICATION, "alice", "CASE-A", "search", "fp-b1", NOW))
                .isEmpty();
        assertThat(store.find("b1")).hasValueSatisfying(r -> assertThat(r.status()).isEqualTo(Status.APPROVED));

        assertThat(store.consumeApproved(Kind.TOOL_CALL, "alice", "CASE-A", "search", "fp-b1", NOW)).isPresent();
    }

    @Test
    void theRequesterLockKeyIsNeitherAnEntryKeyNorAnIdLockKey() {
        for (Kind k : Kind.values()) {
            for (String principal : new String[]{"alice", "", null, "a\0b"}) {
                String lock = HazelcastApprovalStore.requesterLockKey(k, principal);
                // Ids hold no NUL, so a bare-id lock key holds none; the lock key holds NULs.
                assertThat(lock).contains("\0");
                // An entry key is scope + NUL + id, and an id holds no NUL, so its first NUL is followed by none.
                String afterFirstSeparator = lock.substring(lock.indexOf('\0') + 1);
                assertThat(afterFirstSeparator).contains("\0");
                assertThat(ScopeKeys.approvalId(lock)).contains("\0");
            }
        }
        assertThat(HazelcastApprovalStore.requesterLockKey(Kind.TOOL_CALL, "alice"))
                .isNotEqualTo(HazelcastApprovalStore.requesterLockKey(Kind.REIDENTIFICATION, "alice"))
                .isNotEqualTo(HazelcastApprovalStore.requesterLockKey(Kind.TOOL_CALL, "bob"));
    }

    @Test
    void concurrentCreatesFromTwoMembersNeverExceedTheCap() throws Exception {
        String name = "dataprism-approval-cap-" + System.nanoTime();
        int port = FreePorts.consecutive(2);
        cluster = PrivacyCluster.using(Hazelcast.newHazelcastInstance(member(name, port, port)), false);
        second = PrivacyCluster.using(Hazelcast.newHazelcastInstance(member(name, port + 1, port)), false);
        assertThat(cluster.instance().getCluster().getMembers()).hasSize(2);
        ApprovalStore viaA = new HazelcastApprovalStore(cluster);
        ApprovalStore viaB = new HazelcastApprovalStore(second);

        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            // Count 4 of 5: two concurrent creates, one from each member, must not both succeed.
            for (int round = 0; round < 10; round++) {
                String requester = "racer-" + round;
                for (int i = 0; i < 4; i++) {
                    viaA.create(live("r" + round + "-pre" + i, Kind.TOOL_CALL, requester), 5);
                }
                int cap = 5;
                CountDownLatch go = new CountDownLatch(1);
                AtomicInteger admitted = new AtomicInteger();
                List<Future<?>> done = new ArrayList<>();
                for (int i = 0; i < 2; i++) {
                    ApprovalStore side = i == 0 ? viaA : viaB;
                    String id = "r" + round + "-race" + i;
                    done.add(pool.submit(() -> {
                        go.await();
                        try {
                            side.create(live(id, Kind.TOOL_CALL, requester), cap);
                            admitted.incrementAndGet();
                        } catch (ApprovalRefusedException refused) {
                            assertThat(refused.code()).isEqualTo(Code.TOO_MANY_PENDING);
                        }
                        return null;
                    }));
                }
                go.countDown();
                for (Future<?> f : done) {
                    f.get();
                }
                assertThat(admitted.get()).isEqualTo(1);
                assertThat(viaB.pending(NOW).stream().filter(r -> r.requesterPrincipalId().equals(requester)))
                        .hasSize(5);
            }

            // From empty, a crowd on both members admits exactly the cap.
            CountDownLatch go = new CountDownLatch(1);
            AtomicInteger admitted = new AtomicInteger();
            List<Future<?>> done = new ArrayList<>();
            for (int i = 0; i < 16; i++) {
                ApprovalStore side = i % 2 == 0 ? viaA : viaB;
                String id = "crowd-" + i;
                done.add(pool.submit(() -> {
                    go.await();
                    try {
                        side.create(live(id, Kind.REIDENTIFICATION, "crowd"), 5);
                        admitted.incrementAndGet();
                    } catch (ApprovalRefusedException refused) {
                        assertThat(refused.code()).isEqualTo(Code.TOO_MANY_PENDING);
                    }
                    return null;
                }));
            }
            go.countDown();
            for (Future<?> f : done) {
                f.get();
            }
            assertThat(admitted.get()).isEqualTo(5);
            assertThat(viaA.pending(NOW).stream().filter(r -> r.requesterPrincipalId().equals("crowd"))).hasSize(5);
        } finally {
            pool.shutdownNow();
        }
    }

    private static Config member(String name, int port, int base) {
        Config config = new Config();
        config.setClusterName(name);
        config.getJetConfig().setEnabled(false);
        config.getNetworkConfig().getJoin().getMulticastConfig().setEnabled(false);
        config = PrivacyCluster.configure(config);
        config.getNetworkConfig().setPort(port).setPortAutoIncrement(false);
        config.getNetworkConfig().getJoin().getTcpIpConfig().setEnabled(true)
                .addMember("127.0.0.1:" + base).addMember("127.0.0.1:" + (base + 1));
        config.getNetworkConfig().getInterfaces().setEnabled(true).addInterface("127.0.0.1");
        return config;
    }
}

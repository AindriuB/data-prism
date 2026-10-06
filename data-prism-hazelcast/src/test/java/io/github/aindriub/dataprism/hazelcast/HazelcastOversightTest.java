package io.github.aindriub.dataprism.hazelcast;

import com.hazelcast.config.Config;
import com.hazelcast.core.Hazelcast;
import io.github.aindriub.dataprism.oversight.ApprovalRefusedException;
import io.github.aindriub.dataprism.oversight.ApprovalRequest;
import io.github.aindriub.dataprism.oversight.ApprovalRequest.Kind;
import io.github.aindriub.dataprism.oversight.ApprovalRequest.Status;
import io.github.aindriub.dataprism.oversight.ApprovalStore;
import io.github.aindriub.dataprism.oversight.InMemoryApprovalStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HazelcastOversightTest {

    private static final Instant NOW = Instant.parse("2030-01-01T00:00:00Z");

    private PrivacyCluster a;
    private PrivacyCluster b;

    @AfterEach
    void shutDown() {
        for (PrivacyCluster c : new PrivacyCluster[]{a, b}) {
            if (c != null) {
                try {
                    c.close();
                } catch (RuntimeException alreadyDown) {
                    // closed by the test
                }
            }
        }
    }

    private static Config config(String name) {
        Config config = new Config();
        config.setClusterName(name);
        config.getJetConfig().setEnabled(false);
        config.getNetworkConfig().getJoin().getMulticastConfig().setEnabled(false);
        config.getNetworkConfig().getJoin().getTcpIpConfig().setEnabled(false);
        return config;
    }

    private void single() {
        a = PrivacyCluster.embedded(config("dataprism-oversight-" + System.nanoTime()), false);
    }

    /** Two members of one loopback-only cluster. */
    private void pair() {
        String name = "dataprism-oversight-pair-" + System.nanoTime();
        int port = 25000 + (int) (System.nanoTime() % 2000) * 2;
        a = PrivacyCluster.using(Hazelcast.newHazelcastInstance(member(name, port, port)), false);
        b = PrivacyCluster.using(Hazelcast.newHazelcastInstance(member(name, port + 1, port)), false);
        assertThat(a.instance().getCluster().getMembers()).hasSize(2);
    }

    private static Config member(String name, int port, int base) {
        Config config = PrivacyCluster.configure(config(name));
        config.getNetworkConfig().setPort(port).setPortAutoIncrement(false);
        config.getNetworkConfig().getJoin().getTcpIpConfig().setEnabled(true)
                .addMember("127.0.0.1:" + base).addMember("127.0.0.1:" + (base + 1));
        config.getNetworkConfig().getInterfaces().setEnabled(true).addInterface("127.0.0.1");
        return config;
    }

    private static ApprovalRequest request(String id, String scope, Instant expiresAt) {
        return new ApprovalRequest(id, Kind.TOOL_CALL, "alice", "client", scope, "search", "fp-" + id,
                "PERSON_NAME", "synthetic", "audit", "CASE-1", NOW, expiresAt, Status.PENDING, null, null);
    }

    private static ApprovalRequest request(String id) {
        return request(id, "CASE-A", NOW.plus(1, ChronoUnit.HOURS));
    }

    @Test
    void aPauseSetOnOneMemberIsObservedOnTheOther() {
        pair();
        var viaA = new HazelcastOversightState(a);
        var viaB = new HazelcastOversightState(b);

        viaA.pauseAll();
        viaA.pauseTool("search");
        viaA.pauseScope("CASE-A");

        assertThat(viaB.allPaused()).isTrue();
        assertThat(viaB.toolPaused("search")).isTrue();
        assertThat(viaB.toolPaused("other")).isFalse();
        assertThat(viaB.scopePaused("CASE-A")).isTrue();
        assertThat(viaB.snapshot().pausedTools()).containsExactly("search");
        assertThat(viaB.snapshot().pausedScopes()).containsExactly("CASE-A");
        viaB.resumeAll();
        assertThat(viaA.allPaused()).isFalse();
    }

    @Test
    void sameIdCreatedConcurrentlyInTwoScopesAdmitsOnlyOneAndNeverCrossApproves() throws Exception {
        pair();
        var viaA = new HazelcastApprovalStore(a);
        var viaB = new HazelcastApprovalStore(b);
        for (int round = 0; round < 20; round++) {
            String id = "dup-" + round;
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                CountDownLatch go = new CountDownLatch(1);
                Callable<Boolean> one = () -> {
                    go.await();
                    try {
                        viaA.create(request(id, "S1", NOW.plus(1, ChronoUnit.HOURS)));
                        return true;
                    } catch (IllegalArgumentException e) {
                        return false;
                    }
                };
                Callable<Boolean> two = () -> {
                    go.await();
                    try {
                        viaB.create(request(id, "S2", NOW.plus(1, ChronoUnit.HOURS)));
                        return true;
                    } catch (IllegalArgumentException e) {
                        return false;
                    }
                };
                Future<Boolean> f1 = pool.submit(one);
                Future<Boolean> f2 = pool.submit(two);
                go.countDown();
                assertThat(List.of(f1.get(), f2.get())).containsExactlyInAnyOrder(true, false);
            } finally {
                pool.shutdownNow();
            }
        }
    }

    @Test
    void anIdHeldInTwoScopesIsRefusedAsUnknown() {
        single();
        var store = new HazelcastApprovalStore(a);
        var raw = a.instance().<String, String>getMap(PrivacyCluster.APPROVAL_MAP);
        raw.put(ScopeKeys.approval("S1", "x"), HazelcastApprovalStore.Codec.encode(request("x", "S1", NOW.plus(1, ChronoUnit.HOURS))));
        raw.put(ScopeKeys.approval("S2", "x"), HazelcastApprovalStore.Codec.encode(request("x", "S2", NOW.plus(1, ChronoUnit.HOURS))));

        assertThatThrownBy(() -> store.approve("x", "bob", NOW))
                .isInstanceOf(ApprovalRefusedException.class);
        assertThatThrownBy(() -> store.find("x")).isInstanceOf(ApprovalRefusedException.class);
    }

    @Test
    void aScopeIdStartingWithToolIsReportedAsAScope() {
        single();
        var state = new HazelcastOversightState(a);
        state.pauseScope("tool:weird");
        assertThat(state.snapshot().pausedScopes()).containsExactly("tool:weird");
        assertThat(state.snapshot().pausedTools()).isEmpty();
    }

    @Test
    void twoMembersConsumingOneApprovalYieldExactlyOneSuccess() throws Exception {
        pair();
        var viaA = new HazelcastApprovalStore(a);
        var viaB = new HazelcastApprovalStore(b);
        viaA.create(request("ap-1"));
        viaB.approve("ap-1", "bob", NOW);

        ExecutorService pool = Executors.newFixedThreadPool(16);
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<Callable<Boolean>> attempts = new java.util.ArrayList<>();
            for (int i = 0; i < 16; i++) {
                ApprovalStore store = i % 2 == 0 ? viaA : viaB;
                attempts.add(() -> {
                    go.await();
                    return store.consumeApproved(Kind.TOOL_CALL, "alice", "CASE-A", "search", "fp-ap-1", NOW)
                            .isPresent();
                });
            }
            List<Future<Boolean>> results = attempts.stream().map(pool::submit).toList();
            go.countDown();
            int successes = 0;
            for (Future<Boolean> f : results) {
                if (f.get()) {
                    successes++;
                }
            }
            assertThat(successes).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(viaB.find("ap-1")).get().extracting(ApprovalRequest::status).isEqualTo(Status.CONSUMED);
    }

    @Test
    void concurrentAcquiresAcrossMembersRespectTheLimit() throws Exception {
        pair();
        var limiters = List.of(new HazelcastCallerRateLimiter(a), new HazelcastCallerRateLimiter(b));
        ExecutorService pool = Executors.newFixedThreadPool(16);
        try {
            List<Callable<Boolean>> attempts = new java.util.ArrayList<>();
            for (int i = 0; i < 60; i++) {
                var limiter = limiters.get(i % 2);
                attempts.add(() -> limiter.tryAcquire("alice", 10, Duration.ofMinutes(1), NOW));
            }
            int admitted = 0;
            for (Future<Boolean> f : pool.invokeAll(attempts)) {
                if (f.get()) {
                    admitted++;
                }
            }
            assertThat(admitted).isEqualTo(10);
        } finally {
            pool.shutdownNow();
        }
        assertThat(limiters.get(0).tryAcquire("alice", 10, Duration.ofMinutes(1),
                NOW.plus(1, ChronoUnit.MINUTES))).isTrue();
    }

    @Test
    void approvalLifecycleMatchesTheInMemoryStore() {
        single();
        for (ApprovalStore store : List.of(new HazelcastApprovalStore(a), new InMemoryApprovalStore())) {
            store.create(request("ap-1"));
            assertThat(store.findPending(Kind.TOOL_CALL, "alice", "CASE-A", "search", "fp-ap-1", NOW)).isPresent();
            assertThat(store.pending(NOW)).hasSize(1);
            assertThatThrownBy(() -> store.approve("ap-1", "alice", NOW))
                    .isInstanceOfSatisfying(ApprovalRefusedException.class,
                            e -> assertThat(e.code()).isEqualTo(ApprovalRefusedException.Code.SELF_APPROVAL));
            assertThatThrownBy(() -> store.approve("nope", "bob", NOW))
                    .isInstanceOfSatisfying(ApprovalRefusedException.class,
                            e -> assertThat(e.code()).isEqualTo(ApprovalRefusedException.Code.UNKNOWN_APPROVAL));
            assertThat(store.consumeApproved(Kind.TOOL_CALL, "alice", "CASE-A", "search", "fp-ap-1", NOW)).isEmpty();
            assertThat(store.approve("ap-1", "bob", NOW).status()).isEqualTo(Status.APPROVED);
            assertThatThrownBy(() -> store.approve("ap-1", "bob", NOW))
                    .isInstanceOfSatisfying(ApprovalRefusedException.class,
                            e -> assertThat(e.code()).isEqualTo(ApprovalRefusedException.Code.NOT_PENDING));
            assertThat(store.consumeApproved(Kind.TOOL_CALL, "alice", "CASE-A", "search", "fp-ap-1", NOW)).isPresent();
            assertThat(store.consumeApproved(Kind.TOOL_CALL, "alice", "CASE-A", "search", "fp-ap-1", NOW)).isEmpty();

            store.create(request("ap-2", "CASE-A", NOW.plusSeconds(10)));
            assertThatThrownBy(() -> store.approve("ap-2", "bob", NOW.plusSeconds(11)))
                    .isInstanceOfSatisfying(ApprovalRefusedException.class,
                            e -> assertThat(e.code()).isEqualTo(ApprovalRefusedException.Code.EXPIRED));
            store.create(request("ap-3"));
            assertThat(store.reject("ap-3", "bob", NOW).status()).isEqualTo(Status.REJECTED);
            store.forgetScope("CASE-A");
            assertThat(store.find("ap-3")).isEmpty();
        }
    }

    @Test
    void createRequiresPendingAndRefusesDuplicatesAndApproveRefusesNullApprover() {
        single();
        var store = new HazelcastApprovalStore(a);
        assertThatThrownBy(() -> store.create(request("ap-1").withStatus(Status.APPROVED)))
                .isInstanceOf(IllegalArgumentException.class);
        store.create(request("ap-1"));
        assertThatThrownBy(() -> store.create(request("ap-1"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.approve("ap-1", null, NOW)).isInstanceOf(NullPointerException.class);
        assertThat(store.find("ap-1")).get().extracting(ApprovalRequest::status).isEqualTo(Status.PENDING);
    }

    @Test
    void approvalsRoundTripFieldsIncludingNullsAndAwkwardCharacters() {
        single();
        var store = new HazelcastApprovalStore(a);
        ApprovalRequest odd = new ApprovalRequest("ap-\\1", Kind.REIDENTIFICATION, "alice", null, null, null,
                "fp", "PERSON_NAME", "syn~thetic", "a\\0b \u0000 c", null, NOW, NOW.plusSeconds(60),
                Status.PENDING, null, null);
        store.create(odd);
        assertThat(store.find("ap-\\1")).contains(odd);
    }

    @Test
    void endingAScopeRemovesItsApprovalsAndScopePauseButNotOthers() {
        single();
        var approvals = new HazelcastApprovalStore(a);
        var state = new HazelcastOversightState(a);
        approvals.create(request("ap-1", "CASE-A", NOW.plusSeconds(600)));
        approvals.create(request("ap-2", "CASE-B", NOW.plusSeconds(600)));
        state.pauseScope("CASE-A");
        state.pauseScope("CASE-B");
        state.pauseAll();
        state.pauseTool("search");

        new ScopeIdentityIndex(a).endScope("CASE-A");

        assertThat(approvals.find("ap-1")).isEmpty();
        assertThat(state.scopePaused("CASE-A")).isFalse();
        assertThat(approvals.find("ap-2")).isPresent();
        assertThat(state.scopePaused("CASE-B")).isTrue();
        assertThat(state.allPaused()).isTrue();
        assertThat(state.toolPaused("search")).isTrue();
    }

    @Test
    void approvalEntriesCarryTheRequestsExpiryAsTheirTtl() {
        single();
        var store = new HazelcastApprovalStore(a);
        store.create(request("ap-1", "CASE-A", NOW.plusSeconds(90)));
        var info = a.instance().getMap(PrivacyCluster.APPROVAL_MAP)
                .getEntryView("CASE-A\u0000ap-1");
        assertThat(info.getTtl()).isEqualTo(90_000L);
        store.approve("ap-1", "bob", NOW.plusSeconds(30));
        assertThat(a.instance().getMap(PrivacyCluster.APPROVAL_MAP).getEntryView("CASE-A\u0000ap-1").getTtl())
                .isEqualTo(60_000L);
    }

    @Test
    void withTheClusterDownEveryMethodThrowsAndReturnsNothing() {
        single();
        var state = new HazelcastOversightState(a);
        var approvals = new HazelcastApprovalStore(a);
        var limiter = new HazelcastCallerRateLimiter(a);
        approvals.create(request("ap-1"));
        a.close();

        List<org.assertj.core.api.ThrowableAssert.ThrowingCallable> calls = List.of(
                state::allPaused, () -> state.toolPaused("t"), () -> state.scopePaused("s"),
                state::pauseAll, state::resumeAll, () -> state.pauseTool("t"), () -> state.resumeTool("t"),
                () -> state.pauseScope("s"), () -> state.resumeScope("s"), state::snapshot,
                () -> approvals.create(request("ap-9")), () -> approvals.find("ap-1"),
                () -> approvals.findPending(Kind.TOOL_CALL, "alice", "CASE-A", "search", "fp", NOW),
                () -> approvals.approve("ap-1", "bob", NOW), () -> approvals.reject("ap-1", "bob", NOW),
                () -> approvals.consumeApproved(Kind.TOOL_CALL, "alice", "CASE-A", "search", "fp", NOW),
                () -> approvals.pending(NOW), () -> approvals.forgetScope("CASE-A"),
                () -> limiter.tryAcquire("alice", 5, Duration.ofMinutes(1), NOW));
        for (var call : calls) {
            assertThatThrownBy(call).isInstanceOf(RuntimeException.class)
                    .isNotInstanceOf(ApprovalRefusedException.class);
        }
    }
}

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
                    com.hazelcast.core.HazelcastInstance started = c.instance();
                    c.close();
                    // close() leaves an instance it did not start, and this test started it.
                    started.getLifecycleService().terminate();
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
        int port = FreePorts.consecutive(2);
        a = PrivacyCluster.using(Hazelcast.newHazelcastInstance(member(name, port, port)), false, TestMembers.KEYS);
        b = PrivacyCluster.using(Hazelcast.newHazelcastInstance(member(name, port + 1, port)), false, TestMembers.KEYS);
        assertThat(TestMembers.settled(30, a, b)).as("both members list each other and their partitions are safe")
                .isTrue();
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
    void anIdHeldInTwoScopesIsUsableOnlyWhereTheClaimNamesIt() {
        single();
        var store = new HazelcastApprovalStore(a);
        var raw = a.instance().<String, String>getMap(PrivacyCluster.APPROVAL_MAP);
        String token = "00000000-0000-0000-0000-000000000001";
        raw.put(a.scopeKeys().approval("S1", "x"), HazelcastApprovalStore.Codec.encode(request("x", "S1", NOW.plus(1, ChronoUnit.HOURS)), token));
        raw.put(a.scopeKeys().approval("S2", "x"), HazelcastApprovalStore.Codec.encode(request("x", "S2", NOW.plus(1, ChronoUnit.HOURS)), token));

        // No claim names either entry, so neither is usable.
        assertThatThrownBy(() -> store.approve("x", "bob", NOW)).isInstanceOf(ApprovalRefusedException.class);
        assertThat(store.find("x")).isEmpty();

        a.instance().<String, String>getMap(PrivacyCluster.APPROVAL_ID_MAP)
                .put(a.scopeKeys().claim("x"), ClaimEntries.value(a.scopeKeys().approval("S1", "x"), token));
        assertThat(store.find("x")).hasValueSatisfying(found -> assertThat(found.scopeId()).isEqualTo("S1"));
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
                .getEntryView(a.scopeKeys().approval("CASE-A", "ap-1"));
        assertThat(info.getTtl()).isEqualTo(90_000L);
        store.approve("ap-1", "bob", NOW.plusSeconds(30));
        assertThat(a.instance().getMap(PrivacyCluster.APPROVAL_MAP).getEntryView(a.scopeKeys().approval("CASE-A", "ap-1")).getTtl())
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

    // ---- 228 A10: the pause gauges are registered once per instance and read through the instance's slot -------

    /** Records every gauge registration, and lets a test read the registered suppliers. */
    private static final class Gauges implements io.github.aindriub.dataprism.core.metrics.PrivacyMetrics {
        final java.util.Map<io.github.aindriub.dataprism.core.metrics.Metric, java.util.List<java.util.function.DoubleSupplier>>
                registered = new java.util.concurrent.ConcurrentHashMap<>();

        @Override
        public void record(io.github.aindriub.dataprism.core.metrics.Metric metric, String tag, Duration elapsed) {
        }

        @Override
        public void increment(io.github.aindriub.dataprism.core.metrics.Metric metric, String tag) {
        }

        @Override
        public void increment(io.github.aindriub.dataprism.core.metrics.Metric metric) {
        }

        @Override
        public void gauge(io.github.aindriub.dataprism.core.metrics.Metric metric,
                          java.util.function.DoubleSupplier supplier) {
            registered.computeIfAbsent(metric, m -> new java.util.concurrent.CopyOnWriteArrayList<>()).add(supplier);
        }

        int registrations(io.github.aindriub.dataprism.core.metrics.Metric metric) {
            var suppliers = registered.get(metric);
            return suppliers == null ? 0 : suppliers.size();
        }

        double read(io.github.aindriub.dataprism.core.metrics.Metric metric) {
            return registered.get(metric).get(0).getAsDouble();
        }
    }

    private static final io.github.aindriub.dataprism.core.metrics.Metric FRESH =
            io.github.aindriub.dataprism.core.metrics.Metric.CLUSTER_PAUSE_FRESH;
    private static final io.github.aindriub.dataprism.core.metrics.Metric AGE =
            io.github.aindriub.dataprism.core.metrics.Metric.CLUSTER_PAUSE_AGE_SECONDS;

    private static void assertRegisteredOnce(Gauges gauges) {
        assertThat(gauges.registrations(FRESH)).isEqualTo(1);
        assertThat(gauges.registrations(AGE)).isEqualTo(1);
    }

    @Test
    void oneWrapperAndOneStateRegisterTheGaugesOnce() {
        single();
        Gauges gauges = new Gauges();
        try (var state = new HazelcastOversightState(a, ClusterLocks.DEFAULT_TIMEOUT, gauges)) {
            assertRegisteredOnce(gauges);
            state.pauseAll();
            assertThat(state.allPaused()).isTrue();
            assertThat(gauges.read(FRESH)).isEqualTo(1.0);
            assertThat(gauges.read(AGE)).isGreaterThanOrEqualTo(0.0);
        }
        assertRegisteredOnce(gauges);
    }

    @Test
    void twoWrappersOverOneInstanceOnOneRegistryRegisterTheGaugesOnce() {
        String name = "dataprism-gauges-" + System.nanoTime();
        int port = FreePorts.consecutive(1);
        var instance = Hazelcast.newHazelcastInstance(member(name, port, port));
        a = PrivacyCluster.using(instance, false, TestMembers.KEYS);
        b = PrivacyCluster.using(instance, false, TestMembers.KEYS);
        Gauges gauges = new Gauges();
        try (var first = new HazelcastOversightState(a, ClusterLocks.DEFAULT_TIMEOUT, gauges);
             var second = new HazelcastOversightState(b, ClusterLocks.DEFAULT_TIMEOUT, gauges)) {
            assertRegisteredOnce(gauges);
            first.pauseTool("search");
            assertThat(second.toolPaused("search")).as("one shared document").isTrue();
        }
    }

    @Test
    void twoStatesOverOneWrapperOnOneRegistryRegisterTheGaugesOnce() {
        single();
        Gauges gauges = new Gauges();
        try (var first = new HazelcastOversightState(a, ClusterLocks.DEFAULT_TIMEOUT, gauges);
             var second = new HazelcastOversightState(a, ClusterLocks.DEFAULT_TIMEOUT, gauges)) {
            assertRegisteredOnce(gauges);
            assertThat(first.document()).isSameAs(second.document());
        }
    }

    @Test
    void theRegisteringStateClosingLeavesTheGaugesRegisteredOnceAndReadingTheDocument() {
        single();
        Gauges gauges = new Gauges();
        var registering = new HazelcastOversightState(a, ClusterLocks.DEFAULT_TIMEOUT, gauges);
        try (var other = new HazelcastOversightState(a, ClusterLocks.DEFAULT_TIMEOUT, gauges)) {
            other.pauseAll();
            assertThat(other.allPaused()).isTrue();
            registering.close();
            assertRegisteredOnce(gauges);
            assertThat(gauges.read(FRESH)).as("still reading the document").isEqualTo(1.0);
        }
    }

    @Test
    void everyStateClosingKeepsTheGaugesReadingAndFreshReadsZeroOnceTheLeasePassesWithoutARenewal() {
        single();
        Gauges gauges = new Gauges();
        long[] now = {1_000_000_000L};
        var state = new HazelcastOversightState(a, ClusterLocks.DEFAULT_TIMEOUT, gauges,
                java.time.Clock.systemUTC(), () -> now[0]);
        state.pauseAll();
        assertThat(state.allPaused()).isTrue();
        assertThat(gauges.read(FRESH)).isEqualTo(1.0);

        state.close();
        assertThat(gauges.read(FRESH)).isEqualTo(1.0);
        now[0] += ClusterSecuritySettings.DEFAULT_PAUSE_LEASE.toNanos() + 1;
        assertThat(gauges.read(FRESH)).as("no renewal once every state is closed").isEqualTo(0.0);
        assertRegisteredOnce(gauges);
    }

    @Test
    void whenTheInstanceIsShutDownFreshReadsZeroAndTheAgeReadsNaN() {
        single();
        Gauges gauges = new Gauges();
        var state = new HazelcastOversightState(a, ClusterLocks.DEFAULT_TIMEOUT, gauges);
        state.pauseAll();
        assertThat(state.allPaused()).isTrue();
        a.instance().getLifecycleService().terminate();

        assertThat(gauges.read(FRESH)).isEqualTo(0.0);
        assertThat(gauges.read(AGE)).isNaN();
    }

    @Test
    void beforeTheDocumentHasAcceptedAnythingFreshReadsZeroAndTheAgeReadsNaN() {
        single();
        // An unreadable value stands where the document should be: nothing is accepted, and a lone member does not
        // write over it.
        a.instance().getMap(PrivacyCluster.OVERSIGHT_MAP).put(
                a.keyedNames().mapKey(PrivacyCluster.OVERSIGHT_MAP, PauseDocument.CANONICAL), "garbage");
        Gauges gauges = new Gauges();
        try (var state = new HazelcastOversightState(a, ClusterLocks.DEFAULT_TIMEOUT, gauges)) {
            assertThat(gauges.read(FRESH)).isEqualTo(0.0);
            assertThat(gauges.read(AGE)).isNaN();
        }
    }

    @Test
    void twoStatesCreatedAtOnceOverOneInstanceRegisterTheGaugesExactlyOnce() throws Exception {
        single();
        Gauges gauges = new Gauges();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        try {
            Callable<HazelcastOversightState> create = () -> {
                go.await();
                return new HazelcastOversightState(a, ClusterLocks.DEFAULT_TIMEOUT, gauges);
            };
            Future<HazelcastOversightState> one = pool.submit(create);
            Future<HazelcastOversightState> two = pool.submit(create);
            go.countDown();
            one.get(60, java.util.concurrent.TimeUnit.SECONDS).close();
            two.get(60, java.util.concurrent.TimeUnit.SECONDS).close();
            assertRegisteredOnce(gauges);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void aNoneRegistryDoesNotClaimTheSlotSoALaterRecordingRegistryRegistersOnce() {
        single();
        Gauges gauges = new Gauges();
        try (var first = new HazelcastOversightState(a, ClusterLocks.DEFAULT_TIMEOUT,
                io.github.aindriub.dataprism.core.metrics.PrivacyMetrics.none());
             var second = new HazelcastOversightState(a, ClusterLocks.DEFAULT_TIMEOUT, gauges)) {
            assertRegisteredOnce(gauges);
        }
    }

    // ---- 228 A9: the status and the operator-visible refusals ------------------------------------------------

    @Test
    void withTheGateOpenAndTheDocumentNotFreshReadsAndResumeRefuseAsUnavailableAndTheClusterStillServes() {
        single();
        long[] now = {1_000_000_000L};
        var state = new HazelcastOversightState(a, ClusterLocks.DEFAULT_TIMEOUT,
                io.github.aindriub.dataprism.core.metrics.PrivacyMetrics.none(), java.time.Clock.systemUTC(),
                () -> now[0]);
        try {
            state.pauseTool("search");
            assertThat(state.toolPaused("search")).isTrue();
            // The stored document stays authentic. The gate fails on freshness alone: a listed peer shows content this
            // member never held, so no gate passes, and the lease runs out.
            Object stored = a.instance().getMap(PrivacyCluster.OVERSIGHT_MAP).get(
                    a.keyedNames().mapKey(PrivacyCluster.OVERSIGHT_MAP, PauseDocument.CANONICAL));
            state.document().close();
            state.document().peers = () -> new PauseDocument.PeerView(
                    java.util.Map.of(java.util.UUID.randomUUID(), new PauseDocument.Digest(new byte[16])), 0, true, 0,
                    -1, false, true);
            now[0] += ClusterSecuritySettings.DEFAULT_PAUSE_LEASE.toNanos() + 1;

            PauseDocumentTest.assertUnavailable("a read", () -> state.toolPaused("search"));
            PauseDocumentTest.assertUnavailable("a resume", () -> state.resumeTool("search"));
            assertThat(a.instance().getMap(PrivacyCluster.OVERSIGHT_MAP).get(
                    a.keyedNames().mapKey(PrivacyCluster.OVERSIGHT_MAP, PauseDocument.CANONICAL)))
                    .as("the document is still there and untouched").isEqualTo(stored);
            assertThat(state.securityStatus().reason()).isEqualTo(ClusterSecurityStatus.Reason.PAUSE_NOT_FRESH);
            assertThat(a.securityStatus().serving()).isTrue();
        } finally {
            state.close();
        }
    }

    // ---- 228: real clusters of host-managed members, driven by injected clocks --------------------------------

    private static final Duration MESH_LEASE = MemberCardTest.LEASE;

    private static final java.util.concurrent.atomic.AtomicInteger NO_WRITE_NOTES =
            new java.util.concurrent.atomic.AtomicInteger();

    /**
     * Ticks the cards of a wrapper and waits, on the write and not on time, until it has completed. A card write is
     * asynchronous and real while the clocks are injected, so a later tick must not read a card that is a step behind.
     * Afterwards the stored card must carry the latest {@code cardSequence} taken, which names a write that did not
     * land if one ever fails. A tick that is not due, or whose failure the tick swallows, writes nothing, and a write
     * that failed sets the mark: both are noted on stderr, at most twenty times per JVM.
     */
    static void tickCardsAndWait(PrivacyCluster wrapper, String who) {
        MemberCards cards = wrapper.memberCards().orElseThrow();
        MemberCards.Declarations declarations = MemberCards.declarations(wrapper.instance());
        int started = cards.writesStarted();
        cards.tick();
        // One state is judged, so a write that a pause notice starts on the executor between two reads cannot fail the
        // check: no write is outstanding, and the stored card carries the latest sequence any write of this instance
        // took, or a failed write has set the mark (the tick swallows it and the next pass retries).
        assertThat(MemberCardTest.Fixture.await(30, () -> !cards.writeOutstanding()
                && (storedCardSequence(wrapper, cards) >= declarations.cardSequenceForTest()
                        || declarations.marked())))
                .as("the card write of " + who + " completed and landed: the stored cardSequence is the latest taken")
                .isTrue();
        if (storedCardSequence(wrapper, cards) < declarations.cardSequenceForTest() && declarations.marked()) {
            note("the card write of " + who + " failed (swallowed by the tick; the mark is set, the next pass retries)");
        }
        if (cards.writesStarted() <= started) {
            note("the card tick of " + who + " started no write (not due, or a failure the tick swallows)");
        }
    }

    private static void note(String line) {
        if (NO_WRITE_NOTES.incrementAndGet() <= 20) {
            System.err.println("mesh: " + line);
        }
    }

    private static long storedCardSequence(PrivacyCluster wrapper, MemberCards cards) {
        var self = wrapper.instance().getCluster().getLocalMember().getUuid();
        String key = cards.keyForTest(self);
        Object stored = wrapper.instance().getMap(PrivacyCluster.MEMBER_CARD_MAP).get(key);
        if (!(stored instanceof String sealed)) {
            return -1;
        }
        var opened = io.github.aindriub.dataprism.hazelcast.seal.Sealer
                .builder(TestMembers.KEYS, io.github.aindriub.dataprism.core.metrics.PrivacyMetrics.none()).build()
                .open(PrivacyCluster.MEMBER_CARD_MAP, key, sealed);
        if (opened.outcome() != io.github.aindriub.dataprism.hazelcast.seal.Opened.Outcome.AUTHENTIC) {
            return -1;
        }
        var card = MemberCards.Card.decode(opened.plaintext());
        return card == null ? -1 : card.cardSequence();
    }

    /**
     * Keyed members of one cluster whose wall clock and monotonic clock a test moves. Nothing ticks by itself: a test
     * calls {@link #tick}, {@link #round} and {@link #advance}.
     */
    private static final class Mesh implements AutoCloseable {
        final String name = TestMembers.uniqueName("mesh");
        final int capacity;
        final int base;
        final ClusterSecuritySettings settings;
        final MemberCardTest.Fixture.Metrics metrics = new MemberCardTest.Fixture.Metrics();
        final java.util.List<com.hazelcast.core.HazelcastInstance> instances = new java.util.ArrayList<>();
        final java.util.List<long[]> nanos = new java.util.ArrayList<>();
        final java.util.List<MemberCardTest.Fixture.TestClock> clocks = new java.util.ArrayList<>();
        final java.util.List<PrivacyCluster> wrappers = new java.util.ArrayList<>();
        final java.util.List<HazelcastOversightState> states = new java.util.ArrayList<>();
        final java.util.List<com.hazelcast.core.HazelcastInstance> intruders = new java.util.ArrayList<>();
        long nowNanos = 1_000_000_000_000L;
        int nextPort;

        Mesh(int capacity, ClusterSecuritySettings settings) {
            this.capacity = capacity;
            this.settings = settings;
            this.base = FreePorts.consecutive(capacity + 2);
            this.nextPort = base;
        }

        Mesh(int capacity) {
            this(capacity, MemberCardTest.Fixture.settings(
                    ClusterSecuritySettings.UnverifiedMemberAction.REFUSE));
        }

        private java.util.List<String> addresses() {
            java.util.List<String> all = new java.util.ArrayList<>();
            for (int i = 0; i < capacity + 2; i++) {
                all.add("127.0.0.1:" + (base + i));
            }
            return all;
        }

        PrivacyCluster wrapperOn(com.hazelcast.core.HazelcastInstance instance, MemberCardTest.Fixture.TestClock clock) {
            var environment = new PrivacyCluster.Environment(clock, metrics, new RecordingLogger().logger(),
                    Duration.ofHours(1));
            return PrivacyCluster.using(instance, false, Duration.ofMinutes(1), TestMembers.KEYS, settings,
                    environment);
        }

        /** Adds a keyed member (instance and wrapper); returns its index. */
        int addMember() {
            long[] clock = {nowNanos};
            Config config = PrivacyCluster.configure(ClusterMembership.tcpIp(name, addresses()).withPort(nextPort++)
                    .withInterface("127.0.0.1").toConfig());
            config.getUserContext().put(MemberCards.NANO_CLOCK_CONTEXT_KEY,
                    (java.util.function.LongSupplier) () -> clock[0]);
            var instance = Hazelcast.newHazelcastInstance(config);
            MemberCardTest.Fixture.TestClock wall = new MemberCardTest.Fixture.TestClock();
            wall.advance(wallOffset);
            instances.add(instance);
            nanos.add(clock);
            clocks.add(wall);
            wrappers.add(wrapperOn(instance, wall));
            states.add(null);
            return instances.size() - 1;
        }

        Duration wallOffset = Duration.ZERO;

        /** Adds a member without keys, an outsider that has no card. */
        void addIntruder() {
            intruders.add(MemberCardTest.Fixture.plain(name, nextPort++, addresses()));
        }

        HazelcastOversightState state(int i) {
            if (states.get(i) == null) {
                states.set(i, new HazelcastOversightState(wrappers.get(i), ClusterLocks.DEFAULT_TIMEOUT,
                        io.github.aindriub.dataprism.core.metrics.PrivacyMetrics.none(),
                        new PauseDocument.Environment(() -> nanos.get(i)[0], clocks.get(i), null)));
            }
            return states.get(i);
        }

        void advance(Duration by) {
            nowNanos += by.toNanos();
            for (long[] n : nanos) {
                n[0] += by.toNanos();
            }
            for (var c : clocks) {
                c.advance(by);
            }
        }

        /** Ticks the cards of member i and waits on the write; see {@link #tickCardsAndWait}. */
        void tickCards(int i) {
            if (wrappers.get(i) != null) {
                tickCardsAndWait(wrappers.get(i), "member " + i);
            }
        }

        void tick(int i) {
            tickCards(i);
            if (states.get(i) != null) {
                states.get(i).document().tick();
            }
        }

        void round() {
            for (int i = 0; i < instances.size(); i++) {
                if (instances.get(i).getLifecycleService().isRunning() && wrappers.get(i) != null) {
                    tick(i);
                }
            }
        }

        /** Advances in steps of a quarter lease, ticking every member after each, for {@code leases} leases. */
        void run(double leases) {
            int steps = (int) Math.round(leases * 4);
            for (int s = 0; s < steps; s++) {
                advance(MESH_LEASE.dividedBy(4));
                round();
            }
        }

        boolean fresh(int i) {
            return states.get(i).document().fresh();
        }

        boolean reads(int i) {
            try {
                states.get(i).snapshot();
                return true;
            } catch (IllegalStateException refused) {
                return false;
            }
        }

        java.util.Set<String> tools(int i) {
            return states.get(i).snapshot().pausedTools();
        }

        PauseDocument.PeerView peers(int i) {
            return MemberCards.peers(wrappers.get(i)).snapshot();
        }

        String documentKey(int i) {
            return wrappers.get(i).keyedNames().mapKey(PrivacyCluster.OVERSIGHT_MAP, PauseDocument.CANONICAL);
        }

        com.hazelcast.map.IMap<String, Object> documentMap(int i) {
            return instances.get(i).getMap(PrivacyCluster.OVERSIGHT_MAP);
        }

        com.hazelcast.map.IMap<String, Object> cardMap(int i) {
            return instances.get(i).getMap(PrivacyCluster.MEMBER_CARD_MAP);
        }

        String cardKey(int viewer, int member) {
            return wrappers.get(viewer).memberCards().orElseThrow()
                    .keyForTest(instances.get(member).getCluster().getLocalMember().getUuid());
        }

        /** The sequence of the stored document, or -1 if there is none or it does not open. */
        long storedSequence(int viewer) {
            Object raw = documentMap(viewer).get(documentKey(viewer));
            if (!(raw instanceof String sealed)) {
                return -1;
            }
            var opened = IdentityMaps.sealerFor(wrappers.get(viewer), io.github.aindriub.dataprism.core.metrics
                    .PrivacyMetrics.none(), io.github.aindriub.dataprism.hazelcast.seal.ServingGate.open())
                    .open(PrivacyCluster.OVERSIGHT_MAP, documentKey(viewer), sealed);
            var doc = PauseDocument.Doc.decode(opened.plaintext());
            return doc == null ? -1 : doc.sequence();
        }

        /** Shuts member i down the way a leaving member does. */
        void leave(int i) {
            if (states.get(i) != null) {
                states.get(i).close();
            }
            wrappers.get(i).close();
            instances.get(i).shutdown();
            wrappers.set(i, null);
        }

        @Override
        public void close() {
            for (int i = 0; i < wrappers.size(); i++) {
                if (states.get(i) != null) {
                    states.get(i).close();
                }
                if (wrappers.get(i) != null) {
                    try {
                        wrappers.get(i).close();
                    } catch (RuntimeException alreadyDown) {
                        // closed by the test
                    }
                }
            }
            for (var instance : instances) {
                try {
                    instance.getLifecycleService().terminate();
                } catch (RuntimeException alreadyDown) {
                    // ditto
                }
            }
            for (var instance : intruders) {
                try {
                    instance.getLifecycleService().terminate();
                } catch (RuntimeException alreadyDown) {
                    // ditto
                }
            }
        }
    }

    @Test
    void threeMembersStartedTogetherWriteExactlyOneFirstDocumentAndEveryReadWorks() {
        try (Mesh mesh = new Mesh(3)) {
            for (int i = 0; i < 3; i++) {
                mesh.addMember();
            }
            for (int i = 0; i < 3; i++) {
                mesh.state(i);
            }
            mesh.run(4);
            assertThat(mesh.storedSequence(0)).as("exactly one first document").isEqualTo(1);
            for (int i = 0; i < 3; i++) {
                assertThat(mesh.reads(i)).as("member " + i).isTrue();
            }
            assertThat(mesh.metrics.count(io.github.aindriub.dataprism.core.metrics.Metric.CLUSTER_PAUSE_CONFLICT))
                    .isZero();
        }
    }

    @Test
    void aFullClusterRestartWritesExactlyOneFirstDocument() {
        for (int restart = 0; restart < 2; restart++) {
            try (Mesh mesh = new Mesh(3)) {
                for (int i = 0; i < 3; i++) {
                    mesh.addMember();
                }
                for (int i = 0; i < 3; i++) {
                    mesh.state(i);
                }
                mesh.run(4);
                assertThat(mesh.storedSequence(0)).as("restart " + restart).isEqualTo(1);
                mesh.state(0).pauseTool("kept");
                mesh.run(1);
                assertThat(mesh.reads(2)).isTrue();
            }
        }
    }

    @Test
    void aLoneColdMemberLosesADepartedMembersPause_theNamedResidual() {
        try (Mesh mesh = new Mesh(2)) {
            int a = mesh.addMember();
            int b = mesh.addMember();
            mesh.state(a);
            mesh.state(b);
            mesh.run(4);
            mesh.state(a).pauseTool("p");
            mesh.run(1);
            assertThat(mesh.tools(b)).contains("p");
            mesh.leave(a);
            mesh.documentMap(b).delete(mesh.documentKey(b));

            // B restarts its wrapper and its document only: the instance, and the cluster's other state, remain, so
            // the assertion below can fail if anything B held leaked through the restart.
            mesh.states.get(b).close();
            mesh.states.set(b, null);
            mesh.wrappers.get(b).close();
            mesh.instances.get(b).getUserContext().remove(HazelcastOversightState.MAIN_DOCUMENT_CONTEXT_KEY);
            mesh.wrappers.set(b, mesh.wrapperOn(mesh.instances.get(b), mesh.clocks.get(b)));
            assertThat(mesh.state(b).document().accepted().elements()).as("a cold document holds nothing").isEmpty();
            mesh.state(b).document().tick();
            mesh.advance(Duration.ofSeconds(1));
            mesh.tick(b);
            assertThat(mesh.storedSequence(b)).as("alone, so at once and with no wait").isEqualTo(1);
            assertThat(mesh.tools(b)).as("the departed member's pause is lost").doesNotContain("p");
        }
    }

    @Test
    void theIndexDocumentOnItsOwnNanoClockDecidesLookAgeLikeTheMainDocumentOnTheInstanceClock() {
        for (long start : new long[] {1_000_000_000L, Long.MAX_VALUE - 100, Long.MIN_VALUE + 100}) {
            try (Mesh m = startedMesh(3, 2)) {
                m.state(0).pauseTool("p");
                m.run(1);
                assertThat(m.reads(0)).as("before").isTrue();
                long[] own = {start};
                var index = new ScopeIdentityIndex(m.wrappers.get(0),
                        io.github.aindriub.dataprism.core.metrics.PrivacyMetrics.none(), ClusterLocks.DEFAULT_TIMEOUT);
                index.pauseEnvironment = new PauseDocument.Environment(() -> own[0], m.clocks.get(0), null);
                PauseDocument indexDocument = index.pauseDocument();
                boolean sawFresh = false;
                boolean sawLapsed = false;
                // Four quarter leases of fresh cards; then this member's wall clock runs ahead, so every peer card reads
                // as expired and the gate cannot pass; then the peers catch up and the gate passes again.
                for (int step = 0; step < 12; step++) {
                    m.advance(MESH_LEASE.dividedBy(4));
                    own[0] += MESH_LEASE.dividedBy(4).toNanos();
                    if (step == 4) {
                        m.clocks.get(0).advance(MESH_LEASE.multipliedBy(5));
                    }
                    if (step == 9) {
                        m.clocks.get(1).advance(MESH_LEASE.multipliedBy(5));
                    }
                    if (step < 4 || step >= 9) {
                        m.round();
                    } else {
                        m.tick(0);
                    }
                    boolean main = m.reads(0);
                    boolean indexReads;
                    try {
                        indexDocument.current(index.pauseDependencies);
                        indexReads = true;
                    } catch (IllegalStateException refused) {
                        indexReads = false;
                    }
                    assertThat(indexReads).as("start " + start + ", step " + step).isEqualTo(main);
                    sawFresh |= main;
                    sawLapsed |= !main;
                }
                assertThat(sawFresh).isTrue();
                assertThat(sawLapsed).as("the run really lapsed").isTrue();
            }
        }
    }

    private static Mesh startedMesh(int capacity, int members) {
        Mesh mesh = new Mesh(capacity);
        for (int i = 0; i < members; i++) {
            mesh.addMember();
        }
        for (int i = 0; i < members; i++) {
            mesh.state(i);
        }
        mesh.run(4);
        return mesh;
    }

    private static final io.github.aindriub.dataprism.hazelcast.PauseDocument.Target TOOL_P =
            PauseDocument.Target.tool("p");

    /** Three members with a pause of p held by all, and the early cards of the first two, taken before the pause. */
    private record Holders(Mesh mesh, String earlyCardOfA, String earlyCardOfB) implements AutoCloseable {
        @Override
        public void close() {
            mesh.close();
        }
    }

    private static Holders holdersWithPause() {
        Mesh mesh = startedMesh(4, 3);
        String a = (String) mesh.cardMap(0).get(mesh.cardKey(0, 0));
        String b = (String) mesh.cardMap(0).get(mesh.cardKey(0, 1));
        mesh.state(0).pauseTool("p");
        mesh.round();
        mesh.round();
        return new Holders(mesh, a, b);
    }

    private void restartCAndLetItWait(Holders h, String variant) {
        Mesh m = h.mesh();
        m.leave(2);
        m.documentMap(0).delete(m.documentKey(0));
        int c = m.addMember();
        m.state(c);
        if (variant.equals("cardsDeleted")) {
            m.cardMap(0).delete(m.cardKey(0, 0));
            m.cardMap(0).delete(m.cardKey(0, 1));
        }
        if (variant.equals("replayedCards")) {
            m.cardMap(0).put(m.cardKey(0, 0), h.earlyCardOfA());
            m.cardMap(0).put(m.cardKey(0, 1), h.earlyCardOfB());
        }
        int steps = variant.equals("replayedCards") ? 2 : 12;
        for (int s = 0; s < steps; s++) {
            m.advance(MESH_LEASE.dividedBy(4));
            m.tick(c);
        }
        assertThat(m.storedSequence(0)).as(variant + ": no first document is written").isEqualTo(-1);
        assertThat(m.reads(c)).as(variant + ": reads refuse until a holder restores").isFalse();
    }

    @Test
    void attackerShapesNeverWriteTheFirstDocumentRefuseReadsUntilAHolderRestoresAndKeepThePause() {
        for (String variant : java.util.List.of("deleted", "cardsDeleted", "replayedCards")) {
            try (Holders h = holdersWithPause()) {
                Mesh m = h.mesh();
                restartCAndLetItWait(h, variant);
                int c = m.instances.size() - 1;
                // A and B tick: their real cards and the restore of what they hold.
                m.tick(0);
                m.tick(1);
                m.run(2);
                assertThat(m.storedSequence(0)).as(variant).isGreaterThan(1);
                assertThat(m.reads(c)).as(variant).isTrue();
                assertThat(m.tools(c)).as(variant + ": the pause survives").contains("p");
                assertThat(m.tools(0)).contains("p");
            }
        }
    }

    private void offendingLook(Duration step, int stepsPerLease) {
        try (Mesh m = startedMeshNoRun(3)) {
            int e = 2;
            Long written = null;
            for (int s = 1; s <= e + stepsPerLease + 8 && written == null; s++) {
                m.advance(step);
                if (s == e) {
                    String saved = (String) m.cardMap(0).get(m.cardKey(0, 1));
                    m.cardMap(0).delete(m.cardKey(0, 1));
                    m.tickCards(0);
                    m.cardMap(0).put(m.cardKey(0, 1), saved);
                    m.tick(1);
                    m.tick(2);
                    m.states.get(0).document().tick();
                } else {
                    m.round();
                }
                if (m.storedSequence(0) == 1) {
                    written = (long) s;
                }
            }
            assertThat(written).as("the first document is written in the end").isNotNull();
            // The restart look is the first completed look after the offending one (step e), at step e + 1; a full
            // lease of clean looks must follow it.
            assertThat(written).isGreaterThanOrEqualTo(e + 1 + stepsPerLease);
            assertThat(written).isLessThanOrEqualTo(e + 1 + stepsPerLease + 3);
        }
    }

    private static Mesh startedMeshNoRun(int members) {
        Mesh mesh = new Mesh(members + 1);
        for (int i = 0; i < members; i++) {
            mesh.addMember();
        }
        for (int i = 0; i < members; i++) {
            mesh.state(i);
        }
        return mesh;
    }

    @Test
    void aPeerReportingAHighPauseSequenceNeverRaisesTheSequenceAMemberWrites() {
        try (Mesh m = startedMesh(3, 2)) {
            // B runs ahead: four pauses take its sequence well above A's, and its card says so.
            for (int i = 0; i < 4; i++) {
                m.state(1).pauseTool("b" + i);
            }
            m.tickCards(1);
            m.tickCards(0);
            long reported = m.wrappers.get(0).memberCards().orElseThrow().highestReportedPauseSequence();
            assertThat(reported).as("A's look shows B's high sequence").isGreaterThanOrEqualTo(5);

            // Storage is rolled back to a lower document that A has already merged (seq 2, holding nothing).
            PauseDocumentTest.plant(m.wrappers.get(0), PauseDocumentTest.doc(2, 0, java.util.Map.of(),
                    java.util.Set.of()));
            long aHeld = m.states.get(0).document().acceptedSequence();
            m.state(0).pauseTool("a1");
            assertThat(m.storedSequence(0)).as("max(held, stored) + 1, with no term from B's card")
                    .isEqualTo(Math.max(aHeld, 2) + 1);
            assertThat(m.storedSequence(0)).isLessThan(reported);
            m.state(0).pauseTool("a2");
            assertThat(m.storedSequence(0)).isEqualTo(Math.max(aHeld, 2) + 2);
        }
    }

    @Test
    void theIndexDocumentOfAMemberThatIsNotServingReportsR13sCodeInR13sOrderNotTheServingGatesRefusal() {
        try (Mesh m = startedMesh(3, 2)) {
            var index = new ScopeIdentityIndex(m.wrappers.get(0),
                    io.github.aindriub.dataprism.core.metrics.PrivacyMetrics.none(), ClusterLocks.DEFAULT_TIMEOUT);
            m.documentMap(0).delete(m.documentKey(0));
            // This wrapper stops being live: the serving gate would refuse at once with CLUSTER_OPERATION_REFUSED.
            // R13 asks first whether the index holds a document, and it holds none.
            m.wrappers.get(0).close();

            PauseDocumentTest.assertUnavailable("held == null comes before not serving",
                    () -> index.pauseDocument().endScope("s", 0L, index.pauseDependencies));
        }
    }

    @Test
    void anOperatorPauseWhoseWriteCannotCommitIsHeldLocallyMakesPeersLapseAndLandsOnTheNextRepairTick()
            throws Exception {
        try (Mesh m = startedMesh(3, 2)) {
            java.util.concurrent.ExecutorService holder = java.util.concurrent.Executors.newSingleThreadExecutor();
            try {
                String key = m.documentKey(0);
                Object storedBefore = m.documentMap(0).get(key);
                holder.submit(() -> m.documentMap(0).lock(key)).get();

                assertThatThrownBy(() -> m.state(0).pauseTool("p"))
                        .isInstanceOf(io.github.aindriub.dataprism.core.refusal.ClusterLockTimeoutException.class);

                assertThat(m.states.get(0).document().accepted().pauses().tools()).as("A holds the pause").contains("p");
                // Repeated failed pauses for the same target keep one local element, not one per attempt.
                assertThatThrownBy(() -> m.state(0).pauseTool("p"))
                        .isInstanceOf(io.github.aindriub.dataprism.core.refusal.ClusterLockTimeoutException.class);
                assertThatThrownBy(() -> m.state(0).pauseTool("p"))
                        .isInstanceOf(io.github.aindriub.dataprism.core.refusal.ClusterLockTimeoutException.class);
                assertThat(m.states.get(0).document().accepted().elements().values().stream()
                        .filter(PauseDocument.Target.tool("p")::equals).count()).as("one unlanded element").isEqualTo(1);
                assertThat(m.tools(0)).as("A honours it").contains("p");
                assertThat(m.documentMap(0).get(key)).as("nothing was written").isEqualTo(storedBefore);

                // A's card now carries a digest B has never seen; B's gate cannot pass, and its lease runs out.
                for (int step = 0; step < 6; step++) {
                    m.advance(MESH_LEASE.dividedBy(4));
                    m.tickCards(0);
                    m.tick(1);
                }
                assertThat(m.reads(1)).as("B lapses and refuses").isFalse();
                assertThat(m.fresh(1)).as("B is past its lease").isFalse();
                assertThat(m.reads(0)).as("A stays fresh and serves the pause").isTrue();
                assertThat(m.fresh(0)).isTrue();
                assertThat(m.tools(0)).contains("p");

                holder.submit(() -> m.documentMap(0).unlock(key)).get();
                m.run(2);

                assertThat(m.storedSequence(0)).as("A's repair landed").isEqualTo(
                        m.states.get(0).document().acceptedSequence());
                assertThat(m.reads(1)).as("B recovers").isTrue();
                assertThat(m.fresh(1)).isTrue();
                assertThat(m.tools(1)).contains("p");
                assertThat(m.states.get(0).document().accepted().elements().values().stream()
                        .filter(PauseDocument.Target.tool("p")::equals).count()).as("still one element").isEqualTo(1);
            } finally {
                holder.shutdownNow();
            }
        }
    }

    /** The quarter-lease step at which three members started together write the first document. */
    private static int stepOfTheFirstDocument() {
        try (Mesh m = startedMeshNoRun(3)) {
            for (int step = 1; step <= 16; step++) {
                m.advance(MESH_LEASE.dividedBy(4));
                m.round();
                if (m.storedSequence(0) == 1) {
                    return step;
                }
            }
        }
        throw new AssertionError("no first document was written");
    }

    @Test
    void aVerifiedMemberLeavingJustBeforeTheDecisionDefersTheFirstDocumentToTheNextLookWithoutAnotherLease() {
        int decision = stepOfTheFirstDocument();
        try (Mesh m = startedMeshNoRun(4)) {
            // Four members, so two others stay listed after the leave and the oldest is never alone.
            for (int step = 1; step < decision; step++) {
                m.advance(MESH_LEASE.dividedBy(4));
                m.round();
                assertThat(m.storedSequence(0)).as("step " + step).isEqualTo(-1);
            }
            m.advance(MESH_LEASE.dividedBy(4));
            m.leave(3);
            // The oldest decides without a new look: its latest look names a member that is no longer listed.
            m.states.get(0).document().tick();
            assertThat(m.storedSequence(0)).as("peer state is unknown: no Doc(1)").isEqualTo(-1);
            // One look later peer state is known and Doc(1) is written at once.
            m.tick(0);
            m.tick(1);
            m.tick(2);
            assertThat(m.storedSequence(0)).as("one look later").isEqualTo(1);
        }
    }

    @Test
    void theOldestMemberWithAVerifiedCardReportingANonEmptyDigestNeverWritesTheFirstDocument() {
        try (Mesh m = new Mesh(4)) {
            for (int i = 0; i < 3; i++) {
                m.addMember();
            }
            m.state(1);
            m.state(2);
            m.state(1).pauseTool("p");
            m.advance(MESH_LEASE.dividedBy(4));
            for (int i = 0; i < 3; i++) {
                m.tickCards(i);
            }
            m.documentMap(0).delete(m.documentKey(0));
            // The oldest has never read a document; its peers' real cards report a non-empty digest.
            m.state(0);
            for (int step = 0; step < 12; step++) {
                m.advance(MESH_LEASE.dividedBy(4));
                m.tick(0);
                m.tickCards(1);
                m.tickCards(2);
            }
            assertThat(m.peers(0).peerDigests().values()).anyMatch(digest -> !digest.equals(
                    PauseDocument.Digest.NONE));
            assertThat(m.storedSequence(0)).as("no Doc(1) in three leases").isEqualTo(-1);
            assertThat(m.reads(0)).isFalse();
            // A holder ticks: it restores what it holds, and the oldest reads the pause.
            m.tick(1);
            m.run(2);
            assertThat(m.tools(0)).contains("p");
        }
    }

    @Test
    void aCardLessOrAVersionOneCardMemberPresentMeansNoFirstDocumentUntilItIsGone() throws Exception {
        // A 0.7 member holds no key and writes no card, as an outsider; its listed presence is the whole condition.
        try (Mesh m = startedMeshNoRun(3)) {
            m.addIntruder();
            for (int step = 0; step < 12; step++) {
                m.advance(MESH_LEASE.dividedBy(4));
                m.round();
            }
            assertThat(m.storedSequence(0)).as("card-less member present").isEqualTo(-1);
            assertThat(m.peers(0).unverifiedCount()).isPositive();
            m.intruders.get(0).getLifecycleService().terminate();
            assertThat(MemberCardTest.Fixture.await(30, () -> m.instances.get(0).getCluster().getMembers().size() == 3))
                    .as("the member list no longer names the intruder").isTrue();
            m.run(4);
            assertThat(m.storedSequence(0)).as("once it is gone").isEqualTo(1);
        }
        // A version-1 card: the layout without a card sequence and a digest.
        try (Mesh m = startedMeshNoRun(3)) {
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            java.io.DataOutputStream out = new java.io.DataOutputStream(bytes);
            var memberB = m.instances.get(1).getCluster().getLocalMember().getUuid();
            out.writeUTF("dpcard");
            out.writeInt(1);
            out.writeUTF(memberB.toString());
            out.writeLong(m.clocks.get(0).instant().plus(MESH_LEASE.multipliedBy(100)).toEpochMilli());
            out.writeLong(0L);
            out.writeInt(1);
            out.writeUTF("test-cluster-1");
            out.writeInt(0);
            String key = m.cardKey(0, 1);
            String v1 = io.github.aindriub.dataprism.hazelcast.seal.Sealer
                    .builder(TestMembers.KEYS, io.github.aindriub.dataprism.core.metrics.PrivacyMetrics.none()).build()
                    .seal(PrivacyCluster.MEMBER_CARD_MAP, key, bytes.toByteArray());
            for (int step = 0; step < 12; step++) {
                m.advance(MESH_LEASE.dividedBy(4));
                m.cardMap(0).set(key, v1);
                m.tick(0);
                m.tick(2);
                m.states.get(1).document().tick();
            }
            assertThat(m.peers(0).unverifiedCount()).as("the version-1 member reads as unverified").isEqualTo(1);
            assertThat(m.storedSequence(0)).as("version-1 card present").isEqualTo(-1);
            m.run(3);
            assertThat(m.storedSequence(0)).as("once its card is current").isEqualTo(1);
        }
    }

    @Test
    void aMemberUnverifiedAtAPastLookRestartsTheFirstStartWaitFromTheNextLook() {
        offendingLook(MESH_LEASE.dividedBy(4), 4);
    }

    @Test
    void anOffendingLookOlderThanALeaseIsNeverForgottenWhenLooksAreHalfALeaseApart() {
        offendingLook(MESH_LEASE.dividedBy(2), 2);
    }

    @Test
    void aRealCardOfAHolderArrivingInsideTheLeaseStopsTheFirstDocumentAfterEmptyDigestCardsWereReplayed() {
        try (Holders h = holdersWithPause()) {
            Mesh m = h.mesh();
            restartCAndLetItWait(h, "replayedCards");
            int c = m.instances.size() - 1;
            m.tick(0);
            m.tick(1);
            m.advance(MESH_LEASE.dividedBy(4));
            m.round();
            m.run(1);
            assertThat(m.storedSequence(0)).as("a Doc(1) would have lifted the pause").isGreaterThan(1);
            assertThat(m.tools(c)).contains("p");
            // A Doc(1) planted inside a holder's own write is merged, not adopted: the write retries over the union.
            java.util.concurrent.atomic.AtomicInteger hooks = new java.util.concurrent.atomic.AtomicInteger();
            PauseDocument holder = m.states.get(0).document();
            holder.beforeCommit = () -> {
                if (hooks.incrementAndGet() == 1) {
                    PauseDocumentTest.plant(m.wrappers.get(0), PauseDocumentTest.doc(1, 0, java.util.Map.of(),
                            java.util.Set.of()));
                }
            };
            m.states.get(0).pauseTool("w");
            holder.beforeCommit = () -> { };
            assertThat(hooks.get()).as("the Doc(1) was planted inside the write").isGreaterThanOrEqualTo(1);
            m.run(1);
            for (int i : new int[] {0, 1, c}) {
                assertThat(m.tools(i)).as("member " + i).contains("p", "w");
            }
        }
    }

    @Test
    void aRestartedMemberThatHoldsNothingNeverMakesTheEstablishedMembersStaleAndBecomesFreshOnceCovered() {
        try (Mesh m = startedMesh(4, 3)) {
            m.state(0).pauseTool("p");
            m.run(1);
            for (int i = 0; i < 3; i++) {
                assertThat(m.fresh(i)).as("member " + i).isTrue();
            }
            m.leave(2);
            int c = m.addMember();
            m.state(c);
            for (int s = 0; s < 6; s++) {
                m.advance(MESH_LEASE.dividedBy(4));
                m.round();
                assertThat(m.fresh(0)).as("A, step " + s).isTrue();
                assertThat(m.fresh(1)).as("B, step " + s).isTrue();
            }
            assertThat(m.fresh(c)).isTrue();
            assertThat(m.tools(c)).contains("p");
        }
    }

    @Test
    void everyMemberLapsingAndSomeRestartingCold_theClusterRecoversWithNoOperatorWrite() {
        try (Mesh m = startedMesh(4, 3)) {
            m.state(0).pauseTool("p");
            m.run(1);
            m.advance(MESH_LEASE.multipliedBy(2));
            assertThat(m.fresh(0) || m.fresh(1) || m.fresh(2)).as("everyone lapsed").isFalse();
            m.leave(2);
            int c = m.addMember();
            m.state(c);
            int elements = m.states.get(0).document().accepted().elements().size();
            m.run(3);
            assertThat(m.fresh(0)).isTrue();
            assertThat(m.fresh(1)).isTrue();
            assertThat(m.fresh(c)).isTrue();
            assertThat(m.tools(c)).contains("p");
            assertThat(m.states.get(0).document().accepted().elements()).as("no operator write").hasSize(elements);
        }
    }

    @Test
    void aKeylessIntruderThatJoinsBeforeACookMembersFirstGateKeepsItNotFreshUntilALookAfterTheIntruderLeaves()
            throws Exception {
        try (Mesh m = new Mesh(3)) {
            int a = m.addMember();
            m.state(a);
            int b = m.addMember();
            m.round();
            m.round();
            m.addIntruder();
            assertThat(MemberCardTest.Fixture.await(30, () -> m.instances.get(b).getCluster().getMembers().size() == 3))
                    .isTrue();
            m.state(b);
            assertThat(m.fresh(b)).as("no look has read the member list with the intruder").isFalse();
            assertThat(m.states.get(b).document().established()).isFalse();
            m.tick(b);
            assertThat(m.peers(b).unverifiedCount()).isEqualTo(1);
            assertThat(m.fresh(b)).as("a look that sees the intruder").isFalse();
            m.intruders.get(0).shutdown();
            assertThat(MemberCardTest.Fixture.await(30, () -> m.instances.get(b).getCluster().getMembers().size() == 2))
                    .isTrue();
            // The membership event starts a look of its own; once one has completed after the leave, B is fresh.
            m.tick(b);
            assertThat(MemberCardTest.Fixture.await(30, () -> m.reads(b))).isTrue();
            assertThat(m.states.get(b).document().established()).isTrue();
        }
    }

    @Test
    void anEstablishedMemberBesideAKeylessIntruderStaysFreshAcrossLeasesWhileAColdOneIsNot() throws Exception {
        try (Mesh m = new Mesh(3)) {
            int a = m.addMember();
            m.state(a);
            m.round();
            m.addIntruder();
            m.run(3);
            assertThat(m.peers(a).unverifiedCount()).isEqualTo(1);
            assertThat(m.peers(a).lookCompleted()).isTrue();
            assertThat(m.fresh(a)).isTrue();
            int b = m.addMember();
            m.state(b);
            m.run(2);
            assertThat(m.fresh(b)).as("a cold member counts the intruder as unverified").isFalse();
            m.intruders.get(0).shutdown();
            assertThat(MemberCardTest.Fixture.await(30, () -> m.instances.get(b).getCluster().getMembers().size() == 2))
                    .isTrue();
            m.run(1);
            assertThat(m.fresh(b)).isTrue();
        }
    }

    @Test
    void alertAndRefuseInsideAGraceKeepEstablishedMembersFreshAndServing_andRefuseAfterTheGraceStopsServingNotPauseFresh()
            throws Exception {
        for (var action : ClusterSecuritySettings.UnverifiedMemberAction.values()) {
            var settings = new ClusterSecuritySettings(MESH_LEASE, ClusterSecuritySettings.DEFAULT_CLOCK_SKEW_TOLERANCE,
                    action, Duration.ofMinutes(10), true);
            try (Mesh m = new Mesh(5, settings)) {
                for (int i = 0; i < 3; i++) {
                    m.addMember();
                }
                for (int i = 0; i < 3; i++) {
                    m.state(i);
                }
                m.run(4);
                m.addIntruder();
                assertThat(MemberCardTest.Fixture.await(30,
                        () -> m.instances.get(0).getCluster().getMembers().size() == 4))
                        .as("the intruder is listed").isTrue();
                m.run(2);
                for (int i = 0; i < 3; i++) {
                    assertThat(m.fresh(i)).as(action + " in the grace").isTrue();
                    assertThat(m.wrappers.get(i).securityStatus().serving()).isTrue();
                }
                for (int s = 0; s < 22; s++) {
                    m.advance(MESH_LEASE.dividedBy(2));
                    m.round();
                }
                for (int i = 0; i < 3; i++) {
                    assertThat(m.fresh(i)).as("pause.fresh is independent of the action, " + action).isTrue();
                    assertThat(m.wrappers.get(i).securityStatus().serving())
                            .as(action + " after the grace").isEqualTo(
                                    action == ClusterSecuritySettings.UnverifiedMemberAction.ALERT);
                }
                int cold = m.addMember();
                m.state(cold);
                m.run(1);
                assertThat(m.fresh(cold)).as("a cold member while the intruder is present").isFalse();
                m.intruders.get(0).shutdown();
                assertThat(MemberCardTest.Fixture.await(30,
                        () -> m.instances.get(cold).getCluster().getMembers().size() == 4)).isTrue();
                m.run(1);
                assertThat(m.fresh(cold)).as("fresh once it leaves").isTrue();
            }
        }
    }

    @Test
    void aWallClockMovedBackChangesNothingAndMovedForwardMakesEveryPeerCardReadAsExpired() {
        try (Mesh m = startedMesh(3, 2)) {
            m.state(1).pauseTool("p");
            m.run(1);
            var uuidB = m.instances.get(1).getCluster().getLocalMember().getUuid();
            var before = m.peers(0).peerDigests();
            assertThat(before).containsKey(uuidB);
            m.clocks.get(0).advance(MESH_LEASE.multipliedBy(-2));
            m.round();
            assertThat(m.peers(0).peerDigests()).isEqualTo(m.peers(0).peerDigests());
            assertThat(m.peers(0).peerDigests().get(uuidB)).isNotEqualTo(PauseDocument.Digest.NONE);
            assertThat(m.fresh(0)).isTrue();
            m.clocks.get(0).advance(MESH_LEASE.multipliedBy(5));
            m.tick(0);
            assertThat(m.peers(0).peerDigests().get(uuidB)).as("the card reads as expired")
                    .isEqualTo(PauseDocument.Digest.NONE);
            m.nanos.get(0)[0] += MESH_LEASE.plusSeconds(1).toNanos();
            m.tick(0);
            assertThat(m.fresh(0)).as("no gate passes once the lease passes by the nano clock").isFalse();
        }
    }

    @Test
    void aReaderWhoseWallClockJumpsBackStillStopsCountingAPeersCardOneLeaseAfterItWasFirstSeen() {
        try (Mesh m = startedMesh(3, 2)) {
            m.state(1).pauseTool("p");
            m.run(1);
            var uuidB = m.instances.get(1).getCluster().getLocalMember().getUuid();
            // A has looked at the latest card of B (B wrote after A's last look of the final round).
            m.tickCards(0);
            m.clocks.get(0).advance(MESH_LEASE.multipliedBy(-2));
            m.nanos.get(0)[0] += MESH_LEASE.plusSeconds(1).toNanos();
            m.tickCards(0);
            assertThat(m.peers(0).peerDigests().get(uuidB)).isEqualTo(PauseDocument.Digest.NONE);
        }
    }

    @Test
    void afterClosingAWrapperOfALapsedMemberTheOtherWrapperStillSeesTheTrackedPeerAsUncovered() {
        try (Mesh m = startedMesh(3, 2)) {
            m.state(1).pauseTool("y");
            m.run(1);
            assertThat(m.tools(0)).contains("y");
            m.cardMap(0).delete(m.cardKey(0, 1));
            var second = m.wrapperOn(m.instances.get(0), m.clocks.get(0));
            m.wrappers.get(0).close();
            m.wrappers.set(0, second);
            for (int s = 0; s < 3; s++) {
                m.advance(MESH_LEASE.dividedBy(2));
                tickCardsAndWait(second, "second");
                m.states.get(0).document().tick();
            }
            var uuidP = m.instances.get(1).getCluster().getLocalMember().getUuid();
            assertThat(MemberCards.peers(second).snapshot().peerDigests().get(uuidP))
                    .isEqualTo(PauseDocument.Digest.NONE);
            assertThat(m.fresh(0)).as("M lapsed").isFalse();
            assertThat(m.states.get(0).document().accepted().live(PauseDocument.Target.tool("y"))).isNotEmpty();
            // Both wrappers closed, a third created: the same holds under it.
            var third = m.wrapperOn(m.instances.get(0), m.clocks.get(0));
            second.close();
            m.wrappers.set(0, third);
            m.advance(MESH_LEASE.dividedBy(2));
            tickCardsAndWait(third, "third");
            m.states.get(0).document().tick();
            assertThat(MemberCards.peers(third).snapshot().peerDigests().get(uuidP))
                    .isEqualTo(PauseDocument.Digest.NONE);
            assertThat(m.fresh(0)).isFalse();
        }
    }

    // ---- 228: keyless members and the close-then-rewrap case ----------------------------------------------------

    @Test
    void aKeylessSoloMemberTakesItsClocksFromItsStateReadsAtOnceWritesTheFirstDocumentWithoutAWaitAndStaysFresh() {
        single();
        long[] nanos = {1_000_000_000L};
        try (var state = new HazelcastOversightState(a, ClusterLocks.DEFAULT_TIMEOUT,
                io.github.aindriub.dataprism.core.metrics.PrivacyMetrics.none(), java.time.Clock.systemUTC(),
                () -> nanos[0])) {
            PauseDocument.PeerView view = state.document().peers.snapshot();
            assertThat(view.isAlone()).isTrue();
            assertThat(view.isOldest()).isTrue();
            assertThat(view.unverifiedCount()).isZero();
            assertThat(state.snapshot().pausedTools()).as("reads work at once").isEmpty();
            assertThat(state.document().acceptedSequence()).as("Doc(1), no wait").isEqualTo(1);
            for (int i = 0; i < 4; i++) {
                nanos[0] += ClusterSecuritySettings.DEFAULT_PAUSE_LEASE.toNanos();
                state.document().tick();
                assertThat(state.document().fresh()).as("fresh on every tick").isTrue();
            }
        }
    }

    /** Forwards to {@code target}; Hazelcast's own joiner check turns away a real joiner, so a join is announced by hand. */
    private static Object forward(Object target, java.lang.reflect.Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (java.lang.reflect.InvocationTargetException failed) {
            throw failed.getCause();
        }
    }

    private static com.hazelcast.core.HazelcastInstance capturing(com.hazelcast.core.HazelcastInstance real,
            List<com.hazelcast.cluster.MembershipListener> listeners) {
        ClassLoader loader = HazelcastOversightTest.class.getClassLoader();
        com.hazelcast.cluster.Cluster cluster = (com.hazelcast.cluster.Cluster) java.lang.reflect.Proxy
                .newProxyInstance(loader, new Class<?>[] {com.hazelcast.cluster.Cluster.class}, (p, m, a) -> {
                    if (m.getName().equals("addMembershipListener")) {
                        listeners.add((com.hazelcast.cluster.MembershipListener) a[0]);
                    }
                    return forward(real.getCluster(), m, a);
                });
        return (com.hazelcast.core.HazelcastInstance) java.lang.reflect.Proxy.newProxyInstance(loader,
                new Class<?>[] {com.hazelcast.core.HazelcastInstance.class},
                (p, m, a) -> m.getName().equals("getCluster") ? cluster : forward(real, m, a));
    }

    @Test
    void aKeylessMemberThatSawAnotherMemberStaysNeitherAloneNorFreshEvenAfterTheJoinerLeavesAndItIsWrappedAgain() {
        String name = "dataprism-keyless-" + System.nanoTime();
        int port = FreePorts.consecutive(1);
        Config config = PrivacyCluster.configure(ClusterMembership.none(name).withPort(port).toConfig());
        var real = Hazelcast.newHazelcastInstance(config);
        List<com.hazelcast.cluster.MembershipListener> listeners = new java.util.ArrayList<>();
        long[] nanos = {1_000_000_000L};
        a = PrivacyCluster.using(capturing(real, listeners), false);
        var state = new HazelcastOversightState(a, ClusterLocks.DEFAULT_TIMEOUT,
                io.github.aindriub.dataprism.core.metrics.PrivacyMetrics.none(), java.time.Clock.systemUTC(),
                () -> nanos[0]);
        state.pauseTool("seed");
        assertThat(state.snapshot().pausedTools()).containsExactly("seed");
        assertThat(state.document().peers.snapshot().isAlone()).isTrue();

        // A second member is announced to every listener, the wrapper's and the document's.
        var local = real.getCluster().getLocalMember();
        var event = new com.hazelcast.cluster.MembershipEvent(real.getCluster(), local,
                com.hazelcast.cluster.MembershipEvent.MEMBER_ADDED, java.util.Set.of(local));
        listeners.forEach(listener -> listener.memberAdded(event));

        PauseDocument.PeerView view = state.document().peers.snapshot();
        assertThat(view.isAlone()).isFalse();
        assertThat(view.isOldest()).isFalse();
        assertThat(view.lookCompleted()).as("peer state is unknown").isFalse();
        nanos[0] += ClusterSecuritySettings.DEFAULT_PAUSE_LEASE.toNanos() + 1;
        state.document().tick();
        assertThat(state.document().fresh()).as("the gate does not pass").isFalse();
        // Refused by the document's own preconditions (peer state unknown), not by the refused wrapper's accessor.
        assertThat(a.isRefused()).as("the first wrapper is refused").isTrue();
        assertThatThrownBy(() -> state.resumeTool("seed"))
                .isInstanceOf(io.github.aindriub.dataprism.core.refusal.ClusterOperationRefusedException.class);

        // The joiner leaves: announced to every listener too. The mark is not cleared.
        var gone = new com.hazelcast.cluster.MembershipEvent(real.getCluster(), local,
                com.hazelcast.cluster.MembershipEvent.MEMBER_REMOVED, java.util.Set.of());
        listeners.forEach(listener -> listener.memberRemoved(gone));
        assertThat(state.document().peers.snapshot().isAlone()).as("still refused after the joiner left").isFalse();
        // The instance is wrapped again: the mark lives as long as the instance.
        b = PrivacyCluster.using(real, false);
        try (var again = new HazelcastOversightState(b, ClusterLocks.DEFAULT_TIMEOUT,
                io.github.aindriub.dataprism.core.metrics.PrivacyMetrics.none(), java.time.Clock.systemUTC(),
                () -> nanos[0])) {
            PauseDocument.PeerView later = again.document().peers.snapshot();
            assertThat(later.isAlone()).as("refused for the life of the instance").isFalse();
            assertThat(later.lookCompleted()).isFalse();
            again.document().tick();
            assertThat(again.document().fresh()).isFalse();
            // The shared document is bound to the instance, so the refused first wrapper does not poison it.
            assertThatThrownBy(() -> again.resumeTool("seed")).isInstanceOf(
                    io.github.aindriub.dataprism.core.refusal.ClusterOperationRefusedException.class);
            assertThat(again.document()).isSameAs(state.document());
        }
    }

    @Test
    void afterTheFirstWrapperClosesBeforeItsRepairTickASecondOneRepairsTheStoredDocumentAndUsesItsOwnDependencies()
            throws Exception {
        try (Mesh m = startedMesh(4, 2)) {
            String early = (String) m.documentMap(0).get(m.documentKey(0));
            m.state(0).pauseTool("p");
            m.documentMap(0).put(m.documentKey(0), early);
            m.states.get(0).close();
            m.wrappers.get(0).close();
            var second = m.wrapperOn(m.instances.get(0), m.clocks.get(0));
            m.wrappers.set(0, second);
            var secondMetrics = new MemberCardTest.Fixture.Metrics();
            var state2 = new HazelcastOversightState(second, Duration.ofMillis(300), secondMetrics,
                    new PauseDocument.Environment(() -> m.nanos.get(0)[0], m.clocks.get(0), null));
            m.states.set(0, state2);
            assertThat(state2.document().accepted().live(TOOL_P)).as("the main document kept what wrapper 1 held")
                    .isNotEmpty();
            m.run(2);
            assertThat(m.storedSequence(0)).isGreaterThan(1);
            assertThat(m.tools(1)).as("the peer becomes covered once it merges the repair").contains("p");
            assertThat(m.fresh(0)).isTrue();

            // Wrapper 2's gate closes: an unverified intruder past the grace.
            m.addIntruder();
            assertThat(MemberCardTest.Fixture.await(30, () -> m.instances.get(0).getCluster().getMembers().size() == 3))
                    .isTrue();
            m.advance(MemberCardTest.GRACE.plusSeconds(1));
            tickCardsAndWait(second, "second");
            tickCardsAndWait(second, "second");
            assertThat(second.securityStatus().serving()).isFalse();
            assertThatThrownBy(() -> state2.resumeTool("p"))
                    .isInstanceOf(io.github.aindriub.dataprism.core.refusal.ClusterOperationRefusedException.class);

            // Gate open again, wrapper 2 fresh and covered: it succeeds, within wrapper 2's lock timeout.
            m.intruders.get(0).shutdown();
            assertThat(MemberCardTest.Fixture.await(30, () -> m.instances.get(0).getCluster().getMembers().size() == 2))
                    .isTrue();
            m.run(1);
            state2.resumeTool("p");
            assertThat(state2.snapshot().pausedTools()).doesNotContain("p");
        }
    }

    @Test
    void aKeylessEndScopeBeforeAnyStateExistsMakesNeitherTheRefusedMarkNorItsListener() {
        String name = "dataprism-keyless-index-" + System.nanoTime();
        int port = FreePorts.consecutive(1);
        Config config = PrivacyCluster.configure(ClusterMembership.none(name).withPort(port).toConfig());
        var real = Hazelcast.newHazelcastInstance(config);
        List<com.hazelcast.cluster.MembershipListener> listeners = new java.util.ArrayList<>();
        a = PrivacyCluster.using(capturing(real, listeners), false);
        int registered = listeners.size();
        try {
            new ScopeIdentityIndex(a).endScope("S");
        } catch (IllegalStateException unavailable) {
            // no pause document exists: refused after the sweeps
        }
        assertThat(listeners).as("the index registered no listener").hasSize(registered);
        assertThat(real.getUserContext().get("dataprism.cluster.pause.keyless.refused")).isNull();

        // A member joins and leaves; a state made afterwards is not marked refused.
        var local = real.getCluster().getLocalMember();
        listeners.forEach(l -> l.memberAdded(new com.hazelcast.cluster.MembershipEvent(real.getCluster(), local,
                com.hazelcast.cluster.MembershipEvent.MEMBER_ADDED, java.util.Set.of(local))));
        listeners.forEach(l -> l.memberRemoved(new com.hazelcast.cluster.MembershipEvent(real.getCluster(), local,
                com.hazelcast.cluster.MembershipEvent.MEMBER_REMOVED, java.util.Set.of())));
        b = PrivacyCluster.using(real, false);
        try (var state = new HazelcastOversightState(b, ClusterLocks.DEFAULT_TIMEOUT,
                io.github.aindriub.dataprism.core.metrics.PrivacyMetrics.none(), java.time.Clock.systemUTC(),
                () -> 1_000_000_000L)) {
            assertThat(state.document().peers.snapshot().isAlone()).as("not marked refused").isTrue();
        }
    }
}

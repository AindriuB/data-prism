package io.github.aindriub.dataprism.hazelcast;

import com.hazelcast.config.Config;
import com.hazelcast.map.IMap;
import io.github.aindriub.dataprism.core.metrics.Metric;
import io.github.aindriub.dataprism.core.metrics.PrivacyMetrics;
import io.github.aindriub.dataprism.core.refusal.ClusterLockTimeoutException;
import io.github.aindriub.dataprism.core.refusal.ClusterOperationRefusedException;
import io.github.aindriub.dataprism.hazelcast.ClusterSecuritySettings.UnverifiedMemberAction;
import io.github.aindriub.dataprism.hazelcast.MemberCardTest.Fixture;
import io.github.aindriub.dataprism.hazelcast.PauseDocument.Digest;
import io.github.aindriub.dataprism.hazelcast.PauseDocument.Doc;
import io.github.aindriub.dataprism.hazelcast.PauseDocument.Id;
import io.github.aindriub.dataprism.hazelcast.PauseDocument.Kind;
import io.github.aindriub.dataprism.hazelcast.PauseDocument.Outcome;
import io.github.aindriub.dataprism.hazelcast.PauseDocument.Target;
import io.github.aindriub.dataprism.hazelcast.boundary.Inputs;
import io.github.aindriub.dataprism.hazelcast.boundary.OversightMapsBoundary;
import io.github.aindriub.dataprism.hazelcast.seal.ClusterKeys;
import io.github.aindriub.dataprism.hazelcast.seal.Opened;
import io.github.aindriub.dataprism.hazelcast.seal.Sealer;
import io.github.aindriub.dataprism.hazelcast.seal.ServingGate;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * The pause document under its freshness gate (docs/plan/design/cluster-security.md 2.3.3, OD-3, OD-4, OD-15; brief
 * 228, contract version 2.40). Every member here is a bare {@link PauseDocument} with its own monotonic clock, its own
 * wall clock and a {@link StubPeers}, which a test moves to say what the cards show; none ticks on its own and none
 * sleeps. Two members on one cluster stand for two instances, with separate memory. The real tracker is tested in
 * {@link MemberCardTest}; the merge cases are in {@link PauseSplitBrainTest}.
 */
class PauseDocumentTest {

    /** A monotonic clock that only moves when a test says so. */
    static final class Nanos implements LongSupplier {
        private final AtomicLong now;

        Nanos() {
            this(1_000_000_000L);
        }

        Nanos(long start) {
            now = new AtomicLong(start);
        }

        @Override
        public long getAsLong() {
            return now.get();
        }

        void advance(Duration by) {
            now.addAndGet(by.toNanos());
        }
    }

    /** What the cards would say, as a test sets it. */
    static final class StubPeers implements PauseDocument.Peers {
        volatile Map<UUID, Digest> digests = Map.of();
        volatile int unverified;
        volatile boolean lookCompleted = true;
        volatile long lookAgeNanos;
        volatile long cleanRunAgeNanos = -1;
        volatile boolean oldest = true;
        volatile boolean alone;
        volatile boolean failing;

        /** A member with no peers. */
        static StubPeers lone() {
            StubPeers peers = new StubPeers();
            peers.alone = true;
            return peers;
        }

        /** A member with one listed peer that shows nothing, and a fresh completed look. */
        static StubPeers withPeer() {
            return new StubPeers();
        }

        StubPeers shows(Digest digest) {
            digests = Map.of(PEER, digest);
            return this;
        }

        StubPeers shows(Map<UUID, Digest> shown) {
            digests = shown;
            return this;
        }

        @Override
        public PauseDocument.PeerView snapshot() {
            if (failing) {
                throw new IllegalStateException("the cards cannot be read");
            }
            return new PauseDocument.PeerView(digests, unverified, lookCompleted, lookAgeNanos, cleanRunAgeNanos,
                    alone, oldest);
        }
    }

    static final UUID PEER = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    static final UUID OTHER = UUID.fromString("00000000-0000-0000-0000-0000000000a2");

    private static final Duration LEASE = ClusterSecuritySettings.DEFAULT_PAUSE_LEASE;
    private static final Duration SKEW = ClusterSecuritySettings.DEFAULT_CLOCK_SKEW_TOLERANCE;
    private static final Duration TIMEOUT = ClusterLocks.DEFAULT_TIMEOUT;

    private final Fixture fixture = new Fixture();
    private final List<PauseDocument> documents = new ArrayList<>();
    private PrivacyCluster cluster;
    private final Fixture.Metrics metrics = new Fixture.Metrics();

    @AfterEach
    void shutDown() {
        documents.forEach(PauseDocument::close);
        if (cluster != null) {
            cluster.close();
        }
        fixture.closeAll();
    }

    private static Config config(String name) {
        Config config = new Config();
        config.setClusterName(name);
        config.getJetConfig().setEnabled(false);
        config.getNetworkConfig().getJoin().getMulticastConfig().setEnabled(false);
        config.getNetworkConfig().getJoin().getTcpIpConfig().setEnabled(false);
        return config;
    }

    /** A solo member: it seals under keys drawn at random for its process. */
    private PrivacyCluster solo() {
        if (cluster != null) {
            documents.forEach(PauseDocument::close);
            documents.clear();
            cluster.close();
        }
        cluster = PrivacyCluster.embedded(config(TestMembers.uniqueName("pause")), false);
        return cluster;
    }

    /** One member of the cluster: a bare document with its own clocks and its own idea of what the cards say. */
    final class Member {
        final PauseDocument doc;
        final Nanos nanos;
        final Fixture.TestClock wall;
        final StubPeers peers;
        final PauseDocument.Dependencies deps;

        Member(Nanos nanos, Fixture.TestClock wall, StubPeers peers) {
            this.nanos = nanos;
            this.wall = wall;
            this.peers = peers;
            this.deps = PauseDocument.Dependencies.of(cluster, metrics, TIMEOUT);
            this.doc = new PauseDocument(cluster, cluster::instance, new PauseDocument.Environment(nanos, wall, null),
                    true, peers);
            doc.useDependencies(deps);
            documents.add(doc);
        }

        void pauseTool(String name) {
            doc.pause(Target.tool(name), deps);
        }

        void resumeTool(String name) {
            doc.resume(Target.tool(name), deps);
        }

        void pauseScope(String name) {
            doc.pause(Target.scope(name), deps);
        }

        void resumeScope(String name) {
            doc.resume(Target.scope(name), deps);
        }

        void pauseAll() {
            doc.pause(Target.ALL, deps);
        }

        void resumeAll() {
            doc.resume(Target.ALL, deps);
        }

        void tick() {
            doc.tick(deps);
        }

        Set<String> tools() {
            return doc.current(deps).tools();
        }

        boolean allPaused() {
            return doc.current(deps).all();
        }

        boolean fresh() {
            return doc.fresh();
        }

        Doc held() {
            return doc.accepted();
        }

        Digest digest() {
            return doc.heldDigest();
        }

        /** The member reads: judges what is stored and answers whether it is fresh. */
        boolean reads() {
            try {
                doc.current(deps);
                return true;
            } catch (IllegalStateException refused) {
                return false;
            }
        }
    }

    private Member member(StubPeers peers) {
        return new Member(new Nanos(), new Fixture.TestClock(), peers);
    }

    private Member member() {
        return member(StubPeers.lone());
    }

    private IMap<String, Object> map(PrivacyCluster on) {
        return on.instance().getMap(PrivacyCluster.OVERSIGHT_MAP);
    }

    private String key(PrivacyCluster on) {
        return on.keyedNames().mapKey(PrivacyCluster.OVERSIGHT_MAP, PauseDocument.CANONICAL);
    }

    private Sealer sealer(PrivacyCluster on) {
        return IdentityMaps.sealerFor(on, PrivacyMetrics.none(), ServingGate.open());
    }

    /** The authentic document now in the map of {@code on}. */
    private Doc stored(PrivacyCluster on) {
        Object value = map(on).get(key(on));
        Opened opened = sealer(on).open(PrivacyCluster.OVERSIGHT_MAP, key(on), (String) value);
        assertThat(opened.outcome()).isEqualTo(Opened.Outcome.AUTHENTIC);
        return Doc.decode(opened.plaintext());
    }

    /** Puts a sealed document straight into the map, as a holder of the keys could. */
    static void plant(PrivacyCluster on, Doc doc) {
        IMap<String, Object> map = on.instance().getMap(PrivacyCluster.OVERSIGHT_MAP);
        String key = on.keyedNames().mapKey(PrivacyCluster.OVERSIGHT_MAP, PauseDocument.CANONICAL);
        map.put(key, IdentityMaps.sealerFor(on, PrivacyMetrics.none(), ServingGate.open())
                .seal(PrivacyCluster.OVERSIGHT_MAP, key, doc.encode()));
    }

    static Id id(long n) {
        return new Id(0, n);
    }

    /** A document dated at the default test wall clock. */
    static Doc doc(long sequence, long refresh, Map<Id, Target> elements, Set<Id> tombstones) {
        return new Doc(sequence, refresh, new Fixture.TestClock().millis(), "test-issuer", elements, tombstones);
    }

    static Doc docOfTools(long sequence, String prefix, int count) {
        Map<Id, Target> elements = new HashMap<>();
        for (int i = 0; i < count; i++) {
            elements.put(new Id(prefix.hashCode(), i), Target.tool(prefix + i));
        }
        return doc(sequence, 0, elements, Set.of());
    }

    private static Map<Id, Target> mapPlus(Map<Id, Target> elements, Id id, Target target) {
        Map<Id, Target> more = new HashMap<>(elements);
        more.put(id, target);
        return more;
    }

    /** A logger that records the formatted WARN lines. */
    private static org.slf4j.Logger recording(List<String> warnings) {
        return (org.slf4j.Logger) java.lang.reflect.Proxy.newProxyInstance(
                PauseDocumentTest.class.getClassLoader(), new Class<?>[]{org.slf4j.Logger.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("warn") && args != null && args[0] instanceof String format) {
                        String line = format;
                        for (int i = 1; args.length > 1 && i < args.length; i++) {
                            Object[] more = args[i] instanceof Object[] array ? array : new Object[]{args[i]};
                            for (Object value : more) {
                                line = line.replaceFirst("\\{}", java.util.regex.Matcher.quoteReplacement(
                                        String.valueOf(value)));
                            }
                        }
                        warnings.add(line);
                    }
                    return method.getReturnType() == boolean.class ? Boolean.FALSE : null;
                });
    }

    // ---- external review round 1: reads, logs --------------------------------------------------------------

    @Test
    void aReadThatBeganBeforeAnAcceptedWriteCountsNoConflictForTheWriteItStraddles() {
        solo();
        Member one = member();
        one.pauseTool("a");
        Object beforeTheWrite = map(cluster).get(key(cluster));
        Doc heldBeforeTheWrite = one.held();
        one.pauseTool("b");
        assertThat(one.held().elements()).hasSize(2);

        one.doc.judgeStraddlingReadForTest(beforeTheWrite, heldBeforeTheWrite, one.deps);
        assertThat(metrics.count(Metric.CLUSTER_PAUSE_CONFLICT)).as("the write began after the read").isZero();

        // The same stored value judged against a document that already held more is a real replay.
        one.doc.judgeStraddlingReadForTest(beforeTheWrite, one.held(), one.deps);
        assertThat(metrics.count(Metric.CLUSTER_PAUSE_CONFLICT)).isEqualTo(1);
    }

    @Test
    void aFailedMaintenanceStepIsLoggedOnceUnderOneStableCodeWithTheCauseClassAndNoMessage() {
        solo();
        StubPeers peers = StubPeers.lone();
        Member one = member(peers);
        List<String> warnings = new ArrayList<>();
        one.doc.log = recording(warnings);
        peers.failing = true;
        one.tick();
        one.tick();
        assertThat(warnings).hasSize(1);
        assertThat(warnings.get(0)).startsWith("PAUSE_DOCUMENT_MAINTENANCE_FAILED").contains("IllegalStateException")
                .doesNotContain("the cards cannot be read");
        one.nanos.advance(LEASE.plusSeconds(1));
        one.tick();
        assertThat(warnings).as("again after a lease").hasSize(2);
    }

    @Test
    void lapsingAndRegainingFreshnessEachLogOneWarning() {
        solo();
        StubPeers peers = StubPeers.withPeer();
        Member one = member(peers);
        one.pauseTool("secret-tool");
        peers.shows(one.digest());
        assertThat(one.reads()).isTrue();
        List<String> warnings = new ArrayList<>();
        one.doc.log = recording(warnings);
        peers.shows(Digest.NONE);
        assertThat(one.reads()).as("a peer outside Seen holds the gate shut").isTrue();
        one.nanos.advance(LEASE.plusSeconds(1));
        assertThat(one.reads()).isFalse();
        assertThat(one.reads()).isFalse();
        assertThat(warnings).filteredOn(line -> line.startsWith("PAUSE_DOCUMENT_LAPSED")).hasSize(1);
        peers.shows(one.digest());
        assertThat(one.reads()).isTrue();
        assertThat(warnings).filteredOn(line -> line.startsWith("PAUSE_DOCUMENT_FRESH")).hasSize(1);
        assertThat(warnings).allSatisfy(line -> assertThat(line).doesNotContain("secret-tool"));
    }

    @Test
    void aRejectedValueThatStaysInTheMapIsReopenedOnEveryReadButCountedOncePerValue() {
        solo();
        Member one = member();
        one.pauseTool("a");
        Object good = map(cluster).get(key(cluster));
        map(cluster).put(key(cluster), 5);
        for (int i = 0; i < 3; i++) {
            assertThat(one.reads()).as("a non-string value is unavailable on every read").isFalse();
        }
        assertThat(metrics.count(Metric.CLUSTER_SEAL_TAMPER)).isEqualTo(1);
        map(cluster).put(key(cluster), 6);
        assertThat(one.reads()).isFalse();
        assertThat(metrics.count(Metric.CLUSTER_SEAL_TAMPER)).as("another value counts again").isEqualTo(2);
        map(cluster).put(key(cluster), good);
        assertThat(one.reads()).as("the authentic document reads again, nothing is remembered as bad").isTrue();
        map(cluster).put(key(cluster), 6);
        assertThat(one.reads()).isFalse();
        assertThat(metrics.count(Metric.CLUSTER_SEAL_TAMPER)).as("after an authentic read it counts again")
                .isEqualTo(3);
    }

    @Test
    void aNotServingMemberRefusesAResumeThroughClusterRefusalsAndItIsLoggedOnce() throws Exception {
        solo();
        Member one = member();
        one.pauseTool("a");
        PauseDocument.Dependencies notServing = new PauseDocument.Dependencies(one.deps.metrics(),
                one.deps.lockTimeout(), () -> ClusterSecurityStatus.notServing(
                        ClusterSecurityStatus.Reason.UNVERIFIED_MEMBER, 2, 1), one.deps.sealer());
        var recorder = new RecordingLogger();
        try (var ignored = ClusterRefusals.captureTo(recorder.logger())) {
            assertThatThrownBy(() -> one.doc.resume(Target.tool("a"), notServing))
                    .isInstanceOf(ClusterOperationRefusedException.class);
        }
        assertThat(recorder.lines()).hasSize(1);
        assertThat(recorder.lines().get(0).message()).contains("CLUSTER_OPERATION_REFUSED", "IllegalStateException");
        assertThat(one.tools()).as("nothing was written").containsExactly("a");
    }

    /** An instance whose lifecycle listeners are counted, and whose adding of one can run a hook first. */
    private static final class CountingInstance {
        final AtomicInteger added = new AtomicInteger();
        final AtomicInteger removed = new AtomicInteger();
        volatile Runnable whileAdding = () -> { };
        volatile boolean failToAdd;
        final com.hazelcast.core.HazelcastInstance instance;

        CountingInstance(com.hazelcast.core.HazelcastInstance real) {
            com.hazelcast.core.LifecycleService lifecycle = (com.hazelcast.core.LifecycleService)
                    java.lang.reflect.Proxy.newProxyInstance(PauseDocumentTest.class.getClassLoader(),
                            new Class<?>[] {com.hazelcast.core.LifecycleService.class}, (proxy, method, args) -> {
                                switch (method.getName()) {
                                    case "addLifecycleListener" -> {
                                        added.incrementAndGet();
                                        whileAdding.run();
                                        if (failToAdd) {
                                            throw new IllegalStateException("cannot add a listener");
                                        }
                                        return UUID.randomUUID();
                                    }
                                    case "removeLifecycleListener" -> {
                                        removed.incrementAndGet();
                                        return true;
                                    }
                                    default -> {
                                        return invoke(method, real.getLifecycleService(), args);
                                    }
                                }
                            });
            this.instance = (com.hazelcast.core.HazelcastInstance) java.lang.reflect.Proxy.newProxyInstance(
                    PauseDocumentTest.class.getClassLoader(), new Class<?>[] {com.hazelcast.core.HazelcastInstance.class},
                    (proxy, method, args) -> method.getName().equals("getLifecycleService") ? lifecycle
                            : invoke(method, real, args));
        }

        private static Object invoke(java.lang.reflect.Method method, Object target, Object[] args) throws Throwable {
            try {
                return method.invoke(target, args);
            } catch (java.lang.reflect.InvocationTargetException failed) {
                throw failed.getCause();
            }
        }
    }

    @Test
    void aReleaseThatLandsWhileTheLifecycleListenerIsBeingAddedLeavesNoListenerBehind() {
        solo();
        CountingInstance counting = new CountingInstance(cluster.instance());
        PauseDocument.Dependencies deps = PauseDocument.Dependencies.of(cluster, metrics, TIMEOUT);
        PauseDocument doc = new PauseDocument(cluster, () -> counting.instance,
                new PauseDocument.Environment(new Nanos(), new Fixture.TestClock(), null), true, StubPeers.lone());
        documents.add(doc);
        counting.whileAdding = () -> doc.release(deps);

        doc.retain(deps);

        assertThat(counting.added.get()).isEqualTo(1);
        assertThat(counting.removed.get()).as("the listener is removed, not kept for a ticker that is gone")
                .isEqualTo(1);
    }

    @Test
    void aRetainThatLandsBetweenAReleasesLastUserDecisionAndItsStopKeepsItsOwnMaintenance() {
        solo();
        CountingInstance counting = new CountingInstance(cluster.instance());
        PauseDocument.Dependencies first = PauseDocument.Dependencies.of(cluster, metrics, TIMEOUT);
        PauseDocument.Dependencies second = PauseDocument.Dependencies.of(cluster, metrics, TIMEOUT);
        PauseDocument doc = new PauseDocument(cluster, () -> counting.instance,
                new PauseDocument.Environment(new Nanos(), new Fixture.TestClock(), Duration.ofMillis(100)), true,
                StubPeers.lone());
        documents.add(doc);
        doc.retain(first);
        doc.pause(Target.tool("t"), first);
        assertThat(counting.added.get()).isEqualTo(1);
        // A second state opens exactly in the gap: after the release decided it was the last user, before it stops.
        doc.betweenReleaseAndStop = () -> {
            doc.betweenReleaseAndStop = () -> { };
            doc.retain(second);
        };

        doc.release(first);

        assertThat(counting.added.get()).as("the new state registered its own listener").isEqualTo(2);
        assertThat(counting.removed.get()).as("only the released user's listener was removed").isEqualTo(1);
        map(cluster).delete(key(cluster));
        assertThat(Fixture.await(20, () -> map(cluster).get(key(cluster)) != null))
                .as("the new state still ticks: the deleted document is repaired").isTrue();
        doc.release(second);
        assertThat(counting.removed.get()).isEqualTo(2);
    }

    @Test
    void aStartThatFailsUndoesTheUserAndALaterRetainStartsAgain() {
        solo();
        CountingInstance counting = new CountingInstance(cluster.instance());
        PauseDocument.Dependencies deps = PauseDocument.Dependencies.of(cluster, metrics, TIMEOUT);
        PauseDocument doc = new PauseDocument(cluster, () -> counting.instance,
                new PauseDocument.Environment(new Nanos(), new Fixture.TestClock(), null), true, StubPeers.lone());
        documents.add(doc);
        counting.failToAdd = true;

        assertThatThrownBy(() -> doc.retain(deps)).isInstanceOf(IllegalStateException.class);
        counting.failToAdd = false;
        doc.retain(deps);

        assertThat(counting.added.get()).as("the second retain starts again").isEqualTo(2);
        doc.release(deps);
        assertThat(counting.removed.get()).as("its release removes the one listener that was kept").isEqualTo(1);
    }

    /** Holds the oversight lock of the document from another thread until the returned runnable is called. */
    private Runnable holdDocumentLock() throws Exception {
        java.util.concurrent.ExecutorService holder = java.util.concurrent.Executors.newSingleThreadExecutor();
        String lockKey = key(cluster);
        holder.submit(() -> map(cluster).lock(lockKey)).get();
        return () -> {
            try {
                holder.submit(() -> map(cluster).unlock(lockKey)).get();
            } catch (Exception failed) {
                throw new IllegalStateException(failed);
            } finally {
                holder.shutdownNow();
            }
        };
    }

    @Test
    void failedPausesForOneTargetKeepOneLocalElementAMintedOneForAnotherTargetAndNothingAtTheCap() throws Exception {
        solo();
        Member one = member();
        one.pauseTool("seed");
        Runnable release = holdDocumentLock();
        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> one.pauseTool("x")).isInstanceOf(ClusterLockTimeoutException.class);
        }
        assertThatThrownBy(() -> one.pauseTool("y")).isInstanceOf(ClusterLockTimeoutException.class);
        assertThat(one.held().live(Target.tool("x"))).as("one unlanded element for x").hasSize(1);
        assertThat(one.held().live(Target.tool("y"))).hasSize(1);
        release.run();

        // Landed by the member's next successful pause, which always mints a fresh element.
        one.pauseTool("x");
        assertThat(one.held().live(Target.tool("x"))).hasSize(2);
        Runnable again = holdDocumentLock();
        assertThatThrownBy(() -> one.pauseTool("x")).isInstanceOf(ClusterLockTimeoutException.class);
        assertThat(one.held().live(Target.tool("x"))).as("the landed ones are not reused").hasSize(3);
        again.run();

        // A pause refused at the cap keeps nothing, and a failed one at the cap adds nothing.
        solo();
        plant(cluster, docOfTools(1000, "t", PauseDocument.PAUSE_CAP));
        Member full = member();
        assertThat(full.tools()).hasSize(PauseDocument.PAUSE_CAP);
        assertThatThrownBy(() -> full.pauseTool("over")).isInstanceOf(ClusterOperationRefusedException.class);
        assertThat(full.held().elements()).hasSize(PauseDocument.PAUSE_CAP);
        Runnable third = holdDocumentLock();
        assertThatThrownBy(() -> full.pauseTool("over")).isInstanceOf(ClusterLockTimeoutException.class);
        assertThat(full.held().elements()).as("a local add at the cap is refused").hasSize(PauseDocument.PAUSE_CAP);
        third.run();
    }

    /** An operator pause whose write did not land: exactly OVERSIGHT_UNAVAILABLE, with the message that says so. */
    private static void assertDidNotLand(ThrowingCallable call) {
        Throwable thrown = catchThrowable(call);
        assertThat(thrown).isNotNull();
        assertThat(thrown.getClass()).isEqualTo(IllegalStateException.class);
        assertThat(thrown).hasMessageContaining("did not land");
    }

    @Test
    void aPauseThatLosesTheRaceInEveryAttemptEndsInUnavailableSaysItDidNotLandAndIsHeldLocally() {
        solo();
        Member one = member();
        Member rival = member();
        one.pauseTool("seed");
        AtomicInteger hooks = new AtomicInteger();
        one.doc.beforeCommit = () -> rival.pauseTool("rival" + hooks.incrementAndGet());

        assertDidNotLand(() -> one.pauseTool("x"));

        assertThat(hooks.get()).as("a rival write in each of the attempts").isEqualTo(PauseDocument.MAX_ATTEMPTS);
        assertThat(one.held().live(Target.tool("x"))).as("held locally").hasSize(1);
    }

    @Test
    void aHazelcastFailureDuringAPauseIsWrappedAsUnavailableAndTheLockTimeoutKeepsItsOwnType() throws Exception {
        solo();
        StubPeers peers = StubPeers.lone();
        Member one = member(peers);
        one.pauseTool("seed");
        peers.failing = true;
        assertDidNotLand(() -> one.pauseTool("x"));
        assertThat(one.held().live(Target.tool("x"))).hasSize(1);
        peers.failing = false;

        Runnable release = holdDocumentLock();
        assertThatThrownBy(() -> one.pauseTool("y")).isInstanceOf(ClusterLockTimeoutException.class);
        release.run();
        assertThat(one.held().live(Target.tool("y"))).hasSize(1);
    }

    @Test
    void aColdMemberKeepsNoGatedReplayJustBecauseAFailedPauseSetHeld() throws Exception {
        solo();
        Doc replay = new Doc(5, 0, new Fixture.TestClock().millis() - LEASE.plus(SKEW).plusSeconds(1).toMillis(),
                "test-issuer", Map.of(id(1), Target.tool("p")), Set.of());
        plant(cluster, replay);
        Member cold = member();
        assertThat(cold.reads()).as("too old for a cold start").isFalse();

        Runnable release = holdDocumentLock();
        assertThatThrownBy(() -> cold.pauseTool("x")).isInstanceOf(ClusterLockTimeoutException.class);
        release.run();

        assertThat(cold.held().pauses().tools()).as("the local pause").containsExactly("x");
        assertThat(cold.reads()).as("the next read still refuses: nothing was accepted").isFalse();
        assertThat(cold.held().pauses().tools()).as("the replay is not merged").containsExactly("x");

        // R9: an operator pause over GATED writes held (the local x), the gated content and the change.
        cold.pauseTool("y");
        assertThat(stored(cluster).pauses().tools()).containsExactlyInAnyOrder("p", "x", "y");
    }

    @Test
    void aLocalPauseKeepsHeldsOrderOrStartsDoc0AndTheRepairWritesItAtSequence1() throws Exception {
        solo();
        Member warm = member();
        warm.pauseTool("seed");
        Doc before = warm.held();
        Runnable release = holdDocumentLock();
        assertThatThrownBy(() -> warm.pauseTool("x")).isInstanceOf(ClusterLockTimeoutException.class);
        release.run();
        Doc after = warm.held();
        assertThat(after.sequence()).isEqualTo(before.sequence());
        assertThat(after.refresh()).isEqualTo(before.refresh());
        assertThat(after.issuedAtMillis()).isEqualTo(before.issuedAtMillis());
        assertThat(after.issuer()).isEqualTo(before.issuer());

        solo();
        Member cold = member();
        Runnable again = holdDocumentLock();
        assertThatThrownBy(() -> cold.pauseTool("x")).isInstanceOf(ClusterLockTimeoutException.class);
        again.run();
        assertThat(cold.held().sequence()).as("Doc(0, 0, now, self)").isZero();
        cold.tick();
        assertThat(stored(cluster).sequence()).as("putIfAbsent(held) at sequence 1").isEqualTo(1);
        assertThat(stored(cluster).pauses().tools()).containsExactly("x");
    }

    @Test
    void theIndexDocumentLogsNoLapseOrRegainWarning() {
        solo();
        StubPeers peers = StubPeers.withPeer();
        Member main = member(peers);
        main.pauseTool("secret-tool");
        peers.shows(main.digest());
        Nanos nanos = new Nanos();
        PauseDocument index = new PauseDocument(cluster, cluster::instance,
                new PauseDocument.Environment(nanos, new Fixture.TestClock(), null), false, peers);
        documents.add(index);
        List<String> warnings = new ArrayList<>();
        index.log = recording(warnings);
        assertThatCode(() -> index.current(main.deps)).doesNotThrowAnyException();
        peers.shows(Digest.NONE);
        nanos.advance(LEASE.plusSeconds(1));
        assertThatThrownBy(() -> index.current(main.deps)).isInstanceOf(IllegalStateException.class);
        peers.shows(main.digest());
        assertThatCode(() -> index.current(main.deps)).doesNotThrowAnyException();

        assertThat(warnings).filteredOn(line -> line.startsWith("PAUSE_DOCUMENT_")).isEmpty();
    }

    // ---- A1: deletion, replay, restart ---------------------------------------------------------------------

    @Test
    void deletingTheDocumentMakesAMemberRefuseAndPauseFreshReadZeroOnceItsLeasePassesWithoutAPassingGate() {
        solo();
        Member one = member();
        Member two = member(StubPeers.withPeer());
        one.pauseAll();
        assertThat(two.allPaused()).isTrue();
        assertThat(two.fresh()).isTrue();

        // A peer shows content two has never held, so no gate can pass; then the document is deleted.
        two.peers.shows(new Digest(new byte[16]));
        map(cluster).delete(key(cluster));

        // The lease has not run out, but a stored document that is absent makes every read refuse (R6).
        assertThat(two.reads()).isFalse();
        two.nanos.advance(LEASE.minusNanos(1));
        assertThat(two.fresh()).isTrue();
        two.nanos.advance(Duration.ofNanos(1));
        assertThat(two.fresh()).isFalse();
        assertThat(two.reads()).isFalse();
        assertUnavailable(two::allPaused);
    }

    @Test
    void aDocumentOlderThanWhatAMemberHoldsNeverResetsItsLeaseOrRemovesContent() {
        solo();
        StubPeers peers = StubPeers.withPeer();
        Member seen = member(peers);
        seen.pauseTool("alpha");
        String older = (String) map(cluster).get(key(cluster));
        seen.pauseTool("beta");
        long[] before = seen.doc.acceptedOrder();
        assertThat(seen.fresh()).isTrue();

        // A peer shows a digest the member never held: its gate fails from now on.
        peers.shows(new Digest(new byte[16]));
        seen.nanos.advance(LEASE);
        map(cluster).put(key(cluster), older);

        assertThat(seen.reads()).isFalse();
        assertThat(seen.fresh()).isFalse();
        assertThat(seen.doc.acceptedOrder()).containsExactly(before);
        assertThat(seen.held().live(Target.tool("beta"))).hasSize(1);
        assertThat(seen.held().live(Target.tool("alpha"))).hasSize(1);
    }

    @Test
    void aTickRepairsAReplayedOlderDocumentWithAUnionSoNoPauseOfEitherIsDropped_andALiftedPauseStaysLifted() {
        solo();
        Member seen = member();
        seen.pauseTool("alpha");
        String older = (String) map(cluster).get(key(cluster));
        Doc replay = stored(cluster);
        seen.resumeTool("alpha");
        seen.pauseTool("beta");
        map(cluster).put(key(cluster), older);

        seen.tick();

        assertThat(seen.tools()).containsExactly("beta");
        assertThat(seen.fresh()).isTrue();
        Doc repaired = stored(cluster);
        assertThat(repaired.covers(replay)).isTrue();
        assertThat(seen.held().covers(repaired)).isTrue();
        assertThat(repaired.sequence()).isGreaterThan(replay.sequence());
        assertThat(repaired.live(Target.tool("alpha"))).isEmpty();
    }

    @Test
    void aRestartedMemberMergesAReplayedOlderDocumentOnlyInsideTheColdStartGate_andAloneItIsFresh_theNamedResidual() {
        solo();
        Member first = member();
        first.pauseTool("seed");
        String beforeThePause = (String) map(cluster).get(key(cluster));
        first.pauseAll();
        map(cluster).put(key(cluster), beforeThePause);

        // Inside the gate, alone: the replay is accepted and the member is fresh on it. The residual window equals
        // the cold-start gate: lease plus skew.
        Fixture.TestClock insideGate = new Fixture.TestClock();
        insideGate.advance(LEASE.plus(SKEW));
        Member restarted = new Member(new Nanos(), insideGate, StubPeers.lone());
        assertThat(restarted.allPaused()).as("the residual: a replayed pre-pause document lifts the pause").isFalse();
        assertThat(restarted.fresh()).isTrue();

        // With a tracked peer that holds newer content, the restarted member is not fresh.
        StubPeers withPeer = StubPeers.withPeer().shows(first.digest());
        Member covered = new Member(new Nanos(), insideGate, withPeer);
        assertThat(covered.reads()).isFalse();
        assertThat(covered.fresh()).isFalse();

        // One millisecond past the cold-start gate the replayed document is not accepted.
        Fixture.TestClock pastGate = new Fixture.TestClock();
        pastGate.advance(LEASE.plus(SKEW).plusMillis(1));
        Member tooLate = new Member(new Nanos(), pastGate, StubPeers.lone());
        assertThat(tooLate.reads()).isFalse();
        assertThat(tooLate.doc.acceptedSequence()).isZero();
        assertThat(tooLate.doc.hasAccepted()).isFalse();
    }

    @Test
    void aWriterThatLosesTheRaceReReadsAndBothPausesSurviveAtAHigherOrder() {
        solo();
        Member one = member();
        Member two = member();
        one.pauseTool("seed");
        AtomicInteger hooks = new AtomicInteger();
        one.doc.beforeCommit = () -> {
            if (hooks.incrementAndGet() == 1) {
                two.pauseTool("rival");
            }
        };

        one.pauseTool("mine");

        assertThat(hooks.get()).isEqualTo(2);
        Doc finalDoc = stored(cluster);
        assertThat(finalDoc.pauses().tools()).containsExactlyInAnyOrder("seed", "mine", "rival");
        assertThat(one.tools()).containsExactlyInAnyOrder("seed", "mine", "rival");
    }

    @Test
    void aWallClockSkewedByMoreThanTheToleranceDoesNotMakeAHealthyDocumentStaleAfterTheFirstAcceptance() {
        solo();
        Member writer = member();
        writer.pauseTool("search");
        Fixture.TestClock reader = new Fixture.TestClock();
        Member skewed = new Member(new Nanos(), reader, StubPeers.lone());
        assertThat(skewed.tools()).containsExactly("search");

        reader.advance(SKEW.multipliedBy(4));
        skewed.nanos.advance(LEASE.dividedBy(3));
        skewed.tick();
        reader.advance(Duration.ofHours(5));
        assertThat(skewed.tools()).containsExactly("search");
        assertThat(skewed.fresh()).isTrue();
    }

    @Test
    void anAttackerDocumentNeverLiftsAPauseIsNeverOverwrittenByATickIsCountedAndAnOperatorPauseKeepsTheHeldPauses() {
        solo();
        Member member = member();
        member.pauseTool("held");
        Sealer sealer = sealer(cluster);
        ClusterKeys foreign = new ClusterKeys("other-cluster-1", Fixture.filled('x'), null, null, null, null,
                "test-name-1", Fixture.filled('n'));
        List<Object> attackers = List.of("plaintext not an envelope", 42,
                Sealer.builder(foreign, PrivacyMetrics.none()).build()
                        .seal(PrivacyCluster.OVERSIGHT_MAP, key(cluster), "x".getBytes()),
                sealer.seal(PrivacyCluster.OVERSIGHT_MAP, key(cluster), new byte[]{1, 2, 3}));
        for (Object attacker : attackers) {
            map(cluster).put(key(cluster), attacker);
            int before = metrics.count(Metric.CLUSTER_SEAL_TAMPER)
                    + metrics.count(Metric.CLUSTER_SEAL_UNSUPPORTED_FORMAT);

            member.tick();

            assertThat(map(cluster).get(key(cluster))).as("a tick never overwrites it").isEqualTo(attacker);
            assertThat(metrics.count(Metric.CLUSTER_SEAL_TAMPER)
                    + metrics.count(Metric.CLUSTER_SEAL_UNSUPPORTED_FORMAT)).isGreaterThan(before);
            assertThat(member.held().live(Target.tool("held"))).as("the pause is held").hasSize(1);

            member.pauseTool("after");
            assertThat(stored(cluster).pauses().tools()).contains("held", "after");
            member.resumeTool("after");
        }
    }

    @Test
    void aGatedDocumentIsUnionedIntoAnOperatorPauseOverItsExactBytes() {
        solo();
        Doc gated = new Doc(5, 0, new Fixture.TestClock().millis() - LEASE.plus(SKEW).plusSeconds(1).toMillis(),
                "test-issuer", Map.of(id(1), Target.tool("p")), Set.of());
        plant(cluster, gated);
        Object raw = map(cluster).get(key(cluster));
        Member cold = member();

        assertThat(cold.reads()).isFalse();
        cold.pauseTool("x");

        Doc written = stored(cluster);
        assertThat(written.pauses().tools()).containsExactlyInAnyOrder("p", "x");
        assertThat(written.compareOrder(gated)).isGreaterThan(0);
        assertThat(map(cluster).get(key(cluster))).isNotEqualTo(raw);
        assertThat(cold.held().pauses().tools()).containsExactlyInAnyOrder("p", "x");
    }

    @Test
    void aMemberHoldingOnlyQKeepsItAndUnionsAnOlderDocumentOrAnUnreadableOneIntoAnOperatorPause() {
        Doc older = new Doc(5, 0, new Fixture.TestClock().millis() - LEASE.plus(SKEW).plusSeconds(1).toMillis(),
                "test-issuer", Map.of(id(1), Target.tool("p")), Set.of());
        for (String stored : List.of("older document", "unreadable value")) {
            solo();
            Member holder = member();
            holder.pauseTool("q");
            assertThat(holder.held().pauses().tools()).as(stored + ": held is q alone").containsExactly("q");
            if (stored.startsWith("older")) {
                plant(cluster, older);
            } else {
                map(cluster).put(key(cluster), "not an envelope");
            }

            holder.pauseTool("x");

            assertThat(stored(cluster).pauses().tools()).as(stored)
                    .containsExactlyInAnyOrder(stored.startsWith("older") ? new String[] {"p", "q", "x"}
                            : new String[] {"q", "x"});
        }
    }

    @Test
    void aReplayWithAnElementThatIsNowTombstonedLeavesItLiftedOnEveryWarmMember() {
        solo();
        Member a = member();
        Member b = member();
        a.pauseTool("p");
        String withP = (String) map(cluster).get(key(cluster));
        assertThat(b.tools()).containsExactly("p");
        a.resumeTool("p");
        assertThat(b.tools()).isEmpty();

        map(cluster).put(key(cluster), withP);

        assertThat(a.reads() ? a.tools() : Set.of()).isEmpty();
        a.tick();
        b.tick();
        assertThat(a.tools()).isEmpty();
        assertThat(b.tools()).isEmpty();
    }

    @Test
    void aColdMemberGivenAReplayShowsItsStateUntilTheNextRepairTickThenTheUnion() {
        solo();
        Member warm = member();
        warm.pauseTool("p");
        String replay = (String) map(cluster).get(key(cluster));
        warm.resumeTool("p");
        warm.pauseTool("q");
        map(cluster).put(key(cluster), replay);

        Member cold = member();
        assertThat(cold.tools()).containsExactly("p");

        warm.tick();
        cold.tick();
        assertThat(cold.tools()).containsExactly("q");
    }

    // ---- A2: the commit guard and retries ------------------------------------------------------------------

    private record Setup(Member member, Object raw, Doc stored) {
    }

    private Setup pausedOnce() {
        solo();
        Member member = member();
        member.pauseTool("kept");
        return new Setup(member, map(cluster).get(key(cluster)), stored(cluster));
    }

    private Outcome commit(Setup setup, Doc next, Id fresh, Set<Id> tombstones) {
        return setup.member().doc.commit(setup.raw(), setup.stored(), setup.stored(), next, fresh, tombstones, true,
                setup.member().deps);
    }

    private void assertRefusedAndUntouched(Setup setup, Outcome expected, Doc next, Id fresh, Set<Id> tombstones) {
        assertThat(commit(setup, next, fresh, tombstones)).isEqualTo(expected);
        assertThat(map(cluster).get(key(cluster))).as("nothing was written").isEqualTo(setup.raw());
    }

    @Test
    void aWriteThatWouldDropAStoredElementOrTombstoneIsRefusedWithNothingWritten() {
        Setup setup = pausedOnce();
        Doc stored = setup.stored();
        assertRefusedAndUntouched(setup, Outcome.DROPS_STORED,
                doc(stored.sequence() + 1, 0, Map.of(), Set.of()), null, Set.of());

        setup.member().resumeTool("kept");
        Setup resumed = new Setup(setup.member(), map(cluster).get(key(cluster)), stored(cluster));
        assertThat(resumed.stored().tombstones()).hasSize(1);
        assertRefusedAndUntouched(resumed, Outcome.DROPS_STORED,
                doc(resumed.stored().sequence() + 1, 0, resumed.stored().elements(), Set.of()), null, Set.of());
    }

    @Test
    void aWriteThatHoldsAnElementOutsideTheBaseAndTheOneFreshPauseIsRefused() {
        Setup setup = pausedOnce();
        Map<Id, Target> elements = new HashMap<>(setup.stored().elements());
        elements.put(id(99), Target.tool("rogue"));
        Doc next = doc(setup.stored().sequence() + 1, 0, elements, Set.of());
        assertRefusedAndUntouched(setup, Outcome.ADDS_ELEMENT, next, null, Set.of());
        assertRefusedAndUntouched(setup, Outcome.ADDS_ELEMENT, next, id(100), Set.of());
        assertThat(commit(setup, next, id(99), Set.of())).isEqualTo(Outcome.WRITTEN);
    }

    @Test
    void aWriteThatAddsATombstoneTheChangeMayNotAddIsRefused_aPauseAddsNoneAndAResumeOnlyItsOwn() {
        Setup setup = pausedOnce();
        Id live = setup.stored().elements().keySet().iterator().next();
        Doc tombstoned = doc(setup.stored().sequence() + 1, 0, setup.stored().elements(), Set.of(live));
        assertRefusedAndUntouched(setup, Outcome.ADDS_TOMBSTONE, tombstoned, null, Set.of());
        assertRefusedAndUntouched(setup, Outcome.ADDS_TOMBSTONE, tombstoned, null, Set.of(id(7)));
        Doc unknown = doc(setup.stored().sequence() + 1, 0, setup.stored().elements(), Set.of(id(12345)));
        assertRefusedAndUntouched(setup, Outcome.ADDS_TOMBSTONE, unknown, null, Set.of(live));
        assertThat(commit(setup, tombstoned, null, Set.of(live))).isEqualTo(Outcome.WRITTEN);
    }

    @Test
    void aWriteThatIsNotAboveTheStoredOrderAndNotItsRefreshIsRefused() {
        Setup setup = pausedOnce();
        Doc stored = setup.stored();
        assertRefusedAndUntouched(setup, Outcome.ORDER,
                doc(stored.sequence(), stored.refresh(), mapPlus(stored.elements(), id(5), Target.scope("s")),
                        Set.of()), id(5), Set.of());
        assertRefusedAndUntouched(setup, Outcome.ORDER,
                doc(stored.sequence() - 1, 0, stored.elements(), Set.of()), null, Set.of());
        assertRefusedAndUntouched(setup, Outcome.ORDER,
                doc(stored.sequence(), stored.refresh(), stored.elements(), Set.of()), null, Set.of());
        assertThat(commit(setup, doc(stored.sequence(), stored.refresh() + 1, stored.elements(), Set.of()), null,
                Set.of())).isEqualTo(Outcome.WRITTEN);
    }

    @Test
    void aWriteOverAnEntryAnotherWriterChangedLosesItsRaceAndWritesNothing() {
        Setup setup = pausedOnce();
        member().pauseTool("rival");
        Doc next = doc(setup.stored().sequence() + 1, 0, setup.stored().elements(), Set.of());

        assertThat(commit(setup, next, null, Set.of())).isEqualTo(Outcome.LOST_RACE);
        assertThat(stored(cluster).elements()).hasSize(2);
    }

    @Test
    void aRivalThatWritesAHigherDocumentWithAnExtraPauseBetweenReadAndCommitLeavesBothAtAHigherOrder() {
        solo();
        Member writer = member();
        Member rival = member();
        writer.pauseTool("seed");
        AtomicInteger hooks = new AtomicInteger();
        writer.doc.beforeCommit = () -> {
            if (hooks.incrementAndGet() == 1) {
                rival.pauseTool("rival-1");
                rival.pauseTool("rival-2");
            }
        };
        long before = writer.doc.acceptedSequence();

        writer.pauseTool("writer");

        assertThat(writer.tools()).containsExactlyInAnyOrder("seed", "rival-1", "rival-2", "writer");
        assertThat(stored(cluster).sequence()).isGreaterThan(before + 2);
    }

    @Test
    void pushHeldNeverWritesOverAStoredDocumentThatIsASupersetOfWhatIsHeld() {
        solo();
        Member member = member();
        member.pauseTool("a");
        Doc held = member.held();
        Doc superset = doc(held.sequence() + 3, 0, mapPlus(held.elements(), id(50), Target.tool("extra")),
                held.tombstones());
        plant(cluster, superset);
        Object raw = map(cluster).get(key(cluster));

        member.tick();

        assertThat(map(cluster).get(key(cluster))).as("the superset is left alone").isEqualTo(raw);
        assertThat(member.tools()).containsExactlyInAnyOrder("a", "extra");
    }

    @Test
    void aPauseOfXLandingBetweenAResumesReadAndItsCommitStaysInForceAndNamesTheOriginalObservedSetOnly() {
        solo();
        Member state = member();
        Member rival = member();
        state.pauseTool("X");
        Set<Id> observed = new HashSet<>(state.held().live(Target.tool("X")));
        AtomicInteger hooks = new AtomicInteger();
        state.doc.beforeCommit = () -> {
            if (hooks.incrementAndGet() == 1) {
                rival.pauseTool("X");
            }
        };

        state.resumeTool("X");

        assertThat(hooks.get()).as("the resume lost its race and retried").isEqualTo(2);
        assertThat(state.tools()).as("the pause that landed mid-resume stays in force").containsExactly("X");
        assertThat(state.held().tombstones()).isEqualTo(observed);

        state.doc.beforeCommit = () -> { };
        state.resumeTool("X");
        assertThat(state.tools()).isEmpty();
    }

    @Test
    void aResumeWhoseObservedIdsWereAllTombstonedElsewhereBeforeItsRetryWritesNothingAndSucceeds() {
        solo();
        Member state = member();
        Member rival = member();
        state.pauseTool("X");
        AtomicInteger hooks = new AtomicInteger();
        AtomicLong sequenceAfterRival = new AtomicLong();
        state.doc.beforeCommit = () -> {
            if (hooks.incrementAndGet() == 1) {
                rival.resumeTool("X");
                sequenceAfterRival.set(rival.doc.acceptedSequence());
            }
        };

        state.resumeTool("X");

        assertThat(state.tools()).isEmpty();
        assertThat(state.doc.acceptedSequence()).as("no write of its own").isEqualTo(sequenceAfterRival.get());
        assertThat(hooks.get()).isEqualTo(1);
    }

    @Test
    void aRetryThatFindsTheDocumentDeletedOrUnreadableIsRefusedAsUnavailableAndWritesNothing() {
        solo();
        Member state = member();
        state.pauseTool("X");
        AtomicInteger hooks = new AtomicInteger();
        // The first read finds the document; beforeCommit deletes it, so the CAS fails; the retry reads ABSENT.
        state.doc.beforeCommit = () -> {
            if (hooks.incrementAndGet() == 1) {
                map(cluster).delete(key(cluster));
            }
        };
        assertUnavailable(() -> state.resumeTool("X"));
        assertThat(map(cluster).get(key(cluster))).as("no putIfAbsent").isNull();
        assertThat(state.held().live(Target.tool("X"))).hasSize(1);

        // The same with an UNREADABLE value: not replaced.
        solo();
        Member second = member();
        second.pauseTool("X");
        hooks.set(0);
        second.doc.beforeCommit = () -> {
            if (hooks.incrementAndGet() == 1) {
                map(cluster).put(key(cluster), "not an envelope");
            }
        };
        assertUnavailable(() -> second.resumeTool("X"));
        assertThat(map(cluster).get(key(cluster))).isEqualTo("not an envelope");
    }

    @Test
    void aRetryAfterTheMemberListChangedIsRefusedAsPeerStateUnknownAndWritesNothing() {
        solo();
        StubPeers peers = StubPeers.withPeer();
        Member state = member(peers);
        Member rival = member();
        state.pauseTool("X");
        AtomicInteger hooks = new AtomicInteger();
        state.doc.beforeCommit = () -> {
            if (hooks.incrementAndGet() == 1) {
                rival.pauseTool("rival");
                // A keyless intruder joined and no look has completed since.
                peers.lookCompleted = false;
            }
        };
        Object before = map(cluster).get(key(cluster));

        assertThatThrownBy(() -> state.resumeTool("X")).isInstanceOf(ClusterOperationRefusedException.class);

        assertThat(peers.unverified).isZero();
        assertThat(map(cluster).get(key(cluster))).isNotEqualTo(before);
        assertThat(stored(cluster).live(Target.tool("X"))).hasSize(1);
    }

    @Test
    void aRetryAfterAMemberBecameUnverifiedIsRefusedAndWritesNothing() {
        solo();
        StubPeers peers = StubPeers.withPeer();
        Member state = member(peers);
        Member rival = member();
        state.pauseTool("X");
        AtomicInteger hooks = new AtomicInteger();
        state.doc.beforeCommit = () -> {
            if (hooks.incrementAndGet() == 1) {
                rival.pauseTool("rival");
                peers.unverified = 1;
            }
        };

        assertThatThrownBy(() -> state.resumeTool("X")).isInstanceOf(ClusterOperationRefusedException.class);
        assertThat(stored(cluster).live(Target.tool("X"))).hasSize(1);
    }

    // ---- pauses are never deduped; a resume lifts what it saw -----------------------------------------------

    @Test
    void aPauseAlwaysMintsAFreshElementAndOneResumeLiftsEveryPauseOfTheTarget() {
        solo();
        Member state = member();
        state.pauseTool("search");
        state.pauseTool("search");
        state.pauseTool("search");
        assertThat(state.held().elements()).hasSize(3);

        state.resumeTool("search");

        assertThat(state.tools()).isEmpty();
        assertThat(state.held().tombstones()).hasSize(3);
    }

    @Test
    void aResumeLiftsOnlyThePausesOfItsOwnTarget_notOtherToolsOtherKindsOrTheGlobalPause() {
        solo();
        Member state = member();
        state.pauseTool("search");
        state.pauseTool("lookup");
        state.pauseScope("search");
        state.pauseAll();

        state.resumeTool("search");

        assertThat(state.tools()).containsExactly("lookup");
        assertThat(state.doc.current(state.deps).scopes()).as("a scope of the same name is another target")
                .containsExactly("search");
        assertThat(state.allPaused()).isTrue();
        assertThat(state.held().tombstones()).hasSize(1);

        state.resumeAll();
        assertThat(state.allPaused()).isFalse();
        assertThat(state.held().tombstones()).hasSize(2);
    }

    @Test
    void endScopeTombstonesOnlyTheScopeKindWithThatName() {
        solo();
        Member state = member();
        state.pauseScope("S");
        state.pauseTool("S");
        state.pauseScope("T");

        state.doc.endScope("S", 0L, state.deps);

        assertThat(state.doc.current(state.deps).scopes()).containsExactly("T");
        assertThat(state.tools()).containsExactly("S");
        assertThat(state.held().elements()).as("tombstoned, not deleted").hasSize(3);
    }

    // ---- A3: the cap and the format bound ------------------------------------------------------------------

    @Test
    void eightThousandOneHundredSeventySixPausesFitAndTheNextOneIsRefused_aResumeAtTheCapIsAccepted() {
        solo();
        Member state = member();
        plant(cluster, docOfTools(1000, "t", PauseDocument.PAUSE_CAP - 1));
        assertThat(state.tools()).hasSize(PauseDocument.PAUSE_CAP - 1);

        state.pauseTool("the-8176th");
        Object raw = map(cluster).get(key(cluster));

        assertThatThrownBy(() -> state.pauseTool("the-8177th")).isInstanceOf(ClusterOperationRefusedException.class)
                .satisfies(refusal -> assertThat(((ClusterOperationRefusedException) refusal).code())
                        .isEqualTo("CLUSTER_OPERATION_REFUSED"));
        assertThat(map(cluster).get(key(cluster))).as("refused, never truncated").isEqualTo(raw);
        assertThat(state.held().elements()).hasSize(PauseDocument.PAUSE_CAP);

        state.resumeTool("the-8176th");
        assertThat(state.tools()).doesNotContain("the-8176th");
    }

    @Test
    void allEightThousandOneHundredSeventySixResumedThenPauseAllSucceedsAndTheNextNonAllPauseIsRefused() {
        solo();
        Member state = member();
        Doc full = docOfTools(1000, "t", PauseDocument.PAUSE_CAP);
        plant(cluster, new Doc(full.sequence(), 0, full.issuedAtMillis(), "test", full.elements(),
                new HashSet<>(full.elements().keySet())));
        assertThat(state.tools()).isEmpty();

        // The cap counts elements only: 8,176 elements, all tombstoned, still block a non-all pause.
        assertThatThrownBy(() -> state.pauseTool("over")).isInstanceOf(ClusterOperationRefusedException.class);
        state.pauseAll();
        assertThat(state.allPaused()).isTrue();
    }

    @Test
    void theCapCountsElementsOnly_manyResumedPausesDoNotBlockANonAllPause() {
        solo();
        Member state = member();
        // 4,200 elements, every one tombstoned: elements plus tombstones is above 8,176, elements alone are not.
        Doc doc = docOfTools(1000, "t", 4_200);
        plant(cluster, new Doc(doc.sequence(), 0, doc.issuedAtMillis(), "test", doc.elements(),
                new HashSet<>(doc.elements().keySet())));
        assertThat(state.tools()).isEmpty();

        state.pauseTool("fits");

        assertThat(state.tools()).containsExactly("fits");
    }

    @Test
    void pauseAllCyclesAtTheCapAreNeverRefused_theReserveIsTheWholeGapToTheFormatBound() {
        solo();
        Member state = member();
        plant(cluster, docOfTools(1000, "t", PauseDocument.PAUSE_CAP));
        assertThat(state.tools()).hasSize(PauseDocument.PAUSE_CAP);
        assertThatThrownBy(() -> state.pauseTool("over")).isInstanceOf(ClusterOperationRefusedException.class);

        for (int cycle = 0; cycle < 20; cycle++) {
            state.pauseAll();
            assertThat(state.allPaused()).isTrue();
            state.resumeAll();
            assertThat(state.allPaused()).isFalse();
        }
        state.pauseAll();

        assertThat(state.allPaused()).isTrue();
        assertThat(state.held().elements()).hasSize(PauseDocument.PAUSE_CAP + 21);
        assertThatThrownBy(() -> state.pauseTool("still-over")).isInstanceOf(ClusterOperationRefusedException.class);
    }

    @Test
    void pauseAllAtTheFormatBoundIsRefusedWithCodeAndNothingIsWritten() {
        solo();
        Member state = member();
        plant(cluster, docOfTools(1000, "t", PauseDocument.FORMAT_BOUND));
        assertThat(state.tools()).hasSize(PauseDocument.FORMAT_BOUND);
        Object raw = map(cluster).get(key(cluster));

        assertThatThrownBy(state::pauseAll).isInstanceOf(ClusterOperationRefusedException.class);
        assertThat(map(cluster).get(key(cluster))).isEqualTo(raw);
    }

    @Test
    void anEncodeOverTheFormatBoundIsRefusedNeverTruncated() {
        Doc tooManyElements = docOfTools(1, "t", PauseDocument.FORMAT_BOUND + 1);
        assertThatThrownBy(tooManyElements::encode).isInstanceOf(IllegalStateException.class);
        Set<Id> tombstones = new HashSet<>();
        for (int i = 0; i <= PauseDocument.FORMAT_BOUND; i++) {
            tombstones.add(id(i));
        }
        Doc tooManyTombstones = doc(1, 0, Map.of(), tombstones);
        assertThatThrownBy(tooManyTombstones::encode).isInstanceOf(IllegalStateException.class);

        Setup setup = pausedOnce();
        Doc next = docOfTools(setup.stored().sequence() + 1, "u", PauseDocument.FORMAT_BOUND + 1);
        Map<Id, Target> elements = new HashMap<>(next.elements());
        elements.putAll(setup.stored().elements());
        assertRefusedAndUntouched(setup, Outcome.OVERSIZE,
                doc(setup.stored().sequence() + 1, 0, elements, Set.of()), null, Set.of());
    }

    /** The plaintext of a document with {@code elements} elements and {@code tombstones} tombstones, unchecked. */
    private static byte[] plaintext(int elements, int tombstones) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeByte(PauseDocument.FORMAT);
        out.writeLong(1000);
        out.writeLong(0);
        out.writeLong(new Fixture.TestClock().millis());
        out.writeInt(1);
        out.write('i');
        out.writeInt(elements);
        for (int i = 0; i < elements; i++) {
            out.writeLong(7);
            out.writeLong(i);
            out.writeByte(Kind.TOOL.ordinal());
            out.writeInt(1);
            out.write('t');
        }
        out.writeInt(tombstones);
        for (int i = 0; i < tombstones; i++) {
            out.writeLong(9);
            out.writeLong(i);
        }
        return bytes.toByteArray();
    }

    @Test
    void aDocumentAboveTheFormatBoundDecodesAsUnreadableAndCountedAsTamper_oneAtTheBoundOrWellBelowItOpens() throws Exception {
        solo();
        Member state = member();
        state.pauseTool("seed");
        assertThat(state.reads()).isTrue();
        Sealer sealer = sealer(cluster);
        for (byte[] oversize : List.of(plaintext(PauseDocument.FORMAT_BOUND + 1, 0),
                plaintext(0, PauseDocument.FORMAT_BOUND + 1))) {
            assertThat(Doc.decode(oversize)).isNull();
            int before = metrics.count(Metric.CLUSTER_SEAL_TAMPER);
            map(cluster).put(key(cluster), sealer.seal(PrivacyCluster.OVERSIGHT_MAP, key(cluster), oversize));
            state.reads();
            assertThat(metrics.count(Metric.CLUSTER_SEAL_TAMPER)).isGreaterThan(before);
        }
        assertThat(Doc.decode(plaintext(PauseDocument.FORMAT_BOUND, PauseDocument.FORMAT_BOUND))).isNotNull();
        assertThat(Doc.decode(plaintext(16_000, 0))).isNotNull();
        map(cluster).put(key(cluster), sealer.seal(PrivacyCluster.OVERSIGHT_MAP, key(cluster),
                plaintext(16_000, 0)));
        assertThat(member().tools()).containsExactly("t");
    }

    @Test
    void decodeRejectsADuplicateIdWithADifferentTargetAnUnknownFormatTagAndKeepsATombstoneForAnUnknownId() throws Exception {
        Id same = id(5);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeByte(PauseDocument.FORMAT);
        out.writeLong(1);
        out.writeLong(0);
        out.writeLong(0);
        out.writeInt(0);
        out.writeInt(2);
        for (String tool : List.of("a", "b")) {
            out.writeLong(same.high());
            out.writeLong(same.low());
            out.writeByte(Kind.TOOL.ordinal());
            out.writeInt(1);
            out.write(tool.charAt(0));
        }
        out.writeInt(0);
        assertThat(Doc.decode(bytes.toByteArray())).as("a duplicate id with a different target").isNull();

        byte[] unknown = plaintext(1, 0);
        unknown[0] = (byte) (PauseDocument.FORMAT + 1);
        assertThat(Doc.decode(unknown)).as("an unknown format tag").isNull();
        unknown[0] = 1;
        assertThat(Doc.decode(unknown)).as("the previous format is unknown too").isNull();

        Doc withOrphan = doc(3, 0, Map.of(id(1), Target.tool("a")), Set.of(id(1), id(777)));
        Doc round = Doc.decode(withOrphan.encode());
        assertThat(round).isNotNull();
        assertThat(round.tombstones()).containsExactlyInAnyOrder(id(1), id(777));
        assertThat(round.elements()).isEqualTo(withOrphan.elements());
    }

    // ---- memoisation and pause.conflict ---------------------------------------------------------------------

    @Test
    void identicalSealedBytesAreNotDecodedTwiceAndDifferentBytesAre() {
        solo();
        Member writer = member();
        Member reader = member();
        AtomicInteger decodes = new AtomicInteger();
        reader.doc.decoder = plaintext -> {
            decodes.incrementAndGet();
            return Doc.decode(plaintext);
        };
        writer.pauseTool("alpha");

        for (int i = 0; i < 5; i++) {
            assertThat(reader.tools()).contains("alpha");
        }
        assertThat(decodes.get()).isEqualTo(1);

        writer.pauseTool("beta");
        for (int i = 0; i < 5; i++) {
            assertThat(reader.tools()).contains("beta");
        }
        assertThat(decodes.get()).isEqualTo(2);
    }

    @Test
    void twoDocumentsOfTheSameOrderButDifferentBytesAreNeverMistakenForOne() {
        solo();
        Member reader = member();
        Doc one = doc(10, 0, Map.of(id(1), Target.tool("one")), Set.of());
        Doc two = doc(10, 0, Map.of(id(2), Target.tool("two")), Set.of());
        plant(cluster, one);
        assertThat(reader.tools()).containsExactly("one");

        plant(cluster, two);
        assertThat(reader.tools()).containsExactlyInAnyOrder("one", "two");
        assertThat(reader.held().live(Target.tool("two"))).hasSize(1);
        assertThat(reader.held().live(Target.tool("one"))).hasSize(1);
    }

    @Test
    void conflictIsCountedOnceForARolledBackStoredDocumentAndOnceForALowerOrderDocumentCarryingAnExtraElement() {
        solo();
        Member member = member();
        member.pauseTool("a");
        String rolledBack = (String) map(cluster).get(key(cluster));
        member.pauseTool("b");
        member.pauseTool("c");
        assertThat(metrics.count(Metric.CLUSTER_PAUSE_CONFLICT)).as("ordinary writes are no conflict").isZero();

        map(cluster).put(key(cluster), rolledBack);
        member.reads();
        assertThat(metrics.count(Metric.CLUSTER_PAUSE_CONFLICT)).isEqualTo(1);
        member.reads();
        assertThat(metrics.count(Metric.CLUSTER_PAUSE_CONFLICT)).isEqualTo(1);

        Doc held = member.held();
        Doc lowerWithExtra = doc(1, 0, mapPlus(held.elements(), id(900), Target.tool("extra")), held.tombstones());
        plant(cluster, lowerWithExtra);
        member.reads();
        assertThat(metrics.count(Metric.CLUSTER_PAUSE_CONFLICT)).isEqualTo(2);
        member.reads();
        member.tick();
        assertThat(metrics.count(Metric.CLUSTER_PAUSE_CONFLICT)).as("the same bytes seen on a tick").isEqualTo(2);
        assertThat(member.held().pauses().tools()).containsExactlyInAnyOrder("a", "b", "c", "extra");
    }

    @Test
    void aDocumentThatNamesAHeldIdAsAnotherTargetIsNotAcceptedIsCountedAsTamperAndWarnsOnce() {
        solo();
        Member member = member();
        member.pauseTool("a");
        Id heldId = member.held().elements().keySet().iterator().next();
        plant(cluster, doc(50, 0, Map.of(heldId, Target.tool("other")), Set.of()));
        int before = metrics.count(Metric.CLUSTER_SEAL_TAMPER);

        member.reads();

        assertThat(metrics.count(Metric.CLUSTER_SEAL_TAMPER)).isGreaterThan(before);
        assertThat(member.held().live(Target.tool("a"))).hasSize(1);
        assertThat(member.held().live(Target.tool("other"))).isEmpty();
    }

    // ---- A4: the freshness gate ----------------------------------------------------------------------------

    private static final Digest UNKNOWN = new Digest(new byte[]{9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9});

    /** Makes {@code member} lapse: a peer shows content it never held, and its lease runs out. */
    private void lapse(Member member) {
        member.peers.shows(UNKNOWN);
        member.nanos.advance(LEASE.plusSeconds(1));
        assertThat(member.reads()).isFalse();
        assertThat(member.fresh()).isFalse();
    }

    @Test
    void aReplayedSequenceThenADeleteAndRepeatedTicksNeverMakeAColdMemberFresh_andItsSequenceRisesByOnePerLoop() {
        solo();
        Member stalled = member();
        stalled.pauseTool("seed");
        stalled.pauseTool("p");
        Digest stalledDigest = stalled.digest();
        Doc replay = doc(1, 0, Map.of(id(1), Target.tool("seed")), Set.of());

        StubPeers peers = StubPeers.withPeer().shows(stalledDigest);
        Member c = member(peers);
        map(cluster).delete(key(cluster));
        for (int loop = 0; loop < 6; loop++) {
            plant(cluster, replay);
            c.tick();
            map(cluster).delete(key(cluster));
            long before = c.doc.acceptedSequence();
            c.tick();
            assertThat(c.fresh()).as("loop " + loop).isFalse();
            assertThat(c.doc.acceptedSequence()).isEqualTo(before + 1);
        }
        assertThat(c.doc.acceptedSequence()).as("1 for the first replay, then one per loop").isEqualTo(7);

        // The stalled peer's card expires while it is still listed: it fails Covered (F5), the same result.
        peers.shows(Map.of(PEER, Digest.NONE));
        plant(cluster, replay);
        c.tick();
        assertThat(c.fresh()).isFalse();
    }

    @Test
    void twoLapsedHoldersRecoverAsStatesWhateverWroteTheBytes() {
        solo();
        StubPeers peersA = StubPeers.withPeer();
        StubPeers peersB = StubPeers.withPeer();
        Member b = member(peersB);
        b.pauseTool("q");
        map(cluster).delete(key(cluster));
        Member a = member(peersA);
        a.pauseTool("p");
        Digest digestP = a.digest();
        map(cluster).delete(key(cluster));
        Digest digestQ = b.digest();
        lapse(a);
        lapse(b);

        // 1. A ticks (it restores {p} over ABSENT): A not fresh, B not fresh.
        peersA.shows(digestQ);
        peersB.shows(digestP);
        a.tick();
        assertThat(a.fresh()).isFalse();
        assertThat(b.fresh()).isFalse();
        assertThat(stored(cluster).pauses().tools()).containsExactly("p");

        // 2. B ticks after a look in which A's card shows Digest({p}): its judge merges A's restore, which puts
        // Digest({p}) into Seen(B), so B is fresh, and it repairs to {p, q}. A is not fresh.
        b.tick();
        assertThat(b.fresh()).isTrue();
        assertThat(stored(cluster).pauses().tools()).containsExactlyInAnyOrder("p", "q");
        assertThat(a.fresh()).isFalse();

        // 3. A reads after a look in which B's card still shows Digest({q}): A holds {p, q} and is not fresh.
        assertThat(a.reads()).isFalse();
        assertThat(a.held().pauses().tools()).containsExactlyInAnyOrder("p", "q");

        // 4. A reads after a look in which B's card shows Digest({p, q}): A is fresh.
        peersA.shows(b.digest());
        assertThat(a.reads()).isTrue();

        // 5. B reads after a look in which A's card still shows Digest({p}): B stays fresh.
        assertThat(b.reads()).isTrue();

        // 6. B reads after a look in which A's card shows Digest({p, q}): fresh, both hold {p, q}.
        peersB.shows(a.digest());
        assertThat(b.reads()).isTrue();
        assertThat(a.held().pauses()).isEqualTo(b.held().pauses());
    }

    @Test
    void aMemberThatIsNotCoveredWritesAUnionButIsNotFresh_anOperatorPauseTakesEffectEverywhereAndItsResumeIsRefused() {
        solo();
        StubPeers peers = StubPeers.withPeer();
        Member uncovered = member(peers);
        Member other = member();
        uncovered.pauseTool("seed");
        assertThat(other.tools()).containsExactly("seed");
        lapse(uncovered);

        uncovered.pauseTool("x");

        assertThat(other.tools()).containsExactlyInAnyOrder("seed", "x");
        assertThat(uncovered.fresh()).as("an operator pause on an uncovered member does not renew it").isFalse();
        assertUnavailable(() -> uncovered.resumeTool("x"));

        // A covered member's operator pause does renew it.
        peers.digests = Map.of();
        uncovered.pauseTool("y");
        assertThat(uncovered.fresh()).isTrue();
        uncovered.resumeTool("x");
        assertThat(other.tools()).containsExactlyInAnyOrder("seed", "y");
    }

    @Test
    void splitHalvesAtTheSameSequenceMergeAndTheMemberIsCoveredOnlyOnceItHoldsBothContents() {
        solo();
        Doc a = doc(3, 0, Map.of(id(1), Target.tool("a")), Set.of());
        Doc b = doc(3, 0, Map.of(id(2), Target.tool("b")), Set.of());
        StubPeers peers = StubPeers.withPeer().shows(Map.of(PEER, a.digest(), OTHER, b.digest()));
        Member c = member(peers);
        plant(cluster, b);

        assertThat(c.reads()).as("holds only b").isFalse();
        plant(cluster, a);
        assertThat(c.reads()).as("merged a as well").isTrue();
        assertThat(c.held().pauses().tools()).containsExactlyInAnyOrder("a", "b");
    }

    @Test
    void aLoneMemberRefreshesOnEveryTickAndStaysFreshAcrossManyLeases() {
        solo();
        Member lone = member();
        lone.pauseTool("search");
        for (int i = 0; i < 8; i++) {
            lone.nanos.advance(LEASE.dividedBy(2));
            lone.tick();
            assertThat(lone.fresh()).isTrue();
        }
        assertThat(lone.held().refresh()).isGreaterThan(0);
    }

    @Test
    void aSoloMembersClockPastTheLeaseRefusesReadsAndOneTickRenewsItThroughTheGate() {
        solo();
        Member lone = member();
        lone.pauseTool("search");
        lone.nanos.advance(LEASE);
        assertThat(lone.fresh()).isFalse();
        // A member is only ever renewed by a passing gate: a tick judges, and a lone member passes it.
        lone.tick();
        assertThat(lone.fresh()).isTrue();
    }

    @Test
    void aMemberWhoseGateFailsIsNeverRenewedByIdenticalBytesAHigherRefreshOrAHigherSequenceWrittenByAnyone() {
        solo();
        StubPeers peers = StubPeers.withPeer();
        Member m = member(peers);
        Member writer = member();
        writer.pauseTool("a");
        assertThat(m.reads()).isTrue();
        lapse(m);

        Doc current = stored(cluster);
        plant(cluster, current);
        assertThat(m.reads()).isFalse();
        plant(cluster, new Doc(current.sequence(), current.refresh() + 1, current.issuedAtMillis(), "x",
                current.elements(), current.tombstones()));
        assertThat(m.reads()).isFalse();
        plant(cluster, new Doc(current.sequence() + 5, 0, current.issuedAtMillis(), "x", current.elements(),
                current.tombstones()));
        assertThat(m.reads()).isFalse();
        assertThat(m.fresh()).isFalse();
    }

    @Test
    void noRenewalOverAbsentOrUnreadableStoredValuesOrForAColdMemberWhileAMemberIsUnverified() {
        solo();
        Member writer = member();
        writer.pauseTool("a");

        // A cold member with an unverified member is not fresh, whatever else holds.
        StubPeers peers = StubPeers.withPeer();
        peers.unverified = 1;
        Member cold = member(peers);
        assertThat(cold.reads()).isFalse();
        assertThat(cold.doc.established()).isFalse();
        peers.unverified = 0;
        assertThat(cold.reads()).isTrue();
        assertThat(cold.doc.established()).isTrue();
        // Established, an unverified member no longer blocks its renewal: the lease runs out and the gate passes.
        peers.unverified = 2;
        cold.nanos.advance(LEASE.plusSeconds(1));
        assertThat(cold.fresh()).isFalse();
        assertThat(cold.reads()).isTrue();
        assertThat(cold.fresh()).isTrue();

        // Over an absent or unreadable value nothing renews.
        lapse(cold);
        peers.digests = Map.of();
        map(cluster).delete(key(cluster));
        assertThat(cold.reads()).isFalse();
        map(cluster).put(key(cluster), "garbage");
        assertThat(cold.reads()).isFalse();
    }

    @Test
    void aMemberThatMissedAPauseIsNotFreshWhileAPeersDigestIsOutsideItsSeen_whateverItWrites() {
        solo();
        Member holder = member();
        holder.pauseTool("p");
        assertThat(holder.reads()).isTrue();
        StubPeers peers = StubPeers.withPeer().shows(holder.digest());
        Member missed = member(peers);
        Doc old = doc(1, 0, Map.of(id(77), Target.tool("old")), Set.of());
        plant(cluster, old);
        assertThat(missed.reads()).isFalse();
        missed.pauseTool("mine");
        assertThat(missed.fresh()).isFalse();
        // A member that holds the latest content restores a deleted document as a union and is fresh after it.
        map(cluster).delete(key(cluster));
        assertThat(holder.reads()).as("refused while the document is absent").isFalse();
        holder.tick();
        assertThat(holder.reads()).isTrue();
        assertThat(holder.fresh()).isTrue();
        assertThat(stored(cluster).pauses().tools()).contains("p");
    }

    @Test
    void sinceNoLookHasCompletedAColdMemberWithAListedPeerIsNotFreshUntilALookShowsItCovered() {
        solo();
        Member writer = member();
        writer.pauseTool("a");
        StubPeers peers = StubPeers.withPeer();
        peers.lookCompleted = false;
        Member cold = member(peers);
        assertThat(cold.reads()).as("peerDigests() empty and unverifiedCount() 0, but no look").isFalse();

        peers.lookCompleted = true;
        peers.shows(new Digest(new byte[16]));
        assertThat(cold.reads()).as("a peer at a digest outside Seen").isFalse();
        peers.shows(writer.digest());
        assertThat(cold.reads()).as("it holds that content now").isTrue();

        // A lone member with no look is fresh on its first passing gate.
        Member lone = member();
        assertThat(lone.reads()).isTrue();
    }

    @Test
    void aStaleLookFailsTheGateAndAFreshOneLetsTheNextGatePass() {
        solo();
        StubPeers peers = StubPeers.withPeer();
        Member m = member(peers);
        m.pauseTool("a");
        assertThat(m.reads()).isTrue();
        peers.lookAgeNanos = LEASE.toNanos() + 1;
        m.nanos.advance(LEASE.plusSeconds(1));
        assertThat(m.reads()).isFalse();
        peers.lookAgeNanos = 0;
        assertThat(m.reads()).isTrue();
    }

    @Test
    void aDepartedPeersDigestBlocksUntilItLeavesTheMemberList() {
        solo();
        StubPeers peers = StubPeers.withPeer();
        Member m = member(peers);
        m.pauseTool("a");
        peers.shows(UNKNOWN);
        m.nanos.advance(LEASE.plusSeconds(1));
        assertThat(m.reads()).isFalse();
        peers.digests = Map.of();
        assertThat(m.reads()).isTrue();
    }

    @Test
    void aDeletedBlockedOrReplacedCardOfATrackedPeerFailsCoveredAndANewerValidCardRestoresIt() {
        solo();
        StubPeers peers = StubPeers.withPeer();
        Member m = member(peers);
        m.pauseTool("a");
        peers.shows(m.digest());
        assertThat(m.reads()).isTrue();
        peers.shows(Map.of(PEER, Digest.NONE));
        m.nanos.advance(LEASE.plusSeconds(1));
        assertThat(m.reads()).isFalse();
        assertThat(m.fresh()).isFalse();
        peers.shows(m.digest());
        assertThat(m.reads()).isTrue();
    }

    @Test
    void aPeersDigestInSeenStaysThereAfter257ChangesWhilePinned_andAgesOutAfter256OnceUnpinned() {
        solo();
        StubPeers peers = StubPeers.withPeer();
        Member m = member(peers);
        m.pauseTool("t0");
        Digest pinned = m.digest();
        peers.shows(pinned);
        assertThat(m.reads()).isTrue();
        assertThat(m.doc.pinnedCount()).isEqualTo(1);

        for (int i = 1; i <= 257; i++) {
            m.pauseTool("t" + i);
        }
        assertThat(m.doc.seen(pinned)).isTrue();
        assertThat(m.reads()).as("still covered for the peer").isTrue();
        assertThat(m.doc.pinnedCount()).isLessThanOrEqualTo(1);

        // The peer's card changes: the digest is unpinned and re-enters the ring at its head.
        peers.shows(UNKNOWN);
        m.reads();
        assertThat(m.doc.pinnedCount()).isZero();
        for (int i = 0; i < 255; i++) {
            m.pauseTool("u" + i);
        }
        assertThat(m.doc.seen(pinned)).as("after 255 further changes").isTrue();
        m.pauseTool("last");
        assertThat(m.doc.seen(pinned)).as("after the 256th").isFalse();
    }

    @Test
    void anOperatorPauseOfALapsedMemberIsNotRenewedByItsOwnWriteEvenWhenThePostWriteStatePasses() {
        solo();
        StubPeers peers = StubPeers.withPeer();
        Member m = member(peers);
        m.pauseTool("a");
        lapse(m);
        Digest held = m.digest();
        // The look in beforeCommit shows a digest in Seen: clauses 1 to 3 pass after the write.
        m.doc.beforeCommit = () -> peers.shows(held);

        m.pauseTool("b");

        assertThat(m.fresh()).as("a member lapsed when the write began never renews from it").isFalse();
        m.doc.beforeCommit = () -> { };
        m.tick();
        assertThat(m.fresh()).as("fresh after its next non-write tick").isTrue();
    }

    @Test
    void aTickRepairByALapsedMemberIsNotRenewedByItsOwnWriteEvenWhenThePostWriteStatePasses() {
        solo();
        StubPeers peers = StubPeers.withPeer();
        Member m = member(peers);
        m.pauseTool("a");
        Doc rolledBack = doc(1, 0, Map.of(), Set.of());
        lapse(m);
        Digest held = m.digest();
        plant(cluster, rolledBack);
        m.doc.beforeCommit = () -> peers.shows(held);

        m.tick();

        assertThat(stored(cluster).pauses().tools()).containsExactly("a");
        assertThat(m.fresh()).isFalse();
        m.doc.beforeCommit = () -> { };
        assertThat(m.reads()).isTrue();
    }

    @Test
    void aRefreshDoesNotRenewPastTheGate_theLeaseRunsFromTheJudgeNotTheWrite() {
        solo();
        StubPeers peers = StubPeers.withPeer();
        Member m = member(peers);
        m.pauseTool("a");
        m.nanos.advance(LEASE.dividedBy(2));
        m.doc.beforeCommit = () -> {
            m.nanos.advance(LEASE.dividedBy(2));
            peers.shows(UNKNOWN);
        };

        m.tick();

        assertThat(m.held().refresh()).as("the refresh was written").isEqualTo(1);
        m.doc.beforeCommit = () -> { };
        m.nanos.advance(LEASE.dividedBy(2).plusMillis(1));
        assertThat(m.fresh()).as("tj + L + epsilon").isFalse();
    }

    @Test
    void anOperatorResumeDoesNotRenewPastTheGate_theLeaseRunsFromTheInLockJudge() {
        solo();
        StubPeers peers = StubPeers.withPeer();
        Member m = member(peers);
        m.pauseTool("a");
        AtomicInteger hooks = new AtomicInteger();
        m.doc.beforeCommit = () -> {
            hooks.incrementAndGet();
            m.nanos.advance(LEASE.dividedBy(2));
            peers.shows(UNKNOWN);
        };

        m.resumeTool("a");

        assertThat(hooks.get()).isEqualTo(1);
        assertThat(m.held().live(Target.tool("a"))).isEmpty();
        m.nanos.advance(LEASE.dividedBy(2).plusMillis(1));
        assertThat(m.fresh()).isFalse();
    }

    @Test
    void endScopeDoesNotRenewPastTheGateEither() {
        solo();
        StubPeers peers = StubPeers.withPeer();
        Member m = new Member(new Nanos(), new Fixture.TestClock(), peers);
        m.pauseScope("S");
        PauseDocument index = new PauseDocument(cluster, cluster::instance,
                new PauseDocument.Environment(m.nanos, m.wall, null), false, peers);
        documents.add(index);
        index.useDependencies(m.deps);
        assertThat(index.current(m.deps).scopes()).containsExactly("S");
        index.beforeCommit = () -> {
            m.nanos.advance(LEASE.dividedBy(2));
            peers.shows(UNKNOWN);
        };

        index.endScope("S", 0L, m.deps);

        assertThat(index.accepted().live(Target.scope("S"))).isEmpty();
        m.nanos.advance(LEASE.dividedBy(2).plusMillis(1));
        assertThat(index.fresh()).isFalse();
    }

    @Test
    void theTicksAcceptWrittenJudgesClause4OnFreshnessAfterItsJudge() {
        solo();
        StubPeers peers = StubPeers.withPeer();
        Member m = member(peers);
        m.pauseTool("p");
        m.pauseTool("q");
        Doc behind = doc(1, 0, Map.of(), Set.of());
        lapse(m);
        peers.digests = Map.of();
        plant(cluster, behind);
        m.doc.beforeCommit = () -> m.nanos.advance(LEASE.dividedBy(2));

        m.tick();

        assertThat(stored(cluster).pauses().tools()).containsExactlyInAnyOrder("p", "q");
        m.doc.beforeCommit = () -> { };
        m.nanos.advance(LEASE.dividedBy(2).plusSeconds(1));
        assertThat(m.fresh()).as("renewed at the write: tj + L + epsilon is still fresh").isTrue();
    }

    @Test
    void aLapsedMembersResumeNeedsEveryPeerCoveredAtItsInLockJudge() {
        solo();
        StubPeers peers = StubPeers.withPeer();
        Member m = member(peers);
        m.pauseTool("a");
        lapse(m);
        assertUnavailable(() -> m.resumeTool("a"));
        peers.digests = Map.of();
        m.resumeTool("a");
        assertThat(m.tools()).isEmpty();
    }

    @Test
    void aResumeIsRefusedWhenTheStoredDocumentIsMissingOrUnreadableEvenWhileTheMemberIsStillFresh() {
        solo();
        Member m = member();
        m.pauseTool("a");
        assertThat(m.reads()).isTrue();
        map(cluster).delete(key(cluster));
        assertThat(m.fresh()).isTrue();
        assertUnavailable(() -> m.resumeTool("a"));
        assertThat(map(cluster).get(key(cluster))).as("nothing written").isNull();
        map(cluster).put(key(cluster), "garbage");
        assertUnavailable(() -> m.resumeTool("a"));
        assertThat(map(cluster).get(key(cluster))).isEqualTo("garbage");
        assertThat(m.held().live(Target.tool("a"))).hasSize(1);
    }

    @Test
    void aResumeIsRefusedWhenAPeerIsOutsideSeenNowEvenThoughTheMemberIsStillFreshFromAnEarlierGate() {
        solo();
        StubPeers peers = StubPeers.withPeer();
        Member m = member(peers);
        m.pauseTool("a");
        assertThat(m.reads()).isTrue();
        m.nanos.advance(LEASE.dividedBy(4));
        peers.shows(UNKNOWN);
        assertThat(m.fresh()).isTrue();

        assertUnavailable(() -> m.resumeTool("a"));
        assertThat(stored(cluster).live(Target.tool("a"))).hasSize(1);
    }

    @Test
    void aResumeIsRefusedWithPeerStateUnknownAnUnverifiedMemberOrANotServingMemberButNotForALoneMember() {
        solo();
        StubPeers peers = StubPeers.withPeer();
        Member m = member(peers);
        m.pauseTool("a");
        peers.lookCompleted = false;
        assertThatThrownBy(() -> m.resumeTool("a")).isInstanceOf(ClusterOperationRefusedException.class);
        peers.lookCompleted = true;
        peers.unverified = 1;
        assertThatThrownBy(() -> m.resumeTool("a")).isInstanceOf(ClusterOperationRefusedException.class);
        peers.unverified = 0;
        m.resumeTool("a");
        assertThat(m.tools()).isEmpty();

        // A lone member is not refused by the peer-state check.
        solo();
        StubPeers lone = StubPeers.lone();
        lone.lookCompleted = false;
        Member l = member(lone);
        l.pauseTool("b");
        l.resumeTool("b");
        assertThat(l.tools()).isEmpty();

        // Not serving.
        solo();
        Member notServing = member();
        notServing.pauseTool("c");
        PauseDocument.Dependencies closed = new PauseDocument.Dependencies(notServing.deps.metrics(),
                TIMEOUT, () -> ClusterSecurityStatus.notServing(ClusterSecurityStatus.Reason.CARDS_UNAVAILABLE, 1, 0),
                notServing.deps.sealer());
        assertThatThrownBy(() -> notServing.doc.resume(Target.tool("c"), closed))
                .isInstanceOf(ClusterOperationRefusedException.class);
        notServing.doc.pause(Target.tool("d"), closed);
        assertThat(notServing.tools()).containsExactlyInAnyOrder("c", "d");
    }

    @Test
    void aResumeAfterARollbackSucceedsOnceTheMemberIsFreshAndCoveredAndWritesItsTombstones() {
        solo();
        Member m = member();
        m.pauseTool("p");
        Doc rolledBack = doc(1, 0, Map.of(), Set.of());
        plant(cluster, rolledBack);
        m.tick();
        m.resumeTool("p");
        assertThat(m.tools()).isEmpty();
        assertThat(stored(cluster).live(Target.tool("p"))).isEmpty();
    }

    // ---- A5: first start ------------------------------------------------------------------------------------

    @Test
    void aLoneMemberWritesTheFirstDocumentAtOnceWithNoUptimeWait() {
        solo();
        Member lone = member();
        lone.tick();
        assertThat(stored(cluster).sequence()).isEqualTo(1);
        assertThat(stored(cluster).elements()).isEmpty();
        assertThat(lone.reads()).isTrue();
        assertThat(lone.doc.established()).isTrue();
    }

    @Test
    void theOldestMemberWritesNoFirstDocumentBeforeOneLeaseOfUptimeAndNeedsALeaseOfCleanLooks() {
        solo();
        StubPeers peers = StubPeers.withPeer();
        Member oldest = member(peers);
        oldest.tick();
        assertThat(map(cluster).get(key(cluster))).isNull();

        oldest.nanos.advance(LEASE);
        peers.cleanRunAgeNanos = LEASE.toNanos() - 1;
        oldest.tick();
        assertThat(map(cluster).get(key(cluster))).as("the clean span is a nanosecond short").isNull();

        peers.lookAgeNanos = LEASE.dividedBy(2).toNanos() + 1;
        peers.cleanRunAgeNanos = LEASE.toNanos() + peers.lookAgeNanos;
        oldest.tick();
        assertThat(map(cluster).get(key(cluster))).as("the latest look is older than L/2").isNull();

        peers.lookAgeNanos = LEASE.dividedBy(2).toNanos();
        peers.cleanRunAgeNanos = LEASE.toNanos() + peers.lookAgeNanos;
        peers.lookCompleted = false;
        oldest.tick();
        assertThat(map(cluster).get(key(cluster))).as("peer state is unknown").isNull();

        peers.lookCompleted = true;
        peers.unverified = 1;
        oldest.tick();
        assertThat(map(cluster).get(key(cluster))).as("a member is unverified").isNull();

        peers.unverified = 0;
        peers.oldest = false;
        oldest.tick();
        assertThat(map(cluster).get(key(cluster))).as("not the oldest").isNull();

        peers.oldest = true;
        oldest.tick();
        assertThat(stored(cluster).sequence()).isEqualTo(1);
    }

    @Test
    void aCleanSpanUnderOneLeaseDoesNotWriteWhateverTheCleanRunAgeIs() {
        solo();
        StubPeers peers = StubPeers.withPeer();
        Member oldest = member(peers);
        oldest.nanos.advance(LEASE.multipliedBy(2));
        // cleanRunAge L + L/4 + epsilon, look age L/4: the span is L, enough. With look age L/4 + epsilon: not.
        peers.lookAgeNanos = LEASE.dividedBy(4).toNanos();
        peers.cleanRunAgeNanos = LEASE.toNanos() + peers.lookAgeNanos - 1;
        oldest.tick();
        assertThat(map(cluster).get(key(cluster))).isNull();
        peers.cleanRunAgeNanos = LEASE.toNanos() + peers.lookAgeNanos;
        oldest.tick();
        assertThat(map(cluster).get(key(cluster))).isNotNull();
    }

    @Test
    void twoEligibleMembersRaceToWriteTheFirstDocumentAndOneDocumentResultsWithNoConflictCounted() {
        solo();
        Member a = member();
        Member b = member();
        AtomicInteger hooks = new AtomicInteger();
        a.doc.beforeCommit = () -> {
            if (hooks.incrementAndGet() == 1) {
                b.tick();
            }
        };

        a.tick();

        assertThat(stored(cluster).sequence()).isEqualTo(1);
        assertThat(a.doc.hasAccepted()).isTrue();
        assertThat(b.doc.hasAccepted()).isTrue();
        assertThat(metrics.count(Metric.CLUSTER_PAUSE_CONFLICT)).isZero();
        assertThat(a.reads()).isTrue();
    }

    @Test
    void aMemberWithAHeldDocumentNeverTakesTheFirstStartPath_andAWrongDocOneIsMergedAndRepaired() {
        solo();
        Member holder = member();
        holder.pauseTool("p");
        Member cold = member();
        map(cluster).delete(key(cluster));
        // A Doc(1) forced in through beforeCommit by the cold member.
        cold.tick();
        assertThat(stored(cluster).sequence()).isEqualTo(1);
        holder.tick();
        assertThat(holder.tools()).containsExactly("p");
        Doc repaired = stored(cluster);
        assertThat(repaired.live(Target.tool("p"))).hasSize(1);
        assertThat(repaired.sequence()).isEqualTo(Math.max(holder.doc.acceptedSequence(), 1));
        assertThat(repaired.sequence()).isGreaterThan(1);
    }

    @Test
    void aMemberPastTheColdStartGateOverAnAbsentOrGatedOrUnreadableDocumentWritesNothing() {
        solo();
        Member cold = member(StubPeers.withPeer());
        cold.nanos.advance(LEASE.multipliedBy(2));
        map(cluster).put(key(cluster), "garbage");
        cold.tick();
        assertThat(map(cluster).get(key(cluster))).isEqualTo("garbage");
        Doc gated = new Doc(5, 0, new Fixture.TestClock().millis() - LEASE.plus(SKEW).plusSeconds(1).toMillis(), "i",
                Map.of(id(1), Target.tool("p")), Set.of());
        plant(cluster, gated);
        Object raw = map(cluster).get(key(cluster));
        cold.tick();
        assertThat(map(cluster).get(key(cluster))).isEqualTo(raw);
    }

    @Test
    void theGateDecidesTheSameWhenTheMonotonicClockStartsNearLongMaxValueAndWraps() {
        solo();
        for (long start : new long[] {1_000_000_000L, Long.MAX_VALUE - LEASE.dividedBy(2).toNanos(),
                Long.MIN_VALUE + 7}) {
            Nanos nanos = new Nanos(start);
            StubPeers peers = StubPeers.withPeer();
            Member m = new Member(nanos, new Fixture.TestClock(), peers);
            m.pauseTool("a" + start);
            assertThat(m.reads()).isTrue();
            nanos.advance(LEASE.minusNanos(1));
            assertThat(m.fresh()).as("one nanosecond short of the lease, start " + start).isTrue();
            nanos.advance(Duration.ofNanos(1));
            assertThat(m.fresh()).as("the lease, start " + start).isFalse();
            peers.lookAgeNanos = LEASE.toNanos() + 1;
            assertThat(m.reads()).as("a stale look fails the gate whatever the clock reads").isFalse();
            peers.lookAgeNanos = 0;
            assertThat(m.reads()).isTrue();
        }
    }

    // ---- A7: lapsed holders, restore and repair ------------------------------------------------------------

    @Test
    void aLapsedHolderOverAbsentPutsBackWhatItHoldsWithoutRenewing_itsNextTickRenewsItOnlyIfCovered() {
        solo();
        StubPeers peers = StubPeers.withPeer();
        Member m = member(peers);
        m.pauseTool("a");
        lapse(m);
        map(cluster).delete(key(cluster));

        m.tick();

        assertThat(stored(cluster).pauses().tools()).containsExactly("a");
        assertThat(m.fresh()).as("its own write never renews a lapsed member").isFalse();
        m.tick();
        assertThat(m.fresh()).as("still not covered").isFalse();
        peers.digests = Map.of();
        m.tick();
        assertThat(m.fresh()).as("covered at its next tick").isTrue();
    }

    @Test
    void aReplayedOlderDocumentIsRepairedToASupersetOfItAndOfHeldElementsAndTombstones() {
        solo();
        Member m = member();
        m.pauseTool("p");
        m.pauseTool("q");
        m.resumeTool("q");
        Doc replay = doc(1, 0, Map.of(id(900), Target.tool("old")), Set.of());
        plant(cluster, replay);

        m.tick();

        Doc repaired = stored(cluster);
        assertThat(repaired.covers(replay)).isTrue();
        assertThat(repaired.covers(m.held())).isTrue();
        assertThat(m.held().covers(repaired)).isTrue();
        assertThat(repaired.tombstones()).isNotEmpty();
    }

    // ---- 228: post-write renewal after a heal, the 197-11 read case, endScope retries, look age on one clock ---

    @Test
    void reconcileAfterMergeByALapsedMemberIsNotRenewedByItsOwnWriteAndTheNextReadMayRenewIt() {
        solo();
        StubPeers peers = StubPeers.withPeer();
        Member m = member(peers);
        m.pauseTool("a");
        lapse(m);
        m.doc.beginMerge();
        plant(cluster, doc(50, 0, Map.of(id(900), Target.tool("b")), Set.of()));
        Digest held = m.digest();
        m.doc.beforeCommit = () -> peers.shows(held);

        m.doc.reconcileAfterMerge();

        assertThat(stored(cluster).pauses().tools()).containsExactlyInAnyOrder("a", "b");
        assertThat(m.fresh()).as("a lapsed member's own write never renews it").isFalse();
        m.doc.beforeCommit = () -> { };
        assertThat(m.reads()).as("a later renewal point may renew it").isTrue();
    }

    @Test
    void aLapsedMembersRepairLeavesItLapsedAndALaterReadRenewsItOnlyIfCoveredThen() {
        solo();
        StubPeers peers = StubPeers.withPeer();
        Member m = member(peers);
        m.pauseTool("a");
        lapse(m);
        plant(cluster, doc(1, 0, Map.of(), Set.of()));

        m.tick();

        assertThat(stored(cluster).pauses().tools()).containsExactly("a");
        assertThat(m.fresh()).as("no read and no tick after the write: still lapsed").isFalse();
        assertThat(m.reads()).as("a read with the peer's digest still outside Seen").isFalse();
        peers.shows(m.digest());
        assertThat(m.reads()).as("a look between shows the written document's digest").isTrue();
    }

    private enum Retry { DELETED, UNREADABLE, JOINED, UNVERIFIED }

    @Test
    void anEndScopeRetryAppliesEveryPreconditionAndWritesNothingWhenItIsRefused() {
        for (Retry retry : Retry.values()) {
            solo();
            Member writer = member();
            writer.pauseScope("S");
            StubPeers peers = StubPeers.withPeer();
            PauseDocument index = new PauseDocument(cluster, cluster::instance,
                    new PauseDocument.Environment(new Nanos(), new Fixture.TestClock(), null), false, peers);
            documents.add(index);
            index.useDependencies(writer.deps);
            assertThat(index.current(writer.deps).scopes()).containsExactly("S");
            Member rival = member();
            AtomicInteger hooks = new AtomicInteger();
            index.beforeCommit = () -> {
                if (hooks.incrementAndGet() == 1) {
                    rival.pauseTool("rival");
                    switch (retry) {
                        case DELETED -> map(cluster).delete(key(cluster));
                        case UNREADABLE -> map(cluster).put(key(cluster), "garbage");
                        case JOINED -> peers.lookCompleted = false;
                        case UNVERIFIED -> peers.unverified = 1;
                    }
                }
            };
            Throwable thrown = catchThrowable(
                    () -> index.endScope("S", 0L, writer.deps));
            assertThat(thrown).as(retry.name()).isNotNull();
            if (retry == Retry.JOINED || retry == Retry.UNVERIFIED) {
                assertThat(thrown).isInstanceOf(ClusterOperationRefusedException.class);
            } else {
                assertUnavailable("unavailable", () -> {
                    throw thrown;
                });
            }
            assertThat(index.accepted().live(Target.scope("S"))).as(retry + ": the pause is kept").isNotEmpty();
            if (retry == Retry.DELETED) {
                assertThat(map(cluster).get(key(cluster))).as("no putIfAbsent").isNull();
            } else if (retry == Retry.UNREADABLE) {
                assertThat(map(cluster).get(key(cluster))).isEqualTo("garbage");
            }
            index.close();
            cluster.close();
            cluster = null;
        }
    }

    @Test
    void theIndexDocumentOnItsOwnNanoClockNearLongMaxValueDecidesLookAgeLikeOneWithEqualClocks() {
        solo();
        for (long start : new long[] {1_000_000_000L, Long.MAX_VALUE - 100, Long.MIN_VALUE + 100}) {
            StubPeers peers = StubPeers.withPeer();
            Member main = new Member(new Nanos(), new Fixture.TestClock(), peers);
            main.pauseTool("a");
            assertThat(main.reads()).isTrue();
            Nanos indexNanos = new Nanos(start);
            PauseDocument index = new PauseDocument(cluster, cluster::instance,
                    new PauseDocument.Environment(indexNanos, new Fixture.TestClock(), null), false, peers);
            documents.add(index);
            index.useDependencies(main.deps);
            for (long age : new long[] {0, LEASE.toNanos(), LEASE.toNanos() + 1}) {
                peers.lookAgeNanos = age;
                indexNanos.advance(LEASE.plusSeconds(1));
                main.nanos.advance(LEASE.plusSeconds(1));
                boolean mainFresh = main.reads();
                boolean indexFresh;
                try {
                    index.current(main.deps);
                    indexFresh = true;
                } catch (IllegalStateException refused) {
                    indexFresh = false;
                }
                assertThat(indexFresh).as("start " + start + ", look age " + age).isEqualTo(mainFresh);
                assertThat(mainFresh).isEqualTo(age <= LEASE.toNanos());
            }
        }
    }

    @Test
    void noSequenceHasATermDerivedFromAPeer_pauseResumePushHeldAndReconcileAllWriteAtMaxPlusOne() {
        solo();
        StubPeers peers = StubPeers.withPeer();
        Member m = member(peers);
        m.pauseTool("a");
        assertThat(m.reads()).isTrue();
        peers.shows(Map.of(PEER, m.digest(), OTHER, m.digest()));
        long s0 = stored(cluster).sequence();

        m.pauseTool("b");
        assertThat(stored(cluster).sequence()).as("pause").isEqualTo(s0 + 1);
        m.resumeTool("b");
        assertThat(stored(cluster).sequence()).as("resume").isEqualTo(s0 + 2);
        plant(cluster, doc(1, 0, Map.of(), Set.of()));
        m.tick();
        assertThat(stored(cluster).sequence()).as("pushHeld").isEqualTo(s0 + 3);
        m.doc.beginMerge();
        plant(cluster, doc(s0 + 50, 0, Map.of(id(900), Target.tool("z")), Set.of()));
        m.doc.reconcileAfterMerge();
        assertThat(stored(cluster).sequence()).as("reconcileAfterMerge").isEqualTo(s0 + 51);
        m.pauseScope("S");
        m.doc.endScope("S", 0L, m.deps);
        assertThat(stored(cluster).sequence()).as("endScope").isEqualTo(s0 + 53);
    }

    @Test
    void anOperatorPauseByAMemberLapsedBeforeItsInLockJudgeRenewsAtTheWriteBecauseTheJudgeRenewedItFirst() {
        solo();
        StubPeers peers = StubPeers.withPeer();
        Member m = member(peers);
        m.pauseTool("a");
        lapse(m);
        peers.digests = Map.of();
        m.doc.beforeCommit = () -> m.nanos.advance(LEASE.dividedBy(2));

        m.pauseTool("b");

        m.doc.beforeCommit = () -> { };
        m.nanos.advance(LEASE.dividedBy(2).plusSeconds(1));
        assertThat(m.fresh()).as("fresh when the write began, as it stood after the judge: renewed at the write")
                .isTrue();
    }

    @Test
    void aFreshMemberReadingOverADeletedOrGarbageDocumentIsRefusedWhateverItsLeaseSays() {
        for (String shape : List.of("deleted", "garbage")) {
            solo();
            Member a = member();
            Member bMember = member();
            a.pauseTool("a");
            assertThat(bMember.tools()).containsExactly("a");
            assertThat(a.reads()).isTrue();
            a.pauseTool("x");
            if (shape.equals("deleted")) {
                map(cluster).delete(key(cluster));
            } else {
                map(cluster).put(key(cluster), "garbage");
            }
            assertThat(bMember.fresh()).as(shape + ": still inside its lease").isTrue();

            assertUnavailable(shape, bMember::tools);
            assertUnavailable(shape, bMember::allPaused);
            cluster.close();
            cluster = null;
        }
    }

    @Test
    void whenAUserReleasesTheSharedDocumentsTicksUseTheDependenciesOfAUserThatIsStillRetained() {
        solo();
        PauseDocument.Dependencies first = PauseDocument.Dependencies.of(cluster, metrics, TIMEOUT);
        PauseDocument.Dependencies second = PauseDocument.Dependencies.of(cluster, metrics, TIMEOUT);
        PauseDocument shared = new PauseDocument(cluster, cluster::instance,
                new PauseDocument.Environment(new Nanos(), new Fixture.TestClock(), null), true, StubPeers.lone());
        documents.add(shared);
        shared.retain(first);
        shared.retain(second);
        assertThat(shared.tickDependenciesForTest()).isSameAs(second);

        shared.release(second);

        assertThat(shared.tickDependenciesForTest()).as("the one that closed is not used again").isSameAs(first);
        shared.release(first);
    }

    // ---- A12: properties ------------------------------------------------------------------------------------

    private static Doc randomDoc(Random random, int pool) {
        Map<Id, Target> elements = new HashMap<>();
        Set<Id> tombstones = new HashSet<>();
        for (int i = 0; i < pool; i++) {
            // The target of an id is fixed by the id, so two random documents never name one id as two targets.
            Target target = switch (i % 3) {
                case 0 -> Target.ALL;
                case 1 -> Target.tool("tool-" + (i % 4));
                default -> Target.scope("scope-" + (i % 5));
            };
            if (random.nextInt(3) == 0) {
                elements.put(id(i), target);
            }
            if (random.nextInt(4) == 0) {
                tombstones.add(id(i));
            }
        }
        return new Doc(random.nextInt(10), random.nextInt(3), random.nextInt(4), "issuer-" + random.nextInt(3),
                elements, tombstones);
    }

    private static void assertSameDoc(Doc left, Doc right, String message) {
        assertThat(left.elements()).as(message + " elements").isEqualTo(right.elements());
        assertThat(left.tombstones()).as(message + " tombstones").isEqualTo(right.tombstones());
        assertThat(left.sequence()).as(message + " sequence").isEqualTo(right.sequence());
        assertThat(left.refresh()).as(message + " refresh").isEqualTo(right.refresh());
        assertThat(left.issuedAtMillis()).as(message + " issuedAt").isEqualTo(right.issuedAtMillis());
        assertThat(left.issuer()).as(message + " issuer").isEqualTo(right.issuer());
    }

    @Test
    void mergeIsCommutativeAssociativeAndIdempotentAndAMergedTargetIsPausedExactlyWhenSomeElementHasNoTombstone() {
        Random random = new Random(228);
        for (int run = 0; run < 400; run++) {
            Doc a = randomDoc(random, 24);
            Doc b = randomDoc(random, 24);
            Doc c = randomDoc(random, 24);
            assertSameDoc(a.merge(b), b.merge(a), "commutative");
            assertSameDoc(a.merge(b).merge(c), a.merge(b.merge(c)), "associative");
            assertSameDoc(a.merge(a), a, "idempotent");
            assertSameDoc(a.merge(b).merge(b), a.merge(b), "idempotent over a merge");

            Doc merged = a.merge(b);
            Set<Id> tombstones = new HashSet<>(a.tombstones());
            tombstones.addAll(b.tombstones());
            Map<Id, Target> elements = new HashMap<>(a.elements());
            elements.putAll(b.elements());
            assertThat(merged.elements()).isEqualTo(elements);
            PauseDocument.Pauses view = merged.pauses();
            boolean all = false;
            Set<String> tools = new HashSet<>();
            Set<String> scopes = new HashSet<>();
            for (Map.Entry<Id, Target> element : elements.entrySet()) {
                if (!tombstones.contains(element.getKey())) {
                    switch (element.getValue().kind()) {
                        case ALL -> all = true;
                        case TOOL -> tools.add(element.getValue().name());
                        case SCOPE -> scopes.add(element.getValue().name());
                    }
                }
            }
            assertThat(view.all()).isEqualTo(all);
            assertThat(view.tools()).isEqualTo(tools);
            assertThat(view.scopes()).isEqualTo(scopes);
            for (Doc input : List.of(a, b)) {
                for (Map.Entry<Id, Target> element : input.elements().entrySet()) {
                    if (!tombstones.contains(element.getKey())) {
                        assertThat(merged.live(element.getValue())).contains(element.getKey());
                    }
                }
            }
        }
    }

    @Test
    void aMergedOrderNeverDecreasesEqualsTheLexicographicMaximumAndTakesTheMetadataOfTheMaxOrderInput() {
        Random random = new Random(40);
        for (int run = 0; run < 400; run++) {
            Doc held = randomDoc(random, 12);
            Doc other = randomDoc(random, 12);
            Doc merged = held.merge(other);
            assertThat(merged.compareOrder(held)).isGreaterThanOrEqualTo(0);
            assertThat(merged.compareOrder(other)).isGreaterThanOrEqualTo(0);
            Doc top = held.compareOrder(other) >= 0 ? held : other;
            assertThat(merged.sequence()).isEqualTo(top.sequence());
            assertThat(merged.refresh()).isEqualTo(top.refresh());
            if (held.compareOrder(other) != 0) {
                assertThat(merged.issuedAtMillis()).isEqualTo(top.issuedAtMillis());
                assertThat(merged.issuer()).isEqualTo(top.issuer());
            }
        }
    }

    // ---- A10: boundary and the map -------------------------------------------------------------------------

    @Test
    void theOversightMapHoldsExactlyTheDocumentAndItOpensUnderTheTestKey() {
        int port = FreePorts.consecutive(1);
        cluster = fixture.keyed(TestMembers.uniqueName("boundary"), port, List.of("127.0.0.1:" + port),
                TestMembers.KEYS, Fixture.settings(UnverifiedMemberAction.REFUSE), new Fixture.Metrics(),
                new RecordingLogger(), false);
        HazelcastOversightState state = new HazelcastOversightState(cluster, TIMEOUT, metrics);
        try {
            state.pauseAll();
            state.pauseTool("search");
            state.pauseScope("CASE-42");

            Inputs inputs = new Inputs(cluster.keyedNames(), null, "CASE-42", "subject-42", null, "synthetic", "ap-1",
                    "alice", null, "search");
            IMap<Object, Object> contents = cluster.instance().getMap(PrivacyCluster.OVERSIGHT_MAP);
            new OversightMapsBoundary().check(PrivacyCluster.OVERSIGHT_MAP, contents, inputs);
            assertThat(OversightMapsBoundary.opensUnderTestKey(contents, inputs)).isTrue();
            assertThat(contents.size()).isEqualTo(1);
            OversightMapsBoundary.checkMapConfigs(cluster.instance());
        } finally {
            state.close();
        }
    }

    @Test
    void theBoundaryFailsRatherThanSkipsWhenTheNamesAreTheTestNamesAndTheEnvelopeDoesNotOpen() {
        int port = FreePorts.consecutive(1);
        cluster = fixture.keyed(TestMembers.uniqueName("boundary-fail"), port, List.of("127.0.0.1:" + port),
                TestMembers.KEYS, Fixture.settings(UnverifiedMemberAction.REFUSE), new Fixture.Metrics(),
                new RecordingLogger(), false);
        HazelcastOversightState state = new HazelcastOversightState(cluster, TIMEOUT, metrics);
        try {
            state.pauseTool("search");
            ClusterKeys foreign = new ClusterKeys("other-cluster-1", Fixture.filled('x'), null, null, null, null,
                    "test-name-1", Fixture.filled('n'));
            String wrongKey = Sealer.builder(foreign, PrivacyMetrics.none()).build()
                    .seal(PrivacyCluster.OVERSIGHT_MAP, key(cluster), "x".getBytes());
            IMap<Object, Object> contents = cluster.instance().getMap(PrivacyCluster.OVERSIGHT_MAP);
            contents.put(key(cluster), wrongKey);
            Inputs inputs = new Inputs(cluster.keyedNames(), null, "CASE-42", "subject-42", null, "synthetic", "ap-1",
                    "alice", null, "search");

            assertThatThrownBy(
                            () -> new OversightMapsBoundary().check(PrivacyCluster.OVERSIGHT_MAP, contents, inputs))
                    .isInstanceOf(AssertionError.class);
        } finally {
            state.close();
        }
    }

    /**
     * OVERSIGHT_UNAVAILABLE is exactly an IllegalStateException with the document's own message; not a refusal, not a
     * subclass, not another IllegalStateException.
     */
    static void assertUnavailable(ThrowingCallable call) {
        assertUnavailable("unavailable", call);
    }

    static void assertUnavailable(String description, ThrowingCallable call) {
        Throwable thrown = catchThrowable(call);
        assertThat(thrown).as(description).isNotNull();
        assertThat(thrown.getClass()).as(description).isEqualTo(IllegalStateException.class);
        assertThat(thrown).as(description).hasMessage(PauseDocument.unavailable().getMessage());
    }
}

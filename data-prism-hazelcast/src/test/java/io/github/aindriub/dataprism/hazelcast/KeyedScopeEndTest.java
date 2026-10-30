package io.github.aindriub.dataprism.hazelcast;

import io.github.aindriub.dataprism.annotations.PrivacyNamespace;
import io.github.aindriub.dataprism.core.metrics.PrivacyMetrics;
import io.github.aindriub.dataprism.core.model.PrivacyContext;
import io.github.aindriub.dataprism.core.model.PrivacyScopeType;
import io.github.aindriub.dataprism.core.model.PseudonymisationVersion;
import io.github.aindriub.dataprism.core.spi.SyntheticValueSource;
import io.github.aindriub.dataprism.hazelcast.PauseDocument.Doc;
import io.github.aindriub.dataprism.hazelcast.PauseDocument.Id;
import io.github.aindriub.dataprism.hazelcast.PauseDocument.Target;
import io.github.aindriub.dataprism.hazelcast.seal.Opened;
import io.github.aindriub.dataprism.hazelcast.seal.ServingGate;
import io.github.aindriub.dataprism.oversight.ApprovalRequest.Status;
import com.hazelcast.map.IMap;
import io.github.aindriub.dataprism.core.refusal.ClusterLockTimeoutException;
import io.github.aindriub.dataprism.core.refusal.ClusterOperationRefusedException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;

import static io.github.aindriub.dataprism.hazelcast.TestMembers.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Ending a scope is still a prefix match once keys are keyed names: {@code endScope}, {@code forgetScope} and the
 * budget's {@code forget} remove exactly the entries of one scope and leave a second scope's entries, on one member
 * and on two.
 */
class KeyedScopeEndTest {

    private static final String ENDED = "scope-ended";
    private static final String KEPT = "scope-kept";
    private static final Duration TIMEOUT = Duration.ofSeconds(2);

    private PrivacyCluster[] members;

    @AfterEach
    void stop() {
        if (members != null) {
            for (PrivacyCluster member : members) {
                member.close();
            }
        }
    }

    private void singleMember() {
        int port = FreePorts.consecutive(1);
        members = new PrivacyCluster[]{TestMembers.member(TestMembers.uniqueName("scope-end-1"), port,
                java.util.List.of("127.0.0.1:" + port), OptionalInt.empty())};
    }

    private void twoMembers() {
        members = TestMembers.pair(TestMembers.uniqueName("scope-end-2"), OptionalInt.empty());
    }

    /** The authentic pause document now stored by the cluster of {@code on}. */
    private static Doc document(PrivacyCluster on) {
        String key = on.keyedNames().mapKey(PrivacyCluster.OVERSIGHT_MAP, PauseDocument.CANONICAL);
        Object stored = on.instance().getMap(PrivacyCluster.OVERSIGHT_MAP).get(key);
        Opened opened = IdentityMaps.sealerFor(on, PrivacyMetrics.none(), ServingGate.open())
                .open(PrivacyCluster.OVERSIGHT_MAP, key, (String) stored);
        assertThat(opened.outcome()).isEqualTo(Opened.Outcome.AUTHENTIC);
        return Doc.decode(opened.plaintext());
    }

    private static ScopeIdentityIndex index(PrivacyCluster on) {
        return new ScopeIdentityIndex(on, PrivacyMetrics.none(), TIMEOUT);
    }

    private static PrivacyContext scope(String scopeId) {
        return new PrivacyContext(scopeId, PrivacyScopeType.CASE, "DEFAULT", "test",
                Instant.now().plus(1, ChronoUnit.HOURS), PseudonymisationVersion.HMAC_SHA256_V1);
    }

    private static final SyntheticValueSource GENERATOR =
            (subjectId, namespace, context) -> "generated:" + context.scopeId() + ":" + subjectId;

    /** Writes through the first member; reads and ends through the last, so two members really share the state. */
    private void populate(String scopeId, String subject) {
        PrivacyCluster writer = members[0];
        var caching = new CachingSyntheticValueSource(GENERATOR, writer);
        caching.syntheticValue(subject, PrivacyNamespace.PERSON_NAME, scope(scopeId));
        new HazelcastScopeBudget(writer, TIMEOUT).tryRead(scopeId, subject, 5);
        new HazelcastOversightState(writer, TIMEOUT).pauseScope(scopeId);
        new HazelcastApprovalStore(writer, TIMEOUT).create(
                TestMembers.request("approval-" + scopeId, scopeId, Status.PENDING));
    }

    private int total() {
        PrivacyCluster any = members[members.length - 1];
        return any.instance().getMap(PrivacyCluster.IDENTITY_MAP).size()
                + any.instance().getMap(PrivacyCluster.REIDENTIFICATION_MAP).size()
                + any.instance().getMap(PrivacyCluster.BUDGET_MAP).size()
                + any.instance().getMap(PrivacyCluster.APPROVAL_MAP).size()
                + any.instance().getMap(PrivacyCluster.APPROVAL_ID_MAP).size()
                + any.instance().getMap(PrivacyCluster.APPROVAL_PENDING_MAP).size()
                + any.instance().getMap(PrivacyCluster.OVERSIGHT_MAP).size();
    }

    private void endScopeLeavesTheOtherScope() {
        populate(ENDED, "subject-1");
        int one = total();
        populate(KEPT, "subject-1");
        int two = total();
        assertThat(one).isPositive();
        assertThat(two).as("the second scope adds entries to every map but the pending set").isGreaterThan(one);

        PrivacyCluster ender = members[members.length - 1];
        new ScopeIdentityIndex(ender, io.github.aindriub.dataprism.core.metrics.PrivacyMetrics.none(), TIMEOUT)
                .endScope(ENDED);

        assertThat(new HazelcastOversightState(ender, TIMEOUT).scopePaused(ENDED)).isFalse();
        assertThat(new HazelcastOversightState(ender, TIMEOUT).scopePaused(KEPT)).isTrue();
        // In the document the ended scope's element is tombstoned, not deleted, and the other scope's is live.
        Doc paused = document(ender);
        assertThat(paused.live(Target.scope(ENDED))).isEmpty();
        assertThat(paused.live(Target.scope(KEPT))).hasSize(1);
        assertThat(paused.elements().values().stream().filter(Target.scope(ENDED)::equals)).hasSize(1);
        assertThat(paused.tombstones()).hasSize(1);
        assertThat(new HazelcastScopeBudget(ender, TIMEOUT).reads(ENDED, "subject-1")).isZero();
        assertThat(new HazelcastScopeBudget(ender, TIMEOUT).reads(KEPT, "subject-1")).isEqualTo(1);
        var approvals = new HazelcastApprovalStore(ender, TIMEOUT);
        assertThat(approvals.find("approval-" + ENDED)).isEmpty();
        assertThat(approvals.find("approval-" + KEPT)).isPresent();
        assertThat(ender.instance().getMap(PrivacyCluster.IDENTITY_MAP).size()).isEqualTo(1);
        assertThat(ender.instance().getMap(PrivacyCluster.APPROVAL_ID_MAP).size()).isEqualTo(1);
        assertThat(ender.instance().getMap(PrivacyCluster.REIDENTIFICATION_MAP).size()).isZero();
        assertThat(ender.instance().getMap(PrivacyCluster.BUDGET_MAP).size()).isEqualTo(1);
        assertThat(ender.instance().getMap(PrivacyCluster.OVERSIGHT_MAP).size())
                .as("the pause document is one entry whoever ends a scope").isEqualTo(1);
        assertThat(ender.instance().getMap(PrivacyCluster.APPROVAL_MAP).size()).isEqualTo(1);
        // Both scopes' requester is the same, so the pending set is shared; only the kept scope's id is left in it.
        String pendingKey = TestMembers.SCOPE_KEYS.pending(
                io.github.aindriub.dataprism.oversight.ApprovalRequest.Kind.TOOL_CALL, "alice");
        assertThat(PendingSet.decode((String) ender.instance().getMap(PrivacyCluster.APPROVAL_PENDING_MAP)
                .get(pendingKey))).containsOnlyKeys("approval-" + KEPT);
    }

    @Test
    void endScopeRemovesExactlyOneScopeOnOneMember() {
        singleMember();
        endScopeLeavesTheOtherScope();
    }

    @Test
    void endScopeRemovesExactlyOneScopeOnTwoMembers() {
        twoMembers();
        endScopeLeavesTheOtherScope();
    }

    private void forgetScopeLeavesTheOtherScope() {
        populate(ENDED, "subject-1");
        populate(KEPT, "subject-1");
        PrivacyCluster ender = members[members.length - 1];
        var approvals = new HazelcastApprovalStore(ender, TIMEOUT);
        approvals.forgetScope(ENDED);
        assertThat(approvals.find("approval-" + ENDED)).isEmpty();
        assertThat(approvals.find("approval-" + KEPT)).isPresent();
        assertThat(ender.instance().getMap(PrivacyCluster.APPROVAL_MAP).size()).isEqualTo(1);
        assertThat(ender.instance().getMap(PrivacyCluster.APPROVAL_ID_MAP).size()).isEqualTo(1);
        assertThat(ender.instance().getMap(PrivacyCluster.APPROVAL_PENDING_MAP).size()).isEqualTo(1);
        // The id of the forgotten scope is free again; the other scope's id is not.
        approvals.create(TestMembers.request("approval-" + ENDED, ENDED, Status.PENDING));
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> approvals.create(TestMembers.request("approval-" + KEPT, KEPT, Status.PENDING)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void forgetScopeRemovesExactlyOneScopesApprovalsOnOneMember() {
        singleMember();
        forgetScopeLeavesTheOtherScope();
    }

    @Test
    void forgetScopeRemovesExactlyOneScopesApprovalsOnTwoMembers() {
        twoMembers();
        forgetScopeLeavesTheOtherScope();
    }

    private void budgetForgetLeavesTheOtherScope() {
        PrivacyCluster writer = members[0];
        var budget = new HazelcastScopeBudget(writer, TIMEOUT);
        budget.tryRead(ENDED, "subject-1", 5);
        budget.tryRead(ENDED, "subject-2", 5);
        budget.tryRead(KEPT, "subject-1", 5);
        var other = new HazelcastScopeBudget(members[members.length - 1], TIMEOUT);
        other.forget(ENDED);
        assertThat(other.reads(ENDED, "subject-1")).isZero();
        assertThat(other.reads(ENDED, "subject-2")).isZero();
        assertThat(other.reads(KEPT, "subject-1")).isEqualTo(1);
        assertThat(writer.instance().getMap(PrivacyCluster.BUDGET_MAP).size()).isEqualTo(1);
    }

    @Test
    void budgetForgetRemovesExactlyOneScopeOnOneMember() {
        singleMember();
        budgetForgetLeavesTheOtherScope();
    }

    @Test
    void budgetForgetRemovesExactlyOneScopeOnTwoMembers() {
        twoMembers();
        budgetForgetLeavesTheOtherScope();
    }

    @Test
    void theGlobalAndToolPausesSurviveEndingAScope() {
        singleMember();
        var state = new HazelcastOversightState(members[0], TIMEOUT);
        state.pauseAll();
        state.pauseTool("search");
        state.pauseScope(ENDED);
        new ScopeIdentityIndex(members[0], io.github.aindriub.dataprism.core.metrics.PrivacyMetrics.none(), TIMEOUT)
                .endScope(ENDED);
        assertThat(state.allPaused()).isTrue();
        assertThat(state.toolPaused("search")).isTrue();
        assertThat(state.scopePaused(ENDED)).isFalse();
        assertThat(state.snapshot().pausedScopes()).isEmpty();
        Doc paused = document(members[0]);
        assertThat(paused.live(Target.ALL)).hasSize(1);
        assertThat(paused.live(Target.tool("search"))).hasSize(1);
        assertThat(paused.live(Target.scope(ENDED))).isEmpty();
        assertThat(paused.tombstones()).hasSize(1);
    }

    @Test
    @SuppressWarnings("unchecked")
    void aRefusalInTheFirstMapStillEmptiesTheOthersRunsForgetScopeAndThrows() {
        singleMember();
        populate(ENDED, "subject-1");
        PrivacyCluster cluster = members[0];
        var index = new ScopeIdentityIndex(cluster, io.github.aindriub.dataprism.core.metrics.PrivacyMetrics.none(),
                TIMEOUT);
        index.maps = name -> {
            IMap<String, ?> real = cluster.instance().getMap(name);
            if (!PrivacyCluster.IDENTITY_MAP.equals(name)) {
                return real;
            }
            return (IMap<String, ?>) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[] {IMap.class}, (proxy, method, args) -> {
                        if (method.getName().equals("tryLock")) {
                            throw new IllegalArgumentException("lease too long");
                        }
                        try {
                            return method.invoke(real, args);
                        } catch (java.lang.reflect.InvocationTargetException e) {
                            throw e.getCause();
                        }
                    });
        };

        assertThatThrownBy(() -> index.endScope(ENDED)).isInstanceOf(ClusterOperationRefusedException.class);

        assertThat(cluster.instance().getMap(PrivacyCluster.IDENTITY_MAP).size()).as("refused map").isPositive();
        assertThat(cluster.instance().getMap(PrivacyCluster.REIDENTIFICATION_MAP).size()).isZero();
        assertThat(cluster.instance().getMap(PrivacyCluster.BUDGET_MAP).size()).isZero();
        assertThat(document(cluster).live(Target.scope(ENDED))).as("a sweep failure keeps the pause").hasSize(1);
        assertThat(cluster.instance().getMap(PrivacyCluster.APPROVAL_MAP).size()).as("forgetScope ran").isZero();
    }

    @Test
    void endScopeWithHeldKeysInTwoMapsTakesAboutOneTimeout() throws Exception {
        singleMember();
        Duration timeout = Duration.ofSeconds(1);
        populate(ENDED, "subject-1");
        PrivacyCluster cluster = members[0];
        IMap<String, Object> identity = cluster.instance().getMap(PrivacyCluster.IDENTITY_MAP);
        IMap<String, Object> budget = cluster.instance().getMap(PrivacyCluster.BUDGET_MAP);
        String identityKey = identity.keySet().iterator().next();
        String budgetKey = budget.keySet().iterator().next();
        java.util.concurrent.CountDownLatch locked = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        Thread holder = new Thread(() -> {
            identity.lock(identityKey);
            budget.lock(budgetKey);
            locked.countDown();
            try {
                release.await();
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        });
        holder.start();
        assertThat(locked.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        try {
            var index = new ScopeIdentityIndex(cluster,
                    io.github.aindriub.dataprism.core.metrics.PrivacyMetrics.none(), timeout);
            long start = System.nanoTime();
            assertThatThrownBy(() -> index.endScope(ENDED)).isInstanceOf(ClusterLockTimeoutException.class);
            long elapsed = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            assertThat(elapsed).as("one shared deadline, not one per map").isLessThan(1700);
        } finally {
            identity.forceUnlock(identityKey);
            budget.forceUnlock(budgetKey);
            release.countDown();
            holder.join();
        }
    }

    @Test
    void aRefusedApprovalDoesNotStopLaterApprovalsAndTheCallThrows() {
        singleMember();
        populate(ENDED, "subject-1");
        var store = new HazelcastApprovalStore(members[0], TIMEOUT);
        store.create(TestMembers.request("approval-2-" + ENDED, ENDED, Status.PENDING));
        store.create(TestMembers.request("approval-3-" + ENDED, ENDED, Status.PENDING));
        PrivacyCluster real = members[0];
        java.util.concurrent.atomic.AtomicBoolean thrown = new java.util.concurrent.atomic.AtomicBoolean();
        com.hazelcast.core.HazelcastInstance wrapped = (com.hazelcast.core.HazelcastInstance)
                java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                        new Class<?>[] {com.hazelcast.core.HazelcastInstance.class}, (proxy, method, args) -> {
                            Object target = invokeOn(method, real.instance(), args);
                            if (!method.getName().equals("getMap")
                                    || !PrivacyCluster.APPROVAL_MAP.equals(args[0])) {
                                return target;
                            }
                            return java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                                    new Class<?>[] {IMap.class}, (mapProxy, call, callArgs) -> {
                                        if (call.getName().equals("tryLock") && thrown.compareAndSet(false, true)) {
                                            throw new IllegalArgumentException("lease too long");
                                        }
                                        return invokeOn(call, target, callArgs);
                                    });
                        });
        PrivacyCluster faulty = PrivacyCluster.using(wrapped, false, TestMembers.KEYS);

        assertThatThrownBy(() -> new ScopeIdentityIndex(faulty,
                io.github.aindriub.dataprism.core.metrics.PrivacyMetrics.none(), TIMEOUT).endScope(ENDED))
                .isInstanceOf(ClusterOperationRefusedException.class);

        assertThat(real.instance().getMap(PrivacyCluster.APPROVAL_MAP).size())
                .as("only the approval whose lock was refused is left").isEqualTo(1);
    }

    private static Object invokeOn(java.lang.reflect.Method method, Object target, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw e.getCause();
        }
    }

    @Test
    void endScopeWithTwoHeldApprovalsTakesAboutOneTimeout() throws Exception {
        singleMember();
        Duration timeout = Duration.ofSeconds(1);
        populate(ENDED, "subject-1");
        PrivacyCluster cluster = members[0];
        new HazelcastApprovalStore(cluster, timeout).create(
                TestMembers.request("approval-2-" + ENDED, ENDED, Status.PENDING));
        IMap<String, Object> approvals = cluster.instance().getMap(PrivacyCluster.APPROVAL_MAP);
        java.util.List<String> keys = new java.util.ArrayList<>(approvals.keySet());
        assertThat(keys).hasSize(2);
        java.util.concurrent.CountDownLatch locked = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        Thread holder = new Thread(() -> {
            keys.forEach(approvals::lock);
            locked.countDown();
            try {
                release.await();
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        });
        holder.start();
        assertThat(locked.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        try {
            var index = new ScopeIdentityIndex(cluster,
                    io.github.aindriub.dataprism.core.metrics.PrivacyMetrics.none(), timeout);
            long start = System.nanoTime();
            assertThatThrownBy(() -> index.endScope(ENDED)).isInstanceOf(ClusterLockTimeoutException.class);
            long elapsed = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            assertThat(elapsed).as("the approvals share the call's deadline").isLessThan(1700);
        } finally {
            keys.forEach(approvals::forceUnlock);
            release.countDown();
            holder.join();
        }
    }

    @Test
    void aLegacyPauseStaysPausedAfterEndScopeAndResumeScope() {
        singleMember();
        PrivacyCluster cluster = members[0];
        var state = new HazelcastOversightState(cluster, TIMEOUT);
        // A first document, so that this member has a pause document to resume against.
        state.pauseTool("search");
        cluster.instance().getMap(PrivacyCluster.OVERSIGHT_MAP).put(ENDED + "\0paused", Boolean.TRUE);

        new ScopeIdentityIndex(cluster, io.github.aindriub.dataprism.core.metrics.PrivacyMetrics.none(), TIMEOUT)
                .endScope(ENDED);
        state.resumeScope(ENDED);

        assertThat(state.scopePaused(ENDED)).isTrue();
    }

    // ---- the pause step of ending a scope (task 228, amendment 5) ------------------------------------------

    @Test
    void aToolPausedUnderTheNameOfTheEndedScopeAndTheScopesOfOthersSurviveWhileTheScopesElementIsTombstoned() {
        singleMember();
        var state = new HazelcastOversightState(members[0], TIMEOUT);
        state.pauseScope(ENDED);
        state.pauseTool(ENDED);
        state.pauseScope(KEPT);
        state.pauseAll();

        index(members[0]).endScope(ENDED);

        Doc paused = document(members[0]);
        assertThat(paused.live(Target.scope(ENDED))).isEmpty();
        assertThat(paused.live(Target.tool(ENDED))).as("a tool of the same name is another target").hasSize(1);
        assertThat(paused.live(Target.scope(KEPT))).hasSize(1);
        assertThat(paused.live(Target.ALL)).hasSize(1);
        assertThat(paused.elements()).hasSize(4);
        assertThat(paused.tombstones()).hasSize(1);
        assertThat(new HazelcastOversightState(members[0], TIMEOUT).scopePaused(ENDED)).isFalse();
    }

    @Test
    void everyPauseOfTheScopeIsTombstonedWhenItWasPausedMoreThanOnce() {
        singleMember();
        var state = new HazelcastOversightState(members[0], TIMEOUT);
        state.pauseScope(ENDED);
        state.pauseScope(ENDED);
        state.pauseScope(ENDED);

        index(members[0]).endScope(ENDED);

        assertThat(document(members[0]).live(Target.scope(ENDED))).isEmpty();
        assertThat(document(members[0]).tombstones()).hasSize(3);
    }

    @Test
    void endingAScopeWhileNoPauseDocumentExistsIsANoOpSuccess() {
        singleMember();
        populate(KEPT, "subject-1");
        members[0].instance().getMap(PrivacyCluster.OVERSIGHT_MAP).clear();

        index(members[0]).endScope(KEPT);

        assertThat(members[0].instance().getMap(PrivacyCluster.OVERSIGHT_MAP).isEmpty()).isTrue();
        assertThat(members[0].instance().getMap(PrivacyCluster.IDENTITY_MAP).isEmpty()).isTrue();
    }

    @Test
    void aScopeWithNoLivePauseWritesNothing() {
        singleMember();
        var state = new HazelcastOversightState(members[0], TIMEOUT);
        state.pauseTool("search");
        Object raw = members[0].instance().getMap(PrivacyCluster.OVERSIGHT_MAP)
                .get(members[0].keyedNames().mapKey(PrivacyCluster.OVERSIGHT_MAP, PauseDocument.CANONICAL));

        index(members[0]).endScope(ENDED);

        assertThat(members[0].instance().getMap(PrivacyCluster.OVERSIGHT_MAP)
                .get(members[0].keyedNames().mapKey(PrivacyCluster.OVERSIGHT_MAP, PauseDocument.CANONICAL)))
                .isEqualTo(raw);
    }

    /** A document whose first look is gated: older than the cold-start gate, so a cold index holds nothing. */
    private static Doc oldDocumentPausing(String scopeId) {
        Map<Id, Target> elements = new HashMap<>();
        elements.put(new Id(1, 1), Target.scope(scopeId));
        return new Doc(7, 0, Instant.parse("2020-01-01T00:00:00Z").toEpochMilli(), "old", elements, Set.of());
    }

    @Test
    void whenNoDocumentWasAcceptedTheDataIsErasedThePauseIsKeptAndTheCallThrowsUnavailable() {
        singleMember();
        populate(ENDED, "subject-1");
        PauseDocumentTest.plant(members[0], oldDocumentPausing(ENDED));
        Doc before = document(members[0]);

        assertThatThrownBy(() -> index(members[0]).endScope(ENDED)).isInstanceOf(IllegalStateException.class)
                .isNotInstanceOf(ClusterOperationRefusedException.class)
                .isNotInstanceOf(ClusterLockTimeoutException.class);

        assertThat(members[0].instance().getMap(PrivacyCluster.IDENTITY_MAP).isEmpty()).as("data erased").isTrue();
        assertThat(members[0].instance().getMap(PrivacyCluster.BUDGET_MAP).isEmpty()).isTrue();
        assertThat(new HazelcastApprovalStore(members[0], TIMEOUT).find("approval-" + ENDED)).isEmpty();
        assertThat(document(members[0])).as("the pause is kept").isEqualTo(before);
    }

    @Test
    void aStaleDocumentKeepsThePauseErasesTheDataAndThrowsUnavailable() {
        singleMember();
        var state = new HazelcastOversightState(members[0], TIMEOUT);
        state.pauseScope(ENDED);
        var index = index(members[0]);
        index.endScope("another-scope");
        populate(ENDED, "subject-1");
        // A replay of an older document: this index has accepted a higher order, so what it finds is behind.
        Doc replay = new Doc(0, 0, System.currentTimeMillis(), "replay", Map.of(new Id(1, 1), Target.scope(ENDED)),
                Set.of());
        PauseDocumentTest.plant(members[0], replay);

        assertThatThrownBy(() -> index.endScope(ENDED)).isInstanceOf(IllegalStateException.class)
                .isNotInstanceOf(ClusterOperationRefusedException.class);

        assertThat(members[0].instance().getMap(PrivacyCluster.IDENTITY_MAP).isEmpty()).as("data erased").isTrue();
        assertThat(document(members[0]).live(Target.scope(ENDED))).as("the pause is kept").hasSize(1);
    }

    @Test
    void aSweepFailureKeepsThePauseAndThrowsTheSweepsRefusal() {
        singleMember();
        populate(ENDED, "subject-1");
        PrivacyCluster cluster = members[0];
        var index = index(cluster);
        index.maps = name -> {
            if (!PrivacyCluster.BUDGET_MAP.equals(name)) {
                return cluster.instance().getMap(name);
            }
            return (IMap<String, ?>) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[] {IMap.class}, (proxy, method, args) -> {
                        if (method.getName().equals("tryLock")) {
                            throw new IllegalArgumentException("lease too long");
                        }
                        return invokeOn(method, cluster.instance().getMap(name), args);
                    });
        };

        assertThatThrownBy(() -> index.endScope(ENDED)).isInstanceOf(ClusterOperationRefusedException.class);

        assertThat(document(cluster).live(Target.scope(ENDED))).hasSize(1);
        assertThat(cluster.instance().getMap(PrivacyCluster.IDENTITY_MAP).isEmpty()).isTrue();
    }

    @Test
    void aReplayedOlderDocumentAtAColdIndexDoesNotLiftOtherScopesAndEndScopeTombstonesOnlyTheEndedScopesIds() {
        singleMember();
        Map<Id, Target> elements = new HashMap<>();
        elements.put(new Id(1, 1), Target.scope(ENDED));
        elements.put(new Id(1, 2), Target.scope(KEPT));
        elements.put(new Id(1, 3), Target.tool("search"));
        elements.put(new Id(1, 4), Target.ALL);
        PauseDocumentTest.plant(members[0], new Doc(3, 0, System.currentTimeMillis(), "replay", elements,
                Set.of(new Id(1, 4))));

        index(members[0]).endScope(ENDED);

        Doc paused = document(members[0]);
        assertThat(paused.live(Target.scope(ENDED))).isEmpty();
        assertThat(paused.live(Target.scope(KEPT))).hasSize(1);
        assertThat(paused.live(Target.tool("search"))).hasSize(1);
        assertThat(paused.live(Target.ALL)).as("tombstoned in the replay, stays so").isEmpty();
        assertThat(paused.tombstones()).containsExactlyInAnyOrder(new Id(1, 1), new Id(1, 4));
    }

    @Test
    void anUnverifiedMemberKeepsThePauseAndTheCallThrowsTheClusterCodeAfterTheDataIsErased() {
        // Two keyed members and a third that holds no key: inside the grace the cluster still serves, with one unverified.
        String name = TestMembers.uniqueName("scope-end-unverified");
        int first = FreePorts.consecutive(3);
        java.util.List<String> all = java.util.List.of("127.0.0.1:" + first, "127.0.0.1:" + (first + 1),
                "127.0.0.1:" + (first + 2));
        var fixture = new MemberCardTest.Fixture();
        com.hazelcast.core.HazelcastInstance outsider = null;
        try {
            var settings = MemberCardTest.Fixture.settings(ClusterSecuritySettings.UnverifiedMemberAction.REFUSE);
            PrivacyCluster a = fixture.keyed(name, first, all, TestMembers.KEYS, settings,
                    new MemberCardTest.Fixture.Metrics(), new RecordingLogger(), false);
            PrivacyCluster b = fixture.keyed(name, first + 1, all, TestMembers.KEYS, settings,
                    new MemberCardTest.Fixture.Metrics(), new RecordingLogger(), false);
            outsider = MemberCardTest.Fixture.plain(name, first + 2, all);
            assertThat(MemberCardTest.Fixture.awaitMembers(a, 3)).isTrue();
            assertThat(MemberCardTest.Fixture.awaitMembers(b, 3)).isTrue();
            a.memberCards().orElseThrow().tick();
            b.memberCards().orElseThrow().tick();
            assertThat(a.securityStatus().serving()).isTrue();
            assertThat(a.securityStatus().unverifiedCount()).isEqualTo(1);
            var state = new HazelcastOversightState(a, TIMEOUT);
            state.pauseScope(ENDED);
            new HazelcastScopeBudget(a, TIMEOUT).tryRead(ENDED, "subject-1", 5);

            assertThatThrownBy(() -> index(a).endScope(ENDED)).isInstanceOf(ClusterOperationRefusedException.class)
                    .satisfies(refusal -> assertThat(((ClusterOperationRefusedException) refusal).code())
                            .isEqualTo("CLUSTER_OPERATION_REFUSED"));

            assertThat(a.instance().getMap(PrivacyCluster.BUDGET_MAP).isEmpty()).as("data erased").isTrue();
            assertThat(document(a).live(Target.scope(ENDED))).as("the pause is kept").hasSize(1);
            state.close();
        } finally {
            if (outsider != null) {
                outsider.shutdown();
            }
            fixture.closeAll();
        }
    }

    @Test
    void aLockHeldOnTheDocumentExhaustsTheDeadlineKeepsThePauseAndThrowsTheLockTimeout() throws Exception {
        singleMember();
        Duration timeout = Duration.ofSeconds(1);
        var state = new HazelcastOversightState(members[0], timeout);
        state.pauseScope(ENDED);
        populate(ENDED, "subject-1");
        PrivacyCluster cluster = members[0];
        IMap<String, Object> pauses = cluster.instance().getMap(PrivacyCluster.OVERSIGHT_MAP);
        String key = cluster.keyedNames().mapKey(PrivacyCluster.OVERSIGHT_MAP, PauseDocument.CANONICAL);
        java.util.concurrent.CountDownLatch locked = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        Thread holder = new Thread(() -> {
            pauses.lock(key);
            locked.countDown();
            try {
                release.await();
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        });
        holder.start();
        assertThat(locked.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        try {
            var index = new ScopeIdentityIndex(cluster, PrivacyMetrics.none(), timeout);
            long start = System.nanoTime();
            assertThatThrownBy(() -> index.endScope(ENDED)).isInstanceOf(ClusterLockTimeoutException.class);
            long elapsed = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            assertThat(elapsed).as("one shared deadline, the pause step included").isLessThan(1700);
        } finally {
            pauses.forceUnlock(key);
            release.countDown();
            holder.join();
        }

        assertThat(cluster.instance().getMap(PrivacyCluster.IDENTITY_MAP).isEmpty()).as("data erased").isTrue();
        assertThat(document(cluster).live(Target.scope(ENDED))).as("the pause is kept").isNotEmpty();
    }

    @Test
    void underTheHundredMillisecondFloorThePauseStepIsSkippedAndCountedAsALockTimeout() {
        singleMember();
        var state = new HazelcastOversightState(members[0], TIMEOUT);
        state.pauseScope(ENDED);

        assertThatThrownBy(() -> new ScopeIdentityIndex(members[0], PrivacyMetrics.none(), Duration.ofMillis(100))
                .endScope(ENDED)).isInstanceOf(ClusterLockTimeoutException.class);

        assertThat(document(members[0]).live(Target.scope(ENDED))).hasSize(1);
    }

    @Test
    void anOversizeEncodeIsRefusedAndThePauseIsKept() {
        singleMember();
        Map<Id, Target> elements = new HashMap<>();
        elements.put(new Id(1, 1), Target.scope(ENDED));
        Set<Id> tombstones = new HashSet<>();
        for (int i = 0; i < PauseDocument.FORMAT_BOUND; i++) {
            tombstones.add(new Id(2, i));
        }
        PauseDocumentTest.plant(members[0], new Doc(3, 0, System.currentTimeMillis(), "full", elements, tombstones));
        Doc before = document(members[0]);

        assertThatThrownBy(() -> index(members[0]).endScope(ENDED)).isInstanceOf(ClusterOperationRefusedException.class);

        assertThat(document(members[0])).isEqualTo(before);
        assertThat(document(members[0]).live(Target.scope(ENDED))).hasSize(1);
    }
}

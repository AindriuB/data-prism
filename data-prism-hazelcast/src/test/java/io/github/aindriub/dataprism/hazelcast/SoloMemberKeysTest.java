package io.github.aindriub.dataprism.hazelcast;

import io.github.aindriub.dataprism.annotations.PrivacyNamespace;
import io.github.aindriub.dataprism.core.model.PrivacyContext;
import io.github.aindriub.dataprism.core.model.PrivacyScopeType;
import io.github.aindriub.dataprism.core.model.PseudonymisationVersion;
import io.github.aindriub.dataprism.core.refusal.ClusterLockTimeoutException;
import io.github.aindriub.dataprism.hazelcast.PrivacyClusterRefusal.Code;
import io.github.aindriub.dataprism.hazelcast.seal.ClusterKeys;
import io.github.aindriub.dataprism.hazelcast.seal.KeyedNames;
import io.github.aindriub.dataprism.oversight.ApprovalRequest.Status;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.OptionalInt;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Solo members (join mode none, OD-215-8 (a)): with supplied keys every keyed map key and lock key comes from the
 * supplied name key; without keys the member draws a random cluster key and name key for its process, and nothing
 * about them is shared, logged or shown. A joining membership never gets generated keys.
 */
class SoloMemberKeysTest {

    private static final char NUL = 0;
    private static final String SCOPE = "scope-solo";
    private static final String SUBJECT = "subject-solo";
    private static final String APPROVAL = "approval-solo-91c";
    private static final String PRINCIPAL = "principal-solo";
    private static final Duration TIMEOUT = Duration.ofMillis(300);

    private static PrivacyCluster solo(ClusterKeys keys) {
        var membership = ClusterMembership.none(TestMembers.uniqueName("solo-keys"))
                .withPort(FreePorts.consecutive(1));
        return keys == null
                ? PrivacyCluster.embedded(membership, true)
                : PrivacyCluster.embedded(membership, true, OptionalInt.empty(), PendingSweeper.DEFAULT_INTERVAL, keys);
    }

    private static PrivacyContext scope() {
        return new PrivacyContext(SCOPE, PrivacyScopeType.CASE, "DEFAULT", "test",
                Instant.now().plus(1, ChronoUnit.HOURS), PseudonymisationVersion.HMAC_SHA256_V1);
    }

    /** Writes one entry into every map that holds scoped or keyed state. */
    private static void writeEverything(PrivacyCluster cluster) {
        new CachingSyntheticValueSource((subject, namespace, context) -> "generated-" + subject, cluster, 
                io.github.aindriub.dataprism.core.metrics.PrivacyMetrics.none(), TIMEOUT)
                .syntheticValue(SUBJECT, PrivacyNamespace.PERSON_NAME, scope());
        new HazelcastScopeBudget(cluster, TIMEOUT).tryRead(SCOPE, SUBJECT, 5);
        new HazelcastApprovalStore(cluster, TIMEOUT).create(new io.github.aindriub.dataprism.oversight.ApprovalRequest(
                APPROVAL, io.github.aindriub.dataprism.oversight.ApprovalRequest.Kind.TOOL_CALL, PRINCIPAL, "client",
                SCOPE, "search", "fp", "PERSON_NAME", "synthetic", "audit", "CASE-1", TestMembers.NOW,
                TestMembers.NOW.plus(1, ChronoUnit.HOURS), Status.PENDING, null, null));
        new HazelcastCallerRateLimiter(cluster, TIMEOUT).tryAcquire(PRINCIPAL, 5, Duration.ofMinutes(1),
                TestMembers.NOW);
        new HazelcastOversightState(cluster, TIMEOUT).pauseScope(SCOPE);
    }

    private static Set<Object> keysOf(PrivacyCluster cluster, String map) {
        return new TreeSet<>(cluster.instance().getMap(map).keySet());
    }

    @Test
    void suppliedKeysDeriveEveryMapKeyAndLockKeyFromTheSuppliedNameKey() throws Exception {
        try (PrivacyCluster cluster = solo(TestMembers.KEYS)) {
            writeEverything(cluster);
            KeyedNames expected = new KeyedNames(TestMembers.KEYS);
            assertThat(keysOf(cluster, PrivacyCluster.IDENTITY_MAP)).containsExactly(
                    expected.scoped(PrivacyCluster.IDENTITY_MAP, SCOPE, SCOPE + NUL + "PERSON_NAME" + NUL + SUBJECT));
            assertThat(keysOf(cluster, PrivacyCluster.REIDENTIFICATION_MAP)).containsExactly(
                    expected.scoped(PrivacyCluster.REIDENTIFICATION_MAP, SCOPE,
                            SCOPE + NUL + "PERSON_NAME" + NUL + "generated-" + SUBJECT));
            String budgetKey = expected.scoped(PrivacyCluster.BUDGET_MAP, SCOPE, SCOPE + NUL + SUBJECT);
            assertThat(keysOf(cluster, PrivacyCluster.BUDGET_MAP)).containsExactly(budgetKey);
            assertThat(keysOf(cluster, PrivacyCluster.APPROVAL_MAP)).containsExactly(
                    expected.scoped(PrivacyCluster.APPROVAL_MAP, SCOPE, SCOPE + NUL + APPROVAL));
            assertThat(keysOf(cluster, PrivacyCluster.APPROVAL_ID_MAP)).containsExactly(
                    expected.mapKey(PrivacyCluster.APPROVAL_ID_MAP, APPROVAL));
            assertThat(keysOf(cluster, PrivacyCluster.APPROVAL_PENDING_MAP)).containsExactly(
                    expected.mapKey(PrivacyCluster.APPROVAL_PENDING_MAP, "TOOL_CALL" + NUL + "=" + PRINCIPAL));
            assertThat(keysOf(cluster, PrivacyCluster.CALLER_RATE_MAP)).containsExactly(
                    expected.mapKey(PrivacyCluster.CALLER_RATE_MAP, PRINCIPAL));
            // The oversight map holds the one pause document, under the keyed name of its fixed canonical string.
            assertThat(keysOf(cluster, PrivacyCluster.OVERSIGHT_MAP)).containsExactly(
                    expected.mapKey(PrivacyCluster.OVERSIGHT_MAP, PauseDocument.CANONICAL));

            // The lock key is the keyed name too: a lock held on the recomputed budget key stops the read.
            assertThat(cluster.keyedNames().mapKey(PrivacyCluster.CALLER_RATE_MAP, PRINCIPAL))
                    .isEqualTo(expected.mapKey(PrivacyCluster.CALLER_RATE_MAP, PRINCIPAL));
            refusesWhileLocked(cluster, PrivacyCluster.BUDGET_MAP, budgetKey,
                    () -> new HazelcastScopeBudget(cluster, TIMEOUT).tryRead(SCOPE, SUBJECT, 5));
        }
    }

    private static void refusesWhileLocked(PrivacyCluster cluster, String map, String key, Runnable call)
            throws Exception {
        var held = cluster.instance().getMap(map);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread holder = new Thread(() -> {
            held.lock(key);
            locked.countDown();
            try {
                release.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                held.unlock(key);
            }
        });
        holder.start();
        ExecutorService caller = Executors.newSingleThreadExecutor();
        try {
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            Future<?> running = caller.submit(call);
            Throwable failure = null;
            try {
                running.get(10, TimeUnit.SECONDS);
            } catch (ExecutionException failed) {
                failure = failed.getCause();
            }
            assertThat(failure).isInstanceOf(ClusterLockTimeoutException.class);
        } finally {
            release.countDown();
            holder.join();
            caller.shutdownNow();
        }
    }

    @Test
    void twoMembersWithoutKeysStartedOneAfterTheOtherGiveDifferentNamesForTheSameInputs() {
        String first;
        String second;
        try (PrivacyCluster one = solo(null)) {
            first = one.keyedNames().scoped(PrivacyCluster.BUDGET_MAP, SCOPE, SCOPE + NUL + SUBJECT);
            assertThat(one.keys()).isEmpty();
            assertThat(one.keyIds()).isEmpty();
        }
        try (PrivacyCluster two = solo(null)) {
            second = two.keyedNames().scoped(PrivacyCluster.BUDGET_MAP, SCOPE, SCOPE + NUL + SUBJECT);
        }
        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void aMemberWithoutKeysPutsNoCanonicalInputInAnyKey() {
        try (PrivacyCluster cluster = solo(null)) {
            writeEverything(cluster);
            List<String> secrets = List.of(SCOPE, SUBJECT, APPROVAL, PRINCIPAL, "generated-" + SUBJECT);
            int seen = 0;
            for (String map : List.of(PrivacyCluster.IDENTITY_MAP, PrivacyCluster.REIDENTIFICATION_MAP,
                    PrivacyCluster.BUDGET_MAP, PrivacyCluster.APPROVAL_MAP, PrivacyCluster.APPROVAL_ID_MAP,
                    PrivacyCluster.APPROVAL_PENDING_MAP, PrivacyCluster.CALLER_RATE_MAP,
                    PrivacyCluster.OVERSIGHT_MAP)) {
                Set<Object> keys = keysOf(cluster, map);
                assertThat(keys).as(map).isNotEmpty();
                for (Object key : keys) {
                    seen++;
                    for (String secret : secrets) {
                        assertThat((String) key).as("%s key", map).doesNotContain(secret);
                    }
                }
            }
            assertThat(seen).isGreaterThanOrEqualTo(8);
        }
    }

    @Test
    void generatedKeyMaterialAppearsInNoLogLineExceptionMessageOrToString() throws Exception {
        List<String> logged = new CopyOnWriteArrayList<>();
        Handler capture = new Handler() {
            @Override
            public void publish(LogRecord record) {
                logged.add(record.getLoggerName() + " " + record.getMessage() + " "
                        + java.util.Arrays.toString(record.getParameters()));
                if (record.getThrown() != null) {
                    logged.add(String.valueOf(record.getThrown().getMessage()));
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        Logger root = Logger.getLogger("");
        root.addHandler(capture);
        List<String> messages = new ArrayList<>();
        List<String> shown = new ArrayList<>();
        ClusterKeys generated;
        try (PrivacyCluster cluster = solo(null)) {
            generated = effectiveKeys(cluster);
            writeEverything(cluster);
            shown.add(cluster.toString());
            shown.add(cluster.keyedNames().toString());
            shown.add(cluster.securitySettings().toString());
            shown.add(String.valueOf(cluster.keyIds()));
            shown.add(generated.toString());
            // An exception from a refused call must not carry key material either.
            Throwable refused = catchThrowable(() -> PrivacyCluster.embedded(
                    ClusterMembership.tcpIp(TestMembers.uniqueName("joiner"), List.of("127.0.0.1:1")).withPort(1)
                            .withInterface("127.0.0.1"), false));
            messages.add(String.valueOf(refused));
            messages.add(String.valueOf(refused == null ? null : refused.getMessage()));
        } finally {
            root.removeHandler(capture);
        }
        List<String> everything = new ArrayList<>(logged);
        everything.addAll(shown);
        everything.addAll(messages);
        for (byte[] material : List.of(generated.current().material(), generated.nameKeyMaterial())) {
            for (String form : forms(material)) {
                for (String text : everything) {
                    assertThat(text).doesNotContain(form);
                }
            }
        }
        assertThat(logged).as("the capture saw the member's log lines").isNotEmpty();
    }

    private static List<String> forms(byte[] material) {
        return List.of(Base64.getEncoder().encodeToString(material),
                Base64.getUrlEncoder().withoutPadding().encodeToString(material),
                HexFormat.of().formatHex(material), java.util.Arrays.toString(material));
    }

    /** The keys a cluster's registered sealer holds, reached by reflection: the cluster itself keeps no copy. */
    private static ClusterKeys effectiveKeys(PrivacyCluster cluster) throws ReflectiveOperationException {
        Object sealer = cluster.instance().getUserContext().get("dataprism.cluster.sealer");
        Field field = sealer.getClass().getDeclaredField("keys");
        field.setAccessible(true);
        return (ClusterKeys) field.get(sealer);
    }

    @Test
    void aReWrapOfAKeylessInstanceKeepsSeeingWhatTheFirstWrapperWrote() {
        try (PrivacyCluster base = solo(null)) {
            var inst = base.instance();
            PrivacyCluster a = PrivacyCluster.using(inst, false);
            new HazelcastOversightState(a, TIMEOUT).pauseScope("s");
            new HazelcastScopeBudget(a, TIMEOUT).tryRead(SCOPE, SUBJECT, 5);
            new HazelcastApprovalStore(a, TIMEOUT).create(new io.github.aindriub.dataprism.oversight.ApprovalRequest(
                    APPROVAL, io.github.aindriub.dataprism.oversight.ApprovalRequest.Kind.TOOL_CALL, PRINCIPAL,
                    "client", SCOPE, "search", "fp", "PERSON_NAME", "synthetic", "audit", "CASE-1",
                    TestMembers.NOW, TestMembers.NOW.plus(1, ChronoUnit.HOURS), Status.PENDING, null, null));
            // Side by side: the same names, so the same counts and the same approvals.
            PrivacyCluster side = PrivacyCluster.using(inst, false);
            assertThat(new HazelcastScopeBudget(side, TIMEOUT).reads(SCOPE, SUBJECT)).isEqualTo(1);
            assertThat(new HazelcastApprovalStore(side, TIMEOUT).find(APPROVAL)).isPresent();
            side.close();
            // The reviewer's repro: close the first wrapper, wrap again.
            a.close();
            PrivacyCluster b = PrivacyCluster.using(inst, false);
            assertThat(new HazelcastOversightState(b, TIMEOUT).scopePaused("s")).isTrue();
            assertThat(new HazelcastScopeBudget(b, TIMEOUT).reads(SCOPE, SUBJECT)).isEqualTo(1);
            assertThat(new HazelcastApprovalStore(b, TIMEOUT).find(APPROVAL)).isPresent();
            assertThat(b.keyedNames().mapKey(PrivacyCluster.CALLER_RATE_MAP, "x"))
                    .isEqualTo(base.keyedNames().mapKey(PrivacyCluster.CALLER_RATE_MAP, "x"));
            b.close();
        }
    }

    @Test
    void aSecondWrapperOfOneInstanceWithOtherKeyIdsIsRefused() {
        try (PrivacyCluster base = solo(TestMembers.KEYS)) {
            var other = TestMembers.keys("other-cluster", "other-name");
            Throwable refused = catchThrowable(() -> PrivacyCluster.using(base.instance(), false,
                    PendingSweeper.DEFAULT_INTERVAL, other));
            assertThat(refused).isInstanceOf(IllegalStateException.class);
            PrivacyCluster same = PrivacyCluster.using(base.instance(), false, PendingSweeper.DEFAULT_INTERVAL,
                    TestMembers.KEYS);
            same.close();
        }
    }

    @Test
    void aJoiningMembershipNeverGetsGeneratedKeys() {
        Throwable refused = catchThrowable(() -> PrivacyCluster.embedded(
                ClusterMembership.tcpIp(TestMembers.uniqueName("joiner"), List.of("127.0.0.1:1")).withPort(1)
                        .withInterface("127.0.0.1"), false));
        assertThat(refused).isInstanceOf(PrivacyClusterRefusal.class);
        assertThat(((PrivacyClusterRefusal) refused).code()).isEqualTo(Code.MISSING_CLUSTER_KEY);
    }
}

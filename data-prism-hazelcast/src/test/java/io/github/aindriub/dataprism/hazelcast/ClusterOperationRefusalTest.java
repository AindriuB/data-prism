package io.github.aindriub.dataprism.hazelcast;

import com.hazelcast.config.Config;
import com.hazelcast.map.IMap;
import io.github.aindriub.dataprism.core.refusal.ClusterOperationRefusedException;
import io.github.aindriub.dataprism.oversight.ApprovalRequest;
import io.github.aindriub.dataprism.oversight.ApprovalRequest.Kind;
import io.github.aindriub.dataprism.oversight.ApprovalRequest.Status;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.event.Level;

import java.time.Duration;
import java.util.List;
import java.util.OptionalInt;

import static io.github.aindriub.dataprism.hazelcast.TestMembers.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A lock Hazelcast rejects (a configured maximum lease below the one requested) is refused with
 * {@code CLUSTER_OPERATION_REFUSED}, the cause chained and exactly one log line that names only the cause's class.
 */
class ClusterOperationRefusalTest {

    private static final String SENTINEL = "sentinel-key-x-1";

    private PrivacyCluster cluster;
    private HazelcastApprovalStore refusing;
    private HazelcastApprovalStore accepting;

    @BeforeEach
    void start() {
        int port = FreePorts.consecutive(1);
        Config config = ClusterMembership.tcpIp(TestMembers.uniqueName("refusal"), List.of("127.0.0.1:" + port))
                .withPort(port).withInterface("127.0.0.1").toConfig();
        // Below the 10 s lease of a two-second timeout; above the 500 ms lease of 100 ms.
        config.setProperty("hazelcast.lock.max.lease.time.seconds", "1");
        cluster = PrivacyCluster.embedded(config, false, OptionalInt.empty());
        refusing = new HazelcastApprovalStore(cluster, Duration.ofSeconds(2));
        accepting = new HazelcastApprovalStore(cluster, Duration.ofMillis(100));
    }

    @AfterEach
    void stop() {
        cluster.close();
    }

    private static ApprovalRequest pending(String id) {
        return TestMembers.request(id, "scope-a", Status.PENDING);
    }

    private static void assertRefusedWithCause(Throwable thrown) {
        assertThat(thrown).isInstanceOfSatisfying(ClusterOperationRefusedException.class, refused -> {
            assertThat(refused.code()).isEqualTo("CLUSTER_OPERATION_REFUSED");
            assertThat(refused.getCause()).isNotNull();
            assertThat(refused.getMessage()).doesNotContain(refused.getCause().getMessage());
        });
    }

    @Test
    void theReadBudgetPropagatesARefusedLockInsteadOfAnswering() {
        var budget = new HazelcastScopeBudget(cluster, Duration.ofSeconds(2));
        assertThatThrownBy(() -> budget.tryRead("scope-a", "subject-1", 5))
                .satisfies(ClusterOperationRefusalTest::assertRefusedWithCause);
    }

    @Test
    void theCallerRateLimiterPropagatesARefusedLock() {
        var limiter = new HazelcastCallerRateLimiter(cluster, Duration.ofSeconds(2));
        assertThatThrownBy(() -> limiter.tryAcquire("alice", 5, Duration.ofMinutes(1), NOW))
                .satisfies(ClusterOperationRefusalTest::assertRefusedWithCause);
    }

    @Test
    void createRefusesWithTheCause() {
        assertThatThrownBy(() -> refusing.create(pending("c-1"), 5))
                .satisfies(ClusterOperationRefusalTest::assertRefusedWithCause);
        assertThatThrownBy(() -> refusing.create(pending("c-2")))
                .satisfies(ClusterOperationRefusalTest::assertRefusedWithCause);
    }

    @Test
    void approveRefusesWithTheCause() {
        accepting.create(pending("a-1"));
        assertThatThrownBy(() -> refusing.approve("a-1", "bob", NOW.plusSeconds(1)))
                .satisfies(ClusterOperationRefusalTest::assertRefusedWithCause);
    }

    @Test
    void consumeApprovedRefusesWithTheCause() {
        accepting.create(pending("k-1"));
        accepting.approve("k-1", "bob", NOW.plusSeconds(1));
        assertThatThrownBy(() -> refusing.consumeApproved(Kind.TOOL_CALL, "alice", "scope-a", "search", "fp-k-1",
                NOW.plusSeconds(2))).satisfies(ClusterOperationRefusalTest::assertRefusedWithCause);
    }

    // ----- one WARN line per refusal: the code and the cause's class, never its message or a stack trace

    @Test
    void aBuiltRefusalLogsTheCodeAndTheCauseClassOnly() throws Exception {
        var recorder = new RecordingLogger();
        try (var ignored = ClusterRefusals.captureTo(recorder.logger())) {
            var refused = ClusterRefusals.refused(new IllegalStateException(SENTINEL));
            assertThat(refused.getCause()).isInstanceOf(IllegalStateException.class);
        }
        assertThat(recorder.lines()).hasSize(1).allSatisfy(line -> {
            assertThat(line.level()).isEqualTo(Level.WARN);
            assertThat(line.message()).contains("CLUSTER_OPERATION_REFUSED", "IllegalStateException")
                    .doesNotContain(SENTINEL);
            assertThat(line.withThrowable()).isFalse();
        });
    }

    @Test
    void aRefusedLockInTheStoreLogsOnceAndASecondLayerDoesNotLogAgain() throws Exception {
        var recorder = new RecordingLogger();
        try (var ignored = ClusterRefusals.captureTo(recorder.logger())) {
            // create takes the lock through HeldLocks.take, which passes the refusal on unchanged.
            assertThatThrownBy(() -> refusing.create(pending("l-1"), 5))
                    .isInstanceOf(ClusterOperationRefusedException.class);
        }
        assertOneRefusalLine(recorder);
    }

    @Test
    void aRefusedLockOnTheBudgetAndTheLimiterEachLogOnce() throws Exception {
        var recorder = new RecordingLogger();
        try (var ignored = ClusterRefusals.captureTo(recorder.logger())) {
            assertThatThrownBy(() -> new HazelcastScopeBudget(cluster, Duration.ofSeconds(2))
                    .tryRead("scope-a", "subject-1", 5)).isInstanceOf(ClusterOperationRefusedException.class);
            assertOneRefusalLine(recorder);
            assertThatThrownBy(() -> new HazelcastCallerRateLimiter(cluster, Duration.ofSeconds(2))
                    .tryAcquire("alice", 5, Duration.ofMinutes(1), NOW))
                    .isInstanceOf(ClusterOperationRefusedException.class);
        }
        assertThat(recorder.lines()).hasSize(2);
    }

    @Test
    void aFailedClusterCallInTheStoreLogsOnce() throws Exception {
        IMap<String, String> pendingSets = cluster.instance().getMap(PrivacyCluster.APPROVAL_PENDING_MAP);
        pendingSets.put(HazelcastApprovalStore.pendingKey(Kind.TOOL_CALL, "alice"), "garbage");
        var recorder = new RecordingLogger();
        try (var ignored = ClusterRefusals.captureTo(recorder.logger())) {
            assertThatThrownBy(() -> accepting.create(pending("g-1"), 5))
                    .isInstanceOfSatisfying(ClusterOperationRefusedException.class,
                            refused -> assertThat(refused.getCause()).isNotNull());
        }
        assertOneRefusalLine(recorder);
    }

    private static void assertOneRefusalLine(RecordingLogger recorder) {
        assertThat(recorder.lines()).hasSize(1).allSatisfy(line -> {
            assertThat(line.level()).isEqualTo(Level.WARN);
            assertThat(line.message()).contains("CLUSTER_OPERATION_REFUSED", "Exception");
            assertThat(line.message()).doesNotContain("scope-a", "alice", "subject-1", "\tat ");
            assertThat(line.withThrowable()).isFalse();
        });
    }
}

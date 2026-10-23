package io.github.aindriub.dataprism.hazelcast;

import com.hazelcast.client.HazelcastClient;
import com.hazelcast.client.config.ClientConfig;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.map.IMap;
import io.github.aindriub.dataprism.core.refusal.ClusterLockTimeoutException;
import io.github.aindriub.dataprism.hazelcast.seal.KeyedNames;
import io.github.aindriub.dataprism.oversight.ApprovalRequest.Status;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.OptionalInt;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static io.github.aindriub.dataprism.hazelcast.TestMembers.NOW;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * T11, the keys share: a lock a Hazelcast client holds on the keyed name of a budget entry, of an approval-id claim
 * and of a caller-rate entry (each computed here from the canonical inputs, not read from the code under test) is the
 * lock the store waits on. The matching call refuses with {@code CLUSTER_LOCK_TIMEOUT} within the lock timeout, no
 * thread stays blocked, and once the client lets go the same call succeeds.
 */
class KeyedLockHoldTest {

    private static final Duration TIMEOUT = Duration.ofMillis(300);
    private static final char NUL = 0;

    private PrivacyCluster cluster;
    private HazelcastInstance client;
    private ExecutorService caller;
    private KeyedNames names;

    @BeforeEach
    void start() {
        int port = FreePorts.consecutive(1);
        cluster = TestMembers.member(TestMembers.uniqueName("keyed-lock"), port, List.of("127.0.0.1:" + port),
                OptionalInt.empty());
        names = new KeyedNames(TestMembers.KEYS);
        ClientConfig config = new ClientConfig();
        config.setClusterName(cluster.instance().getConfig().getClusterName());
        config.getNetworkConfig().addAddress("127.0.0.1:" + port);
        client = HazelcastClient.newHazelcastClient(config);
        caller = Executors.newSingleThreadExecutor();
    }

    @AfterEach
    void stop() {
        caller.shutdownNow();
        if (client != null) {
            client.shutdown();
        }
        cluster.close();
    }

    @Test
    void aClientHoldingTheKeyedBudgetLockMakesTheReadRefuse() throws Exception {
        String key = names.scoped(PrivacyCluster.BUDGET_MAP, "scope-1", "scope-1" + NUL + "subject-1");
        var budget = new HazelcastScopeBudget(cluster, TIMEOUT);
        refusesWhileTheClientHolds(PrivacyCluster.BUDGET_MAP, key, () -> budget.tryRead("scope-1", "subject-1", 5));
        assertThat(budget.tryRead("scope-1", "subject-1", 5)).isTrue();
    }

    @Test
    void aClientHoldingTheKeyedClaimLockMakesTheCreationRefuse() throws Exception {
        String key = names.mapKey(PrivacyCluster.APPROVAL_ID_MAP, "request-1");
        var approvals = new HazelcastApprovalStore(cluster, TIMEOUT);
        refusesWhileTheClientHolds(PrivacyCluster.APPROVAL_ID_MAP, key,
                () -> approvals.create(TestMembers.request("request-1", "scope-1", Status.PENDING)));
        assertThat(approvals.create(TestMembers.request("request-1", "scope-1", Status.PENDING)).approvalId())
                .isEqualTo("request-1");
    }

    @Test
    void aClientHoldingTheKeyedCallerRateLockMakesTheCallRefuse() throws Exception {
        String key = names.mapKey(PrivacyCluster.CALLER_RATE_MAP, "alice");
        var limiter = new HazelcastCallerRateLimiter(cluster, TIMEOUT);
        refusesWhileTheClientHolds(PrivacyCluster.CALLER_RATE_MAP, key,
                () -> limiter.tryAcquire("alice", 1, Duration.ofMinutes(10), NOW));
        assertThat(limiter.tryAcquire("alice", 1, Duration.ofMinutes(10), NOW)).isTrue();
    }

    @Test
    void aClientHoldingTheRawIdOfAClaimBlocksNothing() throws Exception {
        // The lock key is not the id: holding the id itself locks nothing the store waits on.
        var approvals = new HazelcastApprovalStore(cluster, TIMEOUT);
        IMap<String, Object> held = client.getMap(PrivacyCluster.APPROVAL_ID_MAP);
        held.lock("request-2");
        try {
            Future<?> creating = caller.submit(
                    () -> approvals.create(TestMembers.request("request-2", "scope-1", Status.PENDING)));
            creating.get(3 * TIMEOUT.toMillis() + 5_000, TimeUnit.MILLISECONDS);
        } finally {
            held.forceUnlock("request-2");
        }
    }

    private interface Call {
        void run() throws Exception;
    }

    private void refusesWhileTheClientHolds(String map, String key, Call call) throws Exception {
        IMap<String, Object> held = client.getMap(map);
        held.lock(key);
        long start = System.nanoTime();
        try {
            Future<?> running = caller.submit(() -> {
                call.run();
                return null;
            });
            Throwable failure = null;
            try {
                running.get(3 * TIMEOUT.toMillis() + 2_000, TimeUnit.MILLISECONDS);
            } catch (ExecutionException failed) {
                failure = failed.getCause();
            } catch (TimeoutException hung) {
                throw new AssertionError("the call stayed blocked on a lock the client holds");
            }
            long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            assertThat(failure).isInstanceOf(ClusterLockTimeoutException.class);
            assertThat(((ClusterLockTimeoutException) failure).code()).isEqualTo("CLUSTER_LOCK_TIMEOUT");
            assertThat(elapsed).isLessThanOrEqualTo(TIMEOUT.toMillis() + 500);
            assertThat(running.isDone()).as("no thread is left blocked").isTrue();
        } finally {
            held.forceUnlock(key);
        }
    }
}

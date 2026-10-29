package io.github.aindriub.dataprism.hazelcast;

import io.github.aindriub.dataprism.oversight.ApprovalRefusedException;
import io.github.aindriub.dataprism.oversight.ApprovalRequest;
import io.github.aindriub.dataprism.oversight.ApprovalRequest.Kind;
import io.github.aindriub.dataprism.oversight.ApprovalRequest.Status;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static io.github.aindriub.dataprism.hazelcast.TestMembers.NOW;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Many threads on two members race for one decision. Whatever the mechanism, exactly one wins each time. These
 * tests are the proof that a conditional operation, not a lock someone might lose, is what makes it so.
 */
class ApprovalRaceTest {

    private static final int THREADS = 16;
    private static final int ROUNDS = 50;

    /**
     * How long a racing call waits for the entry lock. These tests are about who wins, not how fast: the default
     * bound (2 s) is shorter than the 5 s Hazelcast takes to complete a write that lost its backup acknowledgement
     * to a join-time migration, so with it a stalled holder makes a waiter refuse and fail the test (seen on CI,
     * Java 25). The wait is still bounded; the refusal itself is covered by {@code ClusterLockTimeoutTest}.
     */
    private static final Duration RACE_LOCK_TIMEOUT = Duration.ofSeconds(15);

    private PrivacyCluster[] members;
    private HazelcastApprovalStore[] stores;
    private ExecutorService pool;

    @BeforeEach
    void start() {
        members = TestMembers.pair(TestMembers.uniqueName("race"), OptionalInt.empty());
        stores = new HazelcastApprovalStore[]{new HazelcastApprovalStore(members[0], RACE_LOCK_TIMEOUT),
                new HazelcastApprovalStore(members[1], RACE_LOCK_TIMEOUT)};
        pool = Executors.newFixedThreadPool(THREADS);
    }

    @AfterEach
    void stop() {
        pool.shutdownNow();
        for (PrivacyCluster m : members) {
            m.close();
        }
    }

    private <T> List<T> race(java.util.function.IntFunction<T> call) throws Exception {
        CountDownLatch gate = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>();
        for (int t = 0; t < THREADS; t++) {
            int thread = t;
            futures.add(pool.submit(() -> {
                gate.await();
                return call.apply(thread);
            }));
        }
        gate.countDown();
        List<T> results = new ArrayList<>();
        for (Future<T> f : futures) {
            results.add(f.get(60, TimeUnit.SECONDS));
        }
        return results;
    }

    @Test
    void exactlyOneCallConsumesAnApproval() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            String id = "consume-" + round;
            stores[0].create(TestMembers.request(id, "scope-1", Status.PENDING));
            stores[1].approve(id, "bob", NOW);
            List<Optional<ApprovalRequest>> results = race(thread ->
                    stores[thread % 2].consumeApproved(Kind.TOOL_CALL, "alice", "scope-1", "search", "fp-" + id, NOW));
            assertThat(results).as("round %d", round).filteredOn(Optional::isPresent).hasSize(1);
            assertThat(stores[0].find(id)).get().extracting(ApprovalRequest::status).isEqualTo(Status.CONSUMED);
        }
    }

    /**
     * A holder that stalls for longer than the default lock bound (a lost backup acknowledgement completes only after
     * Hazelcast's 5 s backup timeout, and a loaded runner can pause as long) must not turn a race into refusals: the
     * race stores wait longer than that, and exactly one caller still consumes. The stall is made by holding the
     * entry's lock, so nothing here depends on real timing luck.
     */
    @Test
    void exactlyOneCallConsumesAnApprovalWhileTheHolderStallsPastTheDefaultLockBound() throws Exception {
        String id = "stalled";
        stores[0].create(TestMembers.request(id, "scope-1", Status.PENDING));
        stores[1].approve(id, "bob", NOW);
        var held = members[0].instance().getMap(PrivacyCluster.APPROVAL_MAP);
        String key = TestMembers.SCOPE_KEYS.approval("scope-1", id);
        long stallMillis = ClusterLocks.DEFAULT_TIMEOUT.toMillis() + 1000;
        CountDownLatch locked = new CountDownLatch(1);
        Thread holder = new Thread(() -> {
            held.lock(key);
            locked.countDown();
            try {
                Thread.sleep(stallMillis);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                held.forceUnlock(key);
            }
        });
        holder.start();
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
        try {
            long started = System.nanoTime();
            List<Optional<ApprovalRequest>> results = race(thread ->
                    stores[thread % 2].consumeApproved(Kind.TOOL_CALL, "alice", "scope-1", "search", "fp-" + id, NOW));
            assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started))
                    .as("the callers really waited out the stall").isGreaterThan(ClusterLocks.DEFAULT_TIMEOUT.toMillis());
            assertThat(results).filteredOn(Optional::isPresent).hasSize(1);
            assertThat(stores[0].find(id)).get().extracting(ApprovalRequest::status).isEqualTo(Status.CONSUMED);
        } finally {
            holder.join();
        }
    }

    @Test
    void exactlyOneOfApproveAndRejectDecides() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            String id = "decide-" + round;
            stores[0].create(TestMembers.request(id, "scope-1", Status.PENDING));
            AtomicInteger approved = new AtomicInteger();
            AtomicInteger rejected = new AtomicInteger();
            race(thread -> {
                HazelcastApprovalStore store = stores[thread % 2];
                try {
                    if (thread < THREADS / 2) {
                        store.approve(id, "bob", NOW);
                        approved.incrementAndGet();
                    } else {
                        store.reject(id, "carol", NOW);
                        rejected.incrementAndGet();
                    }
                } catch (ApprovalRefusedException refused) {
                    assertThat(refused.code()).isEqualTo(ApprovalRefusedException.Code.NOT_PENDING);
                }
                return null;
            });
            assertThat(approved.get() + rejected.get()).as("round %d", round).isEqualTo(1);
            Status expected = approved.get() == 1 ? Status.APPROVED : Status.REJECTED;
            assertThat(stores[1].find(id)).get().extracting(ApprovalRequest::status).isEqualTo(expected);
        }
    }
}

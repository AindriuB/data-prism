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
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static io.github.aindriub.dataprism.hazelcast.TestMembers.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Many threads on two members race for one decision. Whatever the mechanism, exactly one wins each time. These
 * tests prove exactly-once under the entry lock and the re-read made under it; they do not prove it survives the
 * loss of the lock (lease expiry, split brain), which is a separate test.
 */
class ApprovalRaceTest {

    private static final int THREADS = 16;
    private static final int ROUNDS = 50;

    /**
     * Required, not a backstop: {@code exactlyOneCallConsumesAnApprovalWhileTheHolderStallsPastTheDefaultLockBound}
     * holds the lock past {@code ClusterLocks.DEFAULT_TIMEOUT} + 1 s, and the waiter must still get it.
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
        return collect(startRace(call));
    }

    private <T> List<Future<T>> startRace(java.util.function.IntFunction<T> call) {
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
        return futures;
    }

    private static <T> List<T> collect(List<Future<T>> futures) throws Exception {
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
     * A holder that keeps the entry lock past the default lock bound (a lost backup acknowledgement completes only
     * after Hazelcast's 5 s backup timeout, and a loaded runner can pause as long) must not turn a race into
     * refusals: the race stores wait longer than that, and exactly one caller still consumes. The holder lets go
     * only when the test says so, so nothing here depends on timing luck.
     */
    @Test
    void exactlyOneCallConsumesAnApprovalWhileTheHolderStallsPastTheDefaultLockBound() throws Exception {
        String id = "stalled";
        stores[0].create(TestMembers.request(id, "scope-1", Status.PENDING));
        stores[1].approve(id, "bob", NOW);
        var held = members[0].instance().getMap(PrivacyCluster.APPROVAL_MAP);
        String key = TestMembers.SCOPE_KEYS.approval("scope-1", id);
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
                held.forceUnlock(key);
            }
        });
        holder.start();
        try {
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            List<Future<Optional<ApprovalRequest>>> running = startRace(thread ->
                    stores[thread % 2].consumeApproved(Kind.TOOL_CALL, "alice", "scope-1", "search", "fp-" + id, NOW));
            // Every caller is still waiting after the default bound has passed: none was refused, none consumed.
            assertThatThrownBy(() -> running.get(0).get(ClusterLocks.DEFAULT_TIMEOUT.toMillis() + 1000,
                    TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            assertThat(running).as("no caller finished while the lock was held").noneMatch(Future::isDone);
            release.countDown();
            List<Optional<ApprovalRequest>> results = collect(running);
            assertThat(results).filteredOn(Optional::isPresent).hasSize(1);
            assertThat(stores[0].find(id)).get().extracting(ApprovalRequest::status).isEqualTo(Status.CONSUMED);
        } finally {
            release.countDown();
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

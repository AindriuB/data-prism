package io.github.aindriub.dataprism.hazelcast;

import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.map.IMap;
import io.github.aindriub.dataprism.core.limits.ReadReservation;
import io.github.aindriub.dataprism.core.limits.ReadReservation.Refusal;
import io.github.aindriub.dataprism.core.refusal.ClusterLockTimeoutException;
import io.github.aindriub.dataprism.core.refusal.ClusterOperationRefusedException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.event.Level;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Read-budget reservations on two embedded members (task 285): the counting semantics of
 * {@code InMemoryScopeBudget}, atomic across every member and every subject of a scope.
 */
class HazelcastReservationTest {

    private static final Duration LONG = Duration.ofSeconds(20);
    private static final String SCOPE = "scope-reservations";

    private PrivacyCluster[] members;
    private HazelcastScopeBudget[] budgets;

    @BeforeEach
    void start() {
        members = TestMembers.pair(TestMembers.uniqueName("reservation"), OptionalInt.empty());
        budgets = new HazelcastScopeBudget[]{
                new HazelcastScopeBudget(members[0], LONG), new HazelcastScopeBudget(members[1], LONG)};
    }

    @AfterEach
    void stop() {
        for (PrivacyCluster member : members) {
            member.close();
        }
    }

    /** The key the scope total is stored under, and the key of the scope's lock. */
    private static String totalKey(String scope) {
        return TestMembers.SCOPE_KEYS.budgetTotal(scope);
    }

    // ----- boundaries

    @Test
    void theSubjectCounterIsAllowedToEqualItsBudgetAndNoMore() {
        for (int[] c : new int[][]{{4, 5}, {5, 5}, {6, 5}}) {
            String scope = "boundary-subject-" + c[0];
            ReadReservation r = budgets[0].reserve(scope, "s-1", c[0], 5, 100);
            assertThat(r.granted()).as("%d of 5", c[0]).isEqualTo(c[0] <= 5);
            if (!r.granted()) {
                assertThat(r.refusedBy()).isEqualTo(Refusal.SUBJECT);
                assertThat(budgets[1].reads(scope, "s-1")).isZero();
                assertThat(budgets[1].scopeReads(scope)).isZero();
            } else {
                assertThat(budgets[1].reads(scope, "s-1")).isEqualTo(c[0]);
                assertThat(budgets[1].scopeReads(scope)).isEqualTo(c[0]);
            }
        }
    }

    @Test
    void theScopeTotalIsAllowedToEqualItsBudgetAndNoMore() {
        for (int asked : new int[]{9, 10, 11}) {
            String scope = "boundary-total-" + asked;
            ReadReservation r = budgets[0].reserve(scope, "s-1", asked, 100, 10);
            assertThat(r.granted()).as("%d of 10", asked).isEqualTo(asked <= 10);
            if (!r.granted()) {
                assertThat(r.refusedBy()).isEqualTo(Refusal.SCOPE_TOTAL);
                assertThat(budgets[1].reads(scope, "s-1")).as("the subject counter is undone").isZero();
                assertThat(budgets[1].scopeReads(scope)).isZero();
            }
        }
    }

    @Test
    void subjectIsCheckedBeforeTheTotal() {
        ReadReservation r = budgets[0].reserve(SCOPE, "s-1", 5, 3, 3);
        assertThat(r.refusedBy()).isEqualTo(Refusal.SUBJECT);
    }

    @Test
    void aReadCountsTowardTheTotalButIsNotCappedByIt() {
        assertThat(budgets[0].tryRead(SCOPE, "s-1", 5)).isTrue();
        assertThat(budgets[1].scopeReads(SCOPE)).isEqualTo(1);
        // The total is 1 now, so a reservation of 2 against a total budget of 2 is refused...
        assertThat(budgets[1].reserve(SCOPE, "s-2", 2, 5, 2).refusedBy()).isEqualTo(Refusal.SCOPE_TOTAL);
        // ...and later reads still count past the total without being refused by it.
        assertThat(budgets[1].reserve(SCOPE, "s-2", 1, 5, 2).granted()).isTrue();
        assertThat(budgets[0].tryRead(SCOPE, "s-3", 5)).isTrue();
        assertThat(budgets[0].scopeReads(SCOPE)).isEqualTo(3);
    }

    @Test
    void invalidArgumentsAreRefusedAndChangeNothing() {
        assertThatThrownBy(() -> budgets[0].reserve(SCOPE, "s-1", 0, 5, 5))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> budgets[0].reserve(SCOPE, "s-1", 1, 0, 5))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> budgets[0].reserve(SCOPE, "s-1", 1, 5, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(budgets[0].reserve(SCOPE, "s-1", Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE)
                .granted()).isTrue();
        assertThatThrownBy(() -> budgets[1].reserve(SCOPE, "s-1", 1, Integer.MAX_VALUE, Integer.MAX_VALUE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(budgets[1].reads(SCOPE, "s-1")).isEqualTo(Integer.MAX_VALUE);
    }

    // ----- property tests: 32 threads across both members

    @Test
    void thirtyTwoThreadsOnOneSubjectNeverPassEitherBudget() throws Exception {
        int subjectBudget = 200;
        int totalBudget = 150;
        AtomicInteger granted = new AtomicInteger();
        runThreads(32, 40, (thread, budget) -> {
            int reads = 1 + ThreadLocalRandom.current().nextInt(4);
            if (budget.reserve(SCOPE, "s-1", reads, subjectBudget, totalBudget).granted()) {
                granted.addAndGet(reads);
            }
        });
        assertThat(granted.get()).isLessThanOrEqualTo(totalBudget);
        assertThat(budgets[0].reads(SCOPE, "s-1")).isEqualTo(granted.get());
        assertThat(budgets[1].scopeReads(SCOPE)).isEqualTo(granted.get());
        // 32 * 40 * 2.5 asks far exceed 150: the budget really was exhausted.
        assertThat(granted.get()).isGreaterThan(totalBudget - 4);
    }

    @Test
    void thirtyTwoThreadsOnSeveralSubjectsNeverPassEitherBudget() throws Exception {
        int subjectBudget = 40;
        int totalBudget = 100;
        int subjects = 5;
        AtomicInteger[] granted = new AtomicInteger[subjects];
        for (int i = 0; i < subjects; i++) granted[i] = new AtomicInteger();
        runThreads(32, 40, (thread, budget) -> {
            int s = ThreadLocalRandom.current().nextInt(subjects);
            int reads = 1 + ThreadLocalRandom.current().nextInt(5);
            if (budget.reserve(SCOPE, "s-" + s, reads, subjectBudget, totalBudget).granted()) {
                granted[s].addAndGet(reads);
            }
        });
        int sum = 0;
        for (int i = 0; i < subjects; i++) {
            assertThat(budgets[1].reads(SCOPE, "s-" + i)).isEqualTo(granted[i].get())
                    .isLessThanOrEqualTo(subjectBudget);
            sum += granted[i].get();
        }
        assertThat(sum).isLessThanOrEqualTo(totalBudget).isGreaterThan(totalBudget - 5);
        assertThat(budgets[0].scopeReads(SCOPE)).isEqualTo(sum);
    }

    // ----- many subjects, reserve and release, sampled throughout

    @Test
    void sixtyFourSubjectsWithReleasesNeverPassTheScopeTotalAtAnyTime() throws Exception {
        int subjects = 64;
        int subjectBudget = 12;
        int totalBudget = 300;
        AtomicInteger net = new AtomicInteger();
        AtomicBoolean done = new AtomicBoolean();
        AtomicReference<String> violation = new AtomicReference<>();
        Thread sampler = new Thread(() -> {
            while (!done.get() && violation.get() == null) {
                int total = budgets[1].scopeReads(SCOPE);
                if (total > totalBudget) violation.set("scope total " + total);
                for (int i = 0; i < subjects && violation.get() == null; i += 7) {
                    int reads = budgets[0].reads(SCOPE, "s-" + i);
                    if (reads > subjectBudget) violation.set("subject counter " + reads);
                }
            }
        });
        sampler.start();
        try {
            runThreads(32, 60, (thread, budget) -> {
                ThreadLocalRandom random = ThreadLocalRandom.current();
                String subject = "s-" + random.nextInt(subjects);
                int reads = 1 + random.nextInt(6);
                ReadReservation r = budget.reserve(SCOPE, subject, reads, subjectBudget, totalBudget);
                if (r.granted()) {
                    int unused = random.nextInt(reads + 1);
                    budget.release(r, unused);
                    // Given back once: asking for the rest of a reservation already emptied is a no-op.
                    if (unused == reads) budget.release(r, reads);
                    net.addAndGet(reads - unused);
                }
            });
        } finally {
            done.set(true);
            sampler.join(10_000);
        }
        assertThat(violation.get()).isNull();
        int sum = 0;
        for (int i = 0; i < subjects; i++) {
            int reads = budgets[0].reads(SCOPE, "s-" + i);
            assertThat(reads).isLessThanOrEqualTo(subjectBudget);
            sum += reads;
        }
        assertThat(budgets[1].scopeReads(SCOPE)).isLessThanOrEqualTo(totalBudget).isEqualTo(sum);
        assertThat(net.get()).as("something was granted and kept").isPositive();
        assertThat(sum).as("granted minus released").isEqualTo(net.get());
    }

    // ----- release

    @Test
    void releaseGivesBackAtMostWhatTheReservationHoldsAndNeverWhatOthersHold() {
        ReadReservation a = budgets[0].reserve(SCOPE, "s-1", 4, 10, 100);
        ReadReservation b = budgets[1].reserve(SCOPE, "s-1", 3, 10, 100);
        budgets[0].release(a, 100);
        assertThat(budgets[1].reads(SCOPE, "s-1")).as("b's reads are still held").isEqualTo(3);
        assertThat(budgets[1].scopeReads(SCOPE)).isEqualTo(3);
        budgets[0].release(a, 1);
        assertThat(budgets[1].reads(SCOPE, "s-1")).as("a second release is a no-op").isEqualTo(3);
        budgets[1].release(b, 2);
        assertThat(budgets[0].reads(SCOPE, "s-1")).isEqualTo(1);
        assertThat(budgets[0].scopeReads(SCOPE)).isEqualTo(1);
    }

    @Test
    void releasingARefusedReservationOrAForgedOneDoesNothing() {
        ReadReservation held = budgets[0].reserve(SCOPE, "s-1", 3, 10, 100);
        ReadReservation refused = budgets[0].reserve(SCOPE, "s-1", 50, 10, 100);
        budgets[0].release(refused, 50);
        ReadReservation forged = ReadReservation.granted(SCOPE, "s-1", 3, java.util.Objects.requireNonNullElse(held.origin(), "o"));
        budgets[0].release(forged, 3);
        ReadReservation bare = ReadReservation.granted(SCOPE, "s-1", 3);
        budgets[0].release(bare, 3);
        assertThat(budgets[1].reads(SCOPE, "s-1")).isEqualTo(3);
        assertThat(budgets[1].scopeReads(SCOPE)).isEqualTo(3);
    }

    @Test
    void aReleaseAfterForgetIsANoOpEvenWhenTheScopeIsReused() {
        ReadReservation old = budgets[0].reserve(SCOPE, "s-1", 3, 10, 100);
        budgets[0].forget(SCOPE);
        assertThat(budgets[0].reads(SCOPE, "s-1")).isZero();
        assertThat(budgets[0].reserve(SCOPE, "s-1", 2, 10, 100).granted()).isTrue();
        budgets[0].release(old, 3);
        assertThat(budgets[1].reads(SCOPE, "s-1")).isEqualTo(2);
        assertThat(budgets[1].scopeReads(SCOPE)).isEqualTo(2);
    }

    @Test
    void aReleaseOfAReservationFromAnotherBudgetInstanceIsANoOp() {
        ReadReservation r = budgets[0].reserve(SCOPE, "s-1", 3, 10, 100);
        budgets[1].release(r, 3);
        assertThat(budgets[1].reads(SCOPE, "s-1")).isEqualTo(3);
    }

    // ----- forget racing reserve

    @Test
    void forgetRacingReserveNeverLeavesASubjectCounterMissingFromTheTotal() throws Exception {
        int subjects = 8;
        AtomicBoolean done = new AtomicBoolean();
        Thread forgetter = new Thread(() -> {
            while (!done.get()) budgets[1].forget(SCOPE);
        });
        forgetter.start();
        try {
            runThreads(16, 40, (thread, budget) ->
                    budget.reserve(SCOPE, "s-" + ThreadLocalRandom.current().nextInt(subjects), 1, 1000, 10_000));
        } finally {
            done.set(true);
            forgetter.join(20_000);
        }
        int sum = 0;
        for (int i = 0; i < subjects; i++) sum += budgets[0].reads(SCOPE, "s-" + i);
        assertThat(budgets[1].scopeReads(SCOPE)).isEqualTo(sum);
    }


    // ----- the total key cannot be named by a subject

    @Test
    void aSubjectIdThatLooksLikeTheTotalNeverTouchesIt() {
        String[] lookalikes = {"\0total", "\0\0total", "total", "\0"};
        for (int i = 0; i < lookalikes.length; i++) {
            String lookalike = lookalikes[i];
            String scope = "lookalike-" + i;
            // The total exists first: a read of the lookalike subject must add exactly one to it.
            assertThat(budgets[0].reserve(scope, "a", 50, 100, 100).granted()).isTrue();
            assertThat(budgets[0].tryRead(scope, lookalike, 5)).isTrue();
            assertThat(budgets[1].scopeReads(scope)).as("total after a read of %s", lookalike.replace('\0', '0'))
                    .isEqualTo(51);
            assertThat(budgets[1].reads(scope, lookalike)).isEqualTo(1);
            assertThat(budgets[1].reserve(scope, "b", 50, 100, 100).refusedBy()).isEqualTo(Refusal.SCOPE_TOTAL);
            // The lookalike first: it must not become the total.
            String other = scope + "-first";
            assertThat(budgets[0].tryRead(other, lookalike, 5)).isTrue();
            assertThat(budgets[0].reserve(other, "a", 60, 100, 100).granted()).isTrue();
            assertThat(budgets[1].scopeReads(other)).isEqualTo(61);
            assertThat(budgets[1].reserve(other, "b", 40, 100, 100).refusedBy()).isEqualTo(Refusal.SCOPE_TOTAL);
        }
    }

    // ----- the scope lock is what makes a reservation one unit

    @Test
    void aCountedReadThatIsHalfDoneIsNotMissedByTheTotalAnotherReservationCreates() throws Exception {
        String total = totalKey(SCOPE);
        CountDownLatch reached = new CountDownLatch(1);
        CountDownLatch gate = new CountDownLatch(1);
        var slow = new HazelcastScopeBudget(
                pausingOn(0, key -> key.equals(total), reached, gate), LONG);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            // Has counted the subject and is about to add to the total, which does not exist yet.
            Future<Boolean> read = pool.submit(() -> slow.tryRead(SCOPE, "y", 5));
            assertThat(reached.await(10, TimeUnit.SECONDS)).isTrue();
            // The first reservation of the scope builds the total from the counters. It must wait for the read:
            // built now, the total would include the read, and the read would then add itself a second time.
            Future<ReadReservation> reserving = pool.submit(() -> budgets[1].reserve(SCOPE, "x", 3, 10, 100));
            assertThatThrownBy(() -> reserving.get(1, TimeUnit.SECONDS)).isInstanceOf(TimeoutException.class);
            gate.countDown();
            assertThat(read.get(10, TimeUnit.SECONDS)).isTrue();
            assertThat(reserving.get(10, TimeUnit.SECONDS).granted()).isTrue();
        } finally {
            gate.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(20, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(budgets[0].scopeReads(SCOPE)).isEqualTo(4);
        assertThat(budgets[0].reads(SCOPE, "x") + budgets[0].reads(SCOPE, "y")).isEqualTo(4);
    }

    @Test
    void aReserveWaitsOnTheSubjectLockOnlyAsLongAsTheLockTimeout() throws Exception {
        var shortBudget = new HazelcastScopeBudget(members[0], Duration.ofMillis(200));
        IMap<String, Object> map = members[1].instance().getMap(PrivacyCluster.BUDGET_MAP);
        String key = TestMembers.SCOPE_KEYS.budget(SCOPE, "s-1");
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread holder = new Thread(() -> {
            map.lock(key);
            locked.countDown();
            try {
                release.await();
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            } finally {
                map.unlock(key);
            }
        });
        holder.start();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            Future<ReadReservation> reserving = pool.submit(() -> shortBudget.reserve(SCOPE, "s-1", 1, 5, 5));
            assertThatThrownBy(() -> reserving.get(5, TimeUnit.SECONDS))
                    .isInstanceOf(java.util.concurrent.ExecutionException.class)
                    .hasCauseInstanceOf(ClusterLockTimeoutException.class);
        } finally {
            release.countDown();
            holder.join(10_000);
            pool.shutdown();
            assertThat(pool.awaitTermination(20, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(budgets[0].scopeReads(SCOPE)).isZero();
    }

    // ----- forget and tracking

    @Test
    void aForgetThatFailsStillInvalidatesTheReservationsThisInstanceGranted() {
        PrivacyCluster failing = failingOn(0, "delete", key -> true, false);
        var budget = new HazelcastScopeBudget(failing, LONG);
        ReadReservation held = budget.reserve(SCOPE, "s-1", 3, 10, 100);
        assertThat(held.granted()).isTrue();
        assertThatThrownBy(() -> budget.forget(SCOPE)).isInstanceOf(ClusterOperationRefusedException.class);
        assertThat(budgets[1].reads(SCOPE, "s-1")).as("the delete failed, so the counts are still there")
                .isEqualTo(3);
        budget.release(held, 3);
        assertThat(budgets[1].reads(SCOPE, "s-1")).isEqualTo(3);
    }

    @Test
    void reservationsThatAreFullyReleasedLeaveNothingTrackedPerScope() {
        for (int i = 0; i < 200; i++) {
            String scope = "tracked-" + i;
            ReadReservation r = budgets[0].reserve(scope, "s-1", 2, 5, 5);
            budgets[0].release(r, 2);
        }
        assertThat(budgets[0].trackedScopes()).isZero();
        ReadReservation kept = budgets[0].reserve("tracked-kept", "s-1", 2, 5, 5);
        assertThat(budgets[0].trackedScopes()).isEqualTo(1);
        budgets[0].forget("tracked-kept");
        assertThat(budgets[0].trackedScopes()).isZero();
        assertThat(kept.granted()).isTrue();
    }

    // ----- cluster failures

    @Test
    void aTimedOutScopeLockSurfacesAsAClusterLockTimeout() throws Exception {
        var shortBudget = new HazelcastScopeBudget(members[0], Duration.ofMillis(100));
        IMap<String, Object> map = members[1].instance().getMap(PrivacyCluster.BUDGET_MAP);
        String key = totalKey(SCOPE);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread holder = new Thread(() -> {
            map.lock(key);
            locked.countDown();
            try {
                release.await();
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            } finally {
                map.unlock(key);
            }
        });
        holder.start();
        try {
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> shortBudget.reserve(SCOPE, "s-1", 1, 5, 5))
                    .isInstanceOf(ClusterLockTimeoutException.class);
            assertThatThrownBy(() -> shortBudget.tryRead(SCOPE, "s-1", 5))
                    .isInstanceOf(ClusterLockTimeoutException.class);
            assertThatThrownBy(() -> shortBudget.forget(SCOPE)).isInstanceOf(ClusterLockTimeoutException.class);
        } finally {
            release.countDown();
            holder.join(10_000);
        }
        assertThat(budgets[0].reads(SCOPE, "s-1")).isZero();
        assertThat(budgets[0].reserve(SCOPE, "s-1", 1, 5, 5).granted()).isTrue();
    }

    @Test
    void aSecondUpdateThatFailsLeavesBothCountersAsTheyWere() {
        assertThat(budgets[0].reserve(SCOPE, "s-1", 2, 10, 100).granted()).isTrue();
        PrivacyCluster failing = failingOn(0, "executeOnKey", key -> key.equals(totalKey(SCOPE)), false);
        var budget = new HazelcastScopeBudget(failing, LONG);
        assertThatThrownBy(() -> budget.reserve(SCOPE, "s-1", 3, 10, 100))
                .isInstanceOf(ClusterOperationRefusedException.class)
                .satisfies(e -> assertThat(((ClusterOperationRefusedException) e).code())
                        .isEqualTo("CLUSTER_OPERATION_REFUSED"));
        assertThat(budgets[1].reads(SCOPE, "s-1")).isEqualTo(2);
        assertThat(budgets[1].scopeReads(SCOPE)).isEqualTo(2);
    }

    @Test
    void anUndoThatAlsoFailsLeavesTheCounterOverCountedAndLogsOneLineNamingNothing() throws Exception {
        PrivacyCluster failing = failingOn(0, "executeOnKey", key -> true, true);
        var budget = new HazelcastScopeBudget(failing, LONG);
        var recorder = new RecordingLogger();
        try (var ignored = HazelcastScopeBudget.captureTo(recorder.logger())) {
            assertThatThrownBy(() -> budget.reserve("scope-secret", "subject-secret", 3, 10, 100))
                    .isInstanceOf(ClusterOperationRefusedException.class);
        }
        // Over-counted, never under-counted: the subject keeps its reads.
        assertThat(budgets[1].reads("scope-secret", "subject-secret")).isGreaterThanOrEqualTo(3);
        assertThat(recorder.lines()).anySatisfy(line -> {
            assertThat(line.level()).isEqualTo(Level.WARN);
            assertThat(line.message()).doesNotContain("scope-secret", "subject-secret");
        });
    }

    @Test
    void aRefusedLockSurfacesAsAClusterOperationRefusal() {
        // The lock lease of a two-second timeout exceeds this maximum, so Hazelcast rejects the lock.
        int port = FreePorts.consecutive(1);
        var config = ClusterMembership.tcpIp(TestMembers.uniqueName("reservation-refused"),
                List.of("127.0.0.1:" + port)).withPort(port).withInterface("127.0.0.1").toConfig();
        config.setProperty("hazelcast.lock.max.lease.time.seconds", "1");
        PrivacyCluster solo = PrivacyCluster.embedded(config, false, OptionalInt.empty(), TestMembers.KEYS);
        try {
            var budget = new HazelcastScopeBudget(solo, Duration.ofSeconds(2));
            assertThatThrownBy(() -> budget.reserve(SCOPE, "s-1", 1, 5, 5))
                    .isInstanceOfSatisfying(ClusterOperationRefusedException.class,
                            e -> assertThat(e.code()).isEqualTo("CLUSTER_OPERATION_REFUSED"));
        } finally {
            solo.close();
        }
    }

    // ----- helpers

    private interface Work {
        void run(int thread, HazelcastScopeBudget budget) throws Exception;
    }

    /** Threads alternate between the two members; every thread starts together and does {@code each} calls. */
    private void runThreads(int threads, int each, Work work) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            List<Future<?>> futures = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                int thread = t;
                Callable<Void> body = () -> {
                    start.await();
                    for (int i = 0; i < each; i++) work.run(thread, budgets[thread % 2]);
                    return null;
                };
                futures.add(pool.submit(body));
            }
            start.countDown();
            for (Future<?> f : futures) f.get(120, TimeUnit.SECONDS);
        }
    }

    /**
     * A cluster over member {@code index} whose budget map throws on {@code executeOnKey} for the keys that
     * match {@code failing}. With {@code letFirstThrough} the first call to match succeeds and every later one
     * fails, so an update goes through and its undo does not.
     */
    private PrivacyCluster failingOn(int index, String methodName, Predicate<String> failing, boolean letFirstThrough) {
        HazelcastInstance real = members[index].instance();
        AtomicInteger seen = new AtomicInteger();
        Object map = Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{IMap.class},
                (proxy, method, args) -> {
                    if (method.getName().equals(methodName) && failing.test((String) args[0])) {
                        if (!letFirstThrough || seen.getAndIncrement() > 0) {
                            throw new IllegalStateException("injected failure");
                        }
                    }
                    return invoke(method, real.getMap(PrivacyCluster.BUDGET_MAP), args);
                });
        HazelcastInstance instance = (HazelcastInstance) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{HazelcastInstance.class}, (proxy, method, args) ->
                        method.getName().equals("getMap") && PrivacyCluster.BUDGET_MAP.equals(args[0])
                                ? map : invoke(method, real, args));
        return PrivacyCluster.using(instance, false, TestMembers.KEYS);
    }


    /** As {@link #failingOn}, but a matching {@code executeOnKey} announces itself and waits for the gate. */
    private PrivacyCluster pausingOn(int index, Predicate<String> pausing, CountDownLatch reached,
                                     CountDownLatch gate) {
        HazelcastInstance real = members[index].instance();
        Object map = Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{IMap.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("executeOnKey") && pausing.test((String) args[0])) {
                        reached.countDown();
                        if (!gate.await(20, TimeUnit.SECONDS)) throw new AssertionError("gate never opened");
                    }
                    return invoke(method, real.getMap(PrivacyCluster.BUDGET_MAP), args);
                });
        HazelcastInstance instance = (HazelcastInstance) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{HazelcastInstance.class}, (proxy, method, args) ->
                        method.getName().equals("getMap") && PrivacyCluster.BUDGET_MAP.equals(args[0])
                                ? map : invoke(method, real, args));
        return PrivacyCluster.using(instance, false, TestMembers.KEYS);
    }

    private static Object invoke(Method method, Object target, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException failure) {
            throw failure.getCause();
        }
    }
}

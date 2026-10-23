package io.github.aindriub.dataprism.core.link;

import io.github.aindriub.dataprism.core.link.InMemoryLinkAttemptLimiter.Marker;
import io.github.aindriub.dataprism.core.link.InMemoryLinkAttemptLimiter.Stage;
import io.github.aindriub.dataprism.core.link.LinkAttemptLimiter.Admission;
import io.github.aindriub.dataprism.core.link.LinkAttemptLimiter.LockState;
import io.github.aindriub.dataprism.core.link.LinkTestSupport.TestClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import static io.github.aindriub.dataprism.core.link.LinkTestSupport.T0;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class InMemoryLinkAttemptLimiterTest {

    private static final Duration WINDOW = Duration.ofMinutes(10);
    private static final Duration SECOND = Duration.ofSeconds(1);
    private static final long WAIT = 5;

    private TestClock clock;
    private InMemoryLinkAttemptLimiter limiter;

    @BeforeEach
    void setUp() {
        clock = new TestClock(T0);
        limiter = new InMemoryLinkAttemptLimiter(3, WINDOW, clock);
    }

    private static AttemptSubject subject(String principal) {
        return new AttemptSubject("case-1", principal);
    }

    private static AttemptSubject subject(int n) {
        return subject("p" + n);
    }

    /** A queue that can be told to refuse an offer, or to throw from the next peek. */
    private static final class FlakyQueue extends ConcurrentLinkedQueue<Marker> {
        private static final long serialVersionUID = 1L;
        volatile int refuseOffers;
        volatile boolean refuseByThrowing;
        volatile boolean throwOnPeek;

        @Override
        public boolean offer(Marker marker) {
            if (refuseOffers > 0) {
                refuseOffers--;
                if (refuseByThrowing) {
                    throw new IllegalStateException("refused");
                }
                return false;
            }
            return super.offer(marker);
        }

        @Override
        public Marker peek() {
            if (throwOnPeek) {
                throwOnPeek = false;
                throw new IllegalStateException("peek failed");
            }
            return super.peek();
        }
    }

    private static InMemoryLinkAttemptLimiter with(int limit, Clock clock, int max, Consumer<Stage> probe,
            Queue<Marker> queue) {
        return new InMemoryLinkAttemptLimiter(limit, WINDOW, clock, max, new byte[32], probe, () -> queue);
    }

    /** Holds the examination lock on a dedicated thread until released. */
    private static final class Holder implements AutoCloseable {
        private final CountDownLatch release = new CountDownLatch(1);
        private final Thread thread;

        Holder(InMemoryLinkAttemptLimiter limiter) throws InterruptedException {
            CountDownLatch held = new CountDownLatch(1);
            thread = new Thread(() -> {
                limiter.examinationLock().lock();
                try {
                    held.countDown();
                    release.await();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                } finally {
                    limiter.examinationLock().unlock();
                }
            });
            thread.start();
            assertThat(held.await(WAIT, TimeUnit.SECONDS)).isTrue();
        }

        @Override
        public void close() throws InterruptedException {
            release.countDown();
            thread.join(5_000);
        }
    }

    /**
     * Runs a call on another thread with a 5 s bound, for use while a {@link Holder} holds the examination lock.
     * A limiter that waited for the lock would fail here with a timeout instead of hanging the test.
     */
    private static <T> T whileHeld(java.util.concurrent.Callable<T> call) throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            return pool.submit(call).get(WAIT, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
    }

    // ---- the lock and the window ----

    @Test
    void locksAtTheLimitAndNotBefore() {
        assertThat(limiter.recordFailure(subject("a"))).isEqualTo(Admission.WITHIN_LIMIT);
        assertThat(limiter.recordFailure(subject("a"))).isEqualTo(Admission.WITHIN_LIMIT);
        assertThat(limiter.lockState(subject("a"))).isEqualTo(LockState.UNLOCKED);
        assertThat(limiter.recordFailure(subject("a"))).isEqualTo(Admission.WITHIN_LIMIT);
        assertThat(limiter.lockState(subject("a"))).isEqualTo(LockState.LOCKED);
        assertThat(limiter.recordFailure(subject("a"))).isEqualTo(Admission.OVER_LIMIT);
        assertThat(limiter.recordFailure(subject("a"))).isEqualTo(Admission.OVER_LIMIT);
    }

    @Test
    void principalAndScopeAreSeparateCounters() {
        for (int i = 0; i < 3; i++) {
            limiter.recordFailure(new AttemptSubject("case-1", "alice"));
        }
        assertThat(limiter.lockState(new AttemptSubject("case-1", "alice"))).isEqualTo(LockState.LOCKED);
        assertThat(limiter.lockState(new AttemptSubject("case-1", "bob"))).isEqualTo(LockState.UNLOCKED);
        assertThat(limiter.lockState(new AttemptSubject("case-2", "alice"))).isEqualTo(LockState.UNLOCKED);
        // The scope and the principal are not interchangeable.
        assertThat(limiter.lockState(new AttemptSubject("alice", "case-1"))).isEqualTo(LockState.UNLOCKED);
    }

    @Test
    void incrementsDoNotExtendTheWindow() {
        limiter.recordFailure(subject("a"));
        clock.advance(Duration.ofMinutes(9));
        limiter.recordFailure(subject("a"));
        limiter.recordFailure(subject("a"));
        assertThat(limiter.lockState(subject("a"))).isEqualTo(LockState.LOCKED);
        clock.advance(Duration.ofMinutes(1));
        assertThat(limiter.lockState(subject("a"))).isEqualTo(LockState.UNLOCKED);
    }

    @Test
    void theWindowIsDecidedByTheClockAtTheBoundary() {
        for (int i = 0; i < 3; i++) {
            limiter.recordFailure(subject("a"));
        }
        clock.set(T0.plus(WINDOW).minusMillis(1));
        assertThat(limiter.lockState(subject("a"))).isEqualTo(LockState.LOCKED);
        clock.set(T0.plus(WINDOW));
        assertThat(limiter.lockState(subject("a"))).isEqualTo(LockState.UNLOCKED);
    }

    @Test
    void aFailureAfterTheWindowStartsANewOne() {
        limiter.recordFailure(subject("a"));
        limiter.recordFailure(subject("a"));
        clock.advance(Duration.ofMinutes(11));
        assertThat(limiter.recordFailure(subject("a"))).isEqualTo(Admission.WITHIN_LIMIT);
        limiter.recordFailure(subject("a"));
        assertThat(limiter.lockState(subject("a"))).isEqualTo(LockState.UNLOCKED);
        limiter.recordFailure(subject("a"));
        assertThat(limiter.lockState(subject("a"))).isEqualTo(LockState.LOCKED);
    }

    @Test
    void theAdmissionComesFromTheCountAfterTheIncrement() {
        InMemoryLinkAttemptLimiter one = new InMemoryLinkAttemptLimiter(1, WINDOW, clock);
        assertThat(one.recordFailure(subject("a"))).isEqualTo(Admission.WITHIN_LIMIT);
        assertThat(one.recordFailure(subject("a"))).isEqualTo(Admission.OVER_LIMIT);
    }

    @Test
    void aHugeWindowConstructsCountsAndLocksAtTheLimit() {
        InMemoryLinkAttemptLimiter huge =
                new InMemoryLinkAttemptLimiter(3, Duration.ofSeconds(Long.MAX_VALUE), clock);
        huge.recordFailure(subject("a"));
        huge.recordFailure(subject("a"));
        assertThat(huge.lockState(subject("a"))).isEqualTo(LockState.UNLOCKED);
        huge.recordFailure(subject("a"));
        assertThat(huge.lockState(subject("a"))).isEqualTo(LockState.LOCKED);
        clock.advance(Duration.ofDays(365 * 100));
        assertThat(huge.lockState(subject("a"))).isEqualTo(LockState.LOCKED);
        assertThat(huge.size()).isEqualTo(1);
    }

    @Test
    void sixteenThreadsLoseNoCountAndLockExactlyAtTheLimit() throws Exception {
        int limit = 1000;
        InMemoryLinkAttemptLimiter big = new InMemoryLinkAttemptLimiter(limit, WINDOW, clock);
        ExecutorService pool = Executors.newFixedThreadPool(16);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<?>> futures = new ArrayList<>();
            // 16 threads x 62 = 992 failures: below the limit, so none may be lost.
            for (int t = 0; t < 16; t++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    for (int i = 0; i < 62; i++) {
                        big.recordFailure(subject("shared"));
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> f : futures) {
                f.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(big.lockState(subject("shared"))).isEqualTo(LockState.UNLOCKED);
        for (int i = 0; i < 7; i++) {
            big.recordFailure(subject("shared"));
        }
        assertThat(big.lockState(subject("shared"))).isEqualTo(LockState.UNLOCKED);
        assertThat(big.recordFailure(subject("shared"))).isEqualTo(Admission.WITHIN_LIMIT);
        assertThat(big.lockState(subject("shared"))).isEqualTo(LockState.LOCKED);
    }

    @Test
    void clusterWideIsFalseByDefault() {
        assertThat(limiter.clusterWide()).isFalse();
        LinkAttemptLimiter plain = new LinkAttemptLimiter() {
            @Override
            public LockState lockState(AttemptSubject subject) {
                return LockState.UNLOCKED;
            }

            @Override
            public Admission recordFailure(AttemptSubject subject) {
                return Admission.WITHIN_LIMIT;
            }
        };
        assertThat(plain.clusterWide()).isFalse();
    }

    @Test
    void constructionRefusalsAreConfigurationExceptionsWithTheirCode() {
        for (Runnable bad : List.<Runnable>of(
                () -> new InMemoryLinkAttemptLimiter(0, Duration.ofMinutes(1), clock),
                () -> new InMemoryLinkAttemptLimiter(1, Duration.ZERO, clock),
                () -> new InMemoryLinkAttemptLimiter(1, Duration.ofSeconds(-1), clock),
                () -> new InMemoryLinkAttemptLimiter(1, Duration.ofMinutes(1), clock, 0))) {
            assertThatThrownBy(bad::run).isInstanceOf(LinkConfigurationException.class)
                    .satisfies(e -> assertThat(((LinkConfigurationException) e).code())
                            .isEqualTo("INVALID_LINK_LIMIT"))
                    .hasMessageStartingWith("INVALID_LINK_LIMIT");
        }
        assertThat(InMemoryLinkAttemptLimiter.DEFAULT_MAX_ENTRIES).isEqualTo(100_000);
    }

    @Test
    void theClassHasNoPublicSweepAndNoRemoveIfOverTheMap() throws Exception {
        assertThat(InMemoryLinkAttemptLimiter.class.getMethods())
                .noneMatch(m -> m.getName().equals("sweep"));
        String source = Files.readString(Path.of(
                "src/main/java/io/github/aindriub/dataprism/core/link/InMemoryLinkAttemptLimiter.java"));
        assertThat(source).doesNotContain("removeIf(").doesNotContain("SWEEP_EVERY").doesNotContain("void sweep(");
    }

    // ---- the ceiling ----

    @Test
    void aSubjectThatCannotBeStoredGetsTheCeilingAndIsNotStored() {
        InMemoryLinkAttemptLimiter small = new InMemoryLinkAttemptLimiter(3, WINDOW, clock, 4);
        for (int i = 1; i <= 4; i++) {
            assertThat(small.recordFailure(subject(i))).isEqualTo(Admission.WITHIN_LIMIT);
        }
        assertThat(small.recordFailure(subject(5))).isEqualTo(Admission.CEILING);
        assertThat(small.size()).isEqualTo(4);
        assertThat(small.lockState(subject(5))).isEqualTo(LockState.UNLOCKED);
        assertThat(small.entryCount()).isEqualTo(4);
        // A held subject keeps counting at the ceiling.
        assertThat(small.recordFailure(subject(1))).isEqualTo(Admission.WITHIN_LIMIT);
    }

    @Test
    void aLockedSubjectStaysLockedWhileNewSubjectsKeepArriving() {
        InMemoryLinkAttemptLimiter small = new InMemoryLinkAttemptLimiter(3, WINDOW, clock, 4);
        for (int i = 0; i < 3; i++) {
            small.recordFailure(subject(1));
        }
        for (int i = 2; i <= 4; i++) {
            small.recordFailure(subject(i));
        }
        for (int i = 5; i < 200; i++) {
            assertThat(small.recordFailure(subject(i))).isEqualTo(Admission.CEILING);
        }
        assertThat(small.lockState(subject(1))).isEqualTo(LockState.LOCKED);
        assertThat(small.size()).isEqualTo(4);
    }

    @Test
    void theFifthSubjectNeverLocksNoMatterHowOftenItFails() {
        // The consequence the owner accepted under OD-269-3: a subject that is not stored is never locked.
        InMemoryLinkAttemptLimiter small = new InMemoryLinkAttemptLimiter(3, WINDOW, clock, 4);
        for (int i = 1; i <= 4; i++) {
            small.recordFailure(subject(i));
        }
        for (int i = 0; i < 100; i++) {
            assertThat(small.recordFailure(subject(5))).isEqualTo(Admission.CEILING);
            assertThat(small.lockState(subject(5))).isEqualTo(LockState.UNLOCKED);
        }
    }

    @Test
    void theCeilingRetriesTheInsertExactlyOnceAndNeverHangs() {
        InMemoryLinkAttemptLimiter small = new InMemoryLinkAttemptLimiter(3, WINDOW, clock, 4);
        for (int i = 1; i <= 4; i++) {
            small.recordFailure(subject(i));
        }
        // assertTimeoutPreemptively runs the call on another thread, so the counters are read there.
        Object[] answer = assertTimeoutPreemptively(Duration.ofSeconds(5),
                () -> new Object[] {small.recordFailure(subject(5)), small.lastCall()});
        assertThat(answer[0]).isEqualTo(Admission.CEILING);
        assertThat(((InMemoryLinkAttemptLimiter.CallStats) answer[1]).insertAttempts()).isEqualTo(2);
    }

    // ---- the drain ----

    @Test
    void theDrainIsReachedPastALiveHead() throws Exception {
        InMemoryLinkAttemptLimiter l = new InMemoryLinkAttemptLimiter(3, Duration.ofSeconds(10), clock, 5);
        AttemptSubject a = subject("A");
        AttemptSubject b = subject("B");
        AttemptSubject live = subject("L");
        AttemptSubject c = subject("C");
        AttemptSubject d = subject("D");
        clock.set(T0);
        l.recordFailure(a);
        l.recordFailure(b);
        clock.set(T0.plusSeconds(5));
        l.recordFailure(live);
        clock.set(T0.plusSeconds(1));
        l.recordFailure(c);
        l.recordFailure(d);
        clock.set(T0.plusMillis(10_500));
        try (Holder holder = new Holder(l)) {
            whileHeld(() -> l.recordFailure(a));
            whileHeld(() -> l.recordFailure(b));
        }
        assertThat(l.queueSnapshot()).containsExactly(
                new Marker(l.mapKeyOf(a), l.entryIdOf(a), T0), new Marker(l.mapKeyOf(b), l.entryIdOf(b), T0),
                new Marker(l.mapKeyOf(live), l.entryIdOf(live), T0.plusSeconds(5)),
                new Marker(l.mapKeyOf(c), l.entryIdOf(c), T0.plusSeconds(1)),
                new Marker(l.mapKeyOf(d), l.entryIdOf(d), T0.plusSeconds(1)));

        clock.set(T0.plusMillis(11_500));
        AttemptSubject e = subject("E");
        assertThat(l.recordFailure(e)).isEqualTo(Admission.WITHIN_LIMIT);
        assertThat(l.lastCall().preCheckRemoved()).isZero();
        assertThat(l.lastCall().drainRemoved()).isEqualTo(1);
        assertThat(l.lastCall().insertAttempts()).isEqualTo(2);
        // C was freed and the drain stopped there: D is still held, and E took the slot.
        assertThat(l.size()).isEqualTo(5);
        assertThat(l.entryCount()).isEqualTo(5);
        assertThat(l.entryIdOf(d)).isPositive();
        l.checkInvariant();
    }

    @Test
    void theBoundedSweepAndTheDrainStayWithinTheirQueueOperationCaps() {
        InMemoryLinkAttemptLimiter big = new InMemoryLinkAttemptLimiter(3, WINDOW, clock, 20_000);
        for (int i = 0; i < 10_000; i++) {
            big.recordFailure(subject(i));
            assertThat(big.lastCall().queueOperations()).isLessThanOrEqualTo(7);
        }
        for (int i = 0; i < 1_000; i++) {
            big.lockState(subject(-i - 1));
            assertThat(big.lastCall().queueOperations()).isLessThanOrEqualTo(7);
        }
        InMemoryLinkAttemptLimiter capped = new InMemoryLinkAttemptLimiter(3, WINDOW, clock, 100);
        for (int i = 0; i < 10_000; i++) {
            capped.recordFailure(subject(i));
            assertThat(capped.lastCall().queueOperations()).isLessThanOrEqualTo(55);
        }
        assertThat(capped.size()).isEqualTo(100);
    }

    @Test
    void theCeilingRaceNeverBlocksInsideCompute() throws Exception {
        CountDownLatch reached = new CountDownLatch(1);
        CountDownLatch go = new CountDownLatch(1);
        Consumer<Stage> probe = stage -> {
            if (stage == Stage.RESERVED && Thread.currentThread().getName().equals("racer-A")) {
                reached.countDown();
                try {
                    go.await();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        };
        InMemoryLinkAttemptLimiter one = with(3, clock, 1, probe, new ConcurrentLinkedQueue<>());
        ExecutorService a = Executors.newSingleThreadExecutor(r -> new Thread(r, "racer-A"));
        ExecutorService b = Executors.newSingleThreadExecutor(r -> new Thread(r, "racer-B"));
        try {
            Future<Admission> first = a.submit(() -> one.recordFailure(subject(1)));
            assertThat(reached.await(WAIT, TimeUnit.SECONDS)).isTrue();
            Future<Admission> second = b.submit(() -> one.recordFailure(subject(2)));
            assertThat(second.get(WAIT, TimeUnit.SECONDS)).isEqualTo(Admission.CEILING);
            go.countDown();
            assertThat(first.get(WAIT, TimeUnit.SECONDS)).isEqualTo(Admission.WITHIN_LIMIT);
        } finally {
            go.countDown();
            a.shutdownNow();
            b.shutdownNow();
        }
        assertThat(one.size()).isEqualTo(1);
        assertThat(one.entryCount()).isEqualTo(1);
    }

    // ---- no slot leaks ----

    @Test
    void capacityIsRestoredAfterAThrowInTheReservationProbe() {
        AtomicBoolean thrown = new AtomicBoolean();
        Consumer<Stage> probe = stage -> {
            if (stage == Stage.RESERVED && thrown.compareAndSet(false, true)) {
                throw new IllegalStateException("probe");
            }
        };
        InMemoryLinkAttemptLimiter one = with(3, clock, 1, probe, new ConcurrentLinkedQueue<>());
        assertThat(one.recordFailure(subject(1))).isEqualTo(Admission.UNAVAILABLE);
        assertThat(one.size()).isZero();
        assertThat(one.entryCount()).isZero();
        assertThat(one.recordFailure(subject(2))).isEqualTo(Admission.WITHIN_LIMIT);
        assertThat(one.size()).isEqualTo(1);
    }

    @Test
    void aRefusedMarkerAppendLeavesNoEntryAndNoReservation() {
        List<String> results = new ArrayList<>();
        for (boolean byThrowing : new boolean[] {false, true}) {
            FlakyQueue queue = new FlakyQueue();
            queue.refuseOffers = 1;
            queue.refuseByThrowing = byThrowing;
            InMemoryLinkAttemptLimiter one = with(3, clock, 1, stage -> { }, queue);
            Admission answer = one.recordFailure(subject(1));
            results.add(answer + "/" + one.size() + "/" + one.entryCount() + "/" + queue.size());
            // The slot is free again.
            assertThat(one.recordFailure(subject(2))).isEqualTo(Admission.WITHIN_LIMIT);
            one.checkInvariant();
        }
        assertThat(results).containsExactly("UNAVAILABLE/0/0/0", "UNAVAILABLE/0/0/0");
    }

    @Test
    void aProbeThatThrowsAfterStoringRollsTheEntryBack() {
        Consumer<Stage> probe = stage -> {
            if (stage == Stage.STORED) {
                throw new IllegalStateException("probe");
            }
        };
        InMemoryLinkAttemptLimiter one = with(3, clock, 1, probe, new ConcurrentLinkedQueue<>());
        assertThat(one.recordFailure(subject(1))).isEqualTo(Admission.UNAVAILABLE);
        assertThat(one.size()).isZero();
        assertThat(one.entryCount()).isZero();
    }

    @Test
    void rollbackIsByIdWhenAnotherThreadUpdatedTheEntryInPlace() throws Exception {
        CountDownLatch stored = new CountDownLatch(1);
        CountDownLatch go = new CountDownLatch(1);
        Consumer<Stage> probe = stage -> {
            if (stage == Stage.STORED && Thread.currentThread().getName().equals("rollback-A")) {
                stored.countDown();
                try {
                    go.await();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        };
        FlakyQueue queue = new FlakyQueue();
        queue.refuseOffers = 1;
        InMemoryLinkAttemptLimiter l = with(3, clock, 10, probe, queue);
        ExecutorService a = Executors.newSingleThreadExecutor(r -> new Thread(r, "rollback-A"));
        try {
            Future<Admission> first = a.submit(() -> l.recordFailure(subject("S")));
            assertThat(stored.await(WAIT, TimeUnit.SECONDS)).isTrue();
            // B updates the entry in place: same id, new value, no marker of its own.
            assertThat(l.recordFailure(subject("S"))).isEqualTo(Admission.WITHIN_LIMIT);
            assertThat(l.size()).isEqualTo(1);
            go.countDown();
            assertThat(first.get(WAIT, TimeUnit.SECONDS)).isEqualTo(Admission.UNAVAILABLE);
        } finally {
            go.countDown();
            a.shutdownNow();
        }
        assertThat(l.size()).isZero();
        assertThat(l.entryCount()).isZero();
        assertThat(l.queueSize()).isZero();
        assertThat(l.recordFailure(subject("S"))).isEqualTo(Admission.WITHIN_LIMIT);
        assertThat(l.queueSize()).isEqualTo(1);
        l.checkInvariant();
    }

    @Test
    void aRefusedReappendKeepsTheMarkerAndALaterExaminationRepeatsIt() throws Exception {
        for (boolean byThrowing : new boolean[] {false, true}) {
            TestClock c = new TestClock(T0);
            FlakyQueue queue = new FlakyQueue();
            InMemoryLinkAttemptLimiter l = with(1, c, 10, stage -> { }, queue);
            AttemptSubject s = subject("S");
            l.recordFailure(s);
            l.recordFailure(subject("X"));
            c.advance(WINDOW.plusSeconds(1));
            try (Holder holder = new Holder(l)) {
                // The examination is skipped, so S is re-opened in place and keeps its old marker.
                assertThat(whileHeld(() -> l.recordFailure(s))).isEqualTo(Admission.WITHIN_LIMIT);
            }
            assertThat(l.size()).isEqualTo(2);
            queue.refuseOffers = 1;
            queue.refuseByThrowing = byThrowing;
            Marker head = queue.peek();
            l.lockState(subject("absent"));
            assertThat(queue.peek()).isEqualTo(head);
            l.checkInvariant();
            assertThat(l.lockState(s)).isEqualTo(LockState.LOCKED);
            // The queue accepts offers again: S is re-appended with its stored start, X (ended) is removed.
            l.lockState(subject("absent"));
            assertThat(l.size()).isEqualTo(1);
            assertThat(l.lockState(s)).isEqualTo(LockState.LOCKED);
            l.checkInvariant();
            c.advance(WINDOW);
            l.lockState(subject("absent"));
            assertThat(l.size()).isZero();
            assertThat(l.entryCount()).isZero();
            assertThat(queue).isEmpty();
        }
    }

    @Test
    void aThrowDuringExaminationReleasesTheLockAndTheNextCallExaminesNormally() throws Exception {
        FlakyQueue queue = new FlakyQueue();
        InMemoryLinkAttemptLimiter l = with(3, clock, 10, stage -> { }, queue);
        l.recordFailure(subject(1));
        clock.advance(WINDOW);
        queue.throwOnPeek = true;
        assertThat(l.recordFailure(subject(2))).isEqualTo(Admission.UNAVAILABLE);
        AtomicBoolean other = new AtomicBoolean();
        Thread probeThread = new Thread(() -> {
            other.set(l.examinationLock().tryLock());
            if (other.get()) {
                l.examinationLock().unlock();
            }
        });
        probeThread.start();
        probeThread.join(5_000);
        assertThat(other).isTrue();
        queue.throwOnPeek = true;
        assertThat(l.lockState(subject(3))).isEqualTo(LockState.UNAVAILABLE);
        assertThat(l.lockState(subject(3))).isEqualTo(LockState.UNLOCKED);
        assertThat(l.size()).isZero();
    }

    // ---- contention ----

    @Test
    void theDrainDoesNotWaitForTheExaminationLock() throws Exception {
        InMemoryLinkAttemptLimiter l = new InMemoryLinkAttemptLimiter(3, WINDOW, clock, 2);
        l.recordFailure(subject("x"));
        l.recordFailure(subject("y"));
        clock.advance(WINDOW);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (Holder holder = new Holder(l)) {
            Future<Object[]> call = pool.submit(() -> {
                Admission answer = l.recordFailure(subject("z"));
                return new Object[] {answer, l.lastCall()};
            });
            Object[] result = call.get(WAIT, TimeUnit.SECONDS);
            assertThat(result[0]).isEqualTo(Admission.CEILING);
            InMemoryLinkAttemptLimiter.CallStats stats = (InMemoryLinkAttemptLimiter.CallStats) result[1];
            assertThat(stats.preCheckRemoved()).isZero();
            assertThat(stats.drainRemoved()).isZero();
        } finally {
            pool.shutdownNow();
        }
        assertThat(l.recordFailure(subject("z"))).isEqualTo(Admission.WITHIN_LIMIT);
        assertThat(l.lastCall().preCheckRemoved()).isGreaterThanOrEqualTo(1);
        assertThat(l.lastCall().drainRemoved()).isZero();
    }

    /** A clock several threads can read and one can advance. */
    private static final class SharedClock extends Clock {
        private final AtomicLong seconds = new AtomicLong();

        void tick() {
            seconds.incrementAndGet();
        }

        void advanceBy(long by) {
            seconds.addAndGet(by);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return T0.plusSeconds(seconds.get());
        }
    }

    @Test
    void theSweepIsExclusiveAndLeakFreeUnderConcurrency() throws Exception {
        for (int round = 0; round < 20; round++) {
            SharedClock shared = new SharedClock();
            InMemoryLinkAttemptLimiter l = new InMemoryLinkAttemptLimiter(3, Duration.ofSeconds(5), shared, 1_000);
            ExecutorService pool = Executors.newFixedThreadPool(16);
            try {
                CountDownLatch start = new CountDownLatch(1);
                List<Future<?>> futures = new ArrayList<>();
                for (int t = 0; t < 16; t++) {
                    int thread = t;
                    futures.add(pool.submit(() -> {
                        start.await();
                        java.util.Random random = new java.util.Random(thread * 31L);
                        for (int i = 0; i < 1_500; i++) {
                            l.recordFailure(subject(random.nextInt(2_000)));
                            if (thread == 0 && i % 100 == 99) {
                                shared.tick();
                            }
                        }
                        return null;
                    }));
                }
                start.countDown();
                for (Future<?> f : futures) {
                    f.get(60, TimeUnit.SECONDS);
                }
            } finally {
                pool.shutdownNow();
            }
            l.checkInvariant();
            shared.advanceBy(3_600);
            int cap = l.queueSize();
            for (int calls = 0; calls < cap && l.queueSize() > 0; calls++) {
                l.lockState(subject("absent"));
            }
            assertThat(l.queueSize()).isZero();
            assertThat(l.size()).isZero();
            assertThat(l.entryCount()).isZero();
        }
    }

    // ---- the bounded sweep ----

    @Test
    void eachCallRemovesAtMostTwoEndedEntries() {
        int n = 1_000;
        InMemoryLinkAttemptLimiter l = new InMemoryLinkAttemptLimiter(3, WINDOW, clock, 5_000);
        for (int i = 0; i < n; i++) {
            l.recordFailure(subject(i));
        }
        clock.advance(WINDOW);
        for (int k = 1; k <= 520; k++) {
            l.lockState(subject("absent"));
            assertThat(l.size()).isEqualTo(Math.max(0, n - 2 * k));
            assertThat(l.entryCount()).isEqualTo(l.size());
        }
        assertThat(l.size()).isZero();
    }

    @Test
    void aWindowReopenedInPlaceKeepsOneEntryAndOneMarkerAndIsNotRemovedEarly() throws Exception {
        InMemoryLinkAttemptLimiter l = new InMemoryLinkAttemptLimiter(1, WINDOW, clock, 10);
        AttemptSubject s = subject("S");
        l.recordFailure(s);
        clock.advance(WINDOW.plusSeconds(1));
        try (Holder holder = new Holder(l)) {
            whileHeld(() -> l.recordFailure(s));
        }
        assertThat(l.size()).isEqualTo(1);
        assertThat(l.queueSize()).isEqualTo(1);
        // The marker still names the old start; the entry is live and must not be removed by it.
        l.lockState(subject("absent"));
        assertThat(l.size()).isEqualTo(1);
        assertThat(l.queueSize()).isEqualTo(1);
        assertThat(l.queueSnapshot().get(0).windowStart()).isEqualTo(clock.instant());
        assertThat(l.lockState(s)).isEqualTo(LockState.LOCKED);
        l.checkInvariant();
        clock.advance(WINDOW);
        l.lockState(subject("absent"));
        assertThat(l.size()).isZero();
        assertThat(l.queueSize()).isZero();
    }

    @Test
    void staleMarkersAreDroppedWithoutTouchingTheCount() {
        Queue<Marker> queue = new ConcurrentLinkedQueue<>();
        InMemoryLinkAttemptLimiter l = with(3, clock, 10, stage -> { }, queue);
        AttemptSubject s = subject("S");
        l.recordFailure(s);
        Marker mine = queue.poll();
        queue.offer(new Marker("0".repeat(32), 77, T0));
        queue.offer(new Marker(l.mapKeyOf(s), l.entryIdOf(s) + 1000, T0));
        queue.offer(mine);
        l.lockState(subject("absent"));
        assertThat(queue).containsExactly(mine);
        assertThat(l.size()).isEqualTo(1);
        assertThat(l.entryCount()).isEqualTo(1);
        l.checkInvariant();
        clock.advance(WINDOW);
        l.lockState(subject("absent"));
        assertThat(l.size()).isZero();
        assertThat(l.entryCount()).isZero();
    }

    @Test
    void afterTheClockStepsBackEntriesWhoseWindowsEndAreStillRemoved() {
        FlakyQueue queue = new FlakyQueue();
        InMemoryLinkAttemptLimiter l = with(3, clock, 10, stage -> { }, queue);
        clock.set(T0.plus(Duration.ofHours(1)));
        l.recordFailure(subject("future"));
        clock.set(T0);
        l.recordFailure(subject("past"));
        assertThat(l.size()).isEqualTo(2);
        clock.set(T0.plus(WINDOW).plusSeconds(1));
        l.lockState(subject("absent"));
        // The future-start head is rotated behind, and the ended entry behind it is removed in the same call.
        assertThat(l.size()).isEqualTo(1);
        assertThat(l.lastCall().queueOperations()).isLessThanOrEqualTo(7);
        l.checkInvariant();
        clock.set(T0.plus(Duration.ofHours(1)).plus(WINDOW));
        for (int i = 0; i < 3; i++) {
            l.lockState(subject("absent"));
        }
        assertThat(l.size()).isZero();
        assertThat(l.entryCount()).isZero();
    }

    // ---- no leak ----

    @Test
    void mapKeysHoldNeitherIdInPlainOrHexForm() {
        String scope = "scope-SENTINEL-41";
        String principal = "principal-SENTINEL-42";
        limiter.recordFailure(new AttemptSubject(scope, principal));
        assertThat(limiter.mapKeys()).hasSize(1).allSatisfy(key -> {
            assertThat(key).matches("[0-9a-f]{32}");
            for (String id : List.of(scope, principal)) {
                assertThat(key).doesNotContain(id)
                        .doesNotContain(HexFormat.of().formatHex(id.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            }
        });
        assertThat(limiter.toString()).doesNotContain(scope).doesNotContain(principal);
        InMemoryLinkAttemptLimiter other = new InMemoryLinkAttemptLimiter(3, WINDOW, clock);
        other.recordFailure(new AttemptSubject(scope, principal));
        // The key is per instance: two limiters never agree on a map key.
        assertThat(other.mapKeys()).isNotEqualTo(limiter.mapKeys());
    }

    @Test
    void aNullSubjectIsUnavailableNotAnException() {
        assertThat(limiter.lockState(null)).isEqualTo(LockState.UNAVAILABLE);
        assertThat(limiter.recordFailure(null)).isEqualTo(Admission.UNAVAILABLE);
    }
}

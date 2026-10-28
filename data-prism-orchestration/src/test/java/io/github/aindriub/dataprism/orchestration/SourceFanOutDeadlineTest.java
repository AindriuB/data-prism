package io.github.aindriub.dataprism.orchestration;

import io.github.aindriub.dataprism.core.limits.RequestLimits;
import io.github.aindriub.dataprism.core.refusal.PrivacyRefusedException;
import io.github.aindriub.dataprism.core.spi.DataRequest;
import io.github.aindriub.dataprism.core.spi.DataSourceAdapter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The per-source and overall deadlines, with a clock the test moves and latches for the adapters. */
class SourceFanOutDeadlineTest {

    private static final long WAIT_SECONDS = 10;

    /** A clock that only moves when the test says so. */
    static final class MutableClock extends Clock {
        private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-01-01T00:00:00Z"));

        void advance(Duration by) {
            now.updateAndGet(t -> t.plus(by));
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    }

    /** Blocks until released or interrupted, and says what happened. */
    private static final class Blocking implements DataSourceAdapter<String> {
        private final String name;
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicInteger calls = new AtomicInteger();
        final AtomicBoolean interrupted = new AtomicBoolean();
        /** Shared by the adapters of one test: who got a permit first is up to the scheduler, so the test asks. */
        BlockingQueue<Blocking> order = new LinkedBlockingQueue<>();

        Blocking(String name) {
            this.name = name;
        }

        @Override
        public String sourceName() {
            return name;
        }

        @Override
        public Class<String> responseType() {
            return String.class;
        }

        @Override
        public String fetch(DataRequest request) {
            calls.incrementAndGet();
            entered.countDown();
            order.add(this);
            try {
                release.await();
                return "answer-" + name;
            } catch (InterruptedException e) {
                interrupted.set(true);
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted", e);
            }
        }
    }

    private record Counting(String sourceName, AtomicInteger calls, String answer) implements DataSourceAdapter<String> {
        @Override
        public Class<String> responseType() {
            return String.class;
        }

        @Override
        public String fetch(DataRequest request) {
            calls.incrementAndGet();
            if (answer == null) {
                throw new IllegalStateException("boom");
            }
            return answer;
        }
    }

    private static Map<String, DataRequest> askAll(List<? extends DataSourceAdapter<?>> adapters) {
        Map<String, DataRequest> requests = new LinkedHashMap<>();
        adapters.forEach(a -> requests.put(a.sourceName(), DataRequest.of("THING", "s-1")));
        return requests;
    }

    private static RequestLimits limits(int concurrency, Duration perSource, Duration request) {
        return new RequestLimits(8, 500, 512 * 1024, perSource, concurrency, 100, request, 50_000);
    }

    private static <T> T await(CompletableFuture<T> future) throws Exception {
        return future.get(WAIT_SECONDS, TimeUnit.SECONDS);
    }

    private static void await(CountDownLatch latch) throws InterruptedException {
        assertThat(latch.await(WAIT_SECONDS, TimeUnit.SECONDS)).as("latch released in time").isTrue();
    }

    private static SourceOutcome outcomeOf(SourceFanOut.Execution execution, String name) {
        return execution.results().stream().map(SourceFanOut.Fetched::outcome)
                .filter(o -> o.sourceName().equals(name)).findFirst().orElseThrow();
    }

    private static CompletableFuture<SourceFanOut.Execution> run(SourceFanOut fanOut, List<DataSourceAdapter<?>> adapters,
                                                                 RequestLimits limits) {
        return CompletableFuture.supplyAsync(() -> fanOut.execute(fanOut.plan(adapters, askAll(adapters), limits), limits));
    }

    @Test
    @DisplayName("a source's timeout starts when its own call starts, not when the fan-out did")
    void timeoutIsMeasuredFromTheCallsOwnStart() throws Exception {
        var clock = new MutableClock();
        var fanOut = new SourceFanOut(SourceCircuitBreaker.disabled(), clock, SourceFanOutOptions.defaults());
        var order = new LinkedBlockingQueue<Blocking>();
        var one = new Blocking("one");
        var two = new Blocking("two");
        one.order = order;
        two.order = order;
        List<DataSourceAdapter<?>> adapters = List.of(one, two);
        var limits = limits(1, Duration.ofSeconds(1), Duration.ofSeconds(10));
        Instant later = clock.instant().plusMillis(1700);
        var sawLater = new CountDownLatch(1);
        fanOut.tickObserver(seen -> {
            if (!seen.isBefore(later)) {
                sawLater.countDown();
            }
        });

        var running = run(fanOut, adapters, limits);
        var first = order.poll(WAIT_SECONDS, TimeUnit.SECONDS);
        assertThat(first).isNotNull();
        clock.advance(Duration.ofMillis(800));
        first.release.countDown();
        var second = order.poll(WAIT_SECONDS, TimeUnit.SECONDS);
        assertThat(second).isNotNull();
        // 1.7 s since the fan-out began, 0.9 s since the second call did.
        clock.advance(Duration.ofMillis(900));
        // The waiter has now read the moved clock, and its pass applied the timeouts: only then is the
        // second call released, so a timeout measured from the fan-out's start would already have fired.
        await(sawLater);
        assertThat(second.interrupted.get()).isFalse();
        second.release.countDown();

        var execution = await(running);
        assertThat(outcomeOf(execution, "one").status()).isEqualTo(SourceOutcome.Status.ANSWERED);
        assertThat(outcomeOf(execution, "two").status()).isEqualTo(SourceOutcome.Status.ANSWERED);
        assertThat(execution.notStarted()).isZero();
    }

    @Test
    @DisplayName("a call still running a per-source timeout after it started is cancelled and reported TIMED_OUT")
    void aCallPastItsOwnTimeoutIsCancelled() throws Exception {
        var clock = new MutableClock();
        var fanOut = new SourceFanOut(SourceCircuitBreaker.disabled(), clock, SourceFanOutOptions.defaults());
        var stuck = new Blocking("stuck");
        List<DataSourceAdapter<?>> adapters = List.of(stuck);
        var limits = limits(2, Duration.ofSeconds(1), Duration.ofSeconds(10));

        var running = run(fanOut, adapters, limits);
        await(stuck.entered);
        clock.advance(Duration.ofMillis(1100));

        var execution = await(running);
        assertThat(outcomeOf(execution, "stuck").status()).isEqualTo(SourceOutcome.Status.TIMED_OUT);
        assertThat(stuck.interrupted.get()).isTrue();
        assertThat(execution.notStarted()).isZero();
    }

    @Test
    @DisplayName("at the overall deadline every running call is interrupted, a queued source is never called, and fetchAll returns")
    void overallDeadlineCancelsRunningAndNeverStartsQueued() throws Exception {
        var clock = new MutableClock();
        var fanOut = new SourceFanOut(SourceCircuitBreaker.disabled(), clock, SourceFanOutOptions.defaults());
        var order = new LinkedBlockingQueue<Blocking>();
        var a = new Blocking("a");
        var b = new Blocking("b");
        var c = new Blocking("c");
        List.of(a, b, c).forEach(x -> x.order = order);
        List<DataSourceAdapter<?>> adapters = List.of(a, b, c);
        var limits = limits(1, Duration.ofSeconds(3), Duration.ofSeconds(10));

        var future = run(fanOut, adapters, limits);
        var holder = order.poll(WAIT_SECONDS, TimeUnit.SECONDS);
        assertThat(holder).isNotNull();
        clock.advance(Duration.ofSeconds(10));

        var execution = await(future);
        assertThat(execution.results()).extracting(f -> f.outcome().status())
                .containsOnly(SourceOutcome.Status.TIMED_OUT);
        assertThat(holder.interrupted.get()).isTrue();
        assertThat(a.calls.get() + b.calls.get() + c.calls.get())
                .as("a source not started by the deadline is never called").isEqualTo(1);
        assertThat(execution.notStarted()).isEqualTo(2);
    }

    @Test
    @DisplayName("a call that ignores its interrupt does not hold fetchAll past the cancellation interval")
    void anAdapterThatIgnoresInterruptDoesNotHangTheRequest() throws Exception {
        var clock = new MutableClock();
        var fanOut = new SourceFanOut(SourceCircuitBreaker.disabled(), clock, SourceFanOutOptions.defaults());
        var deaf = new BlockingForever("deaf");
        List<DataSourceAdapter<?>> adapters = List.of(deaf);
        var limits = limits(1, Duration.ofSeconds(3), Duration.ofSeconds(10));

        var future = run(fanOut, adapters, limits);
        await(deaf.entered);
        clock.advance(Duration.ofSeconds(10));
        try {
            var execution = await(future);
            assertThat(outcomeOf(execution, "deaf").status()).isEqualTo(SourceOutcome.Status.TIMED_OUT);
        } finally {
            deaf.stop.countDown();
        }
    }

    /** Waits on a latch with no regard for interrupts, until the test stops it. */
    private static final class BlockingForever implements DataSourceAdapter<String> {
        private final String name;
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch stop = new CountDownLatch(1);

        BlockingForever(String name) {
            this.name = name;
        }

        @Override
        public String sourceName() {
            return name;
        }

        @Override
        public Class<String> responseType() {
            return String.class;
        }

        @Override
        public String fetch(DataRequest request) {
            entered.countDown();
            while (stop.getCount() > 0) {
                try {
                    stop.await();
                } catch (InterruptedException ignored) {
                    // keeps waiting
                }
            }
            return "late";
        }
    }

    @Test
    @DisplayName("more sources to call than maxSources refuses the whole call before any adapter is called")
    void tooManySourcesRefusesBeforeAnyCall() {
        var fanOut = new SourceFanOut(SourceCircuitBreaker.disabled(), Clock.systemUTC(), SourceFanOutOptions.defaults());
        var calls = new AtomicInteger();
        List<DataSourceAdapter<?>> adapters = List.of(new Counting("a", calls, "A"),
                new Counting("b", calls, "B"), new Counting("c", calls, "C"));
        var limits = new RequestLimits(2, 500, 512 * 1024, Duration.ofSeconds(3), 4, 100,
                Duration.ofSeconds(10), 50_000);

        assertThatThrownBy(() -> fanOut.fetchAll(adapters, askAll(adapters), limits))
                .isInstanceOfSatisfying(PrivacyRefusedException.class,
                        e -> assertThat(e.code()).isEqualTo("SOURCE_SELECTION_TOO_LARGE"));
        assertThat(calls.get()).isZero();
    }

    @Test
    @DisplayName("a half-open breaker trial taken for a refused selection does not stay taken")
    void refusedSelectionDoesNotLeakABreakerTrial() {
        var clock = new MutableClock();
        var breaker = new SourceCircuitBreaker(1, Duration.ofMinutes(1), clock);
        var fanOut = new SourceFanOut(breaker, clock, SourceFanOutOptions.defaults());
        var calls = new AtomicInteger();
        var flaky = new Counting("flaky", calls, null);
        List<DataSourceAdapter<?>> one = List.of(flaky);
        var wide = limits(2, Duration.ofSeconds(3), Duration.ofSeconds(10));
        fanOut.fetchAll(one, askAll(one), wide);
        clock.advance(Duration.ofMinutes(2));

        List<DataSourceAdapter<?>> three = List.of(flaky, new Counting("b", calls, "B"), new Counting("c", calls, "C"));
        var narrow = new RequestLimits(2, 500, 512 * 1024, Duration.ofSeconds(3), 4, 100,
                Duration.ofSeconds(10), 50_000);
        assertThatThrownBy(() -> fanOut.fetchAll(three, askAll(three), narrow))
                .isInstanceOf(PrivacyRefusedException.class);
        clock.advance(Duration.ofMinutes(2));

        assertThat(breaker.allows("flaky")).as("the source gets another trial after the next cooldown").isTrue();
    }

    @Test
    @DisplayName("a healthy source in an abandoned plan is not counted as a failure")
    void abandonedHealthySourceIsNotPenalised() {
        var clock = new MutableClock();
        var breaker = new SourceCircuitBreaker(1, Duration.ofMinutes(1), clock);
        var fanOut = new SourceFanOut(breaker, clock, SourceFanOutOptions.defaults());
        List<DataSourceAdapter<?>> adapters = List.of(new Counting("healthy", new AtomicInteger(), "A"));
        var limits = limits(2, Duration.ofSeconds(3), Duration.ofSeconds(10));

        fanOut.abandon(fanOut.plan(adapters, askAll(adapters), limits));

        assertThat(breaker.allows("healthy")).isTrue();
        assertThat(breaker.open("healthy")).isFalse();
    }

    @Test
    @DisplayName("1,000 mixed requests leave every bulkhead at maxConcurrency")
    void noPermitLeaks() throws Exception {
        var fanOut = new SourceFanOut(SourceCircuitBreaker.disabled(), Clock.systemUTC(), SourceFanOutOptions.defaults());
        List<Semaphore> bulkheads = Collections.synchronizedList(new ArrayList<>());
        Consumer<Semaphore> hook = bulkheads::add;
        fanOut.bulkheadObserver(hook);
        int concurrency = 2;
        var limits = limits(concurrency, Duration.ofMillis(40), Duration.ofMillis(120));

        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<CompletableFuture<Void>> all = new ArrayList<>();
            for (int i = 0; i < 1_000; i++) {
                int n = i;
                all.add(CompletableFuture.runAsync(() -> {
                    var calls = new AtomicInteger();
                    var stuck = new Blocking("stuck");
                    List<DataSourceAdapter<?>> adapters = new ArrayList<>(List.of(
                            new Counting("answers", calls, "A"),
                            new Counting("fails", calls, null),
                            new Counting("also-answers", calls, "B")));
                    if (n % 10 == 0) {
                        adapters.add(stuck);
                    }
                    if (n % 25 == 0) {
                        // A caller that gives up: the thread is interrupted while it waits.
                        Thread.currentThread().interrupt();
                    }
                    fanOut.fetchAll(adapters, askAll(adapters), limits);
                    Thread.interrupted();
                }, pool));
            }
            for (var f : all) {
                f.get(60, TimeUnit.SECONDS);
            }
        }

        assertThat(bulkheads).hasSize(1_000);
        assertThat(bulkheads).allSatisfy(b -> assertThat(b.availablePermits()).isEqualTo(concurrency));
    }
}

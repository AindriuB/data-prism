package io.github.aindriub.dataprism.orchestration;

import io.github.aindriub.dataprism.core.limits.RequestLimits;
import io.github.aindriub.dataprism.core.metrics.Metric;
import io.github.aindriub.dataprism.core.metrics.PrivacyMetrics;
import io.github.aindriub.dataprism.core.spi.DataRequest;
import io.github.aindriub.dataprism.core.spi.DataSourceAdapter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Calls that ignore their interrupt outlive their request (OD-W6-4 (a)). The fan-out counts them per source and
 * stops starting more than the source's bulkhead allows, so a source that never lets go is not called again.
 */
class SourceFanOutLeftoverCallsTest {

    private static final long WAIT_SECONDS = 10;

    /** Ignores interrupts until the test stops it, and counts how many of its calls are running at once. */
    private static final class Deaf implements DataSourceAdapter<String> {
        final CountDownLatch stop = new CountDownLatch(1);
        final AtomicInteger calls = new AtomicInteger();
        final AtomicInteger running = new AtomicInteger();
        final AtomicInteger peak = new AtomicInteger();
        private volatile CountDownLatch entered = new CountDownLatch(1);

        @Override
        public String sourceName() {
            return "deaf";
        }

        @Override
        public Class<String> responseType() {
            return String.class;
        }

        void expectCall() {
            entered = new CountDownLatch(1);
        }

        void awaitCall() throws InterruptedException {
            assertThat(entered.await(WAIT_SECONDS, TimeUnit.SECONDS)).as("the call began").isTrue();
        }

        @Override
        public String fetch(DataRequest request) {
            calls.incrementAndGet();
            peak.accumulateAndGet(running.incrementAndGet(), Math::max);
            entered.countDown();
            try {
                while (stop.getCount() > 0) {
                    try {
                        stop.await();
                    } catch (InterruptedException ignored) {
                        // does not stop when asked to
                    }
                }
                return "late";
            } finally {
                running.decrementAndGet();
            }
        }
    }

    private static final Map<String, DataRequest> ASK = Map.of("deaf", DataRequest.of("THING", "1"));

    private static RequestLimits limits(int concurrency) {
        return TestLimits.defaults().maxConcurrency(concurrency).build();
    }

    /** One request that the deadline ends while the deaf call is still running. */
    private static List<SourceFanOut.Fetched> requestCutByTheDeadline(SourceFanOut fanOut,
                                                                      SourceFanOutDeadlineTest.MutableClock clock,
                                                                      Deaf deaf, RequestLimits limits)
            throws Exception {
        List<DataSourceAdapter<?>> adapters = List.of(deaf);
        deaf.expectCall();
        var running = TestAsync.supply(() -> fanOut.fetchAll(adapters, ASK, limits));
        deaf.awaitCall();
        clock.advance(limits.requestTimeout());
        return running.get(WAIT_SECONDS, TimeUnit.SECONDS);
    }

    private static void awaitIdle(SourceFanOut fanOut, String source) {
        long limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        while (fanOut.leftovers(source) > 0 && System.nanoTime() < limit) {
            Thread.onSpinWait();
        }
        assertThat(fanOut.leftovers(source)).as("leftover calls ended").isZero();
    }

    @Test
    @DisplayName("leftover calls for a source stop at maxConcurrency, and later requests are told TIMED_OUT without a call")
    void leftoversAreCappedPerSource() throws Exception {
        var clock = new SourceFanOutDeadlineTest.MutableClock();
        var fanOut = new SourceFanOut(SourceCircuitBreaker.disabled(), clock, SourceFanOutOptions.defaults());
        var deaf = new Deaf();
        var limits = limits(2);
        try {
            for (int request = 0; request < 5; request++) {
                var results = requestCutByTheDeadlineOrRefused(fanOut, clock, deaf, limits, request < 2);
                assertThat(results).singleElement().satisfies(f -> {
                    assertThat(f.outcome().status()).isEqualTo(SourceOutcome.Status.TIMED_OUT);
                    assertThat(f.record()).isNull();
                });
            }
            assertThat(deaf.calls.get()).as("only the first two requests reached the source").isEqualTo(2);
            assertThat(deaf.peak.get()).as("each request left one behind").isEqualTo(2);
            assertThat(fanOut.leftovers("deaf")).isEqualTo(2);
        } finally {
            deaf.stop.countDown();
            awaitIdle(fanOut, "deaf");
        }
    }

    private static List<SourceFanOut.Fetched> requestCutByTheDeadlineOrRefused(
            SourceFanOut fanOut, SourceFanOutDeadlineTest.MutableClock clock, Deaf deaf, RequestLimits limits,
            boolean reachesTheSource) throws Exception {
        if (reachesTheSource) {
            return requestCutByTheDeadline(fanOut, clock, deaf, limits);
        }
        // Not callable: the answer is immediate, with no wait for any deadline.
        return fanOut.fetchAll(List.of(deaf), ASK, limits);
    }

    @Test
    @DisplayName("a source that is not callable is reported with a fixed detail that names no value")
    void aBusySourceIsReportedPlainly() throws Exception {
        var clock = new SourceFanOutDeadlineTest.MutableClock();
        var fanOut = new SourceFanOut(SourceCircuitBreaker.disabled(), clock, SourceFanOutOptions.defaults());
        var deaf = new Deaf();
        var limits = limits(1);
        try {
            requestCutByTheDeadline(fanOut, clock, deaf, limits);

            var refused = fanOut.fetchAll(List.of(deaf), ASK, limits);

            assertThat(refused).singleElement().satisfies(f -> {
                assertThat(f.outcome().status()).isEqualTo(SourceOutcome.Status.TIMED_OUT);
                assertThat(f.outcome().sourceName()).isEqualTo("deaf");
                assertThat(f.outcome().detail()).isEqualTo("source still busy with earlier calls");
            });
        } finally {
            deaf.stop.countDown();
            awaitIdle(fanOut, "deaf");
        }
    }

    @Test
    @DisplayName("when the leftover calls end, the source is callable again")
    void theSourceIsCallableOnceTheLeftoversEnd() throws Exception {
        var clock = new SourceFanOutDeadlineTest.MutableClock();
        var fanOut = new SourceFanOut(SourceCircuitBreaker.disabled(), clock, SourceFanOutOptions.defaults());
        var deaf = new Deaf();
        var limits = limits(1);
        requestCutByTheDeadline(fanOut, clock, deaf, limits);
        assertThat(fanOut.fetchAll(List.of(deaf), ASK, limits)).singleElement()
                .satisfies(f -> assertThat(f.outcome().status()).isEqualTo(SourceOutcome.Status.TIMED_OUT));
        assertThat(deaf.calls.get()).isEqualTo(1);

        deaf.stop.countDown();
        awaitIdle(fanOut, "deaf");

        assertThat(fanOut.fetchAll(List.of(deaf), ASK, limits)).singleElement().satisfies(f -> {
            assertThat(f.outcome().status()).isEqualTo(SourceOutcome.Status.ANSWERED);
            assertThat(f.record()).isEqualTo("late");
        });
        assertThat(deaf.calls.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("a source with nothing running leaves no entry behind")
    void countsDoNotAccumulate() {
        var fanOut = new SourceFanOut(SourceCircuitBreaker.disabled(), new SourceFanOutDeadlineTest.MutableClock(),
                SourceFanOutOptions.defaults());
        var deaf = new Deaf();
        deaf.stop.countDown();
        for (int i = 0; i < 20; i++) {
            fanOut.fetchAll(List.of(deaf), ASK, limits(1));
        }
        awaitIdle(fanOut, "deaf");
        assertThat(fanOut.trackedSources()).isZero();
    }

    @Test
    @DisplayName("no second half-open trial is admitted while an earlier trial call is still running")
    void noSecondTrialWhileTheFirstStillRuns() throws Exception {
        var clock = new SourceFanOutDeadlineTest.MutableClock();
        var breaker = new SourceCircuitBreaker(1, Duration.ofSeconds(5), clock);
        var fanOut = new SourceFanOut(breaker, clock, SourceFanOutOptions.defaults());
        var deaf = new Deaf();
        var limits = limits(4);
        breaker.record(new SourceOutcome("deaf", SourceOutcome.Status.FAILED, Duration.ZERO, "x"));
        clock.advance(Duration.ofSeconds(6));
        try {
            // The trial: it is called, ignores the deadline's interrupt, and the breaker re-opens.
            requestCutByTheDeadline(fanOut, clock, deaf, limits);
            clock.advance(Duration.ofSeconds(6));

            // On a worker, so that a regression fails this test instead of hanging the build.
            var results = TestAsync.supply(() -> fanOut.fetchAll(List.of(deaf), ASK, limits))
                    .get(WAIT_SECONDS, TimeUnit.SECONDS);

            assertThat(results).singleElement()
                    .satisfies(f -> assertThat(f.outcome().status()).isEqualTo(SourceOutcome.Status.TIMED_OUT));
            assertThat(deaf.calls.get()).as("the second trial was not called").isEqualTo(1);
            assertThat(breaker.admit("deaf").holdsTrial()).as("and was not even admitted").isTrue();
        } finally {
            deaf.stop.countDown();
            awaitIdle(fanOut, "deaf");
        }
    }

    @Test
    @DisplayName("once the trial call ends, the next trial is admitted and called")
    void theNextTrialRunsAfterTheFirstEnds() throws Exception {
        var clock = new SourceFanOutDeadlineTest.MutableClock();
        var breaker = new SourceCircuitBreaker(1, Duration.ofSeconds(5), clock);
        var fanOut = new SourceFanOut(breaker, clock, SourceFanOutOptions.defaults());
        var deaf = new Deaf();
        var limits = limits(4);
        breaker.record(new SourceOutcome("deaf", SourceOutcome.Status.FAILED, Duration.ZERO, "x"));
        clock.advance(Duration.ofSeconds(6));
        requestCutByTheDeadline(fanOut, clock, deaf, limits);
        deaf.stop.countDown();
        awaitIdle(fanOut, "deaf");
        clock.advance(Duration.ofSeconds(6));

        var results = fanOut.fetchAll(List.of(deaf), ASK, limits);

        assertThat(results).singleElement()
                .satisfies(f -> assertThat(f.outcome().status()).isEqualTo(SourceOutcome.Status.ANSWERED));
        assertThat(deaf.calls.get()).isEqualTo(2);
    }

    /** Answers when released, and counts how many calls are running at once. */
    private static final class Healthy implements DataSourceAdapter<String> {
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicInteger calls = new AtomicInteger();
        final AtomicInteger peak = new AtomicInteger();
        private final AtomicInteger running = new AtomicInteger();
        private volatile CountDownLatch entered = new CountDownLatch(1);

        @Override
        public String sourceName() {
            return "healthy";
        }

        @Override
        public Class<String> responseType() {
            return String.class;
        }

        @Override
        public String fetch(DataRequest request) {
            calls.incrementAndGet();
            peak.accumulateAndGet(running.incrementAndGet(), Math::max);
            entered.countDown();
            try {
                release.await();
                return "ok";
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted", e);
            } finally {
                running.decrementAndGet();
            }
        }
    }

    @Test
    @DisplayName("ordinary overlapping requests to a healthy source are all called: maxConcurrency is per request")
    void overlappingRequestsAreNotBusy() throws Exception {
        var fanOut = new SourceFanOut(SourceCircuitBreaker.disabled(), new SourceFanOutDeadlineTest.MutableClock(),
                SourceFanOutOptions.defaults());
        var healthy = new Healthy();
        var limits = limits(4);
        List<DataSourceAdapter<?>> adapters = List.of(healthy);
        Map<String, DataRequest> ask = Map.of("healthy", DataRequest.of("THING", "1"));
        List<CompletableFuture<List<SourceFanOut.Fetched>>> requests = new ArrayList<>();

        for (int request = 1; request <= 6; request++) {
            healthy.entered = new CountDownLatch(1);
            requests.add(TestAsync.supply(() -> fanOut.fetchAll(adapters, ask, limits)));
            assertThat(healthy.entered.await(WAIT_SECONDS, TimeUnit.SECONDS))
                    .as("request %d reached the source", request).isTrue();
        }
        assertThat(healthy.peak.get()).as("six calls at once with maxConcurrency 4").isEqualTo(6);
        healthy.release.countDown();

        for (var request : requests) {
            assertThat(request.get(WAIT_SECONDS, TimeUnit.SECONDS)).singleElement()
                    .satisfies(f -> assertThat(f.outcome().status()).isEqualTo(SourceOutcome.Status.ANSWERED));
        }
        assertThat(fanOut.trackedSources()).isZero();
    }

    /** Not a RuntimeException, and not a failing virtual machine. */
    private static final class BrokenClock extends Error {
        private static final long serialVersionUID = 1L;
    }

    @Test
    @DisplayName("an execution aborted after its leftovers were counted does not count them twice")
    void aLeftoverIsCountedOnceEvenWhenTheExecutionAborts() throws Exception {
        var clock = new SourceFanOutDeadlineTest.MutableClock();
        var armed = new AtomicBoolean();
        // The breaker's own clock fails once armed, in the middle of recording the timed-out call's outcome.
        var breakerClock = new Clock() {
            @Override
            public ZoneId getZone() {
                return clock.getZone();
            }

            @Override
            public Clock withZone(ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                if (armed.get()) {
                    throw new BrokenClock();
                }
                return clock.instant();
            }
        };
        var breaker = new SourceCircuitBreaker(1, Duration.ofSeconds(5), breakerClock);
        var fanOut = new SourceFanOut(breaker, clock, SourceFanOutOptions.defaults());
        var deaf = new Deaf();
        var limits = limits(2);
        deaf.expectCall();
        var running = TestAsync.supply(() -> fanOut.fetchAll(List.of(deaf), ASK, limits));
        try {
            deaf.awaitCall();
            armed.set(true);
            clock.advance(limits.requestTimeout());

            assertThatThrownBy(() -> running.get(WAIT_SECONDS, TimeUnit.SECONDS))
                    .hasRootCauseInstanceOf(BrokenClock.class);
        } finally {
            deaf.stop.countDown();
        }
        awaitIdle(fanOut, "deaf");

        assertThat(fanOut.leftovers("deaf")).as("counted once, uncounted once").isZero();
        assertThat(fanOut.trackedSources()).isZero();
    }

    @Test
    @DisplayName("a metrics implementation that throws an Error does not end the request")
    void anErrorFromMetricsIsContained() throws Exception {
        PrivacyMetrics broken = new PrivacyMetrics() {
            @Override
            public void increment(Metric metric) {
            }

            @Override
            public void increment(Metric metric, String sourceName) {
                throw new BrokenClock();
            }

            @Override
            public void record(Metric metric, String sourceName, Duration duration) {
                throw new BrokenClock();
            }
        };
        var fanOut = new SourceFanOut(SourceCircuitBreaker.disabled(), new SourceFanOutDeadlineTest.MutableClock(),
                SourceFanOutOptions.defaults().withMetrics(broken));
        var deaf = new Deaf();
        deaf.stop.countDown();

        var results = fanOut.fetchAll(List.of(deaf), ASK, limits(2));

        assertThat(results).singleElement()
                .satisfies(f -> assertThat(f.outcome().status()).isEqualTo(SourceOutcome.Status.ANSWERED));
    }

    @Test
    @DisplayName("a failing virtual machine is not contained as a metrics failure")
    void aVirtualMachineErrorFromMetricsIsNotSwallowed() {
        PrivacyMetrics failing = new PrivacyMetrics() {
            @Override
            public void increment(Metric metric) {
            }

            @Override
            public void increment(Metric metric, String sourceName) {
            }

            @Override
            public void record(Metric metric, String sourceName, Duration duration) {
                throw new InternalError("synthetic");
            }
        };
        var fanOut = new SourceFanOut(SourceCircuitBreaker.disabled(), new SourceFanOutDeadlineTest.MutableClock(),
                SourceFanOutOptions.defaults().withMetrics(failing));
        var deaf = new Deaf();
        deaf.stop.countDown();

        assertThatThrownBy(() -> fanOut.fetchAll(List.of(deaf), ASK, limits(2))).isInstanceOf(InternalError.class);
    }
}

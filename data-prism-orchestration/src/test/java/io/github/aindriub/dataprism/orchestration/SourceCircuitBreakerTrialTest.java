package io.github.aindriub.dataprism.orchestration;

import io.github.aindriub.dataprism.core.correlation.CorrelationIdPolicy;
import io.github.aindriub.dataprism.core.correlation.CorrelationMdc;
import io.github.aindriub.dataprism.core.metrics.Metric;
import io.github.aindriub.dataprism.core.metrics.PrivacyMetrics;
import io.github.aindriub.dataprism.core.spi.SourceCallContext;
import org.slf4j.spi.MDCAdapter;
import io.github.aindriub.dataprism.core.limits.RequestLimits;
import io.github.aindriub.dataprism.core.spi.DataRequest;
import io.github.aindriub.dataprism.core.spi.DataSourceAdapter;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SourceCircuitBreakerTrialTest {

    static DataSourceAdapter<String> adapter(String name, BlockingQueue<String> entered) {
        return new DataSourceAdapter<>() {
            public String sourceName() { return name; }
            public Class<String> responseType() { return String.class; }
            public String fetch(DataRequest r) {
                entered.add(name);
                try { new CountDownLatch(1).await(); } catch (InterruptedException e) { throw new IllegalStateException(e); }
                return "x";
            }
        };
    }

    @Test
    void aNeverStartedSourceLeavesTheBreakersTrialAvailable() throws Exception {
        var clock = new SourceFanOutDeadlineTest.MutableClock();
        var breaker = new SourceCircuitBreaker(1, Duration.ofSeconds(5), clock);
        var fanOut = new SourceFanOut(breaker, clock, SourceFanOutOptions.defaults());
        var entered = new LinkedBlockingQueue<String>();
        List<DataSourceAdapter<?>> adapters = List.of(adapter("slow", entered), adapter("a", entered));
        Map<String, DataRequest> req = new LinkedHashMap<>();
        adapters.forEach(x -> req.put(x.sourceName(), DataRequest.of("THING", "1")));
        var limits = TestLimits.defaults().perSourceTimeout(Duration.ofSeconds(10)).maxConcurrency(1).build();
        var run = TestAsync.supply(() -> fanOut.fetchAll(adapters, req, limits));
        String first = entered.poll(10, TimeUnit.SECONDS);
        assertThat(first).isNotNull();
        String queued = first.equals("a") ? "slow" : "a";
        // Another request's call to "a" fails: the breaker opens.
        breaker.record(new SourceOutcome(queued, SourceOutcome.Status.FAILED, Duration.ZERO, "other request"));
        clock.advance(Duration.ofSeconds(6));
        clock.advance(Duration.ofSeconds(4));
        run.get(10, TimeUnit.SECONDS);
        clock.advance(Duration.ofHours(1));
        assertThat(entered).isEmpty();
        assertThat(breaker.allows(queued)).as("a trial an hour later").isTrue();
    }

    private static SourceOutcome failed(String name) {
        return new SourceOutcome(name, SourceOutcome.Status.FAILED, Duration.ZERO, "x");
    }

    private static SourceOutcome answered(String name) {
        return SourceOutcome.answered(name, Duration.ZERO);
    }

    private static SourceCircuitBreaker openBreakerPastCooldown(SourceFanOutDeadlineTest.MutableClock clock) {
        var breaker = new SourceCircuitBreaker(1, Duration.ofSeconds(5), clock);
        breaker.record(failed("s"));
        clock.advance(Duration.ofSeconds(6));
        return breaker;
    }

    @Test
    void releasingTheTrialLetsTheNextCallerTakeIt() {
        var clock = new SourceFanOutDeadlineTest.MutableClock();
        var breaker = openBreakerPastCooldown(clock);
        var mine = breaker.admit("s");
        assertThat(mine.holdsTrial()).isTrue();
        assertThat(breaker.admit("s").allowed()).as("the trial is single").isFalse();

        breaker.releaseTrial("s", mine);

        assertThat(breaker.admit("s").holdsTrial()).as("taken again after the release").isTrue();
    }

    @Test
    void aReleaseIsNotAFailureAndDoesNotRestartTheCooldown() {
        var clock = new SourceFanOutDeadlineTest.MutableClock();
        var breaker = openBreakerPastCooldown(clock);
        breaker.releaseTrial("s", breaker.admit("s"));

        // Still past the original cooldown: a restarted cooldown would refuse here.
        assertThat(breaker.admit("s").allowed()).isTrue();
    }

    @Test
    void aReleaseByAnotherCallerDoesNotEndTheRunningTrial() {
        var clock = new SourceFanOutDeadlineTest.MutableClock();
        var breaker = new SourceCircuitBreaker(1, Duration.ofSeconds(5), clock);
        var closedThen = breaker.admit("s");
        assertThat(closedThen.holdsTrial()).isFalse();
        breaker.record(failed("s"));
        clock.advance(Duration.ofSeconds(6));
        var running = breaker.admit("s");
        assertThat(running.holdsTrial()).isTrue();

        breaker.releaseTrial("s", closedThen);

        assertThat(breaker.admit("s").allowed()).as("the other request's trial is still running").isFalse();
        breaker.releaseTrial("s", running);
        assertThat(breaker.admit("s").allowed()).isTrue();
    }

    @Test
    void aStaleReleaseAfterAnOutcomeWasRecordedDoesNothing() {
        var clock = new SourceFanOutDeadlineTest.MutableClock();
        var breaker = openBreakerPastCooldown(clock);
        var first = breaker.admit("s");
        breaker.record(failed("s"));
        clock.advance(Duration.ofSeconds(6));
        var second = breaker.admit("s");
        assertThat(second.holdsTrial()).isTrue();

        breaker.releaseTrial("s", first);

        assertThat(breaker.admit("s").allowed()).as("the second trial is not the first's to release").isFalse();
    }

    @Test
    void releasingOnAClosedBreakerChangesNothing() {
        var clock = new SourceFanOutDeadlineTest.MutableClock();
        var breaker = new SourceCircuitBreaker(2, Duration.ofSeconds(5), clock);
        breaker.record(failed("s"));
        var closed = breaker.admit("s");

        breaker.releaseTrial("s", closed);
        breaker.releaseTrial("s", new SourceCircuitBreaker.Admission(true, 1));
        breaker.releaseTrial("unknown", new SourceCircuitBreaker.Admission(true, 1));

        assertThat(breaker.admit("s").allowed()).isTrue();
        breaker.record(failed("s"));
        assertThat(breaker.admit("s").allowed()).as("the failure streak was neither reset nor extended").isFalse();
    }

    @Test
    void releasingOnAnOpenBreakerWithNoTrialTakenChangesNothing() {
        var clock = new SourceFanOutDeadlineTest.MutableClock();
        var breaker = new SourceCircuitBreaker(1, Duration.ofSeconds(5), clock);
        breaker.record(failed("s"));

        breaker.releaseTrial("s", new SourceCircuitBreaker.Admission(true, 1));

        assertThat(breaker.admit("s").allowed()).as("still inside the cooldown").isFalse();
    }

    @Test
    void anAbandonedPlanHandsBackItsTrialAndChargesNoFailure() {
        var clock = new SourceFanOutDeadlineTest.MutableClock();
        var breaker = openBreakerPastCooldown(clock);
        var fanOut = new SourceFanOut(breaker, clock, SourceFanOutOptions.defaults());
        var healthy = adapter("s", new LinkedBlockingQueue<>());
        List<DataSourceAdapter<?>> adapters = List.of(healthy);
        Map<String, DataRequest> req = Map.of("s", DataRequest.of("THING", "1"));
        var limits = TestLimits.defaults().perSourceTimeout(Duration.ofSeconds(10)).maxConcurrency(1).build();

        fanOut.abandon(fanOut.plan(adapters, req, limits));

        assertThat(breaker.admit("s").holdsTrial()).as("the trial is free again").isTrue();
    }

    @Test
    void aMetricsFailureDoesNotLeaveLaterSourcesUnrecorded() {
        var clock = new SourceFanOutDeadlineTest.MutableClock();
        var breaker = new SourceCircuitBreaker(1, Duration.ofMinutes(5), clock);
        PrivacyMetrics throwing =
                new PrivacyMetrics() {
                    @Override
                    public void increment(Metric metric) {
                    }

                    @Override
                    public void increment(Metric metric, String sourceName) {
                        throw new IllegalStateException("metrics down");
                    }

                    @Override
                    public void record(Metric metric, String sourceName,
                                       Duration duration) {
                        throw new IllegalStateException("metrics down");
                    }
                };
        var fanOut = new SourceFanOut(breaker, clock, SourceFanOutOptions.defaults().withMetrics(throwing));
        DataSourceAdapter<String> failing = new DataSourceAdapter<>() {
            public String sourceName() { return "f"; }
            public Class<String> responseType() { return String.class; }
            public String fetch(DataRequest r) { throw new IllegalStateException("down"); }
        };
        DataSourceAdapter<String> failing2 = new DataSourceAdapter<>() {
            public String sourceName() { return "g"; }
            public Class<String> responseType() { return String.class; }
            public String fetch(DataRequest r) { throw new IllegalStateException("down"); }
        };
        List<DataSourceAdapter<?>> adapters = List.of(failing, failing2);
        Map<String, DataRequest> req = new LinkedHashMap<>();
        adapters.forEach(x -> req.put(x.sourceName(), DataRequest.of("THING", "1")));
        var limits = TestLimits.defaults().perSourceTimeout(Duration.ofSeconds(10)).maxConcurrency(2).build();

        fanOut.fetchAll(adapters, req, limits);

        assertThat(breaker.admit("f").allowed()).as("f's failure was recorded").isFalse();
        assertThat(breaker.admit("g").allowed()).as("g's failure was recorded").isFalse();
    }

    @Test
    void aNeverStartedSourceDoesNotEndAnotherRequestsRunningTrial() throws Exception {
        var clock = new SourceFanOutDeadlineTest.MutableClock();
        var breaker = new SourceCircuitBreaker(1, Duration.ofSeconds(5), clock);
        var fanOut = new SourceFanOut(breaker, clock, SourceFanOutOptions.defaults());
        var entered = new LinkedBlockingQueue<String>();
        List<DataSourceAdapter<?>> adapters = List.of(adapter("slow", entered), adapter("a", entered));
        Map<String, DataRequest> req = new LinkedHashMap<>();
        adapters.forEach(x -> req.put(x.sourceName(), DataRequest.of("THING", "1")));
        var limits = TestLimits.defaults().perSourceTimeout(Duration.ofSeconds(10)).maxConcurrency(1).build();
        var run = TestAsync.supply(() -> fanOut.fetchAll(adapters, req, limits));
        String first = entered.poll(10, TimeUnit.SECONDS);
        assertThat(first).isNotNull();
        String queued = first.equals("a") ? "slow" : "a";
        // Another request's failure opens the queued source's breaker; its cooldown passes; that request
        // then takes the half-open trial and is still running it when this request's deadline arrives.
        breaker.record(failed(queued));
        clock.advance(Duration.ofSeconds(6));
        assertThat(breaker.admit(queued).holdsTrial()).isTrue();
        clock.advance(Duration.ofSeconds(4));
        run.get(10, TimeUnit.SECONDS);

        clock.advance(Duration.ofSeconds(6));
        assertThat(breaker.admit(queued).allowed())
                .as("the other request's trial is still held: this request never called the source").isFalse();
    }

    @Test
    void aNeverStartedSourceHandsBackItsOwnTrialWithoutAFailure() throws Exception {
        var clock = new SourceFanOutDeadlineTest.MutableClock();
        var breaker = new SourceCircuitBreaker(1, Duration.ofSeconds(5), clock);
        var fanOut = new SourceFanOut(breaker, clock, SourceFanOutOptions.defaults());
        breaker.record(failed("slow"));
        breaker.record(failed("a"));
        clock.advance(Duration.ofSeconds(6));
        var entered = new LinkedBlockingQueue<String>();
        List<DataSourceAdapter<?>> adapters = List.of(adapter("slow", entered), adapter("a", entered));
        Map<String, DataRequest> req = new LinkedHashMap<>();
        adapters.forEach(x -> req.put(x.sourceName(), DataRequest.of("THING", "1")));
        var limits = TestLimits.defaults().perSourceTimeout(Duration.ofSeconds(10)).maxConcurrency(1).build();
        var run = TestAsync.supply(() -> fanOut.fetchAll(adapters, req, limits));
        String first = entered.poll(10, TimeUnit.SECONDS);
        assertThat(first).isNotNull();
        String queued = first.equals("a") ? "slow" : "a";
        clock.advance(Duration.ofSeconds(10));
        run.get(10, TimeUnit.SECONDS);

        // The source that ran timed out and re-opened at the deadline; the one never called did not.
        clock.advance(Duration.ofSeconds(3));
        assertThat(breaker.admit(first).allowed()).as("the called source is back in its cooldown").isFalse();
        assertThat(breaker.admit(queued).holdsTrial())
                .as("the source never called is neither penalised nor stuck").isTrue();
    }

    /** Remembers which sources the fan-out reported latency and errors for. */
    private static final class RecordedMetrics implements PrivacyMetrics {
        final List<String> latencies = Collections.synchronizedList(new ArrayList<>());
        final List<String> errors = Collections.synchronizedList(new ArrayList<>());

        @Override
        public void increment(Metric metric) {
        }

        @Override
        public void increment(Metric metric, String sourceName) {
            if (metric == Metric.SOURCE_ERRORS) {
                errors.add(sourceName);
            }
        }

        @Override
        public void record(Metric metric, String sourceName, Duration duration) {
            if (metric == Metric.SOURCE_LATENCY) {
                latencies.add(sourceName);
            }
        }
    }

    private static Map<String, DataRequest> ask(List<DataSourceAdapter<?>> adapters) {
        Map<String, DataRequest> req = new LinkedHashMap<>();
        adapters.forEach(x -> req.put(x.sourceName(), DataRequest.of("THING", "1")));
        return req;
    }

    private static RequestLimits oneAtATime() {
        return TestLimits.defaults().perSourceTimeout(Duration.ofSeconds(10)).maxConcurrency(1).build();
    }

    @Test
    void aCallCutByTheDeadlineBeforeItsOwnTimeoutIsNotABreakerFailure() throws Exception {
        var clock = new SourceFanOutDeadlineTest.MutableClock();
        var breaker = new SourceCircuitBreaker(1, Duration.ofSeconds(5), clock);
        var metrics = new RecordedMetrics();
        var fanOut = new SourceFanOut(breaker, clock, SourceFanOutOptions.defaults().withMetrics(metrics));
        breaker.record(failed("one"));
        breaker.record(failed("two"));
        clock.advance(Duration.ofSeconds(6));
        var order = new LinkedBlockingQueue<SourceFanOutDeadlineTest.Blocking>();
        var one = new SourceFanOutDeadlineTest.Blocking("one");
        var two = new SourceFanOutDeadlineTest.Blocking("two");
        one.order = order;
        two.order = order;
        List<DataSourceAdapter<?>> adapters = List.of(one, two);
        var limits = oneAtATime();
        var run = TestAsync.supply(() -> fanOut.fetchAll(adapters, ask(adapters), limits));

        var first = order.poll(10, TimeUnit.SECONDS);
        assertThat(first).isNotNull();
        clock.advance(Duration.ofSeconds(4));
        first.release.countDown();
        var second = order.poll(10, TimeUnit.SECONDS);
        assertThat(second).isNotNull();
        // The second call began at 4 s of a 10 s deadline, so the deadline ends it 6 s in: before its own 10 s.
        clock.advance(Duration.ofSeconds(6));
        var results = run.get(10, TimeUnit.SECONDS);

        assertThat(results).filteredOn(f -> f.outcome().sourceName().equals(second.sourceName())).singleElement()
                .satisfies(f -> assertThat(f.outcome().status()).isEqualTo(SourceOutcome.Status.TIMED_OUT));
        assertThat(breaker.admit(second.sourceName()).holdsTrial())
                .as("no failure was recorded and the trial was handed back").isTrue();
        assertThat(metrics.errors).as("a deadline cut is not a source error").isEmpty();
    }

    @Test
    void aCallerInterruptRecordsNothingForRunningSources() throws Exception {
        var clock = new SourceFanOutDeadlineTest.MutableClock();
        var breaker = new SourceCircuitBreaker(1, Duration.ofSeconds(5), clock);
        var metrics = new RecordedMetrics();
        var fanOut = new SourceFanOut(breaker, clock, SourceFanOutOptions.defaults().withMetrics(metrics));
        breaker.record(failed("s"));
        clock.advance(Duration.ofSeconds(6));
        var blocking = new SourceFanOutDeadlineTest.Blocking("s");
        List<DataSourceAdapter<?>> adapters = List.of(blocking);
        var limits = oneAtATime();
        var caller = new AtomicReference<Thread>();
        var run = TestAsync.supply(() -> {
            caller.set(Thread.currentThread());
            return fanOut.fetchAll(adapters, ask(adapters), limits);
        });
        assertThat(blocking.entered.await(10, TimeUnit.SECONDS)).isTrue();

        caller.get().interrupt();
        var results = run.get(10, TimeUnit.SECONDS);

        assertThat(results).singleElement().satisfies(f -> {
            assertThat(f.outcome().status()).isEqualTo(SourceOutcome.Status.FAILED);
            assertThat(f.outcome().detail()).isEqualTo("interrupted");
        });
        assertThat(breaker.admit("s").holdsTrial()).as("the trial was handed back, no failure recorded").isTrue();
        assertThat(metrics.errors).isEmpty();
    }

    @Test
    void anExecutionThatFailsCancelsEverythingAndHandsBackTheTrials() {
        var clock = new SourceFanOutDeadlineTest.MutableClock();
        var breaker = new SourceCircuitBreaker(1, Duration.ofSeconds(5), clock);
        var fanOut = new SourceFanOut(breaker, clock, SourceFanOutOptions.defaults());
        breaker.record(failed("s"));
        clock.advance(Duration.ofSeconds(6));
        var blocking = new SourceFanOutDeadlineTest.Blocking("s");
        List<DataSourceAdapter<?>> adapters = List.of(blocking);
        var limits = oneAtATime();
        fanOut.bulkheadObserver(bulkhead -> {
            throw new IllegalStateException("observer failed");
        });
        var plan = fanOut.plan(adapters, ask(adapters), limits);

        assertThatThrownBy(() -> fanOut.execute(plan, limits))
                .isInstanceOfSatisfying(SourceFanOut.ExecutionFailure.class, failure -> {
                    assertThat(failure.notStarted()).isEqualTo(1);
                    assertThat(failure.failure()).isInstanceOf(IllegalStateException.class);
                });

        assertThat(blocking.calls.get()).isZero();
        assertThat(breaker.admit("s").holdsTrial()).as("the trial is free again").isTrue();
    }

    @Test
    void aSourceNeverCalledIsNotCountedInLatencyOrErrors() throws Exception {
        var clock = new SourceFanOutDeadlineTest.MutableClock();
        var metrics = new RecordedMetrics();
        var fanOut = new SourceFanOut(SourceCircuitBreaker.disabled(), clock,
                SourceFanOutOptions.defaults().withMetrics(metrics));
        var order = new LinkedBlockingQueue<SourceFanOutDeadlineTest.Blocking>();
        var one = new SourceFanOutDeadlineTest.Blocking("one");
        var two = new SourceFanOutDeadlineTest.Blocking("two");
        one.order = order;
        two.order = order;
        List<DataSourceAdapter<?>> adapters = List.of(one, two);
        var limits = oneAtATime();
        var run = TestAsync.supply(() -> fanOut.fetchAll(adapters, ask(adapters), limits));
        var holder = order.poll(10, TimeUnit.SECONDS);
        assertThat(holder).isNotNull();

        clock.advance(Duration.ofSeconds(10));
        run.get(10, TimeUnit.SECONDS);

        assertThat(metrics.latencies).containsExactly(holder.sourceName());
        assertThat(metrics.errors).containsExactly(holder.sourceName());
    }

    @Test
    void aCallThatFailsBeforeItStartsEndsTheRequestAtOnceAndReleasesItsRead() throws Exception {
        var clock = new SourceFanOutDeadlineTest.MutableClock();
        var breaker = new SourceCircuitBreaker(1, Duration.ofSeconds(5), clock);
        var metrics = new RecordedMetrics();
        MDCAdapter failing = (MDCAdapter) Proxy.newProxyInstance(
                MDCAdapter.class.getClassLoader(), new Class<?>[] {MDCAdapter.class}, (proxy, method, args) -> {
                    throw new IllegalStateException("mdc down");
                });
        var fanOut = new SourceFanOut(breaker, clock, SourceFanOutOptions.defaults()
                .withMetrics(metrics).withMdc(CorrelationMdc.of("transaction_id", failing)));
        var blocking = new SourceFanOutDeadlineTest.Blocking("s");
        List<DataSourceAdapter<?>> adapters = List.of(blocking);
        var id = CorrelationIdPolicy.opaque("[A-Za-z0-9-]{1,64}").validate("ext-1");
        Map<String, DataRequest> req = Map.of("s",
                DataRequest.of("THING", "1").withContext(new SourceCallContext(id)));
        var limits = oneAtATime();

        // The clock never moves: only a waiter that notices the failed call by itself can return.
        var execution = TestAsync.supply(() -> fanOut.execute(fanOut.plan(adapters, req, limits), limits))
                .get(10, TimeUnit.SECONDS);

        assertThat(execution.results()).singleElement().satisfies(f -> {
            assertThat(f.outcome().status()).isEqualTo(SourceOutcome.Status.FAILED);
            assertThat(f.outcome().detail()).isEqualTo("IllegalStateException");
        });
        assertThat(execution.notStarted()).isEqualTo(1);
        assertThat(blocking.calls.get()).isZero();
        assertThat(metrics.errors).containsExactly("s");
        assertThat(breaker.admit("s").allowed()).as("the source is not blamed").isTrue();
    }
}

package io.github.aindriub.dataprism.orchestration;

import io.github.aindriub.dataprism.core.limits.RequestLimits;
import io.github.aindriub.dataprism.core.spi.DataRequest;
import io.github.aindriub.dataprism.core.spi.DataSourceAdapter;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class SourceCircuitBreakerTrialTest {

    static DataSourceAdapter<String> adapter(String name, java.util.concurrent.BlockingQueue<String> entered) {
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
        var entered = new java.util.concurrent.LinkedBlockingQueue<String>();
        List<DataSourceAdapter<?>> adapters = List.of(adapter("slow", entered), adapter("a", entered));
        Map<String, DataRequest> req = new LinkedHashMap<>();
        adapters.forEach(x -> req.put(x.sourceName(), DataRequest.of("THING", "1")));
        var limits = new RequestLimits(8, 500, 512 * 1024, Duration.ofSeconds(10), 1, 100, Duration.ofSeconds(10), 50_000);
        var run = CompletableFuture.supplyAsync(() -> fanOut.fetchAll(adapters, req, limits));
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
        var healthy = adapter("s", new java.util.concurrent.LinkedBlockingQueue<>());
        List<DataSourceAdapter<?>> adapters = List.of(healthy);
        Map<String, DataRequest> req = Map.of("s", DataRequest.of("THING", "1"));
        var limits = new RequestLimits(8, 500, 512 * 1024, Duration.ofSeconds(10), 1, 100, Duration.ofSeconds(10), 50_000);

        fanOut.abandon(fanOut.plan(adapters, req, limits));

        assertThat(breaker.admit("s").holdsTrial()).as("the trial is free again").isTrue();
    }

    @Test
    void aMetricsFailureDoesNotLeaveLaterSourcesUnrecorded() {
        var clock = new SourceFanOutDeadlineTest.MutableClock();
        var breaker = new SourceCircuitBreaker(1, Duration.ofMinutes(5), clock);
        io.github.aindriub.dataprism.core.metrics.PrivacyMetrics throwing =
                new io.github.aindriub.dataprism.core.metrics.PrivacyMetrics() {
                    @Override
                    public void increment(io.github.aindriub.dataprism.core.metrics.Metric metric) {
                    }

                    @Override
                    public void increment(io.github.aindriub.dataprism.core.metrics.Metric metric, String sourceName) {
                        throw new IllegalStateException("metrics down");
                    }

                    @Override
                    public void record(io.github.aindriub.dataprism.core.metrics.Metric metric, String sourceName,
                                       Duration duration) {
                        throw new IllegalStateException("metrics down");
                    }
                };
        var fanOut = new SourceFanOut(breaker, clock, SourceFanOutOptions.defaults().withMetrics(throwing));
        var calls = new java.util.concurrent.atomic.AtomicInteger();
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
        var limits = new RequestLimits(8, 500, 512 * 1024, Duration.ofSeconds(10), 2, 100, Duration.ofSeconds(10), 50_000);

        fanOut.fetchAll(adapters, req, limits);

        assertThat(breaker.admit("f").allowed()).as("f's failure was recorded").isFalse();
        assertThat(breaker.admit("g").allowed()).as("g's failure was recorded").isFalse();
        assertThat(calls.get()).isZero();
    }

    @Test
    void aNeverStartedSourceDoesNotEndAnotherRequestsRunningTrial() throws Exception {
        var clock = new SourceFanOutDeadlineTest.MutableClock();
        var breaker = new SourceCircuitBreaker(1, Duration.ofSeconds(5), clock);
        var fanOut = new SourceFanOut(breaker, clock, SourceFanOutOptions.defaults());
        var entered = new java.util.concurrent.LinkedBlockingQueue<String>();
        List<DataSourceAdapter<?>> adapters = List.of(adapter("slow", entered), adapter("a", entered));
        Map<String, DataRequest> req = new LinkedHashMap<>();
        adapters.forEach(x -> req.put(x.sourceName(), DataRequest.of("THING", "1")));
        var limits = new RequestLimits(8, 500, 512 * 1024, Duration.ofSeconds(10), 1, 100, Duration.ofSeconds(10), 50_000);
        var run = CompletableFuture.supplyAsync(() -> fanOut.fetchAll(adapters, req, limits));
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
        var entered = new java.util.concurrent.LinkedBlockingQueue<String>();
        List<DataSourceAdapter<?>> adapters = List.of(adapter("slow", entered), adapter("a", entered));
        Map<String, DataRequest> req = new LinkedHashMap<>();
        adapters.forEach(x -> req.put(x.sourceName(), DataRequest.of("THING", "1")));
        var limits = new RequestLimits(8, 500, 512 * 1024, Duration.ofSeconds(10), 1, 100, Duration.ofSeconds(10), 50_000);
        var run = CompletableFuture.supplyAsync(() -> fanOut.fetchAll(adapters, req, limits));
        String first = entered.poll(10, TimeUnit.SECONDS);
        assertThat(first).isNotNull();
        String queued = first.equals("a") ? "slow" : "a";
        clock.advance(Duration.ofSeconds(4));
        run.get(10, TimeUnit.SECONDS);

        // The source that ran timed out and re-opened at the deadline; the one never called did not.
        clock.advance(Duration.ofSeconds(3));
        assertThat(breaker.admit(first).allowed()).as("the called source is back in its cooldown").isFalse();
        assertThat(breaker.admit(queued).holdsTrial())
                .as("the source never called is neither penalised nor stuck").isTrue();
    }
}

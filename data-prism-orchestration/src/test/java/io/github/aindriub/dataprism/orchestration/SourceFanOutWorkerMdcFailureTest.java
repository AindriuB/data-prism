package io.github.aindriub.dataprism.orchestration;

import io.github.aindriub.dataprism.core.correlation.CorrelationIdPolicy;
import io.github.aindriub.dataprism.core.correlation.CorrelationMdc;
import io.github.aindriub.dataprism.core.metrics.Metric;
import io.github.aindriub.dataprism.core.metrics.PrivacyMetrics;
import io.github.aindriub.dataprism.core.spi.DataRequest;
import io.github.aindriub.dataprism.core.spi.DataSourceAdapter;
import io.github.aindriub.dataprism.core.spi.SourceCallContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.spi.MDCAdapter;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** A failure on the call's worker thread before the gate reaches INVOKING settles the call as never called. */
class SourceFanOutWorkerMdcFailureTest {

    private static final class Counting implements DataSourceAdapter<String> {
        final AtomicInteger calls = new AtomicInteger();

        @Override public String sourceName() { return "s"; }
        @Override public Class<String> responseType() { return String.class; }
        @Override public String fetch(DataRequest request) {
            calls.incrementAndGet();
            return "x";
        }
    }

    private static final class Metrics implements PrivacyMetrics {
        final List<String> events = Collections.synchronizedList(new ArrayList<>());

        @Override public void increment(Metric metric) { events.add(metric.name()); }
        @Override public void increment(Metric metric, String sourceName) { events.add(metric.name() + ":" + sourceName); }
        @Override public void record(Metric metric, String sourceName, Duration d) { events.add(metric.name() + ":" + sourceName); }
    }

    @Test
    @DisplayName("worker MDC failure: never called, read and slots back, trial handed back, no breaker or latency effect")
    void workerMdcFailureIsNeverCalled() throws Exception {
        var clock = new SourceFanOutDeadlineTest.MutableClock();
        var breaker = new SourceCircuitBreaker(1, Duration.ofSeconds(5), clock);
        breaker.record(new SourceOutcome("s", SourceOutcome.Status.FAILED, Duration.ZERO, "x"));
        clock.advance(Duration.ofSeconds(6));
        var metrics = new Metrics();
        // Fails on the call's platform worker only: the virtual thread's own MDC works.
        MDCAdapter failingOnWorker = (MDCAdapter) Proxy.newProxyInstance(MDCAdapter.class.getClassLoader(),
                new Class<?>[] {MDCAdapter.class}, (proxy, method, args) -> {
                    if (!Thread.currentThread().isVirtual()) {
                        throw new IllegalStateException("mdc down");
                    }
                    return null;
                });
        var slots = new SourceCallSlots(3, 2);
        var fanOut = new SourceFanOut(breaker, clock, SourceFanOutOptions.defaults().withMetrics(metrics)
                .withMdc(CorrelationMdc.of("transaction_id", failingOnWorker)), slots);
        var adapter = new Counting();
        List<DataSourceAdapter<?>> adapters = List.of(adapter);
        var id = CorrelationIdPolicy.opaque("[A-Za-z0-9-]{1,64}").validate("ext-1");
        Map<String, DataRequest> req = Map.of("s",
                DataRequest.of("THING", "1").withContext(new SourceCallContext(id)));
        var limits = TestLimits.defaults().perSourceTimeout(Duration.ofSeconds(10)).maxConcurrency(1).build();

        var execution = TestAsync.supply(() -> fanOut.execute(fanOut.plan(adapters, req, limits), limits))
                .get(10, TimeUnit.SECONDS);

        assertThat(adapter.calls.get()).isZero();
        assertThat(execution.notStarted()).isEqualTo(1);
        assertThat(execution.results()).singleElement().satisfies(f ->
                assertThat(f.outcome().status()).isEqualTo(SourceOutcome.Status.FAILED));
        long limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while ((slots.poolPermits() != 3 || slots.sourcePermits("s") != 2) && System.nanoTime() < limit) {
            Thread.onSpinWait();
        }
        assertThat(slots.poolPermits()).isEqualTo(3);
        assertThat(slots.sourcePermits("s")).isEqualTo(2);
        assertThat(metrics.events).doesNotContain("SOURCE_LATENCY:s");
        // As the same failure on the virtual thread (base): the error is counted once, the source is not blamed.
        assertThat(metrics.events.stream().filter("SOURCE_ERRORS:s"::equals).count()).isEqualTo(1);
        assertThat(breaker.admit("s").holdsTrial()).as("trial was handed back, and no failure re-opened it").isTrue();
    }
}

package io.github.aindriub.dataprism.orchestration;

import io.github.aindriub.dataprism.core.DataRequest;
import io.github.aindriub.dataprism.core.DataSourceAdapter;
import io.github.aindriub.dataprism.core.Metric;
import io.github.aindriub.dataprism.core.PrivacyMetrics;
import io.github.aindriub.dataprism.core.RequestLimits;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class SourceFanOutTest {

    /** A source whose behaviour is dictated by the test. */
    private record Stub(String sourceName, Duration delay, RuntimeException failure, String answer)
            implements DataSourceAdapter<String> {

        @Override
        public Class<String> responseType() {
            return String.class;
        }

        @Override
        public String fetch(DataRequest request) {
            if (!delay.isZero()) {
                try {
                    Thread.sleep(delay.toMillis());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("interrupted", e);
                }
            }
            if (failure != null) {
                throw failure;
            }
            return answer;
        }

        static Stub answering(String name, String answer) {
            return new Stub(name, Duration.ZERO, null, answer);
        }

        static Stub slow(String name, Duration delay) {
            return new Stub(name, delay, null, "eventually");
        }

        static Stub failing(String name) {
            return new Stub(name, Duration.ZERO, new IllegalStateException("boom"), null);
        }
    }

    private static Map<String, DataRequest> askAll(List<DataSourceAdapter<?>> adapters) {
        Map<String, DataRequest> requests = new LinkedHashMap<>();
        adapters.forEach(a -> requests.put(a.sourceName(), DataRequest.of("THING", "s-1")));
        return requests;
    }

    private static RequestLimits limits(int maxSources, Duration timeout, int concurrency) {
        return new RequestLimits(maxSources, 500, 512 * 1024, timeout, concurrency, 100);
    }

    private SourceFanOut fanOut() {
        return new SourceFanOut(SourceCircuitBreaker.disabled(), Clock.systemUTC());
    }

    @Test
    @DisplayName("a slow source times out without failing the whole request")
    void slowSourceDoesNotFailTheRequest() {
        List<DataSourceAdapter<?>> adapters = List.of(
                Stub.answering("fast-a", "A"),
                Stub.slow("slow", Duration.ofSeconds(30)),
                Stub.answering("fast-b", "B"));

        List<SourceFanOut.Fetched> results = fanOut().fetchAll(adapters, askAll(adapters),
                limits(8, Duration.ofMillis(300), 4));

        assertThat(results).hasSize(3);
        assertThat(results).filteredOn(f -> f.outcome().answered())
                .extracting(f -> f.outcome().sourceName())
                .containsExactlyInAnyOrder("fast-a", "fast-b");
        assertThat(results).filteredOn(f -> f.outcome().sourceName().equals("slow"))
                .singleElement()
                .satisfies(f -> {
                    assertThat(f.outcome().status()).isEqualTo(SourceOutcome.Status.TIMED_OUT);
                    assertThat(f.record()).isNull();
                });
    }

    @Test
    @DisplayName("sources run in parallel, so the request is not the sum of their latencies")
    void sourcesRunInParallel() {
        List<DataSourceAdapter<?>> adapters = List.of(
                Stub.slow("a", Duration.ofMillis(200)),
                Stub.slow("b", Duration.ofMillis(200)),
                Stub.slow("c", Duration.ofMillis(200)));

        long start = System.nanoTime();
        List<SourceFanOut.Fetched> results = fanOut().fetchAll(adapters, askAll(adapters),
                limits(8, Duration.ofSeconds(5), 4));
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

        assertThat(results).allSatisfy(f -> assertThat(f.outcome().answered()).isTrue());
        // Sequentially this is 600ms. Generous bound: the assertion is that the
        // calls overlap, not that the machine is fast.
        assertThat(elapsed).isLessThan(Duration.ofMillis(450));
    }

    @Test
    @DisplayName("the bulkhead limits how many sources are in flight at once")
    void bulkheadBoundsConcurrency() {
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();

        List<DataSourceAdapter<?>> adapters = new java.util.ArrayList<>();
        for (int i = 0; i < 8; i++) {
            String name = "source-" + i;
            adapters.add(new DataSourceAdapter<String>() {
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
                    peak.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
                    try {
                        Thread.sleep(60);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        inFlight.decrementAndGet();
                    }
                    return "ok";
                }
            });
        }

        fanOut().fetchAll(adapters, askAll(adapters), limits(8, Duration.ofSeconds(10), 2));

        // The platform must not be the reason a source system falls over.
        assertThat(peak.get()).isLessThanOrEqualTo(2);
    }

    @Test
    @DisplayName("sources beyond the cap are refused rather than quietly dropped")
    void sourceCapIsEnforced() {
        List<DataSourceAdapter<?>> adapters = List.of(
                Stub.answering("a", "A"), Stub.answering("b", "B"), Stub.answering("c", "C"));

        List<SourceFanOut.Fetched> results = fanOut().fetchAll(adapters, askAll(adapters),
                limits(2, Duration.ofSeconds(5), 4));

        // An answer silently built from the first two of three is worse than one
        // that says the third was skipped: nothing downstream could tell.
        assertThat(results).filteredOn(f -> f.outcome().status() == SourceOutcome.Status.SKIPPED_OVER_LIMIT)
                .singleElement()
                .satisfies(f -> assertThat(f.outcome().sourceName()).isEqualTo("c"));
    }

    @Test
    @DisplayName("a source the identity resolver left out is never called")
    void unrequestedSourcesAreNotCalled() {
        AtomicInteger calls = new AtomicInteger();
        DataSourceAdapter<String> counted = new DataSourceAdapter<>() {
            @Override
            public String sourceName() {
                return "unasked";
            }

            @Override
            public Class<String> responseType() {
                return String.class;
            }

            @Override
            public String fetch(DataRequest request) {
                calls.incrementAndGet();
                return "should not happen";
            }
        };

        Map<String, DataRequest> onlyOne = Map.of("asked", DataRequest.of("THING", "s-1"));
        List<SourceFanOut.Fetched> results = fanOut().fetchAll(
                List.of(Stub.answering("asked", "A"), counted), onlyOne,
                limits(8, Duration.ofSeconds(5), 4));

        assertThat(calls.get()).isZero();
        assertThat(results).hasSize(1);
    }

    @Test
    @DisplayName("a failing source trips its breaker and is then skipped, not retried")
    void breakerOpensAfterRepeatedFailures() {
        var breaker = new SourceCircuitBreaker(2, Duration.ofMinutes(5), Clock.systemUTC());
        var fanOut = new SourceFanOut(breaker, Clock.systemUTC());
        List<DataSourceAdapter<?>> adapters = List.of(Stub.failing("broken"));

        for (int attempt = 0; attempt < 2; attempt++) {
            assertThat(fanOut.fetchAll(adapters, askAll(adapters), limits(8, Duration.ofSeconds(5), 4)))
                    .singleElement()
                    .satisfies(f -> assertThat(f.outcome().status()).isEqualTo(SourceOutcome.Status.FAILED));
        }

        assertThat(fanOut.fetchAll(adapters, askAll(adapters), limits(8, Duration.ofSeconds(5), 4)))
                .singleElement()
                .satisfies(f -> assertThat(f.outcome().status())
                        .isEqualTo(SourceOutcome.Status.CIRCUIT_OPEN));
    }

    @Test
    @DisplayName("holding nothing for a subject is an answer, and does not trip the breaker")
    void noDataDoesNotTripTheBreaker() {
        var breaker = new SourceCircuitBreaker(2, Duration.ofMinutes(5), Clock.systemUTC());
        var fanOut = new SourceFanOut(breaker, Clock.systemUTC());
        List<DataSourceAdapter<?>> adapters = List.of(
                new Stub("empty", Duration.ZERO, null, null));

        for (int attempt = 0; attempt < 5; attempt++) {
            assertThat(fanOut.fetchAll(adapters, askAll(adapters), limits(8, Duration.ofSeconds(5), 4)))
                    .singleElement()
                    .satisfies(f -> assertThat(f.outcome().status())
                            .isEqualTo(SourceOutcome.Status.NO_DATA));
        }
    }

    @Test
    @DisplayName("a failure detail never carries the downstream message")
    void failureDetailCarriesNoPayload() {
        DataSourceAdapter<String> leaky = new DataSourceAdapter<>() {
            @Override
            public String sourceName() {
                return "leaky";
            }

            @Override
            public Class<String> responseType() {
                return String.class;
            }

            @Override
            public String fetch(DataRequest request) {
                // A real driver does this: the record that broke ends up in the
                // message, and the message ends up in a log.
                throw new IllegalStateException("could not parse record for Patrick Murphy");
            }
        };

        List<SourceFanOut.Fetched> results = fanOut().fetchAll(List.of(leaky),
                Map.of("leaky", DataRequest.of("THING", "s-1")),
                limits(8, Duration.ofSeconds(5), 4));

        assertThat(results).singleElement().satisfies(f -> {
            assertThat(f.outcome().status()).isEqualTo(SourceOutcome.Status.FAILED);
            assertThat(f.outcome().detail()).isEqualTo("IllegalStateException")
                    .doesNotContain("Patrick Murphy");
        });
    }

    @Test
    @DisplayName("every outcome records its latency, and only a failure counts as a source error")
    void recordsSourceMetricsByConfiguredName() {
        List<Metric> recorded = new ArrayList<>();
        List<Metric> incremented = new ArrayList<>();
        List<String> incrementedFor = new ArrayList<>();
        PrivacyMetrics metrics = new PrivacyMetrics() {
            @Override
            public void increment(Metric metric) {
            }

            @Override
            public void increment(Metric metric, String sourceName) {
                incremented.add(metric);
                incrementedFor.add(sourceName);
            }

            @Override
            public void record(Metric metric, String sourceName, Duration duration) {
                recorded.add(metric);
            }
        };
        var fanOut = new SourceFanOut(SourceCircuitBreaker.disabled(), Clock.systemUTC(), metrics);
        List<DataSourceAdapter<?>> adapters = List.of(Stub.answering("ok", "A"), Stub.failing("broken"));

        fanOut.fetchAll(adapters, askAll(adapters), limits(8, Duration.ofSeconds(5), 4));

        assertThat(recorded).containsExactlyInAnyOrder(Metric.SOURCE_LATENCY, Metric.SOURCE_LATENCY);
        assertThat(incremented).containsExactly(Metric.SOURCE_ERRORS);
        assertThat(incrementedFor).containsExactly("broken");
    }
}

package io.github.aindriub.dataprism.loadtest;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ResultAggregatorTest {

    @Test
    void percentilesOfAKnownSampleAreExact() {
        ResultAggregator aggregator = new ResultAggregator();
        for (int ms = 1; ms <= 100; ms++) {
            aggregator.record(CallOutcome.completed(ms * 1000L, Map.of("a", "ANSWERED")));
        }

        ResultAggregator.Summary summary = aggregator.summary(Duration.ofSeconds(10));

        assertThat(summary.p50Ms()).isEqualTo(50.0);
        assertThat(summary.p95Ms()).isEqualTo(95.0);
        assertThat(summary.p99Ms()).isEqualTo(99.0);
        assertThat(summary.maxMs()).isEqualTo(100.0);
    }

    @Test
    void latencyIsExactUpToAbout262Milliseconds() {
        ResultAggregator aggregator = new ResultAggregator();
        aggregator.record(CallOutcome.completed(262_143, Map.of()));

        assertThat(aggregator.summary(Duration.ofSeconds(1)).maxMs()).isEqualTo(262.143);
    }

    @Test
    void countsAttemptedCompletedAndFailedAndComputesThroughput() {
        ResultAggregator aggregator = new ResultAggregator();
        for (int i = 0; i < 8; i++) {
            aggregator.record(CallOutcome.completed(1000, Map.of("a", "ANSWERED")));
        }
        aggregator.record(CallOutcome.refused(2000, "SCOPE_READ_BUDGET"));
        aggregator.record(CallOutcome.transportFailure("IOException"));

        ResultAggregator.Summary summary = aggregator.summary(Duration.ofSeconds(5));

        assertThat(summary.attempted()).isEqualTo(10);
        assertThat(summary.completed()).isEqualTo(9);
        assertThat(summary.transportFailures()).isEqualTo(1);
        assertThat(summary.throughputPerSecond()).isEqualTo(9 / 5.0);
        assertThat(summary.transportFailureKinds()).containsEntry("IOException", 1L);
    }

    @Test
    void talliesRefusalCodesByCount() {
        ResultAggregator aggregator = new ResultAggregator();
        aggregator.record(CallOutcome.refused(1000, "TOO_MANY_RECORDS"));
        aggregator.record(CallOutcome.refused(1000, "TOO_MANY_RECORDS"));
        aggregator.record(CallOutcome.refused(1000, "RESPONSE_TOO_LARGE"));

        ResultAggregator.Summary summary = aggregator.summary(Duration.ofSeconds(1));

        assertThat(summary.refusalCodes()).containsEntry("TOO_MANY_RECORDS", 2L)
                .containsEntry("RESPONSE_TOO_LARGE", 1L).hasSize(2);
    }

    @Test
    void talliesPerSourceStatusesAndKeepsAllSevenCodesVisibleAtZero() {
        ResultAggregator aggregator = new ResultAggregator();
        aggregator.record(CallOutcome.completed(1000, Map.of("s1", "ANSWERED", "s2", "REFUSED")));
        aggregator.record(CallOutcome.completed(1000, Map.of("s1", "TIMED_OUT", "s2", "REFUSED")));

        ResultAggregator.Summary summary = aggregator.summary(Duration.ofSeconds(1));

        assertThat(SourceStatuses.ALL).containsExactly("ANSWERED", "NO_DATA", "TIMED_OUT", "FAILED",
                "CIRCUIT_OPEN", "SKIPPED_OVER_LIMIT", "REFUSED");
        assertThat(summary.perSourceStatus().get("s1"))
                .containsEntry("ANSWERED", 1L).containsEntry("TIMED_OUT", 1L).containsEntry("REFUSED", 0L)
                .containsKeys(SourceStatuses.ALL.toArray(String[]::new));
        assertThat(summary.perSourceStatus().get("s2")).containsEntry("REFUSED", 2L).containsEntry("ANSWERED", 0L);
        assertThat(summary.totalStatus()).containsEntry("REFUSED", 2L).containsEntry("ANSWERED", 1L);
    }

    @Test
    void anUnrecognisedStatusIsTalliedUnderItsOwnNameNotDropped() {
        ResultAggregator aggregator = new ResultAggregator();
        aggregator.record(CallOutcome.completed(1000, Map.of("s1", "SOMETHING_NEW")));

        assertThat(aggregator.summary(Duration.ofSeconds(1)).perSourceStatus().get("s1"))
                .containsEntry("SOMETHING_NEW", 1L);
    }

    @Test
    void anEmptyRunHasZeroPercentilesRatherThanFailing() {
        ResultAggregator.Summary summary = new ResultAggregator().summary(Duration.ofSeconds(1));

        assertThat(summary.attempted()).isZero();
        assertThat(summary.p99Ms()).isZero();
        assertThat(summary.throughputPerSecond()).isZero();
    }

    @Test
    void isSafeToRecordFromManyThreads() throws Exception {
        ResultAggregator aggregator = new ResultAggregator();
        Thread[] threads = new Thread[8];
        for (int t = 0; t < threads.length; t++) {
            threads[t] = new Thread(() -> {
                for (int i = 0; i < 1000; i++) {
                    aggregator.record(CallOutcome.completed(1000, Map.of("s", "ANSWERED")));
                }
            });
            threads[t].start();
        }
        for (Thread thread : threads) {
            thread.join();
        }

        ResultAggregator.Summary summary = aggregator.summary(Duration.ofSeconds(1));
        assertThat(summary.completed()).isEqualTo(8000);
        assertThat(summary.perSourceStatus().get("s")).containsEntry("ANSWERED", 8000L);
    }
}

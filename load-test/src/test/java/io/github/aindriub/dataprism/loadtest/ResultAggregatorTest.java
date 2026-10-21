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
    void aFailedCallRaisesTheTailItDoesNotHideFrom() {
        ResultAggregator aggregator = new ResultAggregator();
        for (int i = 0; i < 99; i++) {
            aggregator.record(CallOutcome.completed(1000, Map.of()));
        }
        aggregator.record(CallOutcome.timeout("TimeoutException", 60_000_000));

        ResultAggregator.Summary summary = aggregator.summary(Duration.ofSeconds(10));

        assertThat(summary.maxMs()).isCloseTo(60_000.0, org.assertj.core.data.Percentage.withPercentage(0.01));
        assertThat(summary.transportFailures()).isEqualTo(1);
        assertThat(summary.completed()).isEqualTo(99);
        assertThat(summary.throughputPerSecond()).isEqualTo(9.9);
    }

    @Test
    void fastFailuresAfterTheServerDiesLeaveTheMedianAlone() {
        ResultAggregator aggregator = new ResultAggregator();
        for (int i = 0; i < 10; i++) {
            aggregator.record(CallOutcome.completed(20_000, Map.of()));
        }
        for (int i = 0; i < 1000; i++) {
            aggregator.record(CallOutcome.transportFailure("ConnectException"));
        }

        ResultAggregator.Summary summary = aggregator.summary(Duration.ofSeconds(10));

        assertThat(summary.p50Ms()).isEqualTo(20.0);
        assertThat(summary.meanMs()).isEqualTo(20.0);
        assertThat(summary.transportFailures()).isEqualTo(1000);
    }

    @Test
    void valuesBeyondTheTrackableMaximumAreCountedAsClamped() {
        ResultAggregator aggregator = new ResultAggregator();
        aggregator.record(CallOutcome.completed(1000, Map.of()));
        aggregator.record(CallOutcome.completed(ResultAggregator.HIGHEST_TRACKABLE_MICROS + 1, Map.of()));
        aggregator.record(CallOutcome.timeout("TimeoutException", Long.MAX_VALUE));

        ResultAggregator.Summary summary = aggregator.summary(Duration.ofSeconds(1));

        assertThat(summary.clamped()).isEqualTo(2);
        assertThat(summary.maxMs()).isCloseTo(ResultAggregator.HIGHEST_TRACKABLE_MICROS / 1000.0, org.assertj.core.data.Percentage.withPercentage(1));
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
    void talliesPerSourceStatusesAndKeepsAllEightCodesVisibleAtZero() {
        ResultAggregator aggregator = new ResultAggregator();
        aggregator.record(CallOutcome.completed(1000, Map.of("s1", "ANSWERED", "s2", "REFUSED")));
        aggregator.record(CallOutcome.completed(1000, Map.of("s1", "TIMED_OUT", "s2", "REFUSED")));

        ResultAggregator.Summary summary = aggregator.summary(Duration.ofSeconds(1));

        assertThat(SourceStatuses.ALL).containsExactly("ANSWERED", "NO_DATA", "TIMED_OUT", "FAILED",
                "CIRCUIT_OPEN", "SKIPPED_OVER_LIMIT", "REFUSED", "RESOLVER_FAILED");
        assertThat(summary.perSourceStatus().get("scope-1/s1"))
                .containsEntry("ANSWERED", 1L).containsEntry("TIMED_OUT", 1L).containsEntry("REFUSED", 0L)
                .containsKeys(SourceStatuses.ALL.toArray(String[]::new));
        assertThat(summary.perSourceStatus().get("scope-1/s2")).containsEntry("REFUSED", 2L).containsEntry("ANSWERED", 0L);
        assertThat(summary.totalStatus()).containsEntry("REFUSED", 2L).containsEntry("ANSWERED", 1L);
    }

    @Test
    void anUnrecognisedStatusIsTalliedUnderItsOwnNameNotDropped() {
        ResultAggregator aggregator = new ResultAggregator();
        aggregator.record(CallOutcome.completed(1000, Map.of("s1", "SOMETHING_NEW")));

        assertThat(aggregator.summary(Duration.ofSeconds(1)).perSourceStatus().get("scope-1/s1"))
                .containsEntry("SOMETHING_NEW", 1L);
    }

    @Test
    void everyStatusButAnsweredAndNoDataIsNotAnsweredIncludingAnUnknownOne() {
        ResultAggregator aggregator = new ResultAggregator();
        aggregator.record(CallOutcome.completed(1000, Map.of("s1", "ANSWERED", "s2", "NO_DATA", "s3", "RESOLVER_FAILED",
                "s4", "SOMETHING_NEW", "s5", "TIMED_OUT", "s6", "")));

        ResultAggregator.Summary summary = aggregator.summary(Duration.ofSeconds(1));

        assertThat(summary.sourcesNotAnswered()).isEqualTo(4);
        assertThat(summary.totalStatus()).containsEntry("RESOLVER_FAILED", 1L).containsEntry("SOMETHING_NEW", 1L);
    }

    @Test
    void callsFinishedAfterTheDeadlineAreCountedButDoNotInflateThroughput() {
        ResultAggregator aggregator = new ResultAggregator();
        for (int i = 0; i < 10; i++) {
            aggregator.record(CallOutcome.completed(1000, Map.of()));
        }
        for (int i = 0; i < 5; i++) {
            aggregator.record(CallOutcome.completed(1000, Map.of()), true);
        }

        ResultAggregator.Summary summary = aggregator.summary(Duration.ofSeconds(5));

        assertThat(summary.completed()).isEqualTo(15);
        assertThat(summary.completedAfterDeadline()).isEqualTo(5);
        assertThat(summary.throughputPerSecond()).isEqualTo(10 / 5.0);
    }

    @Test
    void aSlowFailureIsTimedButAFastOneIsNot() {
        ResultAggregator aggregator = new ResultAggregator();
        aggregator.record(CallOutcome.completed(1000, Map.of()));
        aggregator.record(CallOutcome.slowFailure("HttpConnectTimeoutException", FailureCause.CONNECT_TIMEOUT, 10_000_000));
        aggregator.record(CallOutcome.transportFailure("ConnectException", FailureCause.CONNECTION_FAILED));

        ResultAggregator.Summary summary = aggregator.summary(Duration.ofSeconds(1));

        assertThat(summary.maxMs()).isCloseTo(10_000.0, org.assertj.core.data.Percentage.withPercentage(0.01));
        assertThat(summary.failureCauses()).containsEntry("CONNECT_TIMEOUT", 1L).containsEntry("CONNECTION_FAILED", 1L);
    }

    @Test
    void aliasesAreKeyedByTheScopeTheyWereIssuedIn() {
        ResultAggregator aggregator = new ResultAggregator();
        aggregator.record(CallOutcome.completed(1000, Map.of("alias-a", "ANSWERED")), false, 1);
        aggregator.record(CallOutcome.completed(1000, Map.of("alias-a", "TIMED_OUT")), false, 2);

        ResultAggregator.Summary summary = aggregator.summary(Duration.ofSeconds(1));

        assertThat(summary.perSourceStatus().keySet()).containsExactly("scope-1/alias-a", "scope-2/alias-a");
        assertThat(summary.perSourceStatus().get("scope-2/alias-a")).containsEntry("TIMED_OUT", 1L)
                .containsEntry("ANSWERED", 0L);
        assertThat(summary.totalStatus()).containsEntry("ANSWERED", 1L).containsEntry("TIMED_OUT", 1L);
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
        assertThat(summary.perSourceStatus().get("scope-1/s")).containsEntry("ANSWERED", 8000L);
    }
}

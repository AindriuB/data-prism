package io.github.aindriub.dataprism.loadtest;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ResultWriterTest {

    private static final Scenario SCENARIO = new Scenario("t", 2, new Scenario.Latency(1, 2),
            new Scenario.Load(1, 5, 0, 0, 0, 30, 0), Scenario.Limits.defaults(2), 1L);

    private static LoadDriver.Run run(ResultAggregator aggregator) {
        return new LoadDriver.Run(aggregator.summary(Duration.ofSeconds(10)), Duration.ofSeconds(12), 0,
                Duration.ofSeconds(10), 3, 1);
    }

    @SuppressWarnings("unchecked")
    @Test
    void theResultStatesWhyEachCallFailedAndWhatWasRotated() {
        ResultAggregator aggregator = new ResultAggregator();
        aggregator.record(CallOutcome.completed(1000, Map.of("a", "ANSWERED", "b", "RESOLVER_FAILED")));
        aggregator.record(CallOutcome.transportFailure("ConnectException", FailureCause.CONNECTION_FAILED));
        aggregator.record(CallOutcome.timeout("TimeoutException", FailureCause.READ_TIMEOUT, 5_000_000));

        Map<String, Object> doc = ResultWriter.document(SCENARIO, Instant.EPOCH, "abc", false, run(aggregator));

        Map<String, Object> calls = (Map<String, Object>) doc.get("calls");
        assertThat((Map<String, Long>) calls.get("failureCauses")).containsEntry("CONNECTION_FAILED", 1L)
                .containsEntry("READ_TIMEOUT", 1L).containsEntry("CONNECT_TIMEOUT", 0L);
        assertThat(calls.get("transportFailures")).isEqualTo(2L);
        assertThat((Map<String, Object>) doc.get("credentials")).containsEntry("tokenReissues", 3L)
                .containsEntry("scopeRotations", 1L);
        assertThat(doc.get("measuredSeconds")).isEqualTo(10.0);
        assertThat(doc.get("elapsedSeconds")).isEqualTo(12.0);
        assertThat(doc.get("sourcesNotAnswered")).isEqualTo(1L);
    }

    @Test
    void aSummaryWhoseCausesDoNotAddUpToTheFailedCallsIsNotWrittenAsIfItDid() {
        ResultAggregator.Summary good = new ResultAggregator().summary(Duration.ofSeconds(1));
        ResultAggregator.Summary broken = new ResultAggregator.Summary(2, 0, 2, Map.of(), 0, 0, 0, 0, 0, 0, Map.of(),
                good.totalStatus(), Map.of(), 0, Map.of("OTHER", 1L), 0, 0);

        assertThatThrownBy(() -> ResultWriter.document(SCENARIO, Instant.EPOCH, "abc", false,
                new LoadDriver.Run(broken, Duration.ofSeconds(1), 0, Duration.ofSeconds(1), 0, 0)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("do not add up");
    }
}

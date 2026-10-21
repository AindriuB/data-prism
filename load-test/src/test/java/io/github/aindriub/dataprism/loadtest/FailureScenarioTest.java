package io.github.aindriub.dataprism.loadtest;

import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The failure-path scenarios drive a path on purpose. Each is checked two ways: the scenario is shaped to reach
 * the path (its numbers cross the limit it names), and a recorded response from that path is classified as the
 * result file must report it.
 */
class FailureScenarioTest {

    private static Scenario scenario(String name) throws Exception {
        return Scenario.load(Path.of("scenarios", name + ".json"));
    }

    private static Duration seconds(String limit) {
        return Duration.ofSeconds(Long.parseLong(limit.replace("s", "")));
    }

    private static McpSchema.CallToolResult sources(Map<String, String> statusBySource) {
        Map<String, Object> sources = new LinkedHashMap<>();
        statusBySource.forEach((source, status) -> sources.put(source, Map.of("status", status)));
        return McpSchema.CallToolResult.builder().structuredContent(Map.of("sources", sources))
                .addTextContent("{}").build();
    }

    private static McpSchema.CallToolResult error(String text) {
        return McpSchema.CallToolResult.builder().isError(true).addTextContent(text).build();
    }

    @Test
    void theTimeoutScenarioHasSomeSourcesSlowerThanThePerSourceTimeoutAndSomeFaster() throws Exception {
        Scenario scenario = scenario("failure-source-timeouts");
        Duration timeout = seconds(scenario.limits().perSourceTimeout());

        assertThat(scenario.slowSources()).isPositive().isLessThan(scenario.sources());
        assertThat(Duration.ofMillis(scenario.slowLatency().minMs())).isGreaterThan(timeout);
        assertThat(Duration.ofMillis(scenario.sourceLatency().maxMs())).isLessThan(timeout);
        // The catalogue's own source timeout must not fire first, or the server-side limit is not what is measured.
        assertThat(Duration.ofMillis(scenario.slowLatency().maxMs())).isLessThan(Duration.ofSeconds(10));
        assertThat(Duration.ofMillis(scenario.slowLatency().maxMs()))
                .isLessThan(Duration.ofSeconds(scenario.load().callTimeoutSeconds()));
        List<SourceDefinition> sources = SourceGenerator.generate(scenario);
        assertThat(sources.stream().filter(s -> s.latencyMinMs() == scenario.slowLatency().minMs()).count())
                .isEqualTo(scenario.slowSources());
    }

    @Test
    void aCallInWhichEverySourceTimesOutIsRefusedAndReportsNoPerSourceStatus() {
        CallOutcome outcome = ResponseClassifier.classify(error("refused: NO_SOURCE_DATA at PARCEL"), 1_000_000);

        assertThat(outcome.refusalCode()).isEqualTo("NO_SOURCE_DATA");
        assertThat(outcome.sourceStatuses()).isEmpty();
    }

    @Test
    void aRecordedTimeoutResponseIsTalliedAsTimedOutPerSource() throws Exception {
        scenario("failure-source-timeouts");
        ResultAggregator aggregator = new ResultAggregator();
        for (int call = 0; call < 3; call++) {
            aggregator.record(ResponseClassifier.classify(sources(Map.of("s1", "TIMED_OUT", "s2", "TIMED_OUT", "s3", "ANSWERED")), 1_050_000));
        }

        ResultAggregator.Summary summary = aggregator.summary(Duration.ofSeconds(1));

        assertThat(summary.perSourceStatus().get("scope-1/s1")).containsEntry("TIMED_OUT", 3L).containsEntry("ANSWERED", 0L);
        assertThat(summary.perSourceStatus().get("scope-1/s2")).containsEntry("TIMED_OUT", 3L);
        assertThat(summary.totalStatus()).containsEntry("TIMED_OUT", 6L);
        assertThat(summary.perSourceStatus().get("scope-1/s3")).containsEntry("ANSWERED", 3L);
        assertThat(summary.sourcesNotAnswered()).isEqualTo(6);
        assertThat(summary.transportFailures()).isZero();
    }

    @Test
    void theReadBudgetScenarioReadsFewerSubjectsThanItMakesCallsSoTheBudgetIsPassed() throws Exception {
        Scenario scenario = scenario("failure-read-budget");

        assertThat(scenario.load().subjectPool()).isPositive();
        int callsPerSubject = scenario.load().calls() / scenario.load().subjectPool();
        assertThat(callsPerSubject).isGreaterThan(scenario.limits().scopeReadBudget());
    }

    @Test
    void aRecordedBudgetRefusalIsTalliedByItsCode() throws Exception {
        scenario("failure-read-budget");
        ResultAggregator aggregator = new ResultAggregator();
        aggregator.record(ResponseClassifier.classify(sources(Map.of("s1", "ANSWERED")), 1000));
        aggregator.record(ResponseClassifier.classify(error("refused: SCOPE_READ_BUDGET at PARCEL"), 1000));
        aggregator.record(ResponseClassifier.classify(error("refused: SCOPE_READ_BUDGET at PARCEL"), 1000));

        ResultAggregator.Summary summary = aggregator.summary(Duration.ofSeconds(1));

        assertThat(summary.refusalCodes()).containsExactlyEntriesOf(Map.of("SCOPE_READ_BUDGET", 2L));
        assertThat(summary.transportFailures()).isZero();
    }

    @Test
    void theOverLimitScenarioConfiguresMoreSourcesThanTheCallMayUse() throws Exception {
        Scenario scenario = scenario("failure-over-limit");

        assertThat(scenario.sources()).isGreaterThan(scenario.limits().maxSources());
        assertThat(SourceGenerator.generate(scenario)).hasSize(scenario.sources());
    }

    @Test
    void aZeroPointSevenServerReportsSkippedOverLimitPerSource() throws Exception {
        scenario("failure-over-limit");
        ResultAggregator aggregator = new ResultAggregator();
        aggregator.record(ResponseClassifier.classify(sources(Map.of("a", "ANSWERED", "b", "SKIPPED_OVER_LIMIT")), 1000));

        ResultAggregator.Summary summary = aggregator.summary(Duration.ofSeconds(1));

        assertThat(summary.totalStatus()).containsEntry("SKIPPED_OVER_LIMIT", 1L).containsEntry("ANSWERED", 1L);
        assertThat(summary.refusalCodes()).isEmpty();
    }

    @Test
    void aServerAfterTheFanOutRewriteRefusesTheSelectionByCode() throws Exception {
        scenario("failure-over-limit");
        ResultAggregator aggregator = new ResultAggregator();
        aggregator.record(ResponseClassifier.classify(error("refused: SOURCE_SELECTION_TOO_LARGE at $"), 1000));
        aggregator.record(ResponseClassifier.classify(error("SOURCE_SELECTION_TOO_LARGE"), 1000));

        ResultAggregator.Summary summary = aggregator.summary(Duration.ofSeconds(1));

        assertThat(summary.refusalCodes()).containsExactlyEntriesOf(Map.of("SOURCE_SELECTION_TOO_LARGE", 2L));
        assertThat(summary.totalStatus().values()).allMatch(count -> count == 0L);
    }
}

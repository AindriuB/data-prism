package io.github.aindriub.dataprism.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class PrivacyMetricsTest {

    @Test
    @DisplayName("Metric covers exactly the §89 names plus the three from §E")
    void exactMetricNameSet() {
        Set<String> expected = Set.of(
                "dataprism.mcp.requests",
                "dataprism.mcp.denied",
                "dataprism.privacy.transformations",
                "dataprism.privacy.validation.failures",
                "dataprism.identity.cache.hit",
                "dataprism.identity.cache.miss",
                "dataprism.source.latency",
                "dataprism.source.errors",
                "dataprism.identity.collision",
                "dataprism.privacy.failclosed",
                "dataprism.reidentification");

        Set<String> actual = Arrays.stream(Metric.values())
                .map(Metric::metricName)
                .collect(Collectors.toSet());

        assertThat(actual).isEqualTo(expected);
        assertThat(Metric.values()).hasSize(expected.size());
    }

    @Test
    @DisplayName("none() is a usable no-op that never throws")
    void noneIsSafeNoOp() {
        PrivacyMetrics metrics = PrivacyMetrics.none();

        assertThatCode(() -> {
            metrics.increment(Metric.MCP_REQUESTS);
            metrics.increment(Metric.SOURCE_ERRORS, "customer-api");
            metrics.record(Metric.SOURCE_LATENCY, "customer-api", Duration.ofMillis(142));
        }).doesNotThrowAnyException();
    }
}

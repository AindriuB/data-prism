package io.github.aindriub.dataprism.core.metrics;

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
                "dataprism.privacy.source.withheld",
                "dataprism.reidentification",
                "dataprism.approval.release.skipped",
                "dataprism.approval.decision.audit_unavailable",
                "dataprism.approval.decision.unconfirmed",
                "dataprism.audit.denial_unavailable",
                "dataprism.audit.retention.unverified",
                "dataprism.audit.retention.anchor_failed",
                "dataprism.audit.retention.delete_failed",
                "dataprism.audit.retention.failed",
                "dataprism.cluster.seal.tamper",
                "dataprism.cluster.seal.unsupported_format",
                "dataprism.cluster.seal.salt_rejected",
                "dataprism.cluster.members.unverified",
                "dataprism.cluster.pause.fresh",
                "dataprism.cluster.pause.age_seconds",
                "dataprism.cluster.pause.conflict",
                "dataprism.cluster.rotation.accepting_next",
                "dataprism.cluster.rotation.blocked",
                "dataprism.cluster.rotation.behind",
                "dataprism.cluster.seal.entries_under_previous",
                "dataprism.cluster.lock.timeout",
                "dataprism.cluster.floor.refusal",
                "dataprism.cluster.legacy.entries",
                "dataprism.resolver.unavailable",
                "dataprism.resolver.timeout",
                "dataprism.resolver.rejected",
                "dataprism.resolver.too_many",
                "dataprism.resolver.invalid_output",
                "dataprism.resolver.latency",
                "dataprism.links.issued",
                "dataprism.links.omitted",
                "dataprism.links.refused",
                "dataprism.links.attempts_exhausted",
                "dataprism.links.keys.loaded",
                "dataprism.paging.pages",
                "dataprism.catalog.descriptions.generated",
                "dataprism.catalog.source_names.nonconforming");

        Set<String> actual = Arrays.stream(Metric.values())
                .map(Metric::metricName)
                .collect(Collectors.toSet());

        assertThat(actual).isEqualTo(expected);
        assertThat(Metric.values()).hasSize(expected.size());
    }

    @Test
    @DisplayName("the cluster metric constants are named after their signal")
    void clusterConstantsMatchTheirNames() {
        for (Metric metric : Metric.values()) {
            if (metric.metricName().startsWith("dataprism.cluster.")) {
                assertThat(metric.name()).isEqualTo(metric.metricName()
                        .substring("dataprism.".length()).toUpperCase(java.util.Locale.ROOT).replace('.', '_'));
            }
        }
    }

    @Test
    @DisplayName("the default gauge method is a no-op, and none() accepts it")
    void gaugeDefaultIsNoOp() {
        PrivacyMetrics legacy = new PrivacyMetrics() {
            @Override public void increment(Metric metric) {}
            @Override public void increment(Metric metric, String sourceName) {}
            @Override public void record(Metric metric, String sourceName, Duration duration) {}
        };
        assertThatCode(() -> {
            legacy.gauge(Metric.CLUSTER_PAUSE_FRESH, () -> 1.0);
            PrivacyMetrics.none().gauge(Metric.CLUSTER_PAUSE_FRESH, () -> 1.0);
        }).doesNotThrowAnyException();
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

package io.github.aindriub.dataprism.spring.boot;

import io.github.aindriub.dataprism.audit.AuditRecorder;
import io.github.aindriub.dataprism.audit.SegmentedFileAuditSink;
import io.github.aindriub.dataprism.core.spi.DataSourceAdapter;
import io.github.aindriub.dataprism.core.spi.IdentityResolver;
import io.github.aindriub.dataprism.core.metrics.Metric;
import io.github.aindriub.dataprism.core.spi.PassThroughIdentityResolver;
import io.github.aindriub.dataprism.core.metrics.PrivacyMetrics;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpTransportContextExtractor;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Owner decision 2026-10-06: a purge integrity failure is visible as a metric and as health DOWN. */
class AuditIntegrityHealthTest {

    private static final Instant NOW = Instant.parse("2026-10-06T12:00:00Z");

    static final class Recording implements PrivacyMetrics {
        final List<Metric> counted = new ArrayList<>();
        public void increment(Metric m) { counted.add(m); }
        public void increment(Metric m, String s) { counted.add(m); }
        public void record(Metric m, String s, Duration d) { }
    }

    private static Path seedTampered(Path audit) throws Exception {
        try (SegmentedFileAuditSink seed = new SegmentedFileAuditSink(audit)) {
            AuditRecorder r = new AuditRecorder(seed, Clock.fixed(Instant.parse("2025-01-10T10:00:00Z"),
                    ZoneOffset.UTC), "seed");
            for (int i = 0; i < 2; i++) {
                r.record("p", "c", "get_entity_context", "CUSTOMER", "ps", "fp", "DEFAULT", "scope",
                        "investigation", "case-1", "ALLOW", Set.of("customer"), Set.of(), "corr");
            }
        }
        Path segment = audit.resolve("audit-2025-01-10.log");
        Files.writeString(segment, Files.readString(segment).replaceFirst("get_entity_context",
                "get_entity_contexT"));
        return segment;
    }

    @Test
    void a_chain_unverified_purge_flips_health_down_counts_the_metric_and_a_clean_purge_restores_up(
            @TempDir Path dir) throws Exception {
        Path audit = dir.resolve("segments");
        Path segment = seedTampered(audit);
        Recording metrics = new Recording();

        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(DataPrismAutoConfiguration.class))
                .withUserConfiguration(Integrations.class)
                .withAllowBeanDefinitionOverriding(true)
                .withBean("metrics", PrivacyMetrics.class, () -> metrics)
                .withBean(Clock.class, () -> Clock.fixed(NOW, ZoneOffset.UTC))
                .withPropertyValues(AuditRetentionConfigurationTest.valid())
                .withPropertyValues("dataprism.audit.sink=hash-chained", "dataprism.audit.directory=" + audit,
                        "dataprism.audit.checkpoint.file-path=" + dir.resolve("cp.jsonl"))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    HealthIndicator health = context.getBean("auditIntegrityHealthIndicator", HealthIndicator.class);
                    assertThat(health.health().getStatus()).isEqualTo(Status.DOWN);
                    assertThat(health.health().getDetails()).containsOnly(
                            java.util.Map.entry("code", "AUDIT_RETENTION_CHAIN_UNVERIFIED"),
                            java.util.Map.entry("segmentDate", "2025-01-10"));
                    assertThat(metrics.counted).containsExactly(Metric.AUDIT_RETENTION_CHAIN_UNVERIFIED);
                    assertThat(segment).exists();

                    Files.delete(segment);
                    context.getBean(AuditMaintenance.class).purge();
                    assertThat(health.health().getStatus()).isEqualTo(Status.UP);
                    assertThat(health.health().getDetails()).isEmpty();
                });
    }

    @Test
    void without_spring_boot_health_on_the_classpath_there_is_no_health_indicator_bean(@TempDir Path dir) {
        new WebApplicationContextRunner()
                .withClassLoader(new org.springframework.boot.test.context.FilteredClassLoader(
                        "org.springframework.boot.health"))
                .withConfiguration(AutoConfigurations.of(DataPrismAutoConfiguration.class))
                .withUserConfiguration(Integrations.class)
                .withAllowBeanDefinitionOverriding(true)
                .withBean(Clock.class, () -> Clock.fixed(NOW, ZoneOffset.UTC))
                .withPropertyValues(AuditRetentionConfigurationTest.valid())
                .withPropertyValues("dataprism.audit.sink=hash-chained",
                        "dataprism.audit.directory=" + dir.resolve("segments"),
                        "dataprism.audit.checkpoint.file-path=" + dir.resolve("cp.jsonl"))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean("auditIntegrityHealthIndicator");
                });
    }

    @Configuration(proxyBeanMethods = false)
    static class Integrations {
        @Bean DataSourceAdapter<String> customerAdapter() {
            return new DataSourceAdapter<>() {
                @Override public String sourceName() { return "customer"; }
                @Override public Class<String> responseType() { return String.class; }
                @Override public String fetch(io.github.aindriub.dataprism.core.spi.DataRequest request) { return null; }
            };
        }
        @Bean IdentityResolver identities() { return new PassThroughIdentityResolver(); }
        @Bean HmacKeyReferenceResolver keys() {
            return (id, reference) -> (reference + ":" + id + ":resolved-key-material").getBytes(StandardCharsets.UTF_8);
        }
        @Bean PrivacyMetrics metrics() { return PrivacyMetrics.none(); }
        @Bean McpTransportContextExtractor<HttpServletRequest> callerExtractor() {
            return request -> McpTransportContext.EMPTY;
        }
    }
}

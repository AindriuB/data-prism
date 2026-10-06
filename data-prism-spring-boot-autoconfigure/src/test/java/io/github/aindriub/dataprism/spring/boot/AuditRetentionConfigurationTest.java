package io.github.aindriub.dataprism.spring.boot;

import io.github.aindriub.dataprism.audit.AuditCheckpoint;
import io.github.aindriub.dataprism.audit.AuditRecorder;
import io.github.aindriub.dataprism.audit.AuditSink;
import io.github.aindriub.dataprism.audit.FileAuditSink;
import io.github.aindriub.dataprism.audit.SegmentedFileAuditSink;
import io.github.aindriub.dataprism.core.DataSourceAdapter;
import io.github.aindriub.dataprism.core.IdentityResolver;
import io.github.aindriub.dataprism.core.PassThroughIdentityResolver;
import io.github.aindriub.dataprism.core.PrivacyMetrics;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpTransportContextExtractor;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Task 103: the {@code dataprism.audit.directory / checkpoint / retention} vocabulary. */
class AuditRetentionConfigurationTest {

    private static final Instant NOW = Instant.parse("2026-10-06T12:00:00Z");

    private static WebApplicationContextRunner runner() {
        return new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(DataPrismAutoConfiguration.class))
                .withUserConfiguration(Integrations.class)
                .withPropertyValues(valid())
                .withPropertyValues("dataprism.audit.sink=hash-chained");
    }

    private static void assertRefusedWith(WebApplicationContextRunner r, String code, String... forbidden) {
        r.run(context -> {
            assertThat(context).hasFailed();
            Throwable failure = context.getStartupFailure();
            while (failure.getCause() != null && !(failure instanceof DataPrismConfigurationException)) {
                failure = failure.getCause();
            }
            assertThat(failure).isInstanceOf(DataPrismConfigurationException.class);
            assertThat(((DataPrismConfigurationException) failure).code()).isEqualTo(code);
            for (String f : forbidden) {
                assertThat(failure.getMessage()).doesNotContain(f);
            }
        });
    }

    @Test
    void directory_and_file_path_together_are_ambiguous(@TempDir Path dir) {
        assertRefusedWith(runner().withPropertyValues("dataprism.audit.directory=" + dir.resolve("a"),
                "dataprism.audit.file-path=" + dir.resolve("a.log"),
                "dataprism.audit.checkpoint.file-path=" + dir.resolve("cp")), "AMBIGUOUS_AUDIT_LOCATION");
    }

    @Test
    void directory_without_a_checkpoint_file_is_refused(@TempDir Path dir) {
        assertRefusedWith(runner().withPropertyValues("dataprism.audit.directory=" + dir.resolve("a")),
                "RETENTION_REQUIRES_CHECKPOINT");
    }

    @Test
    void retention_below_six_months_is_refused_without_the_override(@TempDir Path dir) {
        assertRefusedWith(withDirectory(dir).withPropertyValues("dataprism.audit.retention=P5M"),
                "AUDIT_RETENTION_BELOW_MINIMUM");
    }

    @Test
    void retention_below_six_months_starts_with_the_override(@TempDir Path dir) {
        withDirectory(dir).withPropertyValues("dataprism.audit.retention=P5M",
                "dataprism.audit.retention-override=true")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void six_months_or_more_starts_with_or_without_the_override(@TempDir Path dir) {
        withDirectory(dir).run(context -> assertThat(context).hasNotFailed());
        withDirectory(dir).withPropertyValues("dataprism.audit.retention=P1Y",
                "dataprism.audit.retention-override=true")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void a_non_positive_checkpoint_interval_is_refused(@TempDir Path dir) {
        assertRefusedWith(withDirectory(dir).withPropertyValues("dataprism.audit.checkpoint.interval=0s"),
                "INVALID_AUDIT_CHECKPOINT_INTERVAL");
        assertRefusedWith(withDirectory(dir).withPropertyValues("dataprism.audit.checkpoint.interval=-5m"),
                "INVALID_AUDIT_CHECKPOINT_INTERVAL");
    }

    @Test
    void a_checkpoint_path_equal_to_the_audit_file_is_refused(@TempDir Path dir) {
        Path file = dir.resolve("audit.log");
        assertRefusedWith(runner().withPropertyValues("dataprism.audit.file-path=" + file,
                "dataprism.audit.checkpoint.file-path=" + file), "AUDIT_CHECKPOINT_SAME_AS_AUDIT_FILE");
    }

    @Test
    void an_unopenable_checkpoint_path_is_refused_without_naming_it(@TempDir Path dir) {
        Path bad = dir.resolve("no-such-dir").resolve("distinctive-name-cp");
        assertRefusedWith(runner().withPropertyValues("dataprism.audit.file-path=" + dir.resolve("audit.log"),
                "dataprism.audit.checkpoint.file-path=" + bad), "AUDIT_CHECKPOINT_FILE_UNUSABLE",
                "distinctive-name-cp");
    }

    @Test
    void file_path_wires_the_single_file_sink_and_directory_wires_the_segmented_sink(@TempDir Path dir) {
        runner().withPropertyValues("dataprism.audit.file-path=" + dir.resolve("audit.log"))
                .run(context -> assertThat(context.getBean(AuditSink.class)).isInstanceOf(FileAuditSink.class));
        withDirectory(dir).run(context ->
                assertThat(context.getBean(AuditSink.class)).isInstanceOf(SegmentedFileAuditSink.class));
    }

    @Test
    void the_checkpoint_file_has_boot_periodic_and_shutdown_lines_after_start_and_stop(@TempDir Path dir)
            throws Exception {
        Path cp = dir.resolve("cp.jsonl");
        withDirectory(dir).withPropertyValues("dataprism.audit.checkpoint.interval=50ms").run(context -> {
            assertThat(context).hasNotFailed();
            long deadline = System.currentTimeMillis() + 10_000;
            while (System.currentTimeMillis() < deadline && !Files.readString(cp).contains("PERIODIC")) {
                Thread.sleep(25);
            }
        });
        String text = Files.readString(cp);
        assertThat(text).contains("BOOT").contains("PERIODIC").contains("SHUTDOWN");
        assertThat(text.lines().findFirst().orElseThrow()).contains("BOOT");
        assertThat(text.lines().reduce((a, b) -> b).orElseThrow()).contains("SHUTDOWN");
    }

    @Test
    void purge_runs_at_startup_deleting_an_expired_segment_and_writing_an_anchor(@TempDir Path dir)
            throws Exception {
        Path audit = dir.resolve("segments");
        Instant old = Instant.parse("2025-01-10T10:00:00Z");
        try (SegmentedFileAuditSink seed = new SegmentedFileAuditSink(audit)) {
            AuditRecorder seeded = new AuditRecorder(seed, Clock.fixed(old, ZoneOffset.UTC), "seed");
            seeded.record("p", "c", "get_entity_context", "CUSTOMER", "ps", "fp", "DEFAULT", "scope",
                    "investigation", "case-1", "ALLOW", Set.of("customer"), Set.of(), "corr");
        }
        Path expired = audit.resolve("audit-2025-01-10.log");
        assertThat(expired).exists();
        Path cp = dir.resolve("cp.jsonl");

        runner().withBean(Clock.class, () -> Clock.fixed(NOW, ZoneOffset.UTC))
                .withPropertyValues("dataprism.audit.directory=" + audit,
                        "dataprism.audit.checkpoint.file-path=" + cp)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(expired).doesNotExist();
                });
        assertThat(Files.readAllLines(cp).stream().filter(l -> l.contains(
                AuditCheckpoint.Kind.RETENTION_ANCHOR.name())).toList()).hasSize(1);
    }

    private static WebApplicationContextRunner withDirectory(Path dir) {
        return runner().withPropertyValues("dataprism.audit.directory=" + dir.resolve("segments"),
                "dataprism.audit.checkpoint.file-path=" + dir.resolve("cp.jsonl"));
    }

    private static String[] valid() {
        return new String[] {
                "dataprism.security.jwt.issuer=https://issuer.example", "dataprism.security.jwt.audience=mcp",
                "dataprism.security.jwt.jwk-set-uri=https://issuer.example/jwks",
                "dataprism.security.caller-claims.principal=sub", "dataprism.security.caller-claims.roles=roles",
                "dataprism.security.caller-claims.investigation=case_id",
                "dataprism.security-policy.purposes[0]=investigation",
                "dataprism.security-policy.roles.investigator[0]=GET_ENTITY_CONTEXT",
                "dataprism.privacy.profile=DEFAULT", "dataprism.privacy.scope-lifetime=8h",
                "dataprism.privacy.hmac-key.key-id=v1",
                "dataprism.privacy.hmac-key.environment-variable=DATAPRISM_HMAC_KEY_REF",
                "dataprism.audit.writer-id=test", "dataprism.metrics.sink=micrometer",
                "dataprism.hazelcast.topology=single-node",
                "dataprism.sources.customer.base-url=https://customer.example",
                "dataprism.sources.customer.timeout=2s"};
    }

    @Configuration(proxyBeanMethods = false)
    static class Integrations {
        @Bean
        DataSourceAdapter<String> customerAdapter() {
            return new DataSourceAdapter<>() {
                @Override public String sourceName() { return "customer"; }
                @Override public Class<String> responseType() { return String.class; }
                @Override public String fetch(io.github.aindriub.dataprism.core.DataRequest request) { return null; }
            };
        }

        @Bean
        IdentityResolver identities() {
            return new PassThroughIdentityResolver();
        }

        @Bean
        HmacKeyReferenceResolver keys() {
            return (id, reference) -> (reference + ":" + id + ":resolved-key-material").getBytes(StandardCharsets.UTF_8);
        }

        @Bean
        PrivacyMetrics metrics() {
            return PrivacyMetrics.none();
        }

        @Bean
        McpTransportContextExtractor<HttpServletRequest> callerExtractor() {
            return request -> McpTransportContext.EMPTY;
        }
    }
}

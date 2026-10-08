package io.github.aindriub.dataprism.spring.boot;

import io.github.aindriub.dataprism.audit.AuditCheckpoint;
import io.github.aindriub.dataprism.audit.AuditRecorder;
import io.github.aindriub.dataprism.audit.AuditSink;
import io.github.aindriub.dataprism.audit.FileAuditSink;
import io.github.aindriub.dataprism.audit.SegmentedFileAuditSink;
import io.github.aindriub.dataprism.core.spi.DataSourceAdapter;
import io.github.aindriub.dataprism.core.spi.IdentityResolver;
import io.github.aindriub.dataprism.core.spi.PassThroughIdentityResolver;
import io.github.aindriub.dataprism.core.metrics.PrivacyMetrics;
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
    void a_checkpoint_file_inside_the_audit_directory_is_refused(@TempDir Path dir) {
        Path segments = dir.resolve("segments");
        assertRefusedWith(runner().withPropertyValues("dataprism.audit.directory=" + segments,
                "dataprism.audit.checkpoint.file-path=" + segments.resolve("cp.log")),
                "AUDIT_CHECKPOINT_SAME_AS_AUDIT_FILE");
        // today's segment name, which would otherwise share the segment file itself
        assertRefusedWith(runner().withPropertyValues("dataprism.audit.directory=" + segments,
                "dataprism.audit.checkpoint.file-path=" + segments.resolve("audit-2026-10-06.log")),
                "AUDIT_CHECKPOINT_SAME_AS_AUDIT_FILE");
        // reached through a dot-dot segment, and inside a not-yet-created subdirectory
        assertRefusedWith(runner().withPropertyValues("dataprism.audit.directory=" + segments,
                "dataprism.audit.checkpoint.file-path=" + dir.resolve("other").resolve("..").resolve("segments")
                        .resolve("sub").resolve("cp.log")), "AUDIT_CHECKPOINT_SAME_AS_AUDIT_FILE");
    }

    @Test
    void a_case_variant_checkpoint_in_a_not_yet_created_audit_directory_is_refused(@TempDir Path dir) throws Exception {
        Path probe = Files.createDirectory(dir.resolve("CaseProbe"));
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.exists(dir.resolve("caseprobe")),
                "skipped: this filesystem is case-sensitive, so FRESH and fresh are different directories");
        assertThat(probe).exists();
        Path segments = dir.resolve("FRESH");
        assertThat(segments).doesNotExist();
        assertRefusedWith(runner().withPropertyValues("dataprism.audit.directory=" + segments,
                "dataprism.audit.checkpoint.file-path=" + dir.resolve("fresh").resolve("audit-2026-10-06.log")),
                "AUDIT_CHECKPOINT_SAME_AS_AUDIT_FILE");
    }

    @Test
    void the_checkpoint_bean_itself_refuses_a_case_variant_of_a_directory_the_sink_has_just_created(@TempDir Path dir)
            throws Exception {
        Files.createDirectory(dir.resolve("CaseProbe"));
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.exists(dir.resolve("caseprobe")),
                "skipped: this filesystem is case-sensitive, so FRESH and fresh are different directories");
        DataPrismProperties properties = new DataPrismProperties();
        properties.getAudit().setDirectory(dir.resolve("FRESH").toString());
        properties.getAudit().getCheckpoint().setFilePath(
                dir.resolve("fresh").resolve("audit-2026-10-06.log").toString());
        // validation ran while the directory did not exist and could not tell the paths apart;
        // the sink then creates the directory
        Files.createDirectory(dir.resolve("FRESH"));
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> new DataPrismAutoConfiguration().dataPrismAuditCheckpointSink(properties,
                        new org.springframework.beans.factory.support.StaticListableBeanFactory()
                                .getBeanProvider(AuditSink.class)))
                .isInstanceOfSatisfying(DataPrismConfigurationException.class,
                        e -> assertThat(e.code()).isEqualTo("AUDIT_CHECKPOINT_SAME_AS_AUDIT_FILE"));
    }

    @Test
    void a_checkpoint_path_equal_to_the_audit_directory_is_refused(@TempDir Path dir) {
        Path segments = dir.resolve("segments");
        assertRefusedWith(runner().withPropertyValues("dataprism.audit.directory=" + segments,
                "dataprism.audit.checkpoint.file-path=" + segments), "AUDIT_CHECKPOINT_SAME_AS_AUDIT_FILE");
    }

    @Test
    void a_checkpoint_reaching_the_audit_location_through_a_symlink_is_refused(@TempDir Path dir) throws Exception {
        Path segments = Files.createDirectory(dir.resolve("segments"));
        Path link = dir.resolve("link");
        Files.createSymbolicLink(link, segments);
        assertRefusedWith(runner().withPropertyValues("dataprism.audit.directory=" + segments,
                "dataprism.audit.checkpoint.file-path=" + link.resolve("cp.log")),
                "AUDIT_CHECKPOINT_SAME_AS_AUDIT_FILE");
        Path file = dir.resolve("audit.log");
        Files.createFile(file);
        Path fileLink = dir.resolve("audit-link.log");
        Files.createSymbolicLink(fileLink, file);
        assertRefusedWith(runner().withPropertyValues("dataprism.audit.file-path=" + file,
                "dataprism.audit.checkpoint.file-path=" + fileLink), "AUDIT_CHECKPOINT_SAME_AS_AUDIT_FILE");
    }

    @Test
    void a_checkpoint_beside_the_audit_directory_with_a_shared_name_prefix_is_accepted(@TempDir Path dir) {
        runner().withPropertyValues("dataprism.audit.directory=" + dir.resolve("segments"),
                "dataprism.audit.checkpoint.file-path=" + dir.resolve("segments-cp.log"))
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void a_directory_without_a_checkpoint_file_is_refused_whatever_the_sink(@TempDir Path dir) {
        for (String sink : new String[] {"slf4j", "approved-sink"}) {
            assertRefusedWith(runner().withPropertyValues("dataprism.audit.sink=" + sink,
                    "dataprism.audit.directory=" + dir.resolve("segments")), "RETENTION_REQUIRES_CHECKPOINT");
        }
    }

    @Test
    void a_second_purge_with_a_surviving_later_segment_reads_its_own_anchors_back(@TempDir Path dir)
            throws Exception {
        Path audit = dir.resolve("segments");
        java.util.concurrent.atomic.AtomicReference<Instant> now =
                new java.util.concurrent.atomic.AtomicReference<>(Instant.parse("2025-01-10T10:00:00Z"));
        Clock moving = new Clock() {
            @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(java.time.ZoneId zone) { return this; }
            @Override public Instant instant() { return now.get(); }
        };
        try (SegmentedFileAuditSink seed = new SegmentedFileAuditSink(audit)) {
            AuditRecorder recorder = new AuditRecorder(seed, moving, "seed");
            for (String day : new String[] {"2025-01-10", "2025-02-10", "2025-03-10"}) {
                now.set(Instant.parse(day + "T10:00:00Z"));
                recorder.record("p", "c", "get_entity_context", "CUSTOMER", "ps", "fp", "DEFAULT", "scope",
                        "investigation", "case-1", "ALLOW", Set.of("customer"), Set.of(), "corr");
            }
        }
        try (io.github.aindriub.dataprism.audit.FileAuditCheckpointSink cp =
                new io.github.aindriub.dataprism.audit.FileAuditCheckpointSink(dir.resolve("cp.jsonl"),
                        dir.resolve("unrelated.log"))) {
            // first purge deletes only January; the February segment survives to be purged later
            now.set(Instant.parse("2025-07-20T00:00:00Z"));
            io.github.aindriub.dataprism.audit.AuditRetention retention =
                    new io.github.aindriub.dataprism.audit.AuditRetention(audit, java.time.Period.ofMonths(6), cp,
                            moving);
            assertThat(retention.purge()).hasSize(1);
            now.set(Instant.parse("2025-08-20T00:00:00Z"));
            assertThat(retention.purge()).hasSize(1);
            now.set(Instant.parse("2025-09-20T00:00:00Z"));
            assertThat(retention.purge()).hasSize(1);
        }
        assertThat(audit.resolve("audit-2025-03-10.log")).doesNotExist();
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

    static String[] valid() {
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
                @Override public String fetch(io.github.aindriub.dataprism.core.spi.DataRequest request) { return null; }
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

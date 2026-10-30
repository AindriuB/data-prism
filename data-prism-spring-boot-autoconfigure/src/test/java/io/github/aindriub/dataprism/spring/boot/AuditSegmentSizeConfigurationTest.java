package io.github.aindriub.dataprism.spring.boot;

import io.github.aindriub.dataprism.audit.AuditRecorder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** Task 211: {@code dataprism.audit.segment-max-size}. */
class AuditSegmentSizeConfigurationTest {

    private static final Instant NOW = Instant.parse("2026-10-06T12:00:00Z");

    private static WebApplicationContextRunner runner() {
        return new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(DataPrismAutoConfiguration.class))
                .withUserConfiguration(AuditRetentionConfigurationTest.Integrations.class)
                .withBean(Clock.class, () -> Clock.fixed(NOW, ZoneOffset.UTC))
                .withPropertyValues(AuditRetentionConfigurationTest.valid())
                .withPropertyValues("dataprism.audit.sink=hash-chained");
    }

    private static WebApplicationContextRunner withDirectory(Path dir) {
        return runner().withPropertyValues("dataprism.audit.directory=" + dir.resolve("segments"),
                "dataprism.audit.checkpoint.file-path=" + dir.resolve("cp.jsonl"));
    }

    private static void assertRefusedWith(WebApplicationContextRunner r, String code) {
        r.run(context -> {
            assertThat(context).hasFailed();
            Throwable failure = context.getStartupFailure();
            while (failure.getCause() != null && !(failure instanceof DataPrismConfigurationException)) {
                failure = failure.getCause();
            }
            assertThat(failure).isInstanceOf(DataPrismConfigurationException.class);
            assertThat(((DataPrismConfigurationException) failure).code()).isEqualTo(code);
        });
    }

    @Test
    void the_size_is_unset_by_default() {
        assertThat(new AuditProperties().getSegmentMaxSize()).isNull();
    }

    @Test
    void a_size_without_a_directory_is_refused(@TempDir Path dir) {
        assertRefusedWith(runner().withPropertyValues("dataprism.audit.file-path=" + dir.resolve("a.log"),
                "dataprism.audit.segment-max-size=2MB"), "AUDIT_SEGMENT_SIZE_REQUIRES_DIRECTORY");
    }

    @Test
    void a_size_under_one_megabyte_is_refused(@TempDir Path dir) {
        assertRefusedWith(withDirectory(dir).withPropertyValues("dataprism.audit.segment-max-size=1023KB"),
                "INVALID_AUDIT_SEGMENT_SIZE");
        assertRefusedWith(withDirectory(dir).withPropertyValues("dataprism.audit.segment-max-size=0B"),
                "INVALID_AUDIT_SEGMENT_SIZE");
    }

    @Test
    void exactly_one_megabyte_starts(@TempDir Path dir) {
        withDirectory(dir).withPropertyValues("dataprism.audit.segment-max-size=1MB")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void writing_past_the_cap_makes_a_second_segment_for_one_day(@TempDir Path dir) throws Exception {
        withDirectory(dir).withPropertyValues("dataprism.audit.segment-max-size=1MB").run(context -> {
            assertThat(context).hasNotFailed();
            AuditRecorder recorder = context.getBean(AuditRecorder.class);
            String padding = "x".repeat(60_000);
            for (int i = 0; i < 24; i++) {
                recorder.record("p", "c", "get_entity_context", "CUSTOMER", "ps", "fp", "DEFAULT", "scope",
                        "investigation", "case-" + padding, "ALLOW", Set.of("customer"), Set.of(), "corr");
            }
        });
        try (Stream<Path> files = Files.list(dir.resolve("segments"))) {
            assertThat(files.map(p -> p.getFileName().toString()).filter(n -> n.startsWith("audit-2026-10-06")))
                    .hasSizeGreaterThanOrEqualTo(2);
        }
    }
}

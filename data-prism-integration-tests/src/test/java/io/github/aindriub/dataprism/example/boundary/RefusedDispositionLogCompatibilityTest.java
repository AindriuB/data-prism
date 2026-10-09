package io.github.aindriub.dataprism.example.boundary;

import io.github.aindriub.dataprism.audit.AuditEvent;
import io.github.aindriub.dataprism.audit.AuditRecorder;
import io.github.aindriub.dataprism.audit.format.AuditRecordFormat;
import io.github.aindriub.dataprism.audit.sink.FileAuditSink;
import io.github.aindriub.dataprism.audit.verify.AuditChainVerifier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 190 changes the value recorded for a refused source from {@code REFUSED} to {@code REFUSED:<code>}.
 * The record's fields and the hash-chain encoding are unchanged, so a log written before the change must
 * still verify, and a log that mixes both values must verify too.
 */
class RefusedDispositionLogCompatibilityTest {

    private static final String FIXTURE = "/audit/pre-190-plain-refused-chain.jsonl";

    @Test
    @DisplayName("a log written before the change, with plain REFUSED dispositions, still verifies")
    void preChangeLogStillVerifies() throws IOException {
        try (InputStream in = getClass().getResourceAsStream(FIXTURE)) {
            assertThat(in).as("the checked-in log").isNotNull();
            AuditChainVerifier.VerificationReport report = AuditChainVerifier.verify(in);

            // One call per writer instance, so three one-event chains.
            assertThat(report.writers()).hasSize(3).allSatisfy(w -> assertThat(w.broken()).isFalse());
            assertThat(report.hasBreak()).isFalse();
            assertThat(report.hasStructuralAnomaly()).isFalse();
        }
        try (InputStream in = getClass().getResourceAsStream(FIXTURE)) {
            List<AuditEvent> events = new String(in.readAllBytes(), StandardCharsets.UTF_8).lines()
                    .map(AuditRecordFormat::parse).toList();
            assertThat(events).anySatisfy(e -> assertThat(e.fieldDispositions().values()).contains("REFUSED"));
        }
    }

    @Test
    @DisplayName("a log mixing plain REFUSED and REFUSED:<code> dispositions verifies")
    void codedDispositionVerifies(@TempDir Path tempDir) throws IOException {
        Path path = tempDir.resolve("audit.log");
        try (FileAuditSink sink = new FileAuditSink(path)) {
            AuditRecorder recorder = new AuditRecorder(sink, PerSourceHarness.CLOCK, "instance-1");
            for (Map<String, String> dispositions : List.of(
                    Map.of("bad-api:<refused>", "REFUSED"),
                    Map.of("bad-api:<refused>", "REFUSED:UNKNOWN_FIELD", "ok-api:/name", "SYNTHESIZE"))) {
                recorder.record(new io.github.aindriub.dataprism.audit.AuditEntry("p", "c", "get_entity_context",
                        "THING", "pseudo", "fp", "DEFAULT", "scope", "purpose", "CASE-1", "ALLOW",
                        Set.of("ok-api:ANSWERED", "bad-api:REFUSED"), Set.of(), "corr", dispositions, "", "", ""));
            }
        }

        AuditChainVerifier.VerificationReport report = AuditChainVerifier.verify(path);

        assertThat(report.hasBreak()).isFalse();
        assertThat(report.hasStructuralAnomaly()).isFalse();
        assertThat(report.writers().get(0).sequenceCount()).isEqualTo(2);
        assertThat(Files.readString(path)).contains("REFUSED:UNKNOWN_FIELD");
    }

    private static AuditEvent withDisposition(String value) {
        return new AuditEvent("e", PerSourceHarness.CLOCK.instant(), "p", "c", "get_entity_context", "THING",
                "pseudo", "fp", "DEFAULT", "scope", "purpose", "CASE-1", "ALLOW", Set.of(), Set.of(), "corr",
                "i", 1, "0", "h", AuditEvent.CURRENT_VERSION, Map.of("src:<refused>", value), "", "", "");
    }

    @Test
    @DisplayName("REFUSED:<code> is accepted only when <code> is an upper-case token")
    void onlyATokenCodeFollowsRefused() {
        assertThat(withDisposition("REFUSED:UNKNOWN_FIELD").fieldDispositions())
                .containsEntry("src:<refused>", "REFUSED:UNKNOWN_FIELD");
        assertThat(withDisposition("REFUSED:INVALID_REFUSAL_CODE")).isNotNull();
        for (String bad : List.of("REFUSED:", "REFUSED:lower", "REFUSED:A B", "REFUSED:1ABC", "REFUSED:A:B",
                "REFUSED: A", "REFUSED:A\n", "refused:CODE", "REFUSED:" + "A".repeat(65), "REFUSEDX", "DENY:CODE",
                "REDACT:CODE")) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> withDisposition(bad))
                    .as(bad).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(withDisposition("REFUSED:" + "A".repeat(64))).isNotNull();
    }
}

package io.github.aindriub.dataprism.audit.verify;

import io.github.aindriub.dataprism.audit.AuditEntry;
import io.github.aindriub.dataprism.audit.AuditRecorder;
import io.github.aindriub.dataprism.audit.sink.FileAuditSink;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** A chain whose events carry the {@code names} key verifies, with no change to the verifier. */
class AuditChainVerifierNamesKeyTest {

    private static final Clock FIXED = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);

    @TempDir
    Path tempDir;

    private static void write(AuditRecorder recorder, String names) {
        recorder.record(new AuditEntry("investigator-1", "client-1", "get_entity_context", "CUSTOMER", "pseudo-1",
                "fp-1", "DEFAULT", "scope-1", "investigation", "CASE-1", "ALLOW",
                Set.of("customer-api:ANSWERED"), Set.of(), "corr-1",
                Map.of("names", names, "customer-api:/name", "SYNTHESIZE"), "", ""));
    }

    @Test
    @DisplayName("a chain of events carrying names=REAL and names=ALIAS verifies intact")
    void chainWithNamesKeyVerifies() throws IOException {
        Path path = tempDir.resolve("audit.log");
        try (FileAuditSink sink = new FileAuditSink(path)) {
            AuditRecorder recorder = new AuditRecorder(sink, FIXED, "instance-1");
            write(recorder, "ALIAS");
            write(recorder, "REAL");
            write(recorder, "ALIAS");
        }

        AuditChainVerifier.VerificationReport report = AuditChainVerifier.verify(path);

        assertThat(report.writers()).hasSize(1);
        assertThat(report.writers().get(0).sequenceCount()).isEqualTo(3);
        assertThat(report.hasBreak()).isFalse();
        assertThat(report.hasStructuralAnomaly()).isFalse();
        assertThat(report.tail()).isEmpty();
    }
}

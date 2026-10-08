package io.github.aindriub.dataprism.audit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class FileAuditCheckpointSinkTest {

    private static final String HEAD = "a".repeat(64);

    @TempDir
    Path tempDir;

    private static AuditCheckpoint checkpoint(AuditCheckpoint.Kind kind, long sequence) {
        return new AuditCheckpoint(kind, "w", sequence, HEAD, Instant.parse("2026-01-01T00:00:00Z"));
    }

    private Path tornFile() throws IOException {
        Path cp = tempDir.resolve("cp.jsonl");
        String torn = checkpoint(AuditCheckpoint.Kind.PERIODIC, 5).toJsonLine();
        Files.writeString(cp, checkpoint(AuditCheckpoint.Kind.BOOT, 0).toJsonLine() + "\n"
                + torn.substring(0, torn.length() / 2), StandardCharsets.UTF_8);
        return cp;
    }

    @Test
    void aTornTailIsTerminatedSoTheNextCheckpointIsOnItsOwnLine() throws IOException {
        Path cp = tornFile();
        try (FileAuditCheckpointSink sink = new FileAuditCheckpointSink(cp, tempDir.resolve("audit.log"))) {
            sink.record(checkpoint(AuditCheckpoint.Kind.SHUTDOWN, 6));
        }
        String content = Files.readString(cp, StandardCharsets.UTF_8);
        assertThat(content).endsWith("\n").contains("\r\n");
        List<String> lines = List.of(content.split("\n"));
        assertThat(lines).hasSize(3);
        assertThat(lines.get(1)).endsWith("\r");
        AuditCheckpoint last = AuditCheckpoint.fromJsonLine(lines.get(2));
        assertThat(last.kind()).isEqualTo(AuditCheckpoint.Kind.SHUTDOWN);
        assertThat(last.sequence()).isEqualTo(6);
    }

    @Test
    void anIntactFileIsLeftByteForByteUntouchedOnOpen() throws IOException {
        Path cp = tempDir.resolve("cp.jsonl");
        String intact = checkpoint(AuditCheckpoint.Kind.BOOT, 0).toJsonLine() + "\n";
        Files.writeString(cp, intact, StandardCharsets.UTF_8);
        new FileAuditCheckpointSink(cp, tempDir.resolve("audit.log")).close();
        assertThat(Files.readString(cp, StandardCharsets.UTF_8)).isEqualTo(intact);
    }

    @Test
    void readBackOnOpenToleratesTheTerminatedTornLine() throws IOException {
        Path cp = tempDir.resolve("cp.jsonl");
        AuditCheckpoint anchor = new AuditCheckpoint(AuditCheckpoint.Kind.RETENTION_ANCHOR, "w", 3, HEAD,
                Instant.parse("2026-01-01T00:00:00Z"), java.time.LocalDate.parse("2025-01-01"));
        String torn = checkpoint(AuditCheckpoint.Kind.PERIODIC, 5).toJsonLine();
        Files.writeString(cp, anchor.toJsonLine() + "\n" + torn.substring(0, 20), StandardCharsets.UTF_8);
        try (FileAuditCheckpointSink sink = new FileAuditCheckpointSink(cp, tempDir.resolve("audit.log"))) {
            assertThat(sink.retentionAnchors()).containsExactly(anchor);
        }
    }

    private static AuditEvent write(AuditRecorder recorder) {
        return recorder.record("investigator-1", "client-1", "get_entity_context", "CUSTOMER", "pseudo-1",
                "fp-1", "DEFAULT", "scope-1", "investigation", "CASE-1", "ALLOW",
                Set.of("customer-api:ANSWERED"), Set.of(), "corr-1");
    }

    private static List<AuditChainVerifier.StructuralAnomaly> torn(AuditChainVerifier.VerificationReport report) {
        return report.anomalies().stream()
                .filter(a -> a.type() == AuditChainVerifier.AnomalyType.TORN_CHECKPOINT_LINE).toList();
    }

    @Test
    void verifierReportsATornCheckpointLineAsANonBreakAnomalyAndStillUsesIntactCheckpoints() throws IOException {
        Path audit = tempDir.resolve("audit.log");
        Path cp = tempDir.resolve("cp.jsonl");
        Clock fixed = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
        try (FileAuditSink sink = new FileAuditSink(audit);
             FileAuditCheckpointSink cpSink = new FileAuditCheckpointSink(cp, audit)) {
            AuditRecorder r = new AuditRecorder(sink, fixed, "w", cpSink);
            write(r);
            write(r);
            r.close();
        }
        List<String> lines = Files.readAllLines(cp);
        String last = lines.get(lines.size() - 1);
        Files.writeString(cp, String.join("\n", lines.subList(0, lines.size() - 1)) + "\n"
                + last.substring(0, last.length() / 2), StandardCharsets.UTF_8);

        // restart: the resumed sink terminates the torn tail, the new boot checkpoints after it
        try (FileAuditSink sink = new FileAuditSink(audit);
             FileAuditCheckpointSink cpSink = new FileAuditCheckpointSink(cp, audit)) {
            AuditRecorder r = new AuditRecorder(sink, fixed, "w", cpSink);
            write(r);
            r.close();
        }

        AuditChainVerifier.VerificationReport report = AuditChainVerifier.verify(audit, cp);
        assertThat(torn(report)).hasSize(1);
        assertThat(report.hasBreak()).isFalse();
        assertThat(report.checkpointFindings()).isEmpty();
        assertThat(report.hasStructuralAnomaly()).isTrue();

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int exit = AuditChainVerifierCli.run(new String[] {audit.toString(), "--checkpoints", cp.toString()},
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
        assertThat(exit).isEqualTo(AuditChainVerifierCli.EXIT_STRUCTURAL_ANOMALY);
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("TORN_CHECKPOINT_LINE");
    }

    @Test
    void anUnterminatedUnparseableFinalCheckpointLineIsAlsoReportedTorn() throws IOException {
        Path audit = tempDir.resolve("audit.log");
        Files.writeString(audit, "");
        Path cp = tornFile();
        AuditChainVerifier.VerificationReport report = AuditChainVerifier.verify(audit, cp);
        assertThat(torn(report)).hasSize(1);
        assertThat(report.hasBreak()).isFalse();
    }

    @Test
    void anUnparseableMiddleLineStillFailsAsUnreadableInput() throws IOException {
        Path audit = tempDir.resolve("audit.log");
        Files.writeString(audit, "");
        Path cp = tempDir.resolve("cp.jsonl");
        Files.writeString(cp, "not json\n" + checkpoint(AuditCheckpoint.Kind.BOOT, 0).toJsonLine() + "\n");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> AuditChainVerifier.verify(audit, cp))
                .isInstanceOf(IOException.class);
    }
}

package io.github.aindriub.dataprism.audit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

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
}

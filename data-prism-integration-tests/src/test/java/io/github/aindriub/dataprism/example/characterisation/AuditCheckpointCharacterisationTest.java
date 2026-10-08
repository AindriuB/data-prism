package io.github.aindriub.dataprism.example.characterisation;

import io.github.aindriub.dataprism.audit.AuditCheckpoint;
import io.github.aindriub.dataprism.audit.checkpoint.FileAuditCheckpointSink;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Pins the checkpoint file written by the production path, {@link FileAuditCheckpointSink},
 * which appends {@link AuditCheckpoint#toJsonLine()} plus a line feed per checkpoint.
 *
 * <p>Observed Jackson 2 behaviour: fields in the order kind, instanceId, sequence, headHash,
 * recordedAt, then segmentDate only when present; no spaces; the sequence is a bare number;
 * a non-ASCII character in a string is NOT escaped here (this generator has no
 * {@code ESCAPE_NON_ASCII}) and is written as UTF-8; each line ends with a single LF, no CR.
 */
class AuditCheckpointCharacterisationTest {

    @Test
    @DisplayName("four consecutive checkpoints: fixed field order, no escaping of non-ASCII, one LF after each line")
    void consecutiveLines(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("checkpoints.jsonl");
        try (FileAuditCheckpointSink sink = new FileAuditCheckpointSink(file, dir.resolve("audit.jsonl"))) {
            sink.record(new AuditCheckpoint(AuditCheckpoint.Kind.BOOT, "instance-1", 0, "head-0",
                    Instant.parse("2026-09-08T12:00:00Z")));
            sink.record(new AuditCheckpoint(AuditCheckpoint.Kind.PERIODIC, "instance-café-中", 41,
                    "head-41", Instant.parse("2026-09-08T12:05:00.123456789Z")));
            sink.record(new AuditCheckpoint(AuditCheckpoint.Kind.RETENTION_ANCHOR, "instance-1", 42, "head-42",
                    Instant.parse("2026-09-08T12:06:00Z"), LocalDate.parse("2026-08-01")));
            sink.record(new AuditCheckpoint(AuditCheckpoint.Kind.SHUTDOWN, "quote\"slash\\", 43, "head-43",
                    Instant.parse("2026-09-08T12:07:00Z")));
        }

        Golden.assertMatches("checkpoints.jsonl", Files.readString(file, StandardCharsets.UTF_8));
    }
}

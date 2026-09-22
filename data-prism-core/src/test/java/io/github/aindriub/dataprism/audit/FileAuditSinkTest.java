package io.github.aindriub.dataprism.audit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileAuditSinkTest {

    @TempDir
    Path tempDir;

    private static AuditEvent event(String eventId, long sequence, String previousHash, String eventHash) {
        return new AuditEvent(
                eventId,
                Instant.parse("2026-01-01T00:00:00Z"),
                "investigator-1",
                "client-1",
                "get_entity_context",
                "CUSTOMER",
                "pseudo-1",
                "fp-1",
                "DEFAULT",
                "scope-1",
                "investigation",
                "CASE-1",
                "ALLOW",
                Set.of("customer-api:ANSWERED"),
                Set.of(),
                "corr-1",
                "instance-1",
                sequence,
                previousHash,
                eventHash);
    }

    @Test
    void opensAppendOnlyAndCreatesFileIfAbsent() throws IOException {
        Path path = tempDir.resolve("audit.log");
        assertThat(Files.exists(path)).isFalse();

        try (FileAuditSink sink = new FileAuditSink(path)) {
            sink.record(event("event-1", 0, "root", "hash-1"));
        }

        assertThat(Files.exists(path)).isTrue();
    }

    @Test
    void twoSinkInstancesOverTheSamePathAppendInOrder() throws IOException {
        Path path = tempDir.resolve("audit.log");

        try (FileAuditSink first = new FileAuditSink(path)) {
            first.record(event("event-1", 0, "root", "hash-1"));
            first.record(event("event-2", 1, "hash-1", "hash-2"));
        }

        try (FileAuditSink second = new FileAuditSink(path)) {
            second.record(event("event-3", 2, "hash-2", "hash-3"));
            second.record(event("event-4", 3, "hash-3", "hash-4"));
        }

        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        assertThat(lines).hasSize(4);
        assertThat(lines.stream().map(AuditRecordFormat::parse).map(AuditEvent::eventId))
                .containsExactly("event-1", "event-2", "event-3", "event-4");
    }

    @Test
    void recordIsDurableWithoutTheTestFlushingOrClosing() throws IOException {
        Path path = tempDir.resolve("audit.log");
        FileAuditSink sink = new FileAuditSink(path);

        sink.record(event("event-1", 0, "root", "hash-1"));

        try (FileChannel reader = FileChannel.open(path, StandardOpenOption.READ)) {
            ByteBuffer buffer = ByteBuffer.allocate((int) reader.size());
            while (buffer.hasRemaining() && reader.read(buffer) != -1) {
                // drain
            }
            String content = new String(buffer.array(), StandardCharsets.UTF_8);
            assertThat(content).contains("event-1");
        }

        sink.close();
    }

    @Test
    void constructingOnAnUnopenablePathThrowsWithAStableCodeNamingThePath() {
        Path unopenable = tempDir.resolve("missing-parent-dir").resolve("audit.log");

        assertThatThrownBy(() -> new FileAuditSink(unopenable))
                .isInstanceOf(FileAuditSink.OpenFailedException.class)
                .satisfies(e -> {
                    FileAuditSink.OpenFailedException open = (FileAuditSink.OpenFailedException) e;
                    assertThat(open.code()).isEqualTo("AUDIT_SINK_OPEN_FAILED");
                    assertThat(open.path()).isEqualTo(unopenable);
                    assertThat(open.getMessage()).contains(unopenable.toString());
                });
    }

    @Test
    void anIoFailureDuringRecordPropagatesRatherThanBeingSwallowed() throws IOException {
        Path path = tempDir.resolve("audit.log");
        FileAuditSink sink = new FileAuditSink(path);
        sink.close();

        assertThatThrownBy(() -> sink.record(event("event-1", 0, "root", "hash-1")))
                .isInstanceOf(UncheckedIOException.class);
    }
}

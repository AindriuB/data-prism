package io.github.aindriub.dataprism.audit.verify;

import io.github.aindriub.dataprism.audit.AuditRecorder;
import io.github.aindriub.dataprism.audit.sink.SegmentedFileAuditSink;
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
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class AuditChainVerifierSizeCapTest {

    private static final Clock DAY = Clock.fixed(Instant.parse("2026-03-01T10:00:00Z"), ZoneOffset.UTC);

    @TempDir
    Path tempDir;

    /** Twelve records over one day, two per segment would need ~12 files; a 10-byte cap gives one each. */
    private Path writeIndexedDay(int records) throws IOException {
        Path dir = tempDir.resolve("seg");
        try (SegmentedFileAuditSink sink = new SegmentedFileAuditSink(dir, 10)) {
            AuditRecorder r = new AuditRecorder(sink, DAY, "w");
            for (int i = 0; i < records; i++) {
                r.record("p", "c", "t", "E", "ps", "fp", "D", "s", "i", "CASE", "ALLOW",
                        Set.of("a:ANSWERED"), Set.of(), "corr");
            }
        }
        return dir;
    }

    @Test
    void directoryModeReadsIndexedSegmentsInIndexOrderEvenPastIndexTen() throws IOException {
        Path dir = writeIndexedDay(12);
        assertThat(Files.exists(dir.resolve("audit-2026-03-01.0011.log"))).isTrue();

        AuditChainVerifier.VerificationReport report = AuditChainVerifier.verify(dir);

        assertThat(report.hasBreak()).isFalse();
        assertThat(report.writers()).singleElement().satisfies(w -> assertThat(w.sequenceCount()).isEqualTo(12));
    }

    @Test
    void deletingAMiddleSegmentOfADayIsABreak() throws IOException {
        Path dir = writeIndexedDay(5);
        Files.delete(dir.resolve("audit-2026-03-01.0002.log"));

        assertThat(AuditChainVerifier.verify(dir).hasBreak()).isTrue();
    }

    @Test
    void helpMentionsTheIndexedSegmentNames() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        AuditChainVerifierCli.run(new String[]{"--help"}, new PrintStream(out), new PrintStream(out));
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("audit-YYYY-MM-DD.NNNN.log");
    }
}

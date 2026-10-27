package io.github.aindriub.dataprism.audit.sink;

import io.github.aindriub.dataprism.audit.AuditChainKey;
import io.github.aindriub.dataprism.audit.AuditChainMode;
import io.github.aindriub.dataprism.audit.AuditEvent;
import io.github.aindriub.dataprism.audit.AuditRecorder;
import io.github.aindriub.dataprism.audit.format.AuditRecordFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SegmentedFileAuditSinkSizeCapTest {

    private static final Clock DAY = Clock.fixed(Instant.parse("2026-03-01T10:00:00Z"), ZoneOffset.UTC);

    @TempDir
    Path tempDir;

    private static AuditEvent write(AuditRecorder recorder) {
        return recorder.record("investigator-1", "client-1", "get_entity_context", "CUSTOMER", "pseudo-1",
                "fp-1", "DEFAULT", "scope-1", "investigation", "CASE-1", "ALLOW",
                Set.of("customer-api:ANSWERED"), Set.of(), "corr-1");
    }

    private static long lineBytes(AuditEvent e) {
        return AuditRecordFormat.serialize(e).getBytes(java.nio.charset.StandardCharsets.UTF_8).length + 1L;
    }

    private static List<String> names(Path dir) throws IOException {
        try (Stream<Path> s = Files.list(dir)) {
            return s.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    @Test
    void withoutACapTheNamesAreExactlyTheUnindexedDayFile() throws IOException {
        Path dir = tempDir.resolve("s");
        try (SegmentedFileAuditSink sink = new SegmentedFileAuditSink(dir)) {
            AuditRecorder r = new AuditRecorder(sink, DAY, "w");
            for (int i = 0; i < 5; i++) {
                write(r);
            }
        }
        assertThat(names(dir)).containsExactly("audit-2026-03-01.log");
    }

    @Test
    void rotatesBeforeAWriteThatWouldPassTheCapAndNamesSegmentsByIndex() throws IOException {
        Path dir = tempDir.resolve("s");
        // The first record's size is fixed by a throwaway recorder so two records fit, a third does not.
        long one;
        try (SegmentedFileAuditSink probe = new SegmentedFileAuditSink(tempDir.resolve("probe"))) {
            one = lineBytes(write(new AuditRecorder(probe, DAY, "w")));
        }
        try (SegmentedFileAuditSink sink = new SegmentedFileAuditSink(dir, one * 2 + one / 2)) {
            AuditRecorder r = new AuditRecorder(sink, DAY, "w");
            for (int i = 0; i < 5; i++) {
                write(r);
            }
        }
        assertThat(names(dir)).containsExactly("audit-2026-03-01.0001.log", "audit-2026-03-01.0002.log",
                "audit-2026-03-01.log");
        assertThat(Files.readAllLines(dir.resolve("audit-2026-03-01.log"))).hasSize(2);
        assertThat(Files.readAllLines(dir.resolve("audit-2026-03-01.0001.log"))).hasSize(2);
        assertThat(Files.readAllLines(dir.resolve("audit-2026-03-01.0002.log"))).hasSize(1);
    }

    @Test
    void aRecordExactlyFillingTheCapStaysAndTheNextRotates() throws IOException {
        Path dir = tempDir.resolve("s");
        long one;
        try (SegmentedFileAuditSink probe = new SegmentedFileAuditSink(tempDir.resolve("probe"))) {
            one = lineBytes(write(new AuditRecorder(probe, DAY, "w")));
        }
        try (SegmentedFileAuditSink sink = new SegmentedFileAuditSink(dir, one)) {
            AuditRecorder r = new AuditRecorder(sink, DAY, "w");
            write(r);
            write(r);
        }
        assertThat(names(dir)).containsExactly("audit-2026-03-01.0001.log", "audit-2026-03-01.log");
    }

    @Test
    void aRecordLargerThanTheCapIsWrittenWholeIntoAFreshSegment() throws IOException {
        Path dir = tempDir.resolve("s");
        try (SegmentedFileAuditSink sink = new SegmentedFileAuditSink(dir, 10)) {
            AuditRecorder r = new AuditRecorder(sink, DAY, "w");
            AuditEvent e1 = write(r);
            AuditEvent e2 = write(r);
            assertThat(names(dir)).containsExactly("audit-2026-03-01.0001.log", "audit-2026-03-01.log");
            assertThat(Files.readAllLines(dir.resolve("audit-2026-03-01.log")))
                    .containsExactly(AuditRecordFormat.serialize(e1));
            assertThat(Files.readAllLines(dir.resolve("audit-2026-03-01.0001.log")))
                    .containsExactly(AuditRecordFormat.serialize(e2));
        }
    }

    @Test
    void aNewDayStartsAtTheUnindexedSegmentAndARestartResumesTheHighestIndex() throws IOException {
        Path dir = tempDir.resolve("s");
        try (SegmentedFileAuditSink sink = new SegmentedFileAuditSink(dir, 10)) {
            write(new AuditRecorder(sink, DAY, "w"));
            write(new AuditRecorder(sink, DAY, "w"));
        }
        assertThat(names(dir)).containsExactly("audit-2026-03-01.0001.log", "audit-2026-03-01.log");
        try (SegmentedFileAuditSink sink = new SegmentedFileAuditSink(dir, 1_000_000)) {
            write(new AuditRecorder(sink, DAY, "w2"));
            write(new AuditRecorder(sink, Clock.fixed(Instant.parse("2026-03-02T00:00:01Z"), ZoneOffset.UTC), "w2"));
        }
        assertThat(names(dir)).containsExactly("audit-2026-03-01.0001.log", "audit-2026-03-01.log",
                "audit-2026-03-02.log");
        assertThat(Files.readAllLines(dir.resolve("audit-2026-03-01.0001.log"))).hasSize(2);
    }

    @Test
    void theCapMustBePositive() {
        assertThatThrownBy(() -> new SegmentedFileAuditSink(tempDir, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void segmentDateReadsBothNameFormsAndNothingElse() {
        assertThat(SegmentedFileAuditSink.segmentDate(Path.of("audit-2026-03-01.log")))
                .isEqualTo(LocalDate.of(2026, 3, 1));
        assertThat(SegmentedFileAuditSink.segmentDate(Path.of("audit-2026-03-01.0007.log")))
                .isEqualTo(LocalDate.of(2026, 3, 1));
        for (String other : new String[]{"audit-2026-03-01.7.log", "audit-2026-03-01.0000.log", "audit-2026-03-01.00007.log",
                "audit-2026-03-01.0001.ndjson", "audit-2026-03-01.ndjson", "audit-2026-13-01.0001.log",
                "audit-2026-03-01.0001.log.bak", "x-audit-2026-03-01.log"}) {
            assertThat(SegmentedFileAuditSink.segmentDate(Path.of(other))).as(other).isNull();
        }
    }

    @Test
    void segmentOrderSortsByDateThenIndexNotByName() {
        List<Path> files = new ArrayList<>(Stream.of("audit-2026-03-02.log", "audit-2026-03-01.0010.log",
                "audit-2026-03-01.0002.log", "audit-2026-03-01.log", "audit-2026-02-28.0001.log")
                .map(Path::of).toList());
        files.sort(SegmentedFileAuditSink.SEGMENT_ORDER);
        assertThat(files.stream().map(Path::toString)).containsExactly("audit-2026-02-28.0001.log",
                "audit-2026-03-01.log", "audit-2026-03-01.0002.log", "audit-2026-03-01.0010.log",
                "audit-2026-03-02.log");
        assertThat(SegmentedFileAuditSink.segmentIndex(Path.of("audit-2026-03-01.log"))).isZero();
        assertThat(SegmentedFileAuditSink.segmentIndex(Path.of("audit-2026-03-01.0010.log"))).isEqualTo(10);
        assertThat(SegmentedFileAuditSink.segmentIndex(Path.of("other.log"))).isEqualTo(-1);
    }

    @Test
    void theChainContinuesAcrossSizeRotationsUnkeyedAndKeyed() throws IOException {
        for (AuditChainMode mode : List.of(AuditChainMode.unkeyed(),
                AuditChainMode.keyed(new AuditChainKey("k1", new byte[32])))) {
            Path dir = tempDir.resolve("chain-" + mode.isKeyed());
            try (SegmentedFileAuditSink sink = new SegmentedFileAuditSink(dir, 10)) {
                AuditRecorder r = new AuditRecorder(sink, DAY, "w", null, null, mode);
                for (int i = 0; i < 4; i++) {
                    write(r);
                }
            }
            List<Path> segments = new ArrayList<>(Files.list(dir).toList());
            segments.sort(SegmentedFileAuditSink.SEGMENT_ORDER);
            assertThat(segments).hasSize(4);
            AuditEvent prev = null;
            for (Path segment : segments) {
                AuditEvent first = AuditRecordFormat.parse(Files.readAllLines(segment).get(0));
                if (prev != null) {
                    assertThat(first.previousHash()).isEqualTo(prev.eventHash());
                }
                List<String> lines = Files.readAllLines(segment);
                prev = AuditRecordFormat.parse(lines.get(lines.size() - 1));
            }
        }
    }

    @Test
    void aFailureOpeningTheNextSegmentPoisonsEveryLaterRecord() throws IOException {
        Path dir = tempDir.resolve("s");
        SegmentedFileAuditSink.ChannelOpener opener = segment -> {
            if (segment.getFileName().toString().contains(".0001.")) {
                throw new IOException("disk full");
            }
            return SegmentedFileAuditSink.APPEND_OPENER.open(segment);
        };
        try (SegmentedFileAuditSink sink = new SegmentedFileAuditSink(dir, opener, 10)) {
            AuditRecorder r = new AuditRecorder(sink, DAY, "w");
            write(r);
            assertThatThrownBy(() -> write(r)).isInstanceOf(FileAuditSink.OpenFailedException.class);
            assertThatThrownBy(() -> write(r)).isInstanceOf(FileAuditSink.PoisonedException.class);
            assertThatThrownBy(() -> write(new AuditRecorder(sink,
                    Clock.fixed(Instant.parse("2026-03-05T00:00:00Z"), ZoneOffset.UTC), "x")))
                    .isInstanceOf(FileAuditSink.PoisonedException.class);
        }
    }
}

package io.github.aindriub.dataprism.audit.retention;

import io.github.aindriub.dataprism.audit.AuditCheckpoint;
import io.github.aindriub.dataprism.audit.AuditEvent;
import io.github.aindriub.dataprism.audit.AuditRecorder;
import io.github.aindriub.dataprism.audit.sink.SegmentedFileAuditSink;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Period;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuditRetentionSizeCapTest {

    private static final Clock NOW = Clock.fixed(Instant.parse("2026-09-10T12:00:00Z"), ZoneOffset.UTC);

    @TempDir
    Path tempDir;

    private final List<AuditCheckpoint> anchors = new ArrayList<>();

    /** One record per segment (10-byte cap): each day lists the instants of its records. */
    private List<AuditEvent> write(Path dir, String... instants) throws IOException {
        List<AuditEvent> out = new ArrayList<>();
        try (SegmentedFileAuditSink sink = new SegmentedFileAuditSink(dir, 10)) {
            for (String instant : instants) {
                AuditRecorder r = recorder(sink, instant);
                out.add(r.record("p", "c", "t", "E", "ps", "fp", "D", "s", "i", "CASE", "ALLOW",
                        Set.of("a:ANSWERED"), Set.of(), "corr"));
            }
        }
        return out;
    }

    private final java.util.Map<String, AuditRecorder> recorders = new java.util.HashMap<>();
    private Instant at;

    private AuditRecorder recorder(SegmentedFileAuditSink sink, String instant) {
        at = Instant.parse(instant);
        return recorders.computeIfAbsent("w" + System.identityHashCode(sink), k -> new AuditRecorder(sink,
                new Clock() {
                    @Override
                    public java.time.ZoneId getZone() {
                        return ZoneOffset.UTC;
                    }

                    @Override
                    public Clock withZone(java.time.ZoneId zone) {
                        return this;
                    }

                    @Override
                    public Instant instant() {
                        return at;
                    }
                }, "w"));
    }

    private static List<String> names(Path dir) throws IOException {
        try (Stream<Path> s = Files.list(dir)) {
            return s.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    private AuditRetention retention(Path dir) {
        return new AuditRetention(dir, Period.ofMonths(6), anchors::add, NOW);
    }

    @Test
    void aThreeSegmentExpiredDayIsDeletedWholeAndAnchoredAtItsLastSegmentsLastRecord() throws IOException {
        Path dir = tempDir.resolve("seg");
        List<AuditEvent> events = write(dir, "2026-03-01T01:00:00Z", "2026-03-01T02:00:00Z",
                "2026-03-01T03:00:00Z");
        assertThat(names(dir)).hasSize(3);

        List<Path> deleted = retention(dir).purge();

        assertThat(deleted.stream().map(p -> p.getFileName().toString())).containsExactly(
                "audit-2026-03-01.log", "audit-2026-03-01.0001.log", "audit-2026-03-01.0002.log");
        assertThat(names(dir)).isEmpty();
        assertThat(anchors).singleElement().satisfies(a -> {
            assertThat(a.kind()).isEqualTo(AuditCheckpoint.Kind.RETENTION_ANCHOR);
            assertThat(a.sequence()).isEqualTo(events.get(2).sequence());
            assertThat(a.headHash()).isEqualTo(events.get(2).eventHash());
            assertThat(a.segmentDate()).isEqualTo(LocalDate.of(2026, 3, 1));
        });
    }

    @Test
    void aMiddleSegmentThatFailsToVerifyKeepsEverySegmentOfItsDay() throws IOException {
        Path dir = tempDir.resolve("seg");
        write(dir, "2026-03-01T01:00:00Z", "2026-03-01T02:00:00Z", "2026-03-01T03:00:00Z");
        Path middle = dir.resolve("audit-2026-03-01.0001.log");
        Files.writeString(middle, Files.readString(middle).replace("ALLOW", "DENY"), StandardCharsets.UTF_8);

        assertThatThrownBy(() -> retention(dir).purge()).isInstanceOf(AuditRetention.RetentionException.class)
                .hasMessageContaining("AUDIT_RETENTION_CHAIN_UNVERIFIED");

        assertThat(names(dir)).containsExactly("audit-2026-03-01.0001.log", "audit-2026-03-01.0002.log",
                "audit-2026-03-01.log");
        assertThat(anchors).isEmpty();
    }

    @Test
    void anEarlierCleanDayIsStillPurgedWhenALaterDaysMiddleSegmentFails() throws IOException {
        Path dir = tempDir.resolve("seg");
        write(dir, "2026-03-01T01:00:00Z", "2026-03-01T02:00:00Z", "2026-03-02T01:00:00Z",
                "2026-03-02T02:00:00Z", "2026-03-02T03:00:00Z");
        Path middle = dir.resolve("audit-2026-03-02.0001.log");
        Files.writeString(middle, Files.readString(middle).replace("ALLOW", "DENY"), StandardCharsets.UTF_8);

        assertThatThrownBy(() -> retention(dir).purge()).isInstanceOf(AuditRetention.RetentionException.class);

        assertThat(names(dir)).containsExactly("audit-2026-03-02.0001.log", "audit-2026-03-02.0002.log",
                "audit-2026-03-02.log");
        assertThat(anchors).singleElement().satisfies(a -> assertThat(a.segmentDate())
                .isEqualTo(LocalDate.of(2026, 3, 1)));
    }

    @Test
    void aDeleteFailingPartwayThroughADayLeavesAPurgeableDayNotAFalseTamperReport() throws IOException {
        Path dir = tempDir.resolve("seg");
        write(dir, "2026-03-01T01:00:00Z", "2026-03-01T02:00:00Z", "2026-03-01T03:00:00Z");
        AuditRetention failing = retention(dir);
        failing.deleter = segment -> {
            if (segment.getFileName().toString().equals("audit-2026-03-01.0001.log")) {
                throw new IOException("injected");
            }
            Files.delete(segment);
        };

        assertThatThrownBy(failing::purge).isInstanceOf(AuditRetention.RetentionException.class)
                .hasMessageContaining("AUDIT_RETENTION_DELETE_FAILED");

        List<Path> second = retention(dir).purge();

        assertThat(names(dir)).isEmpty();
        assertThat(second).isNotEmpty();
    }
}

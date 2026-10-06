package io.github.aindriub.dataprism.audit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Instant;
import java.time.Period;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Attempt-2 defects of task 102: each test here failed on 93ce165. */
class AuditRetentionDefectsTest {

    @TempDir
    Path tempDir;

    private static final String CODE = "AUDIT_RETENTION_BELOW_MINIMUM";

    private final List<AuditCheckpoint> anchors = new ArrayList<>();
    private final AuditCheckpointSink anchorSink = anchors::add;

    private static Clock fixed(String instant) {
        return Clock.fixed(Instant.parse(instant), ZoneOffset.UTC);
    }

    /** One writer; one record per instant, written through a real recorder into a segmented sink. */
    private List<AuditEvent> write(Path dir, String instance, String... instants) throws IOException {
        Instant[] now = new Instant[1];
        Clock mutable = new Clock() {
            @Override
            public ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return now[0];
            }
        };
        List<AuditEvent> out = new ArrayList<>();
        try (SegmentedFileAuditSink sink = new SegmentedFileAuditSink(dir)) {
            AuditRecorder recorder = new AuditRecorder(sink, mutable, instance);
            for (String instant : instants) {
                now[0] = Instant.parse(instant);
                out.add(recorder.record("p", "c", "t", "E", "ps", "fp", "D", "s", "i", "CASE", "ALLOW",
                        Set.of("a:ANSWERED"), Set.of(), "corr"));
            }
        }
        return out;
    }

    private static List<String> names(Path dir) throws IOException {
        try (Stream<Path> s = Files.list(dir)) {
            return s.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    private int cli(ByteArrayOutputStream out, String... args) {
        return AuditChainVerifierCli.run(args, new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(new ByteArrayOutputStream()));
    }

    // ---- Defect 2: day-based periods bypass the floor

    @Test
    void dayBasedPeriodsThatCanBeShorterThanSixCalendarMonthsAreRefused() {
        for (Period p : List.of(Period.ofDays(181), Period.ofDays(183), Period.of(0, 5, 30))) {
            assertThatThrownBy(() -> new AuditRetention(tempDir, p, anchorSink, fixed("2026-12-31T00:00:00Z")))
                    .as(p.toString()).isInstanceOf(IllegalArgumentException.class).hasMessageContaining(CODE);
        }
    }

    @Test
    void p184dAndSixMonthsAreAccepted() {
        for (Period p : List.of(Period.ofDays(184), Period.ofMonths(6), Period.ofYears(1))) {
            assertThatCode(() -> new AuditRetention(tempDir, p, anchorSink, fixed("2026-12-31T00:00:00Z")))
                    .as(p.toString()).doesNotThrowAnyException();
        }
    }

    @Test
    void aPurgeOnTheLastDayOfDecemberNeverDeletesASegmentDatedAfterThirtiethOfJune() throws IOException {
        Path dir = tempDir.resolve("seg");
        write(dir, "w1", "2026-06-28T01:00:00Z", "2026-06-29T01:00:00Z", "2026-06-30T01:00:00Z",
                "2026-07-01T01:00:00Z");

        new AuditRetention(dir, Period.ofDays(184), anchorSink, fixed("2026-12-31T12:00:00Z")).purge();

        assertThat(names(dir)).containsExactly("audit-2026-06-30.jsonl", "audit-2026-07-01.jsonl");
    }

    // ---- Defect 3: a clock stepping back across UTC midnight

    @Test
    void aClockSteppingBackAcrossMidnightKeepsOneWritersRecordsInTheLaterSegment() throws IOException {
        Path dir = tempDir.resolve("seg");
        write(dir, "w1", "2026-03-02T00:00:01Z", "2026-03-01T23:59:59Z", "2026-03-02T00:10:00Z");

        assertThat(names(dir)).containsExactly("audit-2026-03-02.jsonl");
        AuditChainVerifier.VerificationReport report = AuditChainVerifier.verify(dir);
        assertThat(report.hasBreak()).isFalse();
        assertThat(report.writers()).singleElement().satisfies(w -> assertThat(w.sequenceCount()).isEqualTo(3));
    }

    // ---- Defect 1: anchors must not launder recent deletions

    @Test
    void anAnchorAppendedByHandOverRecentlyDeletedSegmentsDoesNotMakeTheChainIntact() throws IOException {
        Path dir = tempDir.resolve("seg");
        List<AuditEvent> events = write(dir, "w1", "2026-09-06T01:00:00Z", "2026-09-07T01:00:00Z",
                "2026-09-08T01:00:00Z", "2026-09-09T01:00:00Z");
        Files.delete(dir.resolve("audit-2026-09-06.jsonl"));
        Files.delete(dir.resolve("audit-2026-09-07.jsonl"));
        Files.delete(dir.resolve("audit-2026-09-08.jsonl"));
        Path checkpoints = tempDir.resolve("cp.jsonl");
        Files.writeString(checkpoints, new AuditCheckpoint(AuditCheckpoint.Kind.RETENTION_ANCHOR,
                events.get(0).instanceId(), 3, events.get(2).eventHash(), Instant.parse("2026-09-10T00:00:00Z"))
                .toJsonLine() + "\n");

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertThat(cli(out, dir.toString(), "--checkpoints", checkpoints.toString())).isNotEqualTo(0);
    }

    @Test
    void anAnchorAppendedByHandSuppressesMissingWriterNoLonger() throws IOException {
        Path dir = tempDir.resolve("seg");
        List<AuditEvent> events = write(dir, "w1", "2026-09-08T01:00:00Z", "2026-09-09T01:00:00Z");
        write(dir.resolveSibling("other"), "w2", "2026-09-09T05:00:00Z");
        Files.delete(dir.resolve("audit-2026-09-08.jsonl"));
        Files.delete(dir.resolve("audit-2026-09-09.jsonl"));
        Files.copy(dir.resolveSibling("other").resolve("audit-2026-09-09.jsonl"),
                dir.resolve("audit-2026-09-09.jsonl"));
        Path checkpoints = tempDir.resolve("cp.jsonl");
        Files.writeString(checkpoints,
                new AuditCheckpoint(AuditCheckpoint.Kind.PERIODIC, events.get(0).instanceId(), 2,
                        events.get(1).eventHash(), Instant.parse("2026-09-09T02:00:00Z")).toJsonLine() + "\n"
                        + new AuditCheckpoint(AuditCheckpoint.Kind.RETENTION_ANCHOR, events.get(0).instanceId(), 2,
                        events.get(1).eventHash(), Instant.parse("2026-09-10T00:00:00Z")).toJsonLine() + "\n");

        assertThat(cli(new ByteArrayOutputStream(), dir.toString(), "--checkpoints", checkpoints.toString()))
                .isNotEqualTo(0);
    }

    // ---- Also required: purge verifies before it anchors

    @Test
    void purgeRefusesASegmentWhoseChainDoesNotVerifyAndEveryLaterOne() throws IOException {
        Path dir = tempDir.resolve("seg");
        write(dir, "w1", "2026-03-01T01:00:00Z", "2026-03-02T01:00:00Z", "2026-03-03T01:00:00Z");
        Path tampered = dir.resolve("audit-2026-03-02.jsonl");
        Files.writeString(tampered, Files.readString(tampered).replace("ALLOW", "DENY"));

        assertThatThrownBy(() -> new AuditRetention(dir, Period.ofMonths(6), anchorSink,
                fixed("2026-09-10T12:00:00Z")).purge())
                .isInstanceOf(AuditRetention.RetentionException.class)
                .hasMessageContaining("AUDIT_RETENTION_CHAIN_UNVERIFIED")
                .hasMessageContaining("audit-2026-03-02.jsonl");

        assertThat(names(dir)).contains("audit-2026-03-02.jsonl", "audit-2026-03-03.jsonl");
        assertThat(anchors).allSatisfy(a -> assertThat(a.sequence()).isLessThan(2));
    }

    // ---- Also required: the newline inserted between segments

    @Test
    void aTornFragmentAtTheEndOfOneSegmentDoesNotSwallowTheNextSegmentsFirstRecord() throws IOException {
        Path dir = tempDir.resolve("seg");
        write(dir, "w1", "2026-03-01T01:00:00Z");
        Files.write(dir.resolve("audit-2026-03-01.jsonl"), "torn-fragment".getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.APPEND);
        write(dir, "w2", "2026-03-02T01:00:00Z");

        AuditChainVerifier.VerificationReport report = AuditChainVerifier.verify(dir);

        assertThat(report.writers()).extracting(AuditChainVerifier.WriterResult::instanceId)
                .hasSize(2);
        assertThat(report.hasBreak()).isFalse();
        assertThat(report.anomalies()).extracting(AuditChainVerifier.StructuralAnomaly::type)
                .containsExactly(AuditChainVerifier.AnomalyType.INTERRUPTED_WRITE_FRAGMENT);
    }
}

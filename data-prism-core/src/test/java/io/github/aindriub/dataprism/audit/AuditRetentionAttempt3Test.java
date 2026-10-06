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
import java.time.LocalDate;
import java.time.Period;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Attempt-3 defects of task 102: forged anchor dates, and purge erasing evidence of a hand deletion. */
class AuditRetentionAttempt3Test {

    private static final String GENESIS = "0".repeat(64);

    @TempDir
    Path tempDir;

    private static String seg(String date) {
        return SegmentedFileAuditSink.segmentName(LocalDate.parse(date));
    }

    private static Clock fixed(String instant) {
        return Clock.fixed(Instant.parse(instant), ZoneOffset.UTC);
    }

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

    private static String line(AuditCheckpoint.Kind kind, AuditEvent e, long seq, String hash, String recordedAt,
                               String segmentDate) {
        return new AuditCheckpoint(kind, e.instanceId(), seq, hash, Instant.parse(recordedAt),
                segmentDate == null ? null : LocalDate.parse(segmentDate)).toJsonLine() + "\n";
    }

    private int cli(ByteArrayOutputStream out, Path dir, Path checkpoints) {
        return AuditChainVerifierCli.run(new String[] {dir.toString(), "--checkpoints", checkpoints.toString()},
                new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(new ByteArrayOutputStream()));
    }

    // ---- Defect 1: a forged anchor date

    @Test
    void aForgedOldAnchorDateIsRejectedBecauseOfAnEarlierHeadCheckpoint() throws IOException {
        Path dir = tempDir.resolve("seg");
        List<AuditEvent> events = write(dir, "w1", "2026-09-06T01:00:00Z", "2026-09-07T01:00:00Z",
                "2026-09-08T01:00:00Z", "2026-09-09T01:00:00Z");
        for (String d : List.of("2026-09-06", "2026-09-07", "2026-09-08")) {
            Files.delete(dir.resolve(seg(d)));
        }
        Path cp = tempDir.resolve("cp.jsonl");
        Files.writeString(cp,
                line(AuditCheckpoint.Kind.BOOT, events.get(0), 0, GENESIS, "2026-09-06T00:00:00Z", null)
                        + line(AuditCheckpoint.Kind.RETENTION_ANCHOR, events.get(0), 3, events.get(2).eventHash(),
                        "2026-09-10T00:00:00Z", "2020-01-01"));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertThat(cli(out, dir, cp)).isEqualTo(AuditChainVerifierCli.EXIT_BREAK_DETECTED);
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("RETENTION_ANCHOR_REJECTED")
                .contains(events.get(0).instanceId());
    }

    @Test
    void aForgedOldAnchorDateDoesNotHideAMissingWriterWhenAHeadCheckpointContradictsIt() throws IOException {
        Path dir = tempDir.resolve("seg");
        List<AuditEvent> events = write(dir, "w1", "2026-09-08T01:00:00Z", "2026-09-09T01:00:00Z");
        write(dir.resolveSibling("other"), "w2", "2026-09-09T05:00:00Z");
        Files.delete(dir.resolve(seg("2026-09-08")));
        Files.delete(dir.resolve(seg("2026-09-09")));
        Files.copy(dir.resolveSibling("other").resolve(seg("2026-09-09")), dir.resolve(seg("2026-09-09")));
        Path cp = tempDir.resolve("cp.jsonl");
        Files.writeString(cp,
                line(AuditCheckpoint.Kind.BOOT, events.get(0), 0, GENESIS, "2026-09-08T00:00:00Z", null)
                        + line(AuditCheckpoint.Kind.PERIODIC, events.get(0), 2, events.get(1).eventHash(),
                        "2026-09-09T02:00:00Z", null)
                        + line(AuditCheckpoint.Kind.RETENTION_ANCHOR, events.get(0), 2, events.get(1).eventHash(),
                        "2026-09-10T00:00:00Z", "2020-01-01"));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertThat(cli(out, dir, cp)).isEqualTo(AuditChainVerifierCli.EXIT_CHECKPOINT_MISMATCH);
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("MISSING_WRITER");
    }

    @Test
    void anAnchorIsRejectedWhenTheFirstSurvivingRecordIsDatedBeforeTheAnchorsSegment() throws IOException {
        Path dir = tempDir.resolve("seg");
        List<AuditEvent> events = write(dir, "w1", "2026-03-01T01:00:00Z", "2026-03-02T01:00:00Z",
                "2026-09-09T01:00:00Z");
        Files.delete(dir.resolve(seg("2026-03-01")));
        Path cp = tempDir.resolve("cp.jsonl");
        Files.writeString(cp, line(AuditCheckpoint.Kind.RETENTION_ANCHOR, events.get(0), 1,
                events.get(0).eventHash(), "2026-09-10T00:00:00Z", "2026-03-05"));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertThat(cli(out, dir, cp)).isEqualTo(AuditChainVerifierCli.EXIT_BREAK_DETECTED);
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("RETENTION_ANCHOR_REJECTED");
    }

    @Test
    void aGenuineAnchorWithAHeadCheckpointRecordedOnItsSegmentDateIsStillAccepted() throws IOException {
        Path dir = tempDir.resolve("seg");
        List<AuditEvent> events = write(dir, "w1", "2026-03-01T01:00:00Z", "2026-09-09T01:00:00Z");
        Files.delete(dir.resolve(seg("2026-03-01")));
        Path cp = tempDir.resolve("cp.jsonl");
        Files.writeString(cp,
                line(AuditCheckpoint.Kind.BOOT, events.get(0), 0, GENESIS, "2026-03-01T00:00:00Z", null)
                        + line(AuditCheckpoint.Kind.RETENTION_ANCHOR, events.get(0), 1, events.get(0).eventHash(),
                        "2026-09-10T00:00:00Z", "2026-03-01"));

        assertThat(cli(new ByteArrayOutputStream(), dir, cp)).isEqualTo(AuditChainVerifierCli.EXIT_INTACT);
    }

    // ---- Defect 2: purge must not erase evidence of a hand deletion

    private AuditRetention retention(Path dir, FileAuditCheckpointSink sink, String now) {
        return new AuditRetention(dir, Period.ofMonths(6), sink, fixed(now));
    }

    @Test
    void purgeRefusesWhenTheFirstExpiringSegmentWasDeletedByHand() throws IOException {
        Path dir = tempDir.resolve("seg");
        write(dir, "w1", "2026-03-01T01:00:00Z", "2026-03-02T01:00:00Z", "2026-03-03T01:00:00Z",
                "2026-09-09T01:00:00Z");
        Files.delete(dir.resolve(seg("2026-03-01")));
        Path cp = tempDir.resolve("cp.jsonl");

        try (FileAuditCheckpointSink sink = new FileAuditCheckpointSink(cp, tempDir.resolve("unused.log"))) {
            assertThatThrownBy(() -> retention(dir, sink, "2026-09-10T12:00:00Z").purge())
                    .isInstanceOf(AuditRetention.RetentionException.class)
                    .hasMessageContaining("AUDIT_RETENTION_CHAIN_UNVERIFIED");
        }

        assertThat(names(dir)).containsExactly(seg("2026-03-02"), seg("2026-03-03"), seg("2026-09-09"));
        assertThat(Files.readString(cp)).isEmpty();
    }

    @Test
    void purgeRefusesWhenTheFrontOfAnExpiringSegmentWasCutAfterAnEarlierPurge() throws IOException {
        Path dir = tempDir.resolve("seg");
        write(dir, "w1", "2026-03-01T01:00:00Z", "2026-03-02T01:00:00Z", "2026-03-02T02:00:00Z",
                "2026-09-09T01:00:00Z");
        Path cp = tempDir.resolve("cp.jsonl");
        try (FileAuditCheckpointSink sink = new FileAuditCheckpointSink(cp, tempDir.resolve("unused.log"))) {
            retention(dir, sink, "2026-09-02T12:00:00Z").purge();
            assertThat(names(dir)).containsExactly(seg("2026-03-02"), seg("2026-09-09"));

            Path d1 = dir.resolve(seg("2026-03-02"));
            List<String> lines = Files.readAllLines(d1);
            Files.write(d1, List.of(lines.get(1)));

            assertThatThrownBy(() -> retention(dir, sink, "2026-09-10T12:00:00Z").purge())
                    .isInstanceOf(AuditRetention.RetentionException.class)
                    .hasMessageContaining("AUDIT_RETENTION_CHAIN_UNVERIFIED");
        }
        assertThat(names(dir)).contains(seg("2026-03-02"));
    }

    @Test
    void aSecondPurgeThatFollowsTheEarlierAnchorExactlyStillSucceeds() throws IOException {
        Path dir = tempDir.resolve("seg");
        write(dir, "w1", "2026-03-01T01:00:00Z", "2026-03-02T01:00:00Z", "2026-09-09T01:00:00Z");
        Path cp = tempDir.resolve("cp.jsonl");
        try (FileAuditCheckpointSink sink = new FileAuditCheckpointSink(cp, tempDir.resolve("unused.log"))) {
            retention(dir, sink, "2026-09-02T12:00:00Z").purge();

            retention(dir, sink, "2026-09-10T12:00:00Z").purge();
        }
        assertThat(names(dir)).containsExactly(seg("2026-09-09"));
    }
}

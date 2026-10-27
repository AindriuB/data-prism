package io.github.aindriub.dataprism.audit.retention;

import io.github.aindriub.dataprism.audit.AuditCheckpoint;
import io.github.aindriub.dataprism.audit.AuditCheckpointSink;
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

    // -- finishing a partly deleted day --------------------------------------------------------------

    /** A checkpoint sink that, like the file one, hands its retention anchors back. */
    private static final class AnchorStore implements AuditCheckpointSink {
        final List<AuditCheckpoint> all = new ArrayList<>();

        @Override
        public void record(AuditCheckpoint checkpoint) {
            all.add(checkpoint);
        }

        @Override
        public List<AuditCheckpoint> retentionAnchors() {
            return List.copyOf(all);
        }
    }

    /** Writes "writer@instant" entries in order through one sink, one record per segment (10-byte cap). */
    private void writeMulti(Path dir, String... entries) throws IOException {
        java.util.Map<String, AuditRecorder> byWriter = new java.util.HashMap<>();
        Instant[] now = new Instant[1];
        Clock clock = new Clock() {
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
                return now[0];
            }
        };
        try (SegmentedFileAuditSink sink = new SegmentedFileAuditSink(dir, 10)) {
            for (String entry : entries) {
                String[] parts = entry.split("@");
                now[0] = Instant.parse(parts[1]);
                byWriter.computeIfAbsent(parts[0], w -> new AuditRecorder(sink, clock, w)).record("p", "c", "t",
                        "E", "ps", "fp", "D", "s", "i", "CASE", "ALLOW", Set.of("a:ANSWERED"), Set.of(), "corr");
            }
        }
    }

    private static AuditRetention.Deleter failingOn(String name) {
        return segment -> {
            if (segment.getFileName().toString().equals(name)) {
                throw new IOException("injected");
            }
            Files.delete(segment);
        };
    }

    private void purgeFailingOn(AuditRetention retention, String name) {
        retention.deleter = failingOn(name);
        assertThatThrownBy(retention::purge).hasMessageContaining("AUDIT_RETENTION_DELETE_FAILED");
    }

    @Test
    void aPartlyDeletedDayIsFinishedEvenWhenTheNextDayHasAlsoExpired() throws IOException {
        Path dir = tempDir.resolve("seg");
        writeMulti(dir, "w@2026-03-01T01:00:00Z", "w@2026-03-01T02:00:00Z", "w@2026-03-01T03:00:00Z",
                "w@2026-03-02T01:00:00Z", "w@2026-09-09T01:00:00Z");
        AnchorStore store = new AnchorStore();
        purgeFailingOn(new AuditRetention(dir, Period.ofMonths(6), store, NOW), "audit-2026-03-01.0001.log");

        new AuditRetention(dir, Period.ofMonths(6), store, NOW).purge();

        assertThat(names(dir)).containsExactly("audit-2026-09-09.log");
    }

    @Test
    void aPartlyDeletedDayWithTwoWritersIsFinishedToo() throws IOException {
        Path dir = tempDir.resolve("seg");
        writeMulti(dir, "w1@2026-03-01T01:00:00Z", "w2@2026-03-01T02:00:00Z", "w1@2026-03-01T03:00:00Z",
                "w2@2026-03-01T04:00:00Z", "w1@2026-03-02T01:00:00Z", "w2@2026-03-02T02:00:00Z",
                "w1@2026-09-09T01:00:00Z");
        AnchorStore store = new AnchorStore();
        purgeFailingOn(new AuditRetention(dir, Period.ofMonths(6), store, NOW), "audit-2026-03-01.0001.log");
        assertThat(names(dir)).contains("audit-2026-03-01.log", "audit-2026-03-01.0001.log")
                .doesNotContain("audit-2026-03-01.0002.log");

        new AuditRetention(dir, Period.ofMonths(6), store, NOW).purge();

        assertThat(names(dir)).containsExactly("audit-2026-09-09.log");
    }

    @Test
    void aDayWhoseTailWasCutByHandWithNoMatchingAnchorStillRefuses() throws IOException {
        Path dir = tempDir.resolve("seg");
        writeMulti(dir, "w@2026-03-01T01:00:00Z", "w@2026-03-01T02:00:00Z", "w@2026-03-01T03:00:00Z",
                "w@2026-03-02T01:00:00Z");
        Files.delete(dir.resolve("audit-2026-03-01.0002.log"));

        assertThatThrownBy(() -> new AuditRetention(dir, Period.ofMonths(6), new AnchorStore(), NOW).purge())
                .hasMessageContaining("AUDIT_RETENTION_CHAIN_UNVERIFIED");
        assertThat(names(dir)).contains("audit-2026-03-02.log");
    }

    @Test
    void aPrefixWhereOnlyOneWriterIsAnchoredStillRefuses() throws IOException {
        Path dir = tempDir.resolve("seg");
        writeMulti(dir, "w1@2026-03-01T01:00:00Z", "w2@2026-03-01T02:00:00Z", "w1@2026-03-01T03:00:00Z",
                "w2@2026-03-01T04:00:00Z", "w1@2026-03-02T01:00:00Z", "w2@2026-03-02T02:00:00Z");
        AnchorStore store = new AnchorStore();
        purgeFailingOn(new AuditRetention(dir, Period.ofMonths(6), store, NOW), "audit-2026-03-01.0001.log");
        store.all.removeIf(a -> a.instanceId().startsWith("w2/") && a.segmentDate().equals(LocalDate.of(2026, 3, 1)));

        assertThatThrownBy(() -> new AuditRetention(dir, Period.ofMonths(6), store, NOW).purge())
                .hasMessageContaining("AUDIT_RETENTION_CHAIN_UNVERIFIED");
        assertThat(names(dir)).contains("audit-2026-03-02.log", "audit-2026-03-02.0001.log");
    }

    @Test
    void aDayFollowingAnEarlierDaysRecordedAnchorIsPurgedThroughTheAnchorStore() throws IOException {
        Path dir = tempDir.resolve("seg");
        writeMulti(dir, "w@2026-03-01T01:00:00Z", "w@2026-03-01T02:00:00Z", "w@2026-03-12T01:00:00Z",
                "w@2026-03-12T02:00:00Z", "w@2026-09-09T01:00:00Z");
        AnchorStore store = new AnchorStore();
        new AuditRetention(dir, Period.ofMonths(6), store, NOW).purge();
        assertThat(names(dir)).doesNotContain("audit-2026-03-01.log").contains("audit-2026-03-12.log");

        Clock later = Clock.fixed(Instant.parse("2026-09-20T12:00:00Z"), ZoneOffset.UTC);
        new AuditRetention(dir, Period.ofMonths(6), store, later).purge();

        assertThat(names(dir)).containsExactly("audit-2026-09-09.log");
        assertThat(store.all).hasSize(2);
    }

    @Test
    void aWriterWhoseRecordsAllSurviveInThePrefixDoesNotBlockFinishingTheDay() throws IOException {
        Path dir = tempDir.resolve("seg");
        writeMulti(dir, "a@2026-03-01T01:00:00Z", "b@2026-03-01T02:00:00Z", "b@2026-03-01T03:00:00Z",
                "b@2026-03-01T04:00:00Z", "a@2026-03-02T01:00:00Z", "b@2026-03-02T02:00:00Z",
                "a@2026-09-09T01:00:00Z");
        AnchorStore store = new AnchorStore();
        purgeFailingOn(new AuditRetention(dir, Period.ofMonths(6), store, NOW), "audit-2026-03-01.0001.log");
        assertThat(names(dir)).contains("audit-2026-03-01.log", "audit-2026-03-01.0001.log")
                .doesNotContain("audit-2026-03-01.0002.log");

        new AuditRetention(dir, Period.ofMonths(6), store, NOW).purge();

        assertThat(names(dir)).containsExactly("audit-2026-09-09.log");
    }

    @Test
    void aTamperedDayWithNoAnchorsIsRefusedAndKeptNotDeletedUnverified() throws IOException {
        Path dir = tempDir.resolve("seg");
        writeMulti(dir, "w@2026-03-01T01:00:00Z", "w@2026-03-01T02:00:00Z", "w@2026-03-01T03:00:00Z",
                "w@2026-03-02T01:00:00Z");
        Path middle = dir.resolve("audit-2026-03-01.0001.log");
        Files.writeString(middle, Files.readString(middle).replace("ALLOW", "DENY"), StandardCharsets.UTF_8);

        assertThatThrownBy(() -> new AuditRetention(dir, Period.ofMonths(6), new AnchorStore(), NOW).purge())
                .hasMessageContaining("AUDIT_RETENTION_CHAIN_UNVERIFIED");

        assertThat(names(dir)).contains("audit-2026-03-01.log", "audit-2026-03-01.0001.log",
                "audit-2026-03-01.0002.log");
    }

    /** An anchor store that can be armed to throw when it is asked to record an anchor for one day. */
    private static final class FlakyAnchorStore implements AuditCheckpointSink {
        final List<AuditCheckpoint> all = new ArrayList<>();
        LocalDate failFor;

        @Override
        public void record(AuditCheckpoint checkpoint) {
            if (checkpoint.segmentDate().equals(failFor)) {
                throw new IllegalStateException("injected anchor failure");
            }
            all.add(checkpoint);
        }

        @Override
        public List<AuditCheckpoint> retentionAnchors() {
            return List.copyOf(all);
        }
    }

    @Test
    void anAnchorFailureAfterFinishingAPartlyDeletedDayNamesTheDayItFinished() throws IOException {
        Path dir = tempDir.resolve("seg");
        writeMulti(dir, "w@2026-03-01T01:00:00Z", "w@2026-03-01T02:00:00Z", "w@2026-03-01T03:00:00Z",
                "w@2026-03-02T01:00:00Z");
        FlakyAnchorStore store = new FlakyAnchorStore();
        purgeFailingOn(new AuditRetention(dir, Period.ofMonths(6), store, NOW), "audit-2026-03-01.0001.log");
        // The earlier run anchored both days, so the second must be refused a *new* anchor to fail.
        store.all.removeIf(a -> a.segmentDate().equals(LocalDate.of(2026, 3, 2)));
        store.failFor = LocalDate.of(2026, 3, 2);

        assertThatThrownBy(() -> new AuditRetention(dir, Period.ofMonths(6), store, NOW).purge())
                .hasMessageContaining("AUDIT_RETENTION_ANCHOR_FAILED")
                .hasMessageContaining("nothing was deleted beyond finishing the partly purged day 2026-03-01");

        assertThat(names(dir)).containsExactly("audit-2026-03-02.log");
    }

    @Test
    void anIntactAnchoredDayThatWasTamperedWithBeforeTheNextRunIsRefusedNotFinished() throws IOException {
        Path dir = tempDir.resolve("seg");
        writeMulti(dir, "w@2026-03-01T01:00:00Z", "w@2026-03-01T02:00:00Z", "w@2026-03-01T03:00:00Z",
                "w@2026-03-02T01:00:00Z");
        FlakyAnchorStore store = new FlakyAnchorStore();
        store.failFor = LocalDate.of(2026, 3, 2);
        assertThatThrownBy(() -> new AuditRetention(dir, Period.ofMonths(6), store, NOW).purge())
                .hasMessageContaining("AUDIT_RETENTION_ANCHOR_FAILED");
        assertThat(names(dir)).hasSize(4);
        assertThat(store.all).hasSize(1); // day 1 is anchored, nothing is deleted
        Path middle = dir.resolve("audit-2026-03-01.0001.log");
        Files.writeString(middle, Files.readString(middle).replace("ALLOW", "DENY"), StandardCharsets.UTF_8);
        store.failFor = null;

        assertThatThrownBy(() -> new AuditRetention(dir, Period.ofMonths(6), store, NOW).purge())
                .hasMessageContaining("AUDIT_RETENTION_CHAIN_UNVERIFIED");

        assertThat(names(dir)).contains("audit-2026-03-01.log", "audit-2026-03-01.0001.log",
                "audit-2026-03-01.0002.log");
    }
}

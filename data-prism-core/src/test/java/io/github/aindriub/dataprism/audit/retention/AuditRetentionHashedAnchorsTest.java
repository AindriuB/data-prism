package io.github.aindriub.dataprism.audit.retention;

import io.github.aindriub.dataprism.audit.AuditChainKey;
import io.github.aindriub.dataprism.audit.AuditChainMode;
import io.github.aindriub.dataprism.audit.AuditCheckpoint;
import io.github.aindriub.dataprism.audit.AuditCheckpointSink;
import io.github.aindriub.dataprism.audit.AuditEvent;
import io.github.aindriub.dataprism.audit.AuditRecorder;
import io.github.aindriub.dataprism.audit.AuditSink;
import io.github.aindriub.dataprism.audit.format.AuditRecordFormat;
import io.github.aindriub.dataprism.audit.sink.SegmentedFileAuditSink;
import io.github.aindriub.dataprism.audit.verify.AuditChainVerifier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

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
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Retention anchors and refusals come only from records the verifier hashed (task 292, rules 1 to 5). Every test
 * runs in plain mode and in keyed mode unless it says otherwise.
 */
class AuditRetentionHashedAnchorsTest {

    static final String D1 = "audit-2026-03-01.log";
    static final String D2 = "audit-2026-03-02.log";
    static final String SEP = "\u001f";

    @TempDir
    Path tempDir;

    /** A directory of audit segments, its writers, a checkpoint store, and the oracle every purge is held to. */
    static final class Lab {
        static final Clock NOW = Clock.fixed(Instant.parse("2026-09-10T12:00:00Z"), ZoneOffset.UTC);
        static final LocalDate CUTOFF = LocalDate.of(2026, 3, 10);

        final Path dir;
        final boolean keyed;
        final AuditChainKey key = new AuditChainKey("k1", new byte[32]);
        final AuditChainMode mode;
        final List<AuditChainKey> keys;
        final Store store = new Store();
        final SegmentedFileAuditSink sink;
        Instant at = Instant.parse("2026-03-01T00:00:00Z");
        final Clock clock = new Clock() {
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
        };

        Lab(Path dir, boolean keyed) throws IOException {
            this(dir, keyed, 0);
        }

        /** {@code cap} of 10 puts one record in each segment. */
        Lab(Path dir, boolean keyed, long cap) throws IOException {
            this.dir = dir;
            this.keyed = keyed;
            this.mode = keyed ? AuditChainMode.keyed(key) : AuditChainMode.unkeyed();
            this.keys = keyed ? List.of(key) : List.of();
            this.sink = cap > 0 ? new SegmentedFileAuditSink(dir, cap) : new SegmentedFileAuditSink(dir);
        }

        AuditRecorder writer(String id) {
            return new AuditRecorder(sink, clock, id, null, null, mode);
        }

        /** A writer that records BOOT, PERIODIC and SHUTDOWN checkpoints into {@link #store}. */
        AuditRecorder closingWriter(String id) {
            return new AuditRecorder(sink, clock, id, store, null, mode);
        }

        AuditRecorder writerOn(AuditSink target, String id) {
            return new AuditRecorder(target, clock, id, store, null, mode);
        }

        AuditEvent record(AuditRecorder w, String instant) {
            at = Instant.parse(instant);
            return w.record("p", "c", "t", "E", "ps", "fp", "D", "s", "i", "CASE", "ALLOW",
                    Set.of("a:ANSWERED"), Set.of(), "corr");
        }

        List<AuditEvent> record(AuditRecorder w, String... instants) {
            List<AuditEvent> out = new ArrayList<>();
            for (String instant : instants) {
                out.add(record(w, instant));
            }
            return out;
        }

        void close() throws IOException {
            sink.close();
        }

        Path file(String name) {
            return dir.resolve(name);
        }

        List<String> names() throws IOException {
            try (Stream<Path> s = Files.list(dir)) {
                return s.map(p -> p.getFileName().toString()).sorted().toList();
            }
        }

        String read(String name) throws IOException {
            return Files.readString(file(name), StandardCharsets.UTF_8);
        }

        void write(String name, String content) throws IOException {
            Files.writeString(file(name), content, StandardCharsets.UTF_8);
        }

        void append(String name, String content) throws IOException {
            Files.writeString(file(name), read(name) + content, StandardCharsets.UTF_8);
        }

        /** Replaces the last line of a segment (and its newline) with {@code replacement}, verbatim. */
        void replaceLastLine(String name, String replacement) throws IOException {
            String content = read(name);
            int end = content.endsWith("\n") ? content.length() - 1 : content.length();
            int start = content.lastIndexOf('\n', end - 1) + 1;
            write(name, content.substring(0, start) + replacement);
        }

        AuditRetention retention() {
            return retention(keys);
        }

        AuditRetention retention(List<AuditChainKey> chainKeys) {
            return new AuditRetention(dir, Period.ofMonths(6), store, Clock.fixed(NOW.instant(), ZoneOffset.UTC),
                    false, chainKeys, false);
        }

        /** The last hashed record per day and writer over the expiring segments, taken before a purge deletes them. */
        Map<String, AuditChainVerifier.HashedRecord> oracle(List<AuditChainKey> chainKeys) throws IOException {
            List<Path> expiring = new ArrayList<>();
            try (Stream<Path> s = Files.list(dir)) {
                s.forEach(p -> {
                    LocalDate d = SegmentedFileAuditSink.segmentDate(p);
                    if (d != null && d.isBefore(CUTOFF)) {
                        expiring.add(p);
                    }
                });
            }
            expiring.sort(SegmentedFileAuditSink.SEGMENT_ORDER);
            Map<String, AuditChainVerifier.HashedRecord> last = new HashMap<>();
            if (expiring.isEmpty()) {
                return last;
            }
            AuditChainVerifier.HashedVerification hv = AuditChainVerifier.verifySegmentsHashed(expiring,
                    new ArrayList<>(), chainKeys, false, Map.of());
            for (int i = 0; i < expiring.size(); i++) {
                String day = SegmentedFileAuditSink.segmentDate(expiring.get(i)).toString();
                hv.segments().get(i).writers().forEach((id, span) -> last.put(day + "|" + id, span.last()));
            }
            return last;
        }

        /** Runs {@code r.purge()}, then holds every anchor it recorded to the oracle taken just before. */
        Outcome purge(AuditRetention r) throws IOException {
            return purge(r, keys);
        }

        Outcome purge(AuditRetention r, List<AuditChainKey> oracleKeys) throws IOException {
            Map<String, AuditChainVerifier.HashedRecord> expected = oracle(oracleKeys);
            int mark = store.recorded.size();
            List<Path> deleted = null;
            RuntimeException thrown = null;
            try {
                deleted = r.purge();
            } catch (RuntimeException e) {
                thrown = e;
            }
            for (AuditCheckpoint a : store.recorded.subList(mark, store.recorded.size())) {
                if (a.kind() != AuditCheckpoint.Kind.RETENTION_ANCHOR) {
                    continue;
                }
                AuditChainVerifier.HashedRecord want = expected.get(a.segmentDate() + "|" + a.instanceId());
                assertThat(want).as("an anchor for %s %s that the oracle did not expect", a.segmentDate(),
                        a.instanceId()).isNotNull();
                assertThat(a.sequence()).isEqualTo(want.sequence());
                assertThat(a.headHash()).isEqualTo(want.eventHash());
            }
            return new Outcome(deleted, thrown);
        }

        List<AuditCheckpoint> anchors() {
            return store.recorded.stream().filter(a -> a.kind() == AuditCheckpoint.Kind.RETENTION_ANCHOR).toList();
        }
    }

    record Outcome(List<Path> deleted, RuntimeException thrown) {
        void refused(String containing) {
            assertThat(thrown).as("the purge should have been refused").isNotNull();
            assertThat(thrown).isInstanceOf(AuditRetention.RetentionException.class)
                    .hasMessageContaining("AUDIT_RETENTION_CHAIN_UNVERIFIED").hasMessageContaining(containing);
        }

        void succeeded() {
            assertThat(thrown).as("the purge should have succeeded").isNull();
        }
    }

    /** A checkpoint store that, like the file one, hands anchors and shutdowns back. */
    static final class Store implements AuditCheckpointSink {
        final List<AuditCheckpoint> recorded = new ArrayList<>();
        final List<AuditCheckpoint> extraShutdowns = new ArrayList<>();
        RuntimeException shutdownReadFails;

        @Override
        public void record(AuditCheckpoint checkpoint) {
            recorded.add(checkpoint);
        }

        @Override
        public List<AuditCheckpoint> retentionAnchors() {
            return recorded.stream().filter(a -> a.kind() == AuditCheckpoint.Kind.RETENTION_ANCHOR).toList();
        }

        @Override
        public List<AuditCheckpoint> shutdownCheckpoints() {
            if (shutdownReadFails != null) {
                throw shutdownReadFails;
            }
            List<AuditCheckpoint> out = new ArrayList<>(
                    recorded.stream().filter(a -> a.kind() == AuditCheckpoint.Kind.SHUTDOWN).toList());
            out.addAll(extraShutdowns);
            return out;
        }
    }

    // -- shape D: a forged anchor ---------------------------------------------------------------------

    @ParameterizedTest
    @CsvSource({"false,true", "false,false", "true,true", "true,false"})
    void aForgedLastRecordAppendedToTheExpiringDayIsRefusedAndNeverAnchored(boolean keyed, boolean terminated)
            throws IOException {
        Lab lab = new Lab(tempDir.resolve("seg"), keyed);
        AuditRecorder w = lab.writer("w");
        lab.record(w, "2026-03-01T01:00:00Z", "2026-03-01T02:00:00Z", "2026-03-01T03:00:00Z");
        List<AuditEvent> middle = lab.record(w, "2026-09-05T01:00:00Z", "2026-09-05T02:00:00Z",
                "2026-09-05T03:00:00Z");
        lab.record(w, "2026-09-08T01:00:00Z");
        lab.close();
        Files.delete(lab.file("audit-2026-09-05.log"));
        AuditEvent six = middle.get(2);
        lab.append(D1, AuditRecordFormat.serialize(six) + (terminated ? "\r\n" : ""));

        Outcome outcome = lab.purge(lab.retention());

        outcome.refused(D1);
        assertThat(lab.names()).contains(D1);
        assertThat(lab.anchors()).noneMatch(a -> a.sequence() == 6 || a.headHash().equals(six.eventHash()));
        assertThat(AuditChainVerifier.verify(lab.dir, null, Period.ofMonths(6), lab.keys, false).hasBreak())
                .isTrue();
    }

    // -- shapes A, A2, B: an edited last record -------------------------------------------------------

    private String edited(AuditEvent e) {
        return AuditRecordFormat.serialize(e).replace("ALLOW", "DENY");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void anEditedLastRecordEndedWithCarriageReturnKeepsTheSingleExpiringDay(boolean keyed) throws IOException {
        Lab lab = new Lab(tempDir.resolve("seg"), keyed);
        AuditRecorder w = lab.writer("w");
        List<AuditEvent> d1 = lab.record(w, "2026-03-01T01:00:00Z", "2026-03-01T02:00:00Z", "2026-03-01T03:00:00Z");
        lab.record(lab.writer("other"), "2026-09-09T01:00:00Z");
        lab.close();
        lab.replaceLastLine(D1, edited(d1.get(2)) + "\r\n");

        Outcome outcome = lab.purge(lab.retention());

        outcome.refused(D1);
        assertThat(lab.names()).contains(D1);
        assertThat(lab.anchors()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void anEditedLastRecordWithNoNewlineKeepsTheFinalExpiringSegment(boolean keyed) throws IOException {
        Lab lab = new Lab(tempDir.resolve("seg"), keyed);
        AuditRecorder w = lab.writer("w");
        List<AuditEvent> d1 = lab.record(w, "2026-03-01T01:00:00Z", "2026-03-01T02:00:00Z", "2026-03-01T03:00:00Z");
        lab.record(lab.writer("other"), "2026-09-09T01:00:00Z");
        lab.close();
        lab.replaceLastLine(D1, edited(d1.get(2)));

        Outcome outcome = lab.purge(lab.retention());

        outcome.refused(D1);
        assertThat(lab.names()).contains(D1);
        assertThat(lab.anchors()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void anEditedLastRecordWithTheNextDayAlsoExpiringKeepsBothDaysAcrossRuns(boolean keyed) throws IOException {
        Lab lab = new Lab(tempDir.resolve("seg"), keyed);
        AuditRecorder w = lab.writer("w");
        List<AuditEvent> d1 = lab.record(w, "2026-03-01T01:00:00Z", "2026-03-01T02:00:00Z", "2026-03-01T03:00:00Z");
        lab.record(w, "2026-03-02T01:00:00Z");
        lab.record(lab.writer("other"), "2026-09-09T01:00:00Z");
        lab.close();
        lab.replaceLastLine(D1, edited(d1.get(2)) + "\r\n");

        Outcome first = lab.purge(lab.retention());
        Outcome second = lab.purge(lab.retention());

        first.refused(D1);
        second.refused(D1);
        assertThat(lab.names()).contains(D1, D2);
        assertThat(lab.anchors()).isEmpty();
    }

    @Test
    void anEditedLastRecordIsRefusedWithTwoKeysSuppliedToo() throws IOException {
        Lab lab = new Lab(tempDir.resolve("seg"), true);
        AuditChainKey k0 = new AuditChainKey("k0", new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16,
                17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32});
        AuditRecorder w = lab.writer("w");
        List<AuditEvent> d1 = lab.record(w, "2026-03-01T01:00:00Z", "2026-03-01T02:00:00Z", "2026-03-01T03:00:00Z");
        lab.record(w, "2026-03-02T01:00:00Z");
        lab.record(lab.writer("other"), "2026-09-09T01:00:00Z");
        lab.close();
        lab.replaceLastLine(D1, edited(d1.get(2)) + "\r\n");
        List<AuditChainKey> both = List.of(k0, lab.key);

        Outcome first = lab.purge(lab.retention(both), both);
        Outcome second = lab.purge(lab.retention(both), both);

        first.refused(D1);
        second.refused(D1);
        assertThat(lab.names()).contains(D1, D2);
    }

    @Test
    void aCleanDayIsPurgedWithTheAnchorOfTheKeyThatVerifiedItAndTheNextBrokenDayStays() throws IOException {
        Lab lab = new Lab(tempDir.resolve("seg"), true);
        AuditChainKey k0 = new AuditChainKey("k0", new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16,
                17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32});
        AuditRecorder w = lab.writer("w");
        List<AuditEvent> d1 = lab.record(w, "2026-03-01T01:00:00Z", "2026-03-01T02:00:00Z");
        List<AuditEvent> d2 = lab.record(w, "2026-03-02T01:00:00Z", "2026-03-02T02:00:00Z");
        lab.record(lab.writer("other"), "2026-09-09T01:00:00Z");
        lab.close();
        // A content break: one field edited, the stored hash kept (the newline-terminated line is a record).
        String content = lab.read(D2);
        lab.write(D2, content.replaceFirst("ALLOW", "DENY"));
        List<AuditChainKey> both = List.of(k0, lab.key);

        Outcome outcome = lab.purge(lab.retention(both), both);

        outcome.refused(D2);
        assertThat(lab.names()).containsExactly(D2, "audit-2026-09-09.log");
        assertThat(lab.anchors()).singleElement().satisfies(a -> {
            assertThat(a.sequence()).isEqualTo(d1.get(1).sequence());
            assertThat(a.headHash()).isEqualTo(d1.get(1).eventHash());
        });
        assertThat(d2).hasSize(2);
    }

    // -- shape E: a duplicate sequence ----------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aDuplicateSequenceLineAppendedToTheExpiringDayIsRefused(boolean keyed) throws IOException {
        Lab lab = new Lab(tempDir.resolve("seg"), keyed);
        AuditRecorder w = lab.writer("w");
        List<AuditEvent> d1 = lab.record(w, "2026-03-01T01:00:00Z", "2026-03-01T02:00:00Z", "2026-03-01T03:00:00Z");
        lab.record(lab.writer("other"), "2026-09-09T01:00:00Z");
        lab.close();
        lab.append(D1, edited(d1.get(1)) + "\n");

        Outcome outcome = lab.purge(lab.retention());

        outcome.refused(D1);
        assertThat(lab.names()).contains(D1);
    }

    // -- the partial-day path (shapes C, C2) ----------------------------------------------------------

    @ParameterizedTest
    @CsvSource({"false,true", "false,false", "true,true", "true,false"})
    void aTamperedPrefixOfAPartlyDeletedDayIsNotFinished(boolean keyed, boolean terminated) throws IOException {
        Lab lab = new Lab(tempDir.resolve("seg"), keyed, 10);
        AuditRecorder w = lab.writer("w");
        lab.record(w, "2026-03-01T01:00:00Z", "2026-03-01T02:00:00Z", "2026-03-01T03:00:00Z");
        lab.record(w, "2026-03-02T01:00:00Z", "2026-09-09T01:00:00Z");
        lab.close();
        AuditRetention failing = lab.retention();
        failing.deleter = segment -> {
            if (segment.getFileName().toString().equals("audit-2026-03-01.0001.log")) {
                throw new IOException("injected");
            }
            Files.delete(segment);
        };
        assertThatThrownBy(failing::purge).hasMessageContaining("AUDIT_RETENTION_DELETE_FAILED");
        assertThat(lab.names()).contains(D1, "audit-2026-03-01.0001.log").doesNotContain("audit-2026-03-01.0002.log");
        String last = lab.read("audit-2026-03-01.0001.log").replace("ALLOW", "DENY");
        lab.write("audit-2026-03-01.0001.log", terminated ? last.substring(0, last.length() - 1) + "\r\n"
                : last.substring(0, last.length() - 1));

        Outcome outcome = lab.purge(lab.retention());

        outcome.refused("audit-2026-03-01");
        assertThat(lab.names()).contains(D1, "audit-2026-03-01.0001.log", D2, "audit-2026-09-09.log");
    }

    // -- a planted line between the cut and the next record -------------------------------------------

    @ParameterizedTest
    @CsvSource({"false,true", "false,false", "true,true", "true,false"})
    void aLastRecordCutFromTheExpiringDayAndPlantedInAnotherExpiredDayDoesNotLetTheDayGo(boolean keyed,
                                                                                         boolean cutToPrefix)
            throws IOException {
        Lab lab = new Lab(tempDir.resolve("seg"), keyed);
        AuditRecorder w = lab.writer("w");
        List<AuditEvent> d1 = lab.record(w, "2026-03-01T01:00:00Z", "2026-03-01T02:00:00Z", "2026-03-01T03:00:00Z");
        lab.record(w, "2026-09-09T01:00:00Z");
        lab.close();
        String third = AuditRecordFormat.serialize(d1.get(2));
        if (cutToPrefix) {
            lab.replaceLastLine(D1, third.substring(0, 60) + "\r\n");
        } else {
            String content = lab.read(D1);
            lab.write(D1, content.substring(0, content.lastIndexOf(third)));
        }
        lab.write(D2, third + SEP + "forged\n");

        Outcome outcome = lab.purge(lab.retention());

        outcome.refused("does not chain from the last record verified there");
        assertThat(outcome.thrown().getMessage()).contains(D1);
        assertThat(lab.names()).contains(D1, D2);
        assertThat(lab.anchors()).isEmpty();
    }

    @Test
    void aWriterCleanThroughEveryExpiringDayUnderTheSecondOfTwoKeysIsAnchoredAtItsLastRecords()
            throws IOException {
        Lab lab = new Lab(tempDir.resolve("seg"), true);
        AuditChainKey k0 = new AuditChainKey("k0", new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16,
                17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32});
        AuditRecorder w = lab.writer("w");
        List<AuditEvent> d1 = lab.record(w, "2026-03-01T01:00:00Z", "2026-03-01T02:00:00Z");
        List<AuditEvent> d2 = lab.record(w, "2026-03-02T01:00:00Z", "2026-03-02T02:00:00Z");
        lab.record(lab.writer("other"), "2026-09-09T01:00:00Z");
        lab.close();
        List<AuditChainKey> both = List.of(k0, lab.key);

        Outcome outcome = lab.purge(lab.retention(both), both);

        outcome.succeeded();
        assertThat(lab.names()).containsExactly("audit-2026-09-09.log");
        assertThat(lab.anchors()).extracting(AuditCheckpoint::sequence, AuditCheckpoint::headHash)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(d1.get(1).sequence(), d1.get(1).eventHash()),
                        org.assertj.core.groups.Tuple.tuple(d2.get(1).sequence(), d2.get(1).eventHash()));
    }

    // -- a crash line that parses ---------------------------------------------------------------------

    /** The line of {@code e} cut inside field {@code index} (0-based), keeping a prefix of that field. */
    private static String cutInsideField(AuditEvent e, int index) {
        String[] fields = AuditRecordFormat.serialize(e).split(SEP, -1);
        String partial = fields[index].isEmpty() ? "" : fields[index].substring(0, Math.max(1, fields[index].length() / 2));
        return String.join(SEP, Arrays.copyOf(fields, index)) + SEP + partial;
    }

    private static boolean parses(String line) {
        try {
            AuditRecordFormat.parse(line);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** A crash fragment of x's third record in D1, then y writing on the next expiring day and a retained one. */
    private Lab crashLab(boolean keyed, java.util.function.Function<AuditEvent, String> fragment)
            throws IOException {
        Lab lab = new Lab(tempDir.resolve("seg"), keyed);
        AuditRecorder x = lab.writer("x");
        List<AuditEvent> d1 = lab.record(x, "2026-03-01T01:00:00Z", "2026-03-01T02:00:00Z", "2026-03-01T03:00:00Z");
        AuditRecorder y = lab.writer("y");
        lab.record(y, "2026-03-02T01:00:00Z", "2026-03-02T02:00:00Z");
        lab.record(lab.writer("z"), "2026-09-09T01:00:00Z");
        lab.close();
        lab.replaceLastLine(D1, fragment.apply(d1.get(2)));
        return lab;
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aCompleteLookingCrashLineEndedWithCarriageReturnKeepsItsDayAndTheNext(boolean keyed) throws IOException {
        Lab lab = crashLab(keyed, e -> AuditRecordFormat.serialize(e) + "\r\n");

        Outcome outcome = lab.purge(lab.retention());

        outcome.refused(D1);
        assertThat(lab.names()).contains(D1, D2);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aCompleteLookingCrashLineLeftUnterminatedKeepsItsDayAndTheNext(boolean keyed) throws IOException {
        Lab lab = crashLab(keyed, AuditRecordFormat::serialize);

        Outcome outcome = lab.purge(lab.retention());

        outcome.refused(D1);
        assertThat(lab.names()).contains(D1, D2);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aVersion3CrashLineCutInsideTheTwentiethFieldStillParsesSoItKeepsItsDay(boolean keyed) throws IOException {
        Lab lab = crashLab(keyed, e -> {
            String cut = cutInsideField(e, 19);
            assertThat(parses(cut)).as("a cut inside raw[19] parses as a version 1 record").isTrue();
            return cut + "\r\n";
        });

        Outcome outcome = lab.purge(lab.retention());

        outcome.refused(D1);
        assertThat(lab.names()).contains(D1, D2);
    }

    // -- a real crash fragment still purges -----------------------------------------------------------

    private void assertFragmentPurges(Lab lab) throws IOException {
        Outcome outcome = lab.purge(lab.retention());

        outcome.succeeded();
        assertThat(lab.names()).containsExactly("audit-2026-09-09.log");
        assertThat(lab.anchors()).extracting(AuditCheckpoint::sequence).contains(2L);
        assertThat(lab.anchors()).hasSize(2); // x at its last hashed record (2), y at its last
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aTornFragmentEndedWithCarriageReturnStillPurgesAndAnchorsTheLastHashedRecord(boolean keyed)
            throws IOException {
        Lab lab = crashLab(keyed, e -> AuditRecordFormat.serialize(e).substring(0, 60) + "\r\n");
        assertFragmentPurges(lab);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aTornFragmentLeftUnterminatedStillPurgesAndAnchorsTheLastHashedRecord(boolean keyed) throws IOException {
        Lab lab = crashLab(keyed, e -> AuditRecordFormat.serialize(e).substring(0, 60));
        assertFragmentPurges(lab);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aVersion3FragmentCutInsideTheTwentyFourthFieldIsTornSoItStillPurges(boolean keyed) throws IOException {
        Lab lab = crashLab(keyed, e -> {
            String cut = cutInsideField(e, 23);
            assertThat(parses(cut)).as("a cut inside raw[23] does not parse").isFalse();
            return cut + "\r\n";
        });
        assertFragmentPurges(lab);
    }

    // -- the source holds no raw parsing --------------------------------------------------------------

    @Test
    void retentionNoLongerParsesRawLines() throws IOException {
        String source = Files.readString(Path.of("src/main/java/io/github/aindriub/dataprism/audit/retention/"
                + "AuditRetention.java"));
        assertThat(source).doesNotContain("readAllLines").doesNotContain("AuditRecordFormat.parse");
    }
}

package io.github.aindriub.dataprism.audit.verify;

import io.github.aindriub.dataprism.audit.AuditChainKey;
import io.github.aindriub.dataprism.audit.AuditChainMode;
import io.github.aindriub.dataprism.audit.AuditEvent;
import io.github.aindriub.dataprism.audit.AuditRecorder;
import io.github.aindriub.dataprism.audit.format.AuditRecordFormat;
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
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Rule 2 of task 292: which records the replay hashed, from the replay the report gives for each writer. */
class AuditChainVerifierHashedRecordsTest {

    private static final AuditChainKey K0 = new AuditChainKey("k0", new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12,
            13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32});
    private static final AuditChainKey K1 = new AuditChainKey("k1", new byte[32]);

    @TempDir
    Path tempDir;

    private Instant at;
    private SegmentedFileAuditSink sink;
    private Path dir;

    private final Clock clock = new Clock() {
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

    private void open() throws IOException {
        dir = tempDir.resolve("seg");
        sink = new SegmentedFileAuditSink(dir);
    }

    private AuditRecorder writer(String id, AuditChainMode mode) {
        return new AuditRecorder(sink, clock, id, null, null, mode);
    }

    private AuditEvent record(AuditRecorder w, String instant) {
        at = Instant.parse(instant);
        return w.record("p", "c", "t", "E", "ps", "fp", "D", "s", "i", "CASE", "ALLOW", Set.of("a:ANSWERED"),
                Set.of(), "corr");
    }

    private List<Path> segments() throws IOException {
        sink.close();
        try (var files = Files.list(dir)) {
            return files.sorted(SegmentedFileAuditSink.SEGMENT_ORDER).toList();
        }
    }

    private AuditChainVerifier.HashedVerification hashed(List<AuditChainKey> keys) throws IOException {
        return AuditChainVerifier.verifySegmentsHashed(segments(), new ArrayList<>(), keys, false, Map.of());
    }

    private static String text(Path p) throws IOException {
        return Files.readString(p, StandardCharsets.UTF_8);
    }

    @Test
    void twoWritersAcrossThreeSegmentsGiveEachWritersFirstAndLastRecordAndTheLatestDate() throws IOException {
        open();
        AuditRecorder a = writer("a", AuditChainMode.unkeyed());
        AuditRecorder b = writer("b", AuditChainMode.unkeyed());
        AuditEvent a1 = record(a, "2026-03-01T01:00:00Z");
        AuditEvent b1 = record(b, "2026-03-01T02:00:00Z");
        AuditEvent a2 = record(a, "2026-03-02T01:00:00Z");
        AuditEvent a3 = record(a, "2026-03-02T05:00:00Z");
        AuditEvent b2 = record(b, "2026-03-03T01:00:00Z");

        AuditChainVerifier.HashedVerification hv = hashed(List.of());

        assertThat(hv.report().hasBreak()).isFalse();
        assertThat(hv.segments()).hasSize(3);
        assertThat(hv.segments().get(0).writers().keySet()).containsExactlyInAnyOrder(a.instanceId(), b.instanceId());
        assertThat(hv.segments().get(0).writers().get(a.instanceId()).first().eventHash()).isEqualTo(a1.eventHash());
        assertThat(hv.segments().get(0).writers().get(a.instanceId()).last().sequence()).isEqualTo(1);
        AuditChainVerifier.WriterSpan second = hv.segments().get(1).writers().get(a.instanceId());
        assertThat(second.first().eventHash()).isEqualTo(a2.eventHash());
        assertThat(second.first().previousHash()).isEqualTo(a1.eventHash());
        assertThat(second.last().eventHash()).isEqualTo(a3.eventHash());
        assertThat(second.last().byteOffset()).isGreaterThan(second.first().byteOffset());
        assertThat(hv.segments().get(1).writers()).doesNotContainKey(b.instanceId());
        assertThat(hv.segments().get(2).writers().get(b.instanceId()).last().eventHash()).isEqualTo(b2.eventHash());
        assertThat(hv.segments().get(0).latestRecordDate()).contains(LocalDate.of(2026, 3, 1));
        assertThat(hv.segments().get(1).latestRecordDate()).contains(LocalDate.of(2026, 3, 2));
        assertThat(hv.firstUnhashedCompleteOffset()).isEmpty();
        assertThat(hv.brokenWriters()).isEmpty();
        assertThat(b1.sequence()).isEqualTo(1);
    }

    @Test
    void aLinkFailureMidSegmentStopsTheHashedRecordsAndIsReportedAsALinkFailure() throws IOException {
        open();
        AuditRecorder w = writer("w", AuditChainMode.unkeyed());
        AuditEvent e1 = record(w, "2026-03-01T01:00:00Z");
        AuditEvent e2 = record(w, "2026-03-01T02:00:00Z");
        record(w, "2026-03-01T03:00:00Z");
        AuditEvent e4 = record(w, "2026-03-01T04:00:00Z");
        Path file = segments().get(0);
        String[] lines = text(file).split("\n");
        Files.writeString(file, lines[0] + "\n" + lines[1] + "\n" + lines[3] + "\n", StandardCharsets.UTF_8);

        AuditChainVerifier.HashedVerification hv = hashed(List.of());

        AuditChainVerifier.BrokenWriter broken = hv.brokenWriters().get(w.instanceId());
        assertThat(broken.linkFailure()).isTrue();
        assertThat(broken.lastHashedBeforeBreak()).hasValueSatisfying(r -> assertThat(r.eventHash())
                .isEqualTo(e2.eventHash()));
        assertThat(hv.segments().get(0).writers().get(w.instanceId()).last().eventHash()).isEqualTo(e2.eventHash());
        assertThat(hv.firstUnhashedCompleteOffset()).isPresent();
        assertThat(e1.sequence()).isEqualTo(1);
        assertThat(e4.sequence()).isEqualTo(4);
    }

    @Test
    void aContentFailureMidSegmentIsReportedAsAContentFailureWithTheRecordBeforeIt() throws IOException {
        open();
        AuditRecorder w = writer("w", AuditChainMode.unkeyed());
        record(w, "2026-03-01T01:00:00Z");
        AuditEvent e2 = record(w, "2026-03-01T02:00:00Z");
        record(w, "2026-03-01T03:00:00Z");
        Path file = segments().get(0);
        String[] lines = text(file).split("\n");
        Files.writeString(file, lines[0] + "\n" + lines[1] + "\n" + lines[2].replace("ALLOW", "DENY") + "\n",
                StandardCharsets.UTF_8);

        AuditChainVerifier.HashedVerification hv = hashed(List.of());

        AuditChainVerifier.BrokenWriter broken = hv.brokenWriters().get(w.instanceId());
        assertThat(broken.linkFailure()).isFalse();
        assertThat(broken.lastHashedBeforeBreak()).hasValueSatisfying(r -> assertThat(r.eventHash())
                .isEqualTo(e2.eventHash()));
        assertThat(hv.segments().get(0).writers().get(w.instanceId()).last().sequence()).isEqualTo(2);
    }

    @Test
    void aKeyedWriterVerifiedUnderTheSecondKeyHasItsHashedRecordsFromThatReplay() throws IOException {
        open();
        AuditRecorder w = writer("w", AuditChainMode.keyed(K1));
        record(w, "2026-03-01T01:00:00Z");
        AuditEvent e2 = record(w, "2026-03-02T01:00:00Z");

        AuditChainVerifier.HashedVerification hv = hashed(List.of(K0, K1));

        assertThat(hv.report().writers()).singleElement().satisfies(r -> assertThat(r.keyId()).contains("k1"));
        assertThat(hv.brokenWriters()).isEmpty();
        assertThat(hv.segments().get(1).writers().get(w.instanceId()).last().eventHash()).isEqualTo(e2.eventHash());
        assertThat(hv.segments().get(0).writers().get(w.instanceId()).first().sequence()).isEqualTo(1);
    }

    @Test
    void aWriterBrokenUnderEveryKeyTakesItsPositionsFromTheReplayThatReachedFurthest() throws IOException {
        open();
        AuditRecorder w = writer("w", AuditChainMode.keyed(K1));
        record(w, "2026-03-01T01:00:00Z");
        AuditEvent e2 = record(w, "2026-03-01T02:00:00Z");
        record(w, "2026-03-01T03:00:00Z");
        Path file = segments().get(0);
        String[] lines = text(file).split("\n");
        Files.writeString(file, lines[0] + "\n" + lines[1] + "\n" + lines[2].replace("ALLOW", "DENY") + "\n",
                StandardCharsets.UTF_8);

        AuditChainVerifier.HashedVerification hv = hashed(List.of(K0, K1));

        AuditChainVerifier.BrokenWriter broken = hv.brokenWriters().get(w.instanceId());
        assertThat(broken.linkFailure()).isFalse();
        assertThat(broken.lastHashedBeforeBreak()).hasValueSatisfying(r -> assertThat(r.eventHash())
                .isEqualTo(e2.eventHash()));
        assertThat(hv.segments().get(0).writers().get(w.instanceId()).last().sequence()).isEqualTo(2);
    }

    @Test
    void aWriterThatOnlyVerifiesUnkeyedWhenAKeyIsRequiredHasNoHashedRecordsAndABrokenContentKind()
            throws IOException {
        open();
        AuditRecorder w = writer("w", AuditChainMode.unkeyed());
        record(w, "2026-03-01T01:00:00Z");
        record(w, "2026-03-01T02:00:00Z");

        AuditChainVerifier.HashedVerification hv = hashed(List.of(K1));

        AuditChainVerifier.BrokenWriter broken = hv.brokenWriters().get(w.instanceId());
        assertThat(broken.lastHashedBeforeBreak()).isEmpty();
        assertThat(broken.linkFailure()).isFalse();
        assertThat(hv.segments().get(0).writers()).isEmpty();
        assertThat(hv.firstUnhashedCompleteOffset()).hasValue(0);
    }

    @Test
    void aCarriageReturnLineThatParsesIsAnUnhashedCompleteRecordAtItsOffset() throws IOException {
        open();
        AuditRecorder w = writer("w", AuditChainMode.unkeyed());
        AuditEvent e1 = record(w, "2026-03-01T01:00:00Z");
        AuditEvent e2 = record(w, "2026-03-01T02:00:00Z");
        Path file = segments().get(0);
        String first = AuditRecordFormat.serialize(e1) + "\n";
        Files.writeString(file, first + AuditRecordFormat.serialize(e2) + "\r\n", StandardCharsets.UTF_8);

        AuditChainVerifier.HashedVerification hv = hashed(List.of());

        assertThat(hv.firstUnhashedCompleteOffset()).hasValue(first.getBytes(StandardCharsets.UTF_8).length);
        assertThat(hv.segments().get(0).writers().get(w.instanceId()).last().sequence()).isEqualTo(1);
    }

    @Test
    void anUnterminatedTailThatParsesIsAnUnhashedCompleteRecordAtItsOffset() throws IOException {
        open();
        AuditRecorder w = writer("w", AuditChainMode.unkeyed());
        AuditEvent e1 = record(w, "2026-03-01T01:00:00Z");
        AuditEvent e2 = record(w, "2026-03-01T02:00:00Z");
        Path file = segments().get(0);
        String first = AuditRecordFormat.serialize(e1) + "\n";
        Files.writeString(file, first + AuditRecordFormat.serialize(e2), StandardCharsets.UTF_8);

        AuditChainVerifier.HashedVerification hv = hashed(List.of());

        assertThat(hv.report().tail()).isPresent();
        assertThat(hv.firstUnhashedCompleteOffset()).hasValue(first.getBytes(StandardCharsets.UTF_8).length);
    }

    @Test
    void aDuplicateSequencesSecondCopyIsAnUnhashedCompleteRecord() throws IOException {
        open();
        AuditRecorder w = writer("w", AuditChainMode.unkeyed());
        AuditEvent e1 = record(w, "2026-03-01T01:00:00Z");
        AuditEvent e2 = record(w, "2026-03-01T02:00:00Z");
        Path file = segments().get(0);
        String both = text(file);
        Files.writeString(file, both + AuditRecordFormat.serialize(e1).replace("ALLOW", "DENY") + "\n",
                StandardCharsets.UTF_8);

        AuditChainVerifier.HashedVerification hv = hashed(List.of());

        assertThat(hv.firstUnhashedCompleteOffset()).hasValue(both.getBytes(StandardCharsets.UTF_8).length);
        assertThat(hv.segments().get(0).writers().get(w.instanceId()).last().eventHash()).isEqualTo(e2.eventHash());
    }

    @Test
    void aFragmentThatDoesNotParseIsNotAnUnhashedCompleteRecord() throws IOException {
        open();
        AuditRecorder w = writer("w", AuditChainMode.unkeyed());
        AuditEvent e1 = record(w, "2026-03-01T01:00:00Z");
        AuditEvent e2 = record(w, "2026-03-01T02:00:00Z");
        Path file = segments().get(0);
        Files.writeString(file, AuditRecordFormat.serialize(e1) + "\n"
                + AuditRecordFormat.serialize(e2).substring(0, 60) + "\r\n"
                + AuditRecordFormat.serialize(e2).substring(0, 60), StandardCharsets.UTF_8);

        AuditChainVerifier.HashedVerification hv = hashed(List.of());

        assertThat(hv.firstUnhashedCompleteOffset()).isEmpty();
        assertThat(hv.report().tail()).isPresent();
    }

    @Test
    void theHeldReportEqualsWhatVerifySegmentsReportsOnTheSameInputs() throws IOException {
        open();
        AuditRecorder w = writer("w", AuditChainMode.unkeyed());
        AuditEvent e1 = record(w, "2026-03-01T01:00:00Z");
        AuditEvent e2 = record(w, "2026-03-02T01:00:00Z");
        AuditEvent e3 = record(w, "2026-03-03T01:00:00Z");
        List<Path> files = segments();
        Files.writeString(files.get(0), AuditRecordFormat.serialize(e1) + "\n"
                + AuditRecordFormat.serialize(e1).replace("ALLOW", "DENY") + "\n"
                + AuditRecordFormat.serialize(e1).substring(0, 60) + "\r\n", StandardCharsets.UTF_8);
        Files.writeString(files.get(2), AuditRecordFormat.serialize(e3).substring(0, 70), StandardCharsets.UTF_8);

        List<Long> plainStarts = new ArrayList<>();
        AuditChainVerifier.VerificationReport expected = AuditChainVerifier.verifySegments(files, plainStarts,
                List.of(), false);
        List<Long> hashedStarts = new ArrayList<>();
        AuditChainVerifier.HashedVerification hv = AuditChainVerifier.verifySegmentsHashed(files, hashedStarts,
                List.of(), false, Map.of());

        assertThat(hv.report().anomalies()).isEqualTo(expected.anomalies());
        assertThat(hv.report().tail()).isEqualTo(expected.tail());
        assertThat(hv.report().hasBreak()).isEqualTo(expected.hasBreak());
        assertThat(hv.report().hasStructuralAnomaly()).isEqualTo(expected.hasStructuralAnomaly());
        assertThat(hv.report().writers()).isEqualTo(expected.writers());
        assertThat(hashedStarts).isEqualTo(plainStarts);
        assertThat(e2.sequence()).isEqualTo(2);
    }

    @Test
    void theHeldReportEqualsVerifySegmentsForKeyedInputsAsWell() throws IOException {
        open();
        AuditRecorder w = writer("w", AuditChainMode.keyed(K1));
        record(w, "2026-03-01T01:00:00Z");
        record(w, "2026-03-02T01:00:00Z");
        List<Path> files = segments();

        AuditChainVerifier.VerificationReport expected = AuditChainVerifier.verifySegments(files, new ArrayList<>(),
                List.of(K0, K1), false);
        AuditChainVerifier.HashedVerification hv = AuditChainVerifier.verifySegmentsHashed(files, new ArrayList<>(),
                List.of(K0, K1), false, Map.of());

        assertThat(hv.report().writers()).isEqualTo(expected.writers());
        assertThat(hv.report().anomalies()).isEqualTo(expected.anomalies());
        assertThat(hv.report().tail()).isEqualTo(expected.tail());
    }

    @Test
    void aProbeReturnsTheHashedRecordAtThatSequence() throws IOException {
        open();
        AuditRecorder w = writer("w", AuditChainMode.unkeyed());
        record(w, "2026-03-01T01:00:00Z");
        AuditEvent e2 = record(w, "2026-03-01T02:00:00Z");

        AuditChainVerifier.HashedVerification hv = AuditChainVerifier.verifySegmentsHashed(segments(),
                new ArrayList<>(), List.of(), false, Map.of(w.instanceId(), Set.of(2L, 9L)));

        assertThat(hv.probed().get(w.instanceId())).containsOnlyKeys(2L);
        assertThat(hv.probed().get(w.instanceId()).get(2L).eventHash()).isEqualTo(e2.eventHash());
    }

    @Test
    void boundaryRecordsReturnTerminatedParsingLinesOfTheWantedInstancesAndFlagCarriageReturns() throws IOException {
        open();
        AuditRecorder a = writer("a", AuditChainMode.unkeyed());
        AuditRecorder b = writer("b", AuditChainMode.unkeyed());
        AuditEvent a1 = record(a, "2026-03-01T01:00:00Z");
        AuditEvent b1 = record(b, "2026-03-01T02:00:00Z");
        AuditEvent a2 = record(a, "2026-03-01T03:00:00Z");
        AuditEvent a3 = record(a, "2026-03-01T04:00:00Z");
        Path file = segments().get(0);
        Files.writeString(file, AuditRecordFormat.serialize(a1) + "\n" + AuditRecordFormat.serialize(b1) + "\n"
                + "not a record\n" + AuditRecordFormat.serialize(a2) + "\r\n" + AuditRecordFormat.serialize(a3),
                StandardCharsets.UTF_8);

        List<AuditChainVerifier.BoundaryRecord> found = AuditChainVerifier.boundaryRecords(file,
                Files::readAllBytes, Set.of(a.instanceId()));

        assertThat(found).extracting(AuditChainVerifier.BoundaryRecord::sequence).containsExactly(1L, 2L);
        assertThat(found).extracting(AuditChainVerifier.BoundaryRecord::endsInCarriageReturn)
                .containsExactly(false, true);
        assertThat(found.get(1).eventHash()).isEqualTo(a2.eventHash());
    }
}

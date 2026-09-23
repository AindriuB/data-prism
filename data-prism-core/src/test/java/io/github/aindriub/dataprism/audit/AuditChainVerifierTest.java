package io.github.aindriub.dataprism.audit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Wave 1 found four ordinary, non-malicious on-disk shapes that resemble
 * tampering (torn trailing write, a restart's fragment concatenation, a
 * duplicate sequence from a non-conforming sink, a new writer's genesis
 * partway through the file). Every one of them is exercised here by
 * constructing the real on-disk shape through {@link FileAuditSink} and
 * {@link AuditRecorder}, never by a hand-asserted string, because a verifier
 * that reports any of them as tampering is worse than no verifier at all.
 */
class AuditChainVerifierTest {

    private static final Clock FIXED = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);

    @TempDir
    Path tempDir;

    private static AuditEvent write(AuditRecorder recorder) {
        return recorder.record("investigator-1", "client-1", "get_entity_context", "CUSTOMER", "pseudo-1",
                "fp-1", "DEFAULT", "scope-1", "investigation", "CASE-1", "ALLOW",
                Set.of("customer-api:ANSWERED"), Set.of(), "corr-1");
    }

    private static AuditEvent baseEvent(String eventId, String instanceId, long sequence, String previousHash,
                                          String eventHash) {
        return new AuditEvent(eventId, FIXED.instant(), "investigator-1", "client-1", "get_entity_context",
                "CUSTOMER", "pseudo-1", "fp-1", "DEFAULT", "scope-1", "investigation", "CASE-1", "ALLOW",
                Set.of("customer-api:ANSWERED"), Set.of(), "corr-1", instanceId, sequence, previousHash,
                eventHash);
    }

    /** Builds an event and fills in its eventHash by calling {@link AuditEventHash} itself, never re-deriving it. */
    private static AuditEvent eventWithComputedHash(String eventId, String instanceId, long sequence,
                                                       String previousHash) {
        AuditEvent draft = baseEvent(eventId, instanceId, sequence, previousHash, "placeholder");
        return baseEvent(eventId, instanceId, sequence, previousHash, AuditEventHash.compute(draft));
    }

    // -- intact chains -----------------------------------------------------

    @Test
    void intactSingleWriterChainReportsSequenceCountHeadHashAndNoBreak() throws IOException {
        Path path = tempDir.resolve("audit.log");
        AuditEvent last;
        try (FileAuditSink sink = new FileAuditSink(path)) {
            AuditRecorder recorder = new AuditRecorder(sink, FIXED, "instance-1");
            write(recorder);
            write(recorder);
            last = write(recorder);
        }

        AuditChainVerifier.VerificationReport report = AuditChainVerifier.verify(path);

        assertThat(report.writers()).hasSize(1);
        AuditChainVerifier.WriterResult writer = report.writers().get(0);
        assertThat(writer.instanceId()).isEqualTo("instance-1");
        assertThat(writer.sequenceCount()).isEqualTo(3);
        assertThat(writer.headHash()).isEqualTo(last.eventHash());
        assertThat(writer.broken()).isFalse();
        assertThat(report.hasBreak()).isFalse();
        assertThat(report.hasStructuralAnomaly()).isFalse();
        assertThat(report.tail()).isEmpty();
    }

    @Test
    void verifyAcceptsAStreamAsWellAsAPath() throws IOException {
        Path path = tempDir.resolve("audit.log");
        try (FileAuditSink sink = new FileAuditSink(path)) {
            AuditRecorder recorder = new AuditRecorder(sink, FIXED, "instance-1");
            write(recorder);
        }

        try (InputStream in = Files.newInputStream(path)) {
            AuditChainVerifier.VerificationReport report = AuditChainVerifier.verify(in);
            assertThat(report.writers()).hasSize(1);
            assertThat(report.writers().get(0).broken()).isFalse();
        }
    }

    @Test
    void interleavedWritersVerifyIndependently() throws IOException {
        Path path = tempDir.resolve("audit.log");
        try (FileAuditSink sink = new FileAuditSink(path)) {
            AuditRecorder a = new AuditRecorder(sink, FIXED, "instance-a");
            AuditRecorder b = new AuditRecorder(sink, FIXED, "instance-b");
            write(a);
            write(b);
            write(a);
            write(b);
            write(a);
        }

        AuditChainVerifier.VerificationReport report = AuditChainVerifier.verify(path);

        assertThat(report.writers()).extracting(AuditChainVerifier.WriterResult::instanceId)
                .containsExactlyInAnyOrder("instance-a", "instance-b");
        assertThat(report.writers()).allSatisfy(w -> assertThat(w.broken()).isFalse());
        assertThat(report.hasBreak()).isFalse();
    }

    @Test
    void newWriterStartingAtGenesisPartwayThroughFileVerifiesAsNormal() throws IOException {
        Path path = tempDir.resolve("audit.log");
        try (FileAuditSink sink = new FileAuditSink(path)) {
            AuditRecorder writerA = new AuditRecorder(sink, FIXED, "instance-a");
            write(writerA);
            write(writerA);

            AuditRecorder writerB = new AuditRecorder(sink, FIXED, "instance-b");
            write(writerB);
        }

        AuditChainVerifier.VerificationReport report = AuditChainVerifier.verify(path);

        assertThat(report.hasBreak()).isFalse();
        assertThat(report.writers()).extracting(AuditChainVerifier.WriterResult::instanceId)
                .containsExactlyInAnyOrder("instance-a", "instance-b");
        assertThat(report.writers()).allSatisfy(w -> assertThat(w.broken()).isFalse());
    }

    // -- genuine breaks ------------------------------------------------------

    @Test
    void mutatedRecordBodyInMiddleIsReportedAsBreakAndLaterRecordsAreAfterBreak() throws IOException {
        Path path = tempDir.resolve("audit.log");
        try (FileAuditSink sink = new FileAuditSink(path)) {
            AuditRecorder recorder = new AuditRecorder(sink, FIXED, "instance-1");
            write(recorder);
            write(recorder);
            write(recorder);
        }

        List<String> lines = new ArrayList<>(Files.readAllLines(path, StandardCharsets.UTF_8));
        AuditEvent middle = AuditRecordFormat.parse(lines.get(1));
        AuditEvent tampered = new AuditEvent(middle.eventId(), middle.timestamp(), "someone-else",
                middle.clientId(), middle.tool(), middle.entityType(), middle.subjectPseudonym(),
                middle.parameterFingerprint(), middle.privacyProfile(), middle.scopeId(), middle.purpose(),
                middle.caseId(), middle.policyDecision(), middle.sourceSystems(), middle.rejectedArguments(),
                middle.correlationId(), middle.instanceId(), middle.sequence(), middle.previousHash(),
                middle.eventHash());
        lines.set(1, AuditRecordFormat.serialize(tampered));
        Files.writeString(path, String.join("\n", lines) + "\n", StandardCharsets.UTF_8);

        AuditChainVerifier.VerificationReport report = AuditChainVerifier.verify(path);

        AuditChainVerifier.WriterResult writer = report.writers().get(0);
        assertThat(writer.broken()).isTrue();
        assertThat(writer.firstBreak()).isPresent();
        assertThat(writer.firstBreak().get().sequence()).isEqualTo(middle.sequence());
        assertThat(writer.afterBreakCount()).isEqualTo(1);
        assertThat(report.hasBreak()).isTrue();
    }

    @Test
    void deletedRecordInMiddleIsReportedAsBreakNamingThePreviousHashMismatch() throws IOException {
        Path path = tempDir.resolve("audit.log");
        AuditEvent third;
        try (FileAuditSink sink = new FileAuditSink(path)) {
            AuditRecorder recorder = new AuditRecorder(sink, FIXED, "instance-1");
            write(recorder);
            write(recorder);
            third = write(recorder);
        }

        List<String> lines = new ArrayList<>(Files.readAllLines(path, StandardCharsets.UTF_8));
        lines.remove(1);
        Files.writeString(path, String.join("\n", lines) + "\n", StandardCharsets.UTF_8);

        AuditChainVerifier.VerificationReport report = AuditChainVerifier.verify(path);

        AuditChainVerifier.WriterResult writer = report.writers().get(0);
        assertThat(writer.broken()).isTrue();
        assertThat(writer.firstBreak().get().sequence()).isEqualTo(third.sequence());
        assertThat(writer.firstBreak().get().reason()).contains("previousHash");
    }

    @Test
    void deletedHeadRecordsAreReportedAsABreakNotIntact() throws IOException {
        Path path = tempDir.resolve("audit.log");
        AuditEvent third;
        try (FileAuditSink sink = new FileAuditSink(path)) {
            AuditRecorder recorder = new AuditRecorder(sink, FIXED, "instance-1");
            write(recorder);
            write(recorder);
            third = write(recorder);
        }

        // Delete the writer's first two records: the survivor's previousHash no longer
        // chains from GENESIS, so this must not read as an intact two-record-shorter chain.
        List<String> lines = new ArrayList<>(Files.readAllLines(path, StandardCharsets.UTF_8));
        lines.remove(0);
        lines.remove(0);
        Files.writeString(path, String.join("\n", lines) + "\n", StandardCharsets.UTF_8);

        AuditChainVerifier.VerificationReport report = AuditChainVerifier.verify(path);

        AuditChainVerifier.WriterResult writer = report.writers().get(0);
        assertThat(writer.broken()).isTrue();
        assertThat(writer.firstBreak()).isPresent();
        assertThat(writer.firstBreak().get().sequence()).isEqualTo(third.sequence());
        assertThat(writer.firstBreak().get().reason()).contains("GENESIS");
        assertThat(writer.firstSequence()).isEqualTo(third.sequence());
        assertThat(report.hasBreak()).isTrue();
    }

    @Test
    void intactChainReportsTheFirstSequenceSeenForEachWriter() throws IOException {
        Path path = tempDir.resolve("audit.log");
        try (FileAuditSink sink = new FileAuditSink(path)) {
            AuditRecorder recorder = new AuditRecorder(sink, FIXED, "instance-1");
            write(recorder);
            write(recorder);
        }

        AuditChainVerifier.VerificationReport report = AuditChainVerifier.verify(path);

        assertThat(report.writers().get(0).firstSequence()).isEqualTo(1L);
    }

    @Test
    void eventHashMismatchIsBreakEvenWhenPreviousHashLinksCorrectly() throws IOException {
        Path path = tempDir.resolve("audit.log");
        AuditEvent second;
        try (FileAuditSink sink = new FileAuditSink(path)) {
            AuditRecorder recorder = new AuditRecorder(sink, FIXED, "instance-1");
            write(recorder);
            second = write(recorder);
        }

        List<String> lines = new ArrayList<>(Files.readAllLines(path, StandardCharsets.UTF_8));
        AuditEvent parsedSecond = AuditRecordFormat.parse(lines.get(1));
        assertThat(parsedSecond.eventId()).isEqualTo(second.eventId());
        String bogusHash = "f".repeat(64);
        AuditEvent tampered = new AuditEvent(parsedSecond.eventId(), parsedSecond.timestamp(),
                parsedSecond.principalId(), parsedSecond.clientId(), parsedSecond.tool(),
                parsedSecond.entityType(), parsedSecond.subjectPseudonym(), parsedSecond.parameterFingerprint(),
                parsedSecond.privacyProfile(), parsedSecond.scopeId(), parsedSecond.purpose(),
                parsedSecond.caseId(), parsedSecond.policyDecision(), parsedSecond.sourceSystems(),
                parsedSecond.rejectedArguments(), parsedSecond.correlationId(), parsedSecond.instanceId(),
                parsedSecond.sequence(), parsedSecond.previousHash(), bogusHash);
        lines.set(1, AuditRecordFormat.serialize(tampered));
        Files.writeString(path, String.join("\n", lines) + "\n", StandardCharsets.UTF_8);

        AuditChainVerifier.VerificationReport report = AuditChainVerifier.verify(path);

        AuditChainVerifier.WriterResult writer = report.writers().get(0);
        assertThat(writer.broken()).isTrue();
        assertThat(writer.firstBreak().get().sequence()).isEqualTo(second.sequence());
        assertThat(writer.firstBreak().get().reason()).contains("eventHash");
    }

    // -- the four ordinary, non-malicious shapes ------------------------------

    @Test
    void tornTrailingRecordWithNoNewlineIsPossiblyInFlightNotABreak() throws IOException {
        Path path = tempDir.resolve("audit.log");
        try (FileAuditSink sink = new FileAuditSink(path)) {
            AuditRecorder recorder = new AuditRecorder(sink, FIXED, "instance-1");
            write(recorder);
            write(recorder);
        }
        long completeLength = Files.size(path);

        try (FileChannel real = FileChannel.open(path, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
                ShortWriteChannel faulty = new ShortWriteChannel(real, 10)) {
            FileAuditSink torn = new FileAuditSink(path, faulty);
            AuditEvent unfinished = eventWithComputedHash("event-torn", "instance-1", 3, "irrelevant-prior");
            assertThatThrownBy(() -> torn.record(unfinished)).isInstanceOf(UncheckedIOException.class);
        }

        byte[] content = Files.readAllBytes(path);
        assertThat(content.length).isEqualTo(completeLength + 10);
        assertThat(new String(content, StandardCharsets.UTF_8)).doesNotEndWith("\n");

        AuditChainVerifier.VerificationReport report = AuditChainVerifier.verify(path);

        assertThat(report.tail()).isPresent();
        assertThat(report.tail().get().byteOffset()).isEqualTo(completeLength);
        assertThat(report.tail().get().message()).contains("no terminating newline");
        assertThat(report.hasBreak()).isFalse();
        assertThat(report.hasStructuralAnomaly()).isFalse();

        AuditChainVerifier.WriterResult writer = report.writers().get(0);
        assertThat(writer.sequenceCount()).isEqualTo(2);
        assertThat(writer.broken()).isFalse();
    }

    @Test
    void midFileFieldCountErrorFromARestartFragmentIsReportedGracefullyAndSurroundingRecordsStillVerify()
            throws IOException {
        Path path = tempDir.resolve("audit.log");
        AuditEvent before;
        try (FileAuditSink sink = new FileAuditSink(path)) {
            AuditRecorder recorder = new AuditRecorder(sink, FIXED, "instance-1");
            before = write(recorder);
        }

        long fragmentOffset = Files.size(path);
        // The fragment must cross at least one field separator, or the merged line below would
        // still parse to exactly 20 fields (the fragment's own tail simply extending the first
        // field) and this would not be the field-count-mismatch shape at all.
        try (FileChannel real = FileChannel.open(path, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
                ShortWriteChannel faulty = new ShortWriteChannel(real, 60)) {
            FileAuditSink torn = new FileAuditSink(path, faulty);
            AuditEvent unfinished = eventWithComputedHash("event-torn", "instance-1", 2, before.eventHash());
            assertThatThrownBy(() -> torn.record(unfinished)).isInstanceOf(UncheckedIOException.class);
        }

        // The operator restarts: a fresh FileAuditSink opens APPEND on the same path and its
        // first record lands directly after the surviving fragment, with no newline between them.
        AuditEvent afterSecond;
        try (FileAuditSink restarted = new FileAuditSink(path)) {
            AuditRecorder recorder = new AuditRecorder(restarted, FIXED, "instance-2");
            write(recorder);
            afterSecond = write(recorder);
        }

        AuditChainVerifier.VerificationReport report = AuditChainVerifier.verify(path);

        assertThat(report.anomalies()).hasSize(1);
        AuditChainVerifier.StructuralAnomaly anomaly = report.anomalies().get(0);
        assertThat(anomaly.type()).isEqualTo(AuditChainVerifier.AnomalyType.INTERRUPTED_WRITE_FRAGMENT);
        assertThat(anomaly.primaryOffset()).isEqualTo(fragmentOffset);
        assertThat(anomaly.message()).contains("not tampering").contains("field count");

        assertThat(report.hasBreak()).isFalse();
        assertThat(report.hasStructuralAnomaly()).isTrue();

        AuditChainVerifier.WriterResult writer1 = writerFor(report, "instance-1");
        assertThat(writer1.broken()).isFalse();
        assertThat(writer1.sequenceCount()).isEqualTo(1);
        assertThat(writer1.headHash()).isEqualTo(before.eventHash());

        // instance-2's own first record() call produced the unparseable merged line above (its
        // bytes are the ones landing directly after the fragment); only its second call produced
        // a clean, attributable line.
        AuditChainVerifier.WriterResult writer2 = writerFor(report, "instance-2");
        assertThat(writer2.broken()).isFalse();
        assertThat(writer2.sequenceCount()).isEqualTo(1);
        assertThat(writer2.headHash()).isEqualTo(afterSecond.eventHash());
    }

    @Test
    void duplicateSequenceNumberWithinOneWriterIsReportedAsSinkContractViolationNotABreak() throws IOException {
        Path path = tempDir.resolve("audit.log");
        AuditEvent first;
        try (FileAuditSink sink = new FileAuditSink(path)) {
            AuditRecorder recorder = new AuditRecorder(sink, FIXED, "instance-1");
            first = write(recorder);
        }

        // A non-conforming sink wrote this record durably and then still threw: a second
        // durable copy at the same sequence, whose previousHash points at the wrong record.
        AuditEvent duplicate = eventWithComputedHash("event-dup", "instance-1", first.sequence(), "f".repeat(64));
        try (FileAuditSink sink = new FileAuditSink(path)) {
            sink.record(duplicate);
        }

        // The chain continues legitimately after the duplicate, chaining from the real first record.
        AuditEvent third = eventWithComputedHash("event-3", "instance-1", first.sequence() + 1, first.eventHash());
        try (FileAuditSink sink = new FileAuditSink(path)) {
            sink.record(third);
        }

        AuditChainVerifier.VerificationReport report = AuditChainVerifier.verify(path);

        assertThat(report.anomalies()).hasSize(1);
        AuditChainVerifier.StructuralAnomaly anomaly = report.anomalies().get(0);
        assertThat(anomaly.type()).isEqualTo(AuditChainVerifier.AnomalyType.DUPLICATE_SEQUENCE);
        assertThat(anomaly.message()).contains("instance-1").contains(String.valueOf(first.sequence()));

        assertThat(report.hasBreak()).isFalse();
        assertThat(report.hasStructuralAnomaly()).isTrue();

        AuditChainVerifier.WriterResult writer = report.writers().get(0);
        assertThat(writer.broken()).isFalse();
        assertThat(writer.sequenceCount()).isEqualTo(3);
        assertThat(writer.headHash()).isEqualTo(third.eventHash());
    }

    // -- parse failures that must escalate to break severity, not the benign bucket ------------

    @Test
    void corruptedTimestampFieldEscalatesToUnparseableRecordNotTheBenignBucket() throws IOException {
        Path path = tempDir.resolve("audit.log");
        try (FileAuditSink sink = new FileAuditSink(path)) {
            AuditRecorder recorder = new AuditRecorder(sink, FIXED, "instance-1");
            write(recorder);
        }

        List<String> lines = new ArrayList<>(Files.readAllLines(path, StandardCharsets.UTF_8));
        String corrupted = lines.get(0).replaceFirst("2026-01-01T00:00:00Z", "not-a-timestamp");
        assertThat(corrupted).isNotEqualTo(lines.get(0));
        lines.set(0, corrupted);
        Files.writeString(path, String.join("\n", lines) + "\n", StandardCharsets.UTF_8);

        AuditChainVerifier.VerificationReport report = AuditChainVerifier.verify(path);

        assertThat(report.anomalies()).hasSize(1);
        assertThat(report.anomalies().get(0).type()).isEqualTo(AuditChainVerifier.AnomalyType.UNPARSEABLE_RECORD);
        assertThat(report.hasBreak()).isTrue();
        assertThat(report.hasStructuralAnomaly()).isFalse();
    }

    @Test
    void corruptedSequenceFieldEscalatesToUnparseableRecordNotTheBenignBucket() throws IOException {
        Path path = tempDir.resolve("audit.log");
        try (FileAuditSink sink = new FileAuditSink(path)) {
            AuditRecorder recorder = new AuditRecorder(sink, FIXED, "instance-1");
            write(recorder);
        }

        // The serialized sequence field for the first (and only) record is "1"; corrupting it to
        // non-numeric content trips NumberFormatException, not the field-count IllegalArgumentException.
        List<String> lines = new ArrayList<>(Files.readAllLines(path, StandardCharsets.UTF_8));
        String original = lines.get(0);
        String fieldSep = "";
        String[] fields = original.split(fieldSep);
        fields[17] = "not-a-number";
        lines.set(0, String.join(fieldSep, fields));
        Files.writeString(path, String.join("\n", lines) + "\n", StandardCharsets.UTF_8);

        AuditChainVerifier.VerificationReport report = AuditChainVerifier.verify(path);

        assertThat(report.anomalies()).hasSize(1);
        assertThat(report.anomalies().get(0).type()).isEqualTo(AuditChainVerifier.AnomalyType.UNPARSEABLE_RECORD);
        assertThat(report.hasBreak()).isTrue();
        assertThat(report.hasStructuralAnomaly()).isFalse();
    }

    /**
     * Pins the assumption {@link AuditChainVerifier#classifyParseFailure} relies on: a field-count
     * mismatch throws exactly {@code IllegalArgumentException}, never a subtype. If {@code
     * AuditRecordFormat} ever changes to throw something else for this condition, this test fails
     * here rather than the change silently reclassifying interrupted writes as possible tampering.
     */
    @Test
    void fieldCountMismatchThrowsExactlyIllegalArgumentException() {
        assertThatThrownBy(() -> AuditRecordFormat.parse("toofewfields"))
                .isExactlyInstanceOf(IllegalArgumentException.class);
    }

    private static AuditChainVerifier.WriterResult writerFor(AuditChainVerifier.VerificationReport report,
                                                                String instanceId) {
        return report.writers().stream()
                .filter(w -> w.instanceId().equals(instanceId))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no writer " + instanceId + " in " + report.writers()));
    }

    /**
     * Delegates to a real channel, but on its one {@code write} call writes only
     * {@code bytesBeforeFailure} bytes and then throws — the same short-write-that-throws shape
     * {@link FileAuditSinkTest} uses to prove {@code FileAuditSink} poisons itself, reused here to
     * produce a genuine torn fragment on disk rather than asserting one by construction.
     */
    private static final class ShortWriteChannel extends FileChannel {

        private final FileChannel delegate;
        private final int bytesBeforeFailure;

        ShortWriteChannel(FileChannel delegate, int bytesBeforeFailure) {
            this.delegate = delegate;
            this.bytesBeforeFailure = bytesBeforeFailure;
        }

        @Override
        public int write(ByteBuffer src) throws IOException {
            ByteBuffer limited = src.duplicate();
            limited.limit(Math.min(src.limit(), src.position() + bytesBeforeFailure));
            int written = delegate.write(limited);
            src.position(src.position() + written);
            throw new IOException("simulated short write failure");
        }

        @Override
        public void force(boolean metaData) throws IOException {
            delegate.force(metaData);
        }

        @Override
        protected void implCloseChannel() throws IOException {
            delegate.close();
        }

        @Override
        public int read(ByteBuffer dst) {
            throw new UnsupportedOperationException();
        }

        @Override
        public long read(ByteBuffer[] dsts, int offset, int length) {
            throw new UnsupportedOperationException();
        }

        @Override
        public long write(ByteBuffer[] srcs, int offset, int length) {
            throw new UnsupportedOperationException();
        }

        @Override
        public long position() {
            throw new UnsupportedOperationException();
        }

        @Override
        public FileChannel position(long newPosition) {
            throw new UnsupportedOperationException();
        }

        @Override
        public long size() {
            throw new UnsupportedOperationException();
        }

        @Override
        public FileChannel truncate(long size) {
            throw new UnsupportedOperationException();
        }

        @Override
        public long transferTo(long position, long count, WritableByteChannel target) {
            throw new UnsupportedOperationException();
        }

        @Override
        public long transferFrom(ReadableByteChannel src, long position, long count) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int read(ByteBuffer dst, long position) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int write(ByteBuffer src, long position) {
            throw new UnsupportedOperationException();
        }

        @Override
        public MappedByteBuffer map(MapMode mode, long position, long size) {
            throw new UnsupportedOperationException();
        }

        @Override
        public FileLock lock(long position, long size, boolean shared) {
            throw new UnsupportedOperationException();
        }

        @Override
        public FileLock tryLock(long position, long size, boolean shared) {
            throw new UnsupportedOperationException();
        }
    }
}

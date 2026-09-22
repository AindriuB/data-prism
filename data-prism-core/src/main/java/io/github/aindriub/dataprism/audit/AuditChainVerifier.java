package io.github.aindriub.dataprism.audit;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Replays every writer's hash chain in a file written by {@link FileAuditSink}
 * and reports either an intact chain or the first edit or deletion, per
 * writer (keyed on {@code instanceId} — see {@code AuditEvent}'s class
 * javadoc for why the chain is never global).
 *
 * <p>This class detects an edit or a deletion of a record that was already
 * written. It cannot detect truncation of a writer's most recent records:
 * deleting the tail of an append-only file leaves a chain that verifies
 * perfectly end to end. Detecting that needs an external checkpoint held
 * outside operator control, which this release does not build — see
 * {@link AuditChainVerifierCli}, which prints that limitation on every run.
 *
 * <p>Four on-disk shapes resemble tampering but are not, and this class
 * reports each distinctly from a genuine break rather than folding it into
 * one:
 *
 * <ul>
 *   <li>a torn trailing record with no newline — an in-progress write, per
 *       {@link AuditRecordFormat}'s class javadoc — reported as the
 *       possibly-in-flight {@link VerificationReport#tail()};
 *   <li>a mid-file field-count error — the shape an operator restart after a
 *       torn write produces when the new {@link FileAuditSink} opens append
 *       on the same path, landing its first record directly after the
 *       surviving fragment — reported as an {@link
 *       AnomalyType#INTERRUPTED_WRITE_FRAGMENT} anomaly, not a break;
 *   <li>a duplicate sequence number within one writer — the shape a sink
 *       produces if it violates {@link AuditSink}'s all-or-nothing contract
 *       by writing durably and then throwing — reported as an {@link
 *       AnomalyType#DUPLICATE_SEQUENCE} anomaly, not a break;
 *   <li>a new writer's chain starting at {@code GENESIS} partway through the
 *       file — an ordinary process restart — verified as its own independent
 *       chain, never as a break in the transition.
 * </ul>
 */
public final class AuditChainVerifier {

    private AuditChainVerifier() {
    }

    /** Reads and verifies {@code path} in one pass. */
    public static VerificationReport verify(Path path) throws IOException {
        return verify(Files.readAllBytes(path));
    }

    /** Reads and verifies every byte {@code in} produces before EOF. */
    public static VerificationReport verify(InputStream in) throws IOException {
        return verify(in.readAllBytes());
    }

    private static VerificationReport verify(byte[] content) {
        Map<String, WriterState> writers = new LinkedHashMap<>();
        List<StructuralAnomaly> anomalies = new ArrayList<>();
        TailAnomaly tail = null;

        int start = 0;
        for (int i = 0; i < content.length; i++) {
            if (content[i] == '\n') {
                String line = new String(content, start, i - start, StandardCharsets.UTF_8);
                processLine(line, start, writers, anomalies);
                start = i + 1;
            }
        }
        if (start < content.length) {
            tail = new TailAnomaly(
                    "the final record at byte offset " + start + " has no terminating newline. Per "
                            + "AuditRecordFormat's contract a record is complete only once its line is "
                            + "newline-terminated, so this trailing chunk is an in-progress write, not a "
                            + "finished record; it is not parsed and not compared against any expected hash. "
                            + "This is reported as \"possibly in flight\", never as a chain break.",
                    start);
        }

        List<WriterResult> results = new ArrayList<>();
        for (WriterState state : writers.values()) {
            results.add(state.toResult());
        }
        return new VerificationReport(results, anomalies, Optional.ofNullable(tail));
    }

    private static void processLine(String line, long offset, Map<String, WriterState> writers,
                                      List<StructuralAnomaly> anomalies) {
        if (line.isEmpty()) {
            return;
        }
        AuditEvent event;
        try {
            event = AuditRecordFormat.parse(line);
        } catch (RuntimeException e) {
            anomalies.add(classifyParseFailure(offset, e));
            return;
        }

        WriterState writer = writers.computeIfAbsent(event.instanceId(), WriterState::new);

        Long firstOffset = writer.seenSequences.get(event.sequence());
        if (firstOffset != null) {
            anomalies.add(duplicateSequence(event, firstOffset, offset));
            writer.recordCount++;
            return;
        }
        writer.seenSequences.put(event.sequence(), offset);
        writer.recordCount++;

        if (writer.broken) {
            writer.afterBreakCount++;
            writer.headHash = event.eventHash();
            return;
        }

        if (writer.lastHash != null && !event.previousHash().equals(writer.lastHash)) {
            writer.broken = true;
            writer.firstBreak = new Break(event.sequence(), event.instanceId(), offset,
                    "its previousHash does not match the eventHash of the record that should precede it in this "
                            + "writer's chain -- the record it should chain from is missing or was altered");
            writer.headHash = event.eventHash();
            return;
        }

        String recomputed = AuditEventHash.compute(event);
        if (!recomputed.equals(event.eventHash())) {
            writer.broken = true;
            writer.firstBreak = new Break(event.sequence(), event.instanceId(), offset,
                    "its eventHash does not match AuditEventHash recomputed from its own stored fields -- its "
                            + "content was altered after it was written");
            writer.headHash = event.eventHash();
            return;
        }

        writer.lastHash = event.eventHash();
        writer.headHash = event.eventHash();
    }

    /**
     * A field-count mismatch — {@code AuditRecordFormat.parse}'s own thrown
     * {@code IllegalArgumentException}, and only that exact class, never a
     * subtype such as {@code NumberFormatException} — is the known shape of a
     * torn write's fragment immediately followed by a restarted writer's first
     * record on the same append-only file. Anything else that fails to parse
     * corrupts a field's own content in a way that shape does not explain, so
     * it is reported as a possible tamper rather than folded into the benign
     * bucket.
     */
    private static StructuralAnomaly classifyParseFailure(long offset, RuntimeException cause) {
        if (cause.getClass() == IllegalArgumentException.class) {
            return new StructuralAnomaly(AnomalyType.INTERRUPTED_WRITE_FRAGMENT,
                    "the line at byte offset " + offset + " does not parse as one record (field count "
                            + "mismatch). This is the on-disk shape of an interrupted write: a torn fragment "
                            + "followed directly, with no newline between them, by the first record a "
                            + "restarted writer appended to the same file. Durable append-only storage "
                            + "(O_APPEND, WORM, or object-lock) is an operator responsibility this release "
                            + "does not enforce -- this is not tampering with an existing record. The records "
                            + "immediately before and after this line were verified independently.",
                    offset, -1);
        }
        return new StructuralAnomaly(AnomalyType.UNPARSEABLE_RECORD,
                "the line at byte offset " + offset + " could not be parsed (" + cause.getClass().getSimpleName()
                        + "). Unlike a field-count mismatch this is not the known interrupted-write-plus-restart "
                        + "shape, so it cannot be ruled out as tampering with a record's own field content -- "
                        + "investigate this line directly.",
                offset, -1);
    }

    private static StructuralAnomaly duplicateSequence(AuditEvent event, long firstOffset, long secondOffset) {
        return new StructuralAnomaly(AnomalyType.DUPLICATE_SEQUENCE,
                "writer " + event.instanceId() + " has two durable records at sequence " + event.sequence()
                        + " (byte offsets " + firstOffset + " and " + secondOffset + "). AuditSink requires an "
                        + "all-or-nothing write per record; this shape is what a sink produces if it writes a "
                        + "record durably and then still throws -- not tampering with an existing record. "
                        + "Investigate the sink implementation that produced this file, not this file's later "
                        + "custody.",
                firstOffset, secondOffset);
    }

    private static final class WriterState {
        private final String instanceId;
        private final Map<Long, Long> seenSequences = new LinkedHashMap<>();
        private long recordCount;
        private long afterBreakCount;
        private String lastHash;
        private String headHash;
        private boolean broken;
        private Break firstBreak;

        WriterState(String instanceId) {
            this.instanceId = instanceId;
        }

        WriterResult toResult() {
            return new WriterResult(instanceId, recordCount, headHash, broken, Optional.ofNullable(firstBreak),
                    afterBreakCount);
        }
    }

    /** What kind of non-tampering structural anomaly a line represents. */
    public enum AnomalyType {
        /** A field-count mismatch: a torn write's fragment concatenated with a restarted writer's first record. */
        INTERRUPTED_WRITE_FRAGMENT,
        /** Two durable records sharing a sequence number for one writer: a sink-contract violation. */
        DUPLICATE_SEQUENCE,
        /** A parse failure that is not the known interrupted-write shape and cannot be ruled out as tampering. */
        UNPARSEABLE_RECORD
    }

    /** The first edit or deletion found in one writer's chain. */
    public record Break(long sequence, String instanceId, long byteOffset, String reason) {
    }

    /** A non-tampering (or, for {@link AnomalyType#UNPARSEABLE_RECORD}, possibly-tampering) structural finding. */
    public record StructuralAnomaly(AnomalyType type, String message, long primaryOffset, long secondaryOffset) {
    }

    /** The unterminated trailing chunk found at the end of the file, if any. */
    public record TailAnomaly(String message, long byteOffset) {
    }

    /** One writer's replayed chain. */
    public record WriterResult(String instanceId, long sequenceCount, String headHash, boolean broken,
                                Optional<Break> firstBreak, long afterBreakCount) {
    }

    /** The full result of one verification pass over a file. */
    public record VerificationReport(List<WriterResult> writers, List<StructuralAnomaly> anomalies,
                                      Optional<TailAnomaly> tail) {

        /** True if any writer's chain has a break, or any anomaly cannot be ruled out as tampering. */
        public boolean hasBreak() {
            return writers.stream().anyMatch(WriterResult::broken)
                    || anomalies.stream().anyMatch(a -> a.type() == AnomalyType.UNPARSEABLE_RECORD);
        }

        /** True if any anomaly is a known non-tampering structural shape (never true together with a break). */
        public boolean hasStructuralAnomaly() {
            return anomalies.stream().anyMatch(a -> a.type() != AnomalyType.UNPARSEABLE_RECORD);
        }
    }
}

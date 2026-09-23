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
 * written, but only insofar as the edited or deleted field is one of the
 * nineteen joined into {@link AuditEventHash}'s chained hash — see {@link
 * AuditChainVerifierCli}'s printed limitation for exactly which fields those
 * are. It also cannot detect truncation of a writer's most recent records: deleting
 * the tail of an append-only file leaves a chain that verifies perfectly end
 * to end. Detecting that needs an external checkpoint held outside operator
 * control, which this release does not build — see {@link
 * AuditChainVerifierCli}, which prints both limitations on every run.
 *
 * <p>Deletion of a writer's EARLIEST records is not exempt from detection,
 * ever: the first record seen for each writer must itself chain from {@code
 * GENESIS} (64 zero characters, matching {@code AuditRecorder}'s starting
 * hash), or that writer is reported as broken (a plain {@link Break}, exit
 * code 2) when nothing on the immediately preceding line explains the
 * missing head, or as {@link WriterResult#nonGenesisStart()} — a distinct
 * finding at structural severity, exit code 4, NEVER folded into "intact"
 * and NEVER exit code 0 — when the immediately preceding line was itself
 * reported as a structural anomaly and so offers plausible (never certain)
 * context for the gap. An earlier revision of this class instead SUPPRESSED
 * the finding entirely in that second case, with no output at all; that
 * exemption was file-global and anomaly-type-blind (one writer's
 * duplicate-sequence anomaly could silence a completely different writer's
 * head deletion on the next line) and, worse, let a single throwaway
 * malformed line launder a fully forged writer — an entirely fabricated
 * chain, correctly self-hashed from an arbitrary non-{@code GENESIS} start —
 * into a report of "intact". That suppression has been removed: the
 * preceding anomaly is now named in the message purely as context for a
 * reader, and never silences, downgrades to "intact", or exit-codes this
 * finding as 0. Deletion of this writer's earliest records can never be
 * ruled out from inside the file alone. Each writer's {@link
 * WriterResult#firstSequence()} is always reported so a reader can see
 * where every chain starts.
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
 *       AnomalyType#DUPLICATE_SEQUENCE} anomaly, not a break. The record at
 *       the FIRST byte offset seen for that sequence is always treated as
 *       canonical and continues the chain; the message names both offsets so
 *       a reader can tell whether a forged record was inserted before the
 *       genuine one;
 *   <li>a new writer's chain starting at {@code GENESIS} partway through the
 *       file — an ordinary process restart — verified as its own independent
 *       chain, never as a break in the transition.
 * </ul>
 */
public final class AuditChainVerifier {

    /**
     * The 64-zero hash every writer's chain begins from — matches {@code
     * AuditRecorder}'s private {@code GENESIS} constant exactly; duplicated
     * here rather than exposed from that class because this verifier owns no
     * part of {@code AuditRecorder}.
     */
    private static final String GENESIS = "0".repeat(64);

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
        // The byte offset of the structural anomaly reported for the immediately preceding line,
        // if any -- used ONLY to name that anomaly in a non-GENESIS-start finding's message for a
        // reader's context. It never suppresses, downgrades, or changes the exit code of that
        // finding; see this class's javadoc for why the earlier version that did so was wrong.
        Long precedingAnomalyOffset = null;
        for (int i = 0; i < content.length; i++) {
            if (content[i] == '\n') {
                String line = new String(content, start, i - start, StandardCharsets.UTF_8);
                int anomaliesBefore = anomalies.size();
                processLine(line, start, writers, anomalies, precedingAnomalyOffset);
                precedingAnomalyOffset = anomalies.size() > anomaliesBefore ? (long) start : null;
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
                                      List<StructuralAnomaly> anomalies, Long precedingAnomalyOffset) {
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

        boolean firstRecordSeenForWriter = writer.firstSequence == null;
        if (firstRecordSeenForWriter) {
            writer.firstSequence = event.sequence();
            if (!GENESIS.equals(event.previousHash())) {
                if (precedingAnomalyOffset != null) {
                    // A structural anomaly was reported for the immediately preceding line -- for
                    // example the mid-file field-count shape of an interrupted write followed by a
                    // restart. That is plausible CONTEXT for this writer's missing head, so it is
                    // named in the message below, but it never suppresses this finding, downgrades
                    // it, or turns it into "intact": deletion of this writer's earliest records
                    // can never be ruled out from inside the file. Reported at structural severity
                    // (never exit 0), not as a break, because a genuine restart is one explanation
                    // this shape is consistent with.
                    writer.nonGenesisStart = new NonGenesisStart(event.sequence(), event.instanceId(), offset,
                            nonGenesisStartMessage(event, offset, precedingAnomalyOffset));
                    writer.lastHash = event.eventHash();
                    writer.headHash = event.eventHash();
                    return;
                }
                writer.broken = true;
                writer.firstBreak = new Break(event.sequence(), event.instanceId(), offset,
                        "this is the first record seen in this file for writer " + event.instanceId()
                                + ", but its previousHash is not GENESIS (64 zero characters), and no "
                                + "structural anomaly on the immediately preceding line offers any context for "
                                + "why -- this writer's chain does not begin here, meaning one or more of this "
                                + "writer's earlier records, up to and including its true first record, were "
                                + "deleted before this point");
                writer.headHash = event.eventHash();
                return;
            }
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
     * bucket. In particular a corrupted {@code timestamp} or {@code sequence}
     * fails with {@code DateTimeParseException} or {@code NumberFormatException}
     * (a subtype of, but not exactly, {@code IllegalArgumentException}), so
     * both already escalate to {@link AnomalyType#UNPARSEABLE_RECORD} rather
     * than the benign bucket.
     *
     * <p>This exact-class check couples the benign classification to a class
     * this verifier does not own: if {@code AuditRecordFormat} ever threw a
     * different exception type for the same field-count-mismatch condition,
     * that shape would silently stop matching here and fall through to {@code
     * UNPARSEABLE_RECORD} — the safer failure direction (an ordinary
     * interrupted write would read as possible tampering, not the reverse),
     * but still a silent behaviour change with no failing test to announce
     * it. Counting unescaped 0x1F field separators directly would test the
     * shape itself and could not drift this way, but would mean re-deriving
     * {@code AuditRecordFormat}'s own escaping rules in a class that does not
     * own that format. Kept as an exact-class check for that reason;
     * {@code AuditChainVerifierTest#fieldCountMismatchThrowsExactlyIllegalArgumentException}
     * pins the assumption so a change to the thrown type fails a test here
     * rather than only in production.
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
                        + " (byte offsets " + firstOffset + " and " + secondOffset + "). The record at the "
                        + "FIRST offset (" + firstOffset + ") is treated as canonical and is the one this "
                        + "writer's chain continues from; the record at the second offset (" + secondOffset
                        + ") is reported as the anomaly. AuditSink requires an all-or-nothing write per record; "
                        + "this shape is what a sink produces if it writes a record durably and then still "
                        + "throws -- not tampering with an existing record. Investigate the sink implementation "
                        + "that produced this file, not this file's later custody. If instead a forged record "
                        + "was inserted before the genuine one, it is the record at the first offset that would "
                        + "be wrongly treated as canonical -- both offsets are named above so a reader can "
                        + "investigate either.",
                firstOffset, secondOffset);
    }

    /**
     * Builds the message for a writer whose first-seen record does not chain from {@code
     * GENESIS} -- always reported, never suppressed. When the immediately preceding line was
     * itself reported as a structural anomaly, that anomaly's offset is named here for a
     * reader's context only: it never changes this finding, its severity, or its exit code.
     */
    private static String nonGenesisStartMessage(AuditEvent event, long offset, long precedingAnomalyOffset) {
        return "CHAIN DOES NOT START AT GENESIS: writer " + event.instanceId() + "'s first record at offset "
                + offset + " does not chain from GENESIS (64 zero characters); the structural anomaly reported "
                + "at offset " + precedingAnomalyOffset + " may explain the missing head, but deletion of this "
                + "writer's earliest records cannot be ruled out. This is neither a confirmed chain break nor "
                + "a verified chain -- investigate this writer's true first record directly.";
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
        private Long firstSequence;
        private NonGenesisStart nonGenesisStart;

        WriterState(String instanceId) {
            this.instanceId = instanceId;
        }

        WriterResult toResult() {
            return new WriterResult(instanceId, recordCount, headHash, broken, Optional.ofNullable(firstBreak),
                    afterBreakCount, firstSequence, Optional.ofNullable(nonGenesisStart));
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

    /**
     * A writer whose first-seen record does not chain from {@code GENESIS} -- always reported at
     * structural severity, never as an intact chain and never with exit code 0, regardless of
     * whether a structural anomaly on the immediately preceding line offers a plausible
     * explanation: deletion of this writer's earliest records can never be ruled out from inside
     * the file alone.
     */
    public record NonGenesisStart(long sequence, String instanceId, long byteOffset, String message) {
    }

    /** One writer's replayed chain. {@code firstSequence} is the sequence of the first record this pass saw. */
    public record WriterResult(String instanceId, long sequenceCount, String headHash, boolean broken,
                                Optional<Break> firstBreak, long afterBreakCount, long firstSequence,
                                Optional<NonGenesisStart> nonGenesisStart) {
    }

    /** The full result of one verification pass over a file. */
    public record VerificationReport(List<WriterResult> writers, List<StructuralAnomaly> anomalies,
                                      Optional<TailAnomaly> tail) {

        /** True if any writer's chain has a break, or any anomaly cannot be ruled out as tampering. */
        public boolean hasBreak() {
            return writers.stream().anyMatch(WriterResult::broken)
                    || anomalies.stream().anyMatch(a -> a.type() == AnomalyType.UNPARSEABLE_RECORD);
        }

        /**
         * True if any anomaly is a known non-tampering structural shape, or any writer's chain
         * does not start at GENESIS. Never true together with a break for the same finding: a
         * writer that both fails to start at GENESIS and later suffers a genuine mid-chain break
         * is reported as a break (the more severe finding), via {@link #hasBreak()}.
         */
        public boolean hasStructuralAnomaly() {
            return anomalies.stream().anyMatch(a -> a.type() != AnomalyType.UNPARSEABLE_RECORD)
                    || writers.stream().anyMatch(w -> w.nonGenesisStart().isPresent());
        }
    }
}

package io.github.aindriub.dataprism.audit.verify;

import io.github.aindriub.dataprism.audit.AuditChainKey;
import io.github.aindriub.dataprism.audit.AuditCheckpoint;
import io.github.aindriub.dataprism.audit.AuditEvent;
import io.github.aindriub.dataprism.audit.AuditEventHash;
import io.github.aindriub.dataprism.audit.AuditSink;
import io.github.aindriub.dataprism.audit.checkpoint.FileAuditCheckpointSink;
import io.github.aindriub.dataprism.audit.format.AuditRecordFormat;
import io.github.aindriub.dataprism.audit.format.FieldCountMismatchException;
import io.github.aindriub.dataprism.audit.sink.FileAuditSink;
import io.github.aindriub.dataprism.audit.sink.SegmentedFileAuditSink;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.time.Period;
import java.util.Optional;

/**
 * Replays every writer's hash chain in a file written by {@link FileAuditSink}
 * and reports either an intact chain or the first edit or deletion, per
 * writer (keyed on {@code instanceId} — see {@code AuditEvent}'s class
 * javadoc for why the chain is never global).
 *
 * <p>This class detects an edit or a deletion of a record that was already
 * written, but only insofar as the edited or deleted field is one of the
 * nineteen original fields (version 2 also covers recordVersion, fieldDispositions, approvalId and approverId) hashed into {@link AuditEventHash}'s chain — see {@link
 * AuditChainVerifierCli}'s printed limitation for exactly which fields those
 * are. Without a checkpoint file it also cannot detect truncation of a writer's most
 * recent records: deleting the tail of an append-only file leaves a chain that verifies
 * perfectly end to end. Given a checkpoint file held outside the audit file's custody
 * ({@link #verify(Path, Path)}), truncation back past a checkpoint and the deletion of a
 * whole boot with checkpointed records are reported. Records written after a writer's last
 * checkpoint remain undetectable if deleted. {@link AuditChainVerifierCli} prints these
 * limitations on every run.
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
 *   <li>a line ending in a raw carriage return — the {@code "\r\n"} a resumed {@link
 *       FileAuditSink} writes after a torn fragment, before its first record — reported as an {@link
 *       AnomalyType#INTERRUPTED_WRITE_FRAGMENT} anomaly, not a break, and never parsed. A fragment
 *       fused with a restarted writer's first record (logs written before 0.5.0) has more fields
 *       than its version allows and is a {@link AnomalyType#FIELD_COUNT_MISMATCH} break;
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

    /**
     * The shortest retention a {@link AuditCheckpoint.Kind#RETENTION_ANCHOR} is believed to
     * stand for by default: EU AI Act Art. 19's six months. A purge only deletes segments older
     * than the retention period, so an anchor over a segment younger than this, relative to when
     * the anchor was recorded, did not come from a legitimate purge.
     */
    public static final Period DEFAULT_MINIMUM_RETENTION = Period.ofMonths(6);

    private AuditChainVerifier() {
    }

    /**
     * Everything one replay needs beyond the bytes: the anchors, the retention floor, the start rule, and the
     * key every hash is recomputed under ({@code null} for the unkeyed chain).
     */
    private record Replay(Map<String, List<AuditCheckpoint>> anchors, List<AuditCheckpoint> checkpoints,
                          Period minimumRetention, boolean lenientStart, AuditChainKey key, Instrument instr,
                          long[] fragmentOffset) {
    }

    /**
     * What a replay records beyond the report, for {@link #verifySegmentsHashed}: where each segment starts in
     * the concatenation, and which (writer, sequence) pairs to keep a hashed record for.
     */
    private record Instrument(long[] starts, Map<String, java.util.Set<Long>> probes,
                              java.util.Set<Long> accepted) {

        int segmentAt(long offset) {
            int lo = 0;
            int hi = starts.length - 1;
            int found = 0;
            while (lo <= hi) {
                int mid = (lo + hi) >>> 1;
                if (starts[mid] <= offset) {
                    found = mid;
                    lo = mid + 1;
                } else {
                    hi = mid - 1;
                }
            }
            return found;
        }
    }

    /** Reads and verifies {@code path} in one pass. */
    public static VerificationReport verify(Path path) throws IOException {
        return verify(readAudit(path), List.of(), DEFAULT_MINIMUM_RETENTION, false);
    }

    /**
     * <strong>NOT a full verification.</strong> Verifies {@code segments} (already in date order)
     * as one concatenation, for {@link io.github.aindriub.dataprism.audit.retention.AuditRetention}'s
     * pre-purge check. It skips the GENESIS anchor on each writer's first record and the checkpoint
     * comparison (no checkpoint file is read), so a file whose head was truncated can look intact. Use {@link #verify(Path, Path)} (or
     * {@link #verify(Path)}) to verify an audit file in full.
     *
     * <p>The first record of each writer is not required to chain
     * from GENESIS, because an earlier purge may have removed its predecessors; every later link
     * and every recomputed hash is still checked. Returns the byte offset at which each segment
     * starts in the concatenation through {@code starts}.
     */
    public static VerificationReport verifySegments(List<Path> segments, List<Long> starts) throws IOException {
        byte[] content = join(segments, starts);
        return verify(content, List.of(), DEFAULT_MINIMUM_RETENTION, true);
    }

    /**
     * As {@link #verifySegments(List, List)}, with the keys a keyed chain may have been written under and
     * whether a writer that verifies only unkeyed is tolerated; see {@link #verify(Path, Path, Period, List,
     * boolean)} for what the keys mean. Used by {@link io.github.aindriub.dataprism.audit.retention.AuditRetention}.
     */
    public static VerificationReport verifySegments(List<Path> segments, List<Long> starts,
                                                    List<AuditChainKey> keys, boolean allowUnkeyedWriters)
            throws IOException {
        byte[] content = join(segments, starts);
        return verifyWithKeys(content, List.of(), DEFAULT_MINIMUM_RETENTION, true, keys, allowUnkeyedWriters);
    }

    /** One record the replay recomputed and matched: its position in the chain and in the concatenation. */
    public record HashedRecord(long sequence, String previousHash, String eventHash, long byteOffset) {
    }

    /** A writer's first and last hashed record within one segment. */
    public record WriterSpan(HashedRecord first, HashedRecord last) {
    }

    /**
     * What one segment holds that was hashed: each writer's {@link WriterSpan}, and the latest UTC date among
     * its hashed records' timestamps (empty if it holds none).
     */
    public record SegmentHashes(Map<String, WriterSpan> writers, Optional<java.time.LocalDate> latestRecordDate) {
    }

    /**
     * A writer the report shows broken: its last hashed record before the break (empty if none) and whether the
     * break is a link failure ({@code previousHash} does not match, or the chain does not start where it must)
     * rather than a content failure ({@code eventHash} does not recompute).
     */
    public record BrokenWriter(String instanceId, Optional<HashedRecord> lastHashedBeforeBreak,
                               boolean linkFailure) {
    }

    /**
     * The result of {@link #verifySegmentsHashed}: the ordinary report plus, from the very replay the report
     * gives for each writer, which records were hashed. Size is bounded by segments times writers (and the
     * requested probes). {@code firstUnhashedCompleteOffset} is the lowest byte offset of a line, or the
     * unterminated tail, that parses as a record once exactly one trailing carriage return is removed but was
     * not hashed: a record after a writer's break, a duplicate sequence's second copy, a line ending in a
     * carriage return, or the tail. A line that does not parse is a crash fragment and is not counted.
     */
    public record HashedVerification(VerificationReport report, List<SegmentHashes> segments,
                                     Map<String, BrokenWriter> brokenWriters,
                                     java.util.OptionalLong firstUnhashedCompleteOffset,
                                     Map<String, Map<Long, HashedRecord>> probed) {
    }

    /**
     * As {@link #verifySegments(List, List, List, boolean)}, additionally reporting which records were hashed.
     * The report held is identical to that method's. {@code probes} names (writer, sequence) pairs whose hashed
     * record, if any, is returned in {@link HashedVerification#probed()}.
     */
    public static HashedVerification verifySegmentsHashed(List<Path> segments, List<Long> starts,
                                                          List<AuditChainKey> keys, boolean allowUnkeyedWriters,
                                                          Map<String, java.util.Set<Long>> probes)
            throws IOException {
        return verifySegmentsHashed(segments, starts, keys, allowUnkeyedWriters, probes, java.util.Set.of());
    }

    /**
     * One audit line an operator confirmed as a crash line: the segment file name, the byte offset of the line in
     * that file and the SHA-256 (hex) of the line's on-disk bytes. A line is "that line" only if all three match.
     */
    public record AcceptedLine(String segment, long offset, String sha256) {
    }

    /**
     * As {@link #verifySegmentsHashed(List, List, List, boolean, Map)}, except that an accepted line is not counted
     * in {@link HashedVerification#firstUnhashedCompleteOffset()}. The match is by segment name, in-segment offset
     * and the SHA-256 of the line's current bytes, so an edited line, or a copy at another place, still counts.
     */
    public static HashedVerification verifySegmentsHashed(List<Path> segments, List<Long> starts,
                                                          List<AuditChainKey> keys, boolean allowUnkeyedWriters,
                                                          Map<String, java.util.Set<Long>> probes,
                                                          java.util.Set<AcceptedLine> accepted)
            throws IOException {
        java.util.Objects.requireNonNull(starts, "starts");
        java.util.Objects.requireNonNull(accepted, "accepted");
        byte[] content = join(segments, starts);
        long[] startArray = starts.stream().mapToLong(Long::longValue).toArray();
        java.util.Set<Long> acceptedOffsets = new java.util.HashSet<>();
        for (int i = 0; i < segments.size() && !accepted.isEmpty(); i++) {
            String name = segments.get(i).getFileName().toString();
            byte[] segment = null;
            for (AcceptedLine a : accepted) {
                if (a.segment().equals(name)) {
                    segment = segment == null ? Files.readAllBytes(segments.get(i)) : segment;
                    if (lineMatches(segment, a)) {
                        acceptedOffsets.add(startArray[i] + a.offset());
                    }
                }
            }
        }
        Detailed d = verifyWithKeys(content, List.of(), DEFAULT_MINIMUM_RETENTION, true, keys, allowUnkeyedWriters,
                new Instrument(startArray, probes, acceptedOffsets));
        List<Map<String, WriterSpan>> perSegment = new ArrayList<>();
        List<java.time.LocalDate> dates = new ArrayList<>();
        for (int i = 0; i < segments.size(); i++) {
            perSegment.add(new LinkedHashMap<>());
            dates.add(null);
        }
        long unhashed = d.fragmentOffset();
        Map<String, BrokenWriter> broken = new LinkedHashMap<>();
        Map<String, Map<Long, HashedRecord>> probed = new LinkedHashMap<>();
        for (WriterResult w : d.report().writers()) {
            Picked pick = d.picked().get(w.instanceId());
            WriterState st = pick.state();
            long writerUnhashed = pick.forger() ? st.firstOffset : st.unhashedComplete;
            if (writerUnhashed >= 0 && (unhashed < 0 || writerUnhashed < unhashed)) {
                unhashed = writerUnhashed;
            }
            if (!pick.forger()) {
                for (Map.Entry<Integer, Span> e : st.spans.entrySet()) {
                    perSegment.get(e.getKey()).put(w.instanceId(), new WriterSpan(e.getValue().first,
                            e.getValue().last));
                    java.time.LocalDate old = dates.get(e.getKey());
                    if (old == null || e.getValue().latest.isAfter(old)) {
                        dates.set(e.getKey(), e.getValue().latest);
                    }
                }
                if (!st.probed.isEmpty()) {
                    probed.put(w.instanceId(), Map.copyOf(st.probed));
                }
            }
            if (w.broken()) {
                broken.put(w.instanceId(), new BrokenWriter(w.instanceId(),
                        pick.forger() ? Optional.empty() : Optional.ofNullable(st.lastHashed),
                        !pick.forger() && st.linkBreak));
            }
        }
        List<SegmentHashes> result = new ArrayList<>();
        for (int i = 0; i < segments.size(); i++) {
            result.add(new SegmentHashes(Map.copyOf(perSegment.get(i)), Optional.ofNullable(dates.get(i))));
        }
        return new HashedVerification(d.report(), result, Map.copyOf(broken),
                unhashed < 0 ? java.util.OptionalLong.empty() : java.util.OptionalLong.of(unhashed), probed);
    }

    /** Reads one segment's bytes; retention passes a counting reader in tests. */
    @FunctionalInterface
    public interface SegmentReader {
        byte[] read(Path segment) throws IOException;
    }

    /** A newline-terminated line of a segment that parses as a record, for the boundary scan. */
    public record BoundaryRecord(String instanceId, long sequence, String previousHash, String eventHash,
                                 boolean endsInCarriageReturn, long byteOffset) {
    }

    /**
     * Every newline-terminated line of {@code segment}, in order, that parses as a record of one of {@code
     * instanceIds} once exactly one trailing carriage return is removed. A line that ends in one is flagged, so
     * the caller cannot step over a carriage-return copy. The unterminated tail is not returned: it may be a
     * write in flight. Nothing here is hashed; the caller decides what a record is worth.
     */
    public static List<BoundaryRecord> boundaryRecords(Path segment, SegmentReader reader,
                                                       java.util.Set<String> instanceIds) throws IOException {
        return boundaryRecords(segment, reader, instanceIds, java.util.Set.of());
    }

    /**
     * As {@link #boundaryRecords(Path, SegmentReader, java.util.Set)}, except that an accepted line (segment name,
     * offset and SHA-256 of its current bytes all matching) is not returned.
     */
    public static List<BoundaryRecord> boundaryRecords(Path segment, SegmentReader reader,
                                                       java.util.Set<String> instanceIds,
                                                       java.util.Set<AcceptedLine> accepted) throws IOException {
        byte[] bytes = reader.read(segment);
        String segmentName = segment.getFileName().toString();
        List<BoundaryRecord> found = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < bytes.length; i++) {
            if (bytes[i] != '\n') {
                continue;
            }
            String line = new String(bytes, start, i - start, StandardCharsets.UTF_8);
            boolean cr = !line.isEmpty() && line.charAt(line.length() - 1) == '\r';
            if (cr) {
                line = line.substring(0, line.length() - 1);
            }
            if (!line.isEmpty() && !isAccepted(bytes, segmentName, start, accepted)) {
                try {
                    AuditEvent event = AuditRecordFormat.parse(line);
                    if (instanceIds.contains(event.instanceId())) {
                        found.add(new BoundaryRecord(event.instanceId(), event.sequence(), event.previousHash(),
                                event.eventHash(), cr, start));
                    }
                } catch (RuntimeException e) {
                    // not a record: a crash fragment or a line the full verifier reports
                }
            }
            start = i + 1;
        }
        return found;
    }

    private static boolean isAccepted(byte[] segmentBytes, String segmentName, long offset,
                                      java.util.Set<AcceptedLine> accepted) {
        for (AcceptedLine a : accepted) {
            if (a.segment().equals(segmentName) && a.offset() == offset && lineMatches(segmentBytes, a)) {
                return true;
            }
        }
        return false;
    }

    /** True if {@code a.offset()} starts a line of {@code segment} whose on-disk bytes hash to {@code a.sha256()}. */
    private static boolean lineMatches(byte[] segment, AcceptedLine a) {
        long offset = a.offset();
        if (offset < 0 || offset >= segment.length || (offset > 0 && segment[(int) offset - 1] != '\n')) {
            return false;
        }
        int end = (int) offset;
        while (end < segment.length && segment[end] != '\n') {
            end++;
        }
        return sha256Hex(segment, (int) offset, end).equalsIgnoreCase(a.sha256());
    }

    private static String sha256Hex(byte[] bytes, int from, int to) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            md.update(bytes, from, to - from);
            return java.util.HexFormat.of().formatHex(md.digest());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Reads {@code path}: a single file as is, or a directory as every {@code audit-YYYY-MM-DD.log}
     * or {@code audit-YYYY-MM-DD.NNNN.log} segment in (date, index) order. A non-final segment that ends without a newline (a torn write) has the
     * writer's torn-tail terminator ({@code "\r\n"}) added so its fragment cannot fuse with the next segment's first record; it then reads as an
     * interrupted-write fragment. Byte offsets in a directory report are into that concatenation.
     */
    private static byte[] readAudit(Path path) throws IOException {
        if (!Files.isDirectory(path)) {
            return Files.readAllBytes(path);
        }
        List<Path> segments = new ArrayList<>();
        try (java.util.stream.Stream<Path> files = Files.list(path)) {
            files.filter(Files::isRegularFile).forEach(f -> {
                if (SegmentedFileAuditSink.segmentDate(f) != null) {
                    segments.add(f);
                }
            });
        }
        segments.sort(SegmentedFileAuditSink.SEGMENT_ORDER);
        return join(segments, null);
    }

    private static byte[] join(List<Path> segments, List<Long> starts) throws IOException {
        java.io.ByteArrayOutputStream all = new java.io.ByteArrayOutputStream();
        int remaining = segments.size();
        for (Path segment : segments) {
            if (starts != null) {
                starts.add((long) all.size());
            }
            byte[] bytes = Files.readAllBytes(segment);
            all.write(bytes);
            remaining--;
            if (remaining > 0 && bytes.length > 0 && bytes[bytes.length - 1] != '\n') {
                // The same terminator a resumed writer adds, so the torn line reads the same way.
                all.write(FileAuditSink.TORN_TAIL_TERMINATOR.getBytes(StandardCharsets.US_ASCII));
            }
        }
        return all.toByteArray();
    }

    /** Reads and verifies every byte {@code in} produces before EOF. */
    public static VerificationReport verify(InputStream in) throws IOException {
        return verify(in.readAllBytes(), List.of(), DEFAULT_MINIMUM_RETENTION, false);
    }

    /**
     * As {@link #verify(Path)}, additionally comparing every writer against the
     * checkpoints in {@code checkpointFile} (one {@link AuditCheckpoint} JSON
     * line each, as written by {@link FileAuditCheckpointSink}). A checkpoint
     * line ending in a raw {@code \r}, or a final unterminated line that is not a valid
     * checkpoint, is what a crash leaves; it is reported as {@link AnomalyType#TORN_CHECKPOINT_LINE},
     * never parsed, never a break, and the intact checkpoints are still used. Any other malformed
     * checkpoint line fails with {@link IOException}: an unreadable checkpoint file must not
     * silently verify as "no checkpoints".
     */
    public static VerificationReport verify(Path auditFile, Path checkpointFile) throws IOException {
        return verify(auditFile, checkpointFile, DEFAULT_MINIMUM_RETENTION);
    }

    /**
     * As {@link #verify(Path, Path)}, with the shortest retention a {@link
     * AuditCheckpoint.Kind#RETENTION_ANCHOR} may stand for. A deployment that runs {@link
     * io.github.aindriub.dataprism.audit.retention.AuditRetention} with the below-minimum override passes its own period here; an anchor whose
     * segment date is not at least this long before its {@code recordedAt} is reported as {@link
     * AnomalyType#RETENTION_ANCHOR_REJECTED} and explains nothing.
     */
    public static VerificationReport verify(Path auditFile, Path checkpointFile, Period minimumRetention)
            throws IOException {
        java.util.Objects.requireNonNull(checkpointFile, "checkpointFile");
        return verify(auditFile, checkpointFile, minimumRetention, List.of(), false);
    }

    /**
     * Verifies {@code auditPath} (a file, or a directory of segments) against an optional {@code
     * checkpointFile} ({@code null} for none), with the keys a keyed chain may have been written under.
     * The caller states the mode; nothing in the files does, so a forged file cannot downgrade itself.
     *
     * <p>With a non-empty {@code keys}, each writer's chain must verify entirely under exactly one
     * supplied key, and the report says which key id verified it ({@link WriterResult#keyId()}). A writer
     * that verifies under no key is a break. A writer that verifies only as an unkeyed chain is a break
     * unless {@code allowUnkeyedWriters}, in which case it is reported as unkeyed
     * ({@link WriterResult#unkeyedAllowed()}) and counted ({@link VerificationReport#unkeyedWriterCount()}).
     * With an empty {@code keys} the chain is verified as unkeyed, as {@link #verify(Path, Path, Period)} does.
     *
     * <p>A keyed chain resists a writer who lacks the key; it does not resist anyone who holds it, including
     * the operator and the process that writes the file.
     */
    public static VerificationReport verify(Path auditPath, Path checkpointFile, Period minimumRetention,
                                            List<AuditChainKey> keys, boolean allowUnkeyedWriters)
            throws IOException {
        java.util.Objects.requireNonNull(minimumRetention, "minimumRetention");
        java.util.Objects.requireNonNull(keys, "keys");
        byte[] content = readAudit(auditPath);
        List<AuditCheckpoint> checkpoints = new ArrayList<>();
        List<StructuralAnomaly> torn = new ArrayList<>();
        if (checkpointFile == null) {
            return verifyWithKeys(content, checkpoints, minimumRetention, false, keys, allowUnkeyedWriters);
        }
        checkpoints.addAll(readCheckpoints(checkpointFile, torn));
        VerificationReport report = verifyWithKeys(content, checkpoints, minimumRetention, false, keys,
                allowUnkeyedWriters);
        if (torn.isEmpty()) {
            return report;
        }
        List<StructuralAnomaly> anomalies = new ArrayList<>(report.anomalies());
        anomalies.addAll(torn);
        return new VerificationReport(report.writers(), anomalies, report.tail(), report.checkpointFindings());
    }

    /**
     * The checkpoints in {@code checkpointFile}, parsed exactly as {@link #verify(Path, Path, Period, List,
     * boolean)} parses them: blank lines, lines ending in a raw carriage return and a torn final line are skipped,
     * and any other malformed line fails with {@link IOException}. Nothing is written.
     */
    public static List<AuditCheckpoint> readCheckpoints(Path checkpointFile) throws IOException {
        return readCheckpoints(checkpointFile, new ArrayList<>());
    }

    private static List<AuditCheckpoint> readCheckpoints(Path checkpointFile, List<StructuralAnomaly> torn)
            throws IOException {
        List<AuditCheckpoint> checkpoints = new ArrayList<>();
        // Split on '\n' only: Files.readAllLines would swallow the '\r' of a terminated torn line.
        String[] lines = Files.readString(checkpointFile, StandardCharsets.UTF_8).split("\n", -1);
        long offset = 0;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            long lineOffset = offset;
            offset += line.getBytes(StandardCharsets.UTF_8).length + 1;
            if (line.isBlank()) {
                continue;
            }
            boolean unterminatedFinal = i == lines.length - 1;
            if (line.endsWith("\r")) {
                torn.add(tornCheckpointLine(checkpointFile, i + 1, lineOffset,
                        "it ends in a raw carriage return, so a resumed writer terminated a torn write"));
                continue;
            }
            try {
                checkpoints.add(AuditCheckpoint.fromJsonLine(line));
            } catch (IllegalArgumentException e) {
                if (unterminatedFinal) {
                    torn.add(tornCheckpointLine(checkpointFile, i + 1, lineOffset,
                            "it is the final line, has no terminating newline and is not a valid checkpoint"));
                    continue;
                }
                throw new IOException("checkpoint file " + checkpointFile + " line " + (i + 1)
                        + " is not a valid checkpoint: " + e.getMessage(), e);
            }
        }
        return checkpoints;
    }

    private static StructuralAnomaly tornCheckpointLine(Path file, int lineNo, long byteOffset, String why) {
        return new StructuralAnomaly(AnomalyType.TORN_CHECKPOINT_LINE,
                "TORN_CHECKPOINT_LINE: checkpoint file " + file + " line " + lineNo + " (byte offset " + byteOffset
                        + ") is a damaged checkpoint: " + why + ". It was not parsed and not counted as a checkpoint. "
                        + "A crash during a checkpoint write leaves this; it is not reported as tampering, but the "
                        + "writer's tail-truncation coverage falls back to its previous checkpoint.",
                byteOffset, -1);
    }

    // -- accept-crash-line ----------------------------------------------------------------------------

    /**
     * What {@link #checkCrashLine} decided. {@code condition} is the number of the first condition that failed
     * (0 when accepted) and {@code message} says which and why. {@code hashStatus} and {@code hashSentence} are
     * the report on whether the line's own hash recomputes; they are {@code null} when condition 1 or 2 refused,
     * before the line was parsed. {@code bootWaived} is true only when the flag waived condition 7 (a).
     */
    public record CrashLineVerdict(boolean accepted, int condition, String message, String hashStatus,
                                   String hashSentence, String instanceId, long sequence,
                                   java.time.LocalDate segmentDate, boolean bootWaived) {
    }

    private static CrashLineVerdict refuseCrashLine(int condition, String message, String[] hash) {
        return new CrashLineVerdict(false, condition, message, hash == null ? null : hash[0],
                hash == null ? null : hash[1], null, 0, null, false);
    }

    private static List<Path> listSegments(Path directory) throws IOException {
        List<Path> segments = new ArrayList<>();
        try (java.util.stream.Stream<Path> files = Files.list(directory)) {
            files.filter(Files::isRegularFile).forEach(f -> {
                if (SegmentedFileAuditSink.segmentDate(f) != null) {
                    segments.add(f);
                }
            });
        }
        segments.sort(SegmentedFileAuditSink.SEGMENT_ORDER);
        return segments;
    }

    /** The report on whether {@code event}'s own hash recomputes: {status, one sentence}. */
    private static String[] hashReport(AuditEvent event, List<AuditChainKey> keys, boolean allowUnkeyedWriters) {
        for (int i = 0; i < keys.size(); i++) {
            if (AuditEventHash.compute(event, keys.get(i)).equals(event.eventHash())) {
                return new String[] {"KEYED_MATCH " + (i + 1), "the line's eventHash recomputes under the supplied "
                        + "key at position " + (i + 1) + " (in the order the --hmac-key-file options were given)."};
            }
        }
        boolean unkeyedMatches = AuditEventHash.compute(event).equals(event.eventHash());
        if (unkeyedMatches && (keys.isEmpty() || allowUnkeyedWriters)) {
            return new String[] {"UNKEYED_MATCH", "the line's eventHash recomputes as an unkeyed hash."};
        }
        if (unkeyedMatches) {
            return new String[] {"UNKEYED_ONLY_FORGER_SHAPE", "keys were supplied without --allow-unkeyed-writers "
                    + "and only the unkeyed hash matches. This is the shape a forger without the key produces, the "
                    + "case the verifier treats as a forger."};
        }
        if (!keys.isEmpty()) {
            return new String[] {"NO_MATCH_LOOKS_CUT", "nothing matches, unkeyed included. The line looks like a cut "
                    + "inside the hashed fields, for example inside raw[19] or raw[24], or an edit."};
        }
        return new String[] {"NO_UNKEYED_MATCH", "the line looks cut if this deployment is unkeyed; a keyed line was "
                + "not checked because no key was given."};
    }

    /**
     * Judges whether an operator may accept the line of {@code segmentName} at byte {@code offset} as a crash line
     * (task 293): conditions 1 to 8, in order, the first that fails being reported. Nothing is written. These
     * checks do not prove a crash; they refuse a line whose own evidence contradicts the checkpoint store or the
     * supplied keys. {@code noBootCheckpoint} waives condition 7 (a) only.
     */
    public static CrashLineVerdict checkCrashLine(Path auditDirectory, Path checkpointFile, String segmentName,
                                                  long offset, String sha256, Period minimumRetention,
                                                  List<AuditChainKey> keys, boolean allowUnkeyedWriters,
                                                  boolean noBootCheckpoint) throws IOException {
        // 1: the offset starts a line of an audit segment of the directory
        List<Path> segments = listSegments(auditDirectory);
        int targetIndex = -1;
        for (int i = 0; i < segments.size(); i++) {
            if (segments.get(i).getFileName().toString().equals(segmentName)) {
                targetIndex = i;
            }
        }
        if (targetIndex < 0) {
            return refuseCrashLine(1, "condition 1: " + segmentName + " is not an audit-YYYY-MM-DD[.NNNN].log "
                    + "segment file of " + auditDirectory, null);
        }
        byte[] segment = Files.readAllBytes(segments.get(targetIndex));
        if (offset < 0 || offset >= segment.length || (offset > 0 && segment[(int) offset - 1] != '\n')) {
            return refuseCrashLine(1, "condition 1: offset " + offset + " is not the start of a line in "
                    + segmentName, null);
        }
        int end = (int) offset;
        while (end < segment.length && segment[end] != '\n') {
            end++;
        }
        boolean terminated = end < segment.length;
        String line = new String(segment, (int) offset, end - (int) offset, StandardCharsets.UTF_8);
        boolean carriageReturn = line.endsWith("\r");

        // 2: skipped by the replay without parsing, and a complete record once one trailing CR is removed
        if (terminated && !carriageReturn) {
            return refuseCrashLine(2, "condition 2: the line is newline-terminated, so the replay parsed it. Only "
                    + "a line ending in a carriage return, or the unterminated tail of a segment, is skipped "
                    + "without parsing.", null);
        }
        AuditEvent event;
        try {
            event = AuditRecordFormat.parse(carriageReturn ? line.substring(0, line.length() - 1) : line);
        } catch (RuntimeException e) {
            return refuseCrashLine(2, "condition 2: the line does not parse as a record once one trailing carriage "
                    + "return is removed. A torn fragment that does not parse is already tolerated by purge.", null);
        }
        String[] hash = hashReport(event, keys, allowUnkeyedWriters);
        String instance = event.instanceId();

        // 3 and the branch facts: every line of the directory that parses as this instance's record
        boolean laterRecord = false;
        boolean otherRecord = false;
        for (int si = 0; si < segments.size(); si++) {
            byte[] bytes = si == targetIndex ? segment : Files.readAllBytes(segments.get(si));
            int from = 0;
            while (from < bytes.length) {
                int to = from;
                while (to < bytes.length && bytes[to] != '\n') {
                    to++;
                }
                if (!(si == targetIndex && from == offset)) {
                    String other = new String(bytes, from, to - from, StandardCharsets.UTF_8);
                    if (other.endsWith("\r")) {
                        other = other.substring(0, other.length() - 1);
                    }
                    try {
                        if (!other.isEmpty() && AuditRecordFormat.parse(other).instanceId().equals(instance)) {
                            otherRecord = true;
                            laterRecord |= si > targetIndex || (si == targetIndex && from > offset);
                        }
                    } catch (RuntimeException e) {
                        // not a record: a fragment or a line the verifier reports
                    }
                }
                from = to + 1;
            }
        }
        if (laterRecord) {
            return refuseCrashLine(3, "condition 3: instance " + instance + " has a later record in the directory, "
                    + "so this line is not its last", hash);
        }

        // 4: the supplied hash is the line's
        if (!sha256Hex(segment, (int) offset, end).equalsIgnoreCase(sha256)) {
            return refuseCrashLine(4, "condition 4: the supplied --sha256 does not match the line's bytes", hash);
        }

        // 5: the line chains as its instance's next record
        List<AuditCheckpoint> checkpoints = readCheckpoints(checkpointFile);
        List<AuditCheckpoint> anchors = checkpoints.stream()
                .filter(c -> c.kind() == AuditCheckpoint.Kind.RETENTION_ANCHOR && c.instanceId().equals(instance))
                .toList();
        String previous = event.previousHash();
        if (otherRecord) {
            List<Long> starts = new ArrayList<>();
            byte[] content = join(segments, starts);
            Detailed d = verifyWithKeys(content, checkpoints, minimumRetention, false, keys, allowUnkeyedWriters,
                    new Instrument(starts.stream().mapToLong(Long::longValue).toArray(), Map.of(),
                            java.util.Set.of()));
            WriterResult writer = d.report().writers().stream().filter(w -> w.instanceId().equals(instance))
                    .findFirst().orElse(null);
            Picked pick = d.picked().get(instance);
            if (writer != null && (writer.broken() || writer.nonGenesisStart().isPresent() || pick.forger()
                    || pick.state().anchorRejection != null)) {
                return refuseCrashLine(5, "condition 5 (i) check 1: instance " + instance + " is broken before the "
                        + "line, or the replay reports a NON_GENESIS_START or RETENTION_ANCHOR_REJECTED for it, "
                        + "so the line does not continue a chain the verifier accepts from its start", hash);
            }
            HashedRecord last = pick == null ? null : pick.state().lastHashed;
            if (last == null) {
                return refuseCrashLine(5, "condition 5 (i) check 2: instance " + instance + " has no hashed record "
                        + "before the line", hash);
            }
            if (!previous.equals(last.eventHash())) {
                return refuseCrashLine(5, "condition 5 (i) check 3: the line's previousHash is not the eventHash of "
                        + "the instance's last hashed record", hash);
            }
            if (event.sequence() != last.sequence() + 1) {
                return refuseCrashLine(5, "condition 5 (i) check 4: the line's sequence is not the last hashed "
                        + "record's sequence + 1", hash);
            }
        } else if (anchors.isEmpty()) {
            if (!GENESIS.equals(previous)) {
                return refuseCrashLine(5, "condition 5 (ii) check 1: the instance's only line has a previousHash "
                        + "that is not GENESIS", hash);
            }
            if (event.sequence() != 1) {
                return refuseCrashLine(5, "condition 5 (ii) check 2: the instance's only line is GENESIS but its "
                        + "sequence is not 1", hash);
            }
        } else {
            long top = anchors.stream().mapToLong(AuditCheckpoint::sequence).max().orElseThrow();
            List<AuditCheckpoint> highest = anchors.stream().filter(a -> a.sequence() == top).toList();
            AuditCheckpoint anchor = highest.get(0);
            if (highest.stream().anyMatch(a -> !a.headHash().equals(anchor.headHash()))) {
                return refuseCrashLine(5, "condition 5 (iii) check 1: two retention anchors share the highest "
                        + "sequence with different headHash values", hash);
            }
            if (GENESIS.equals(previous)) {
                return refuseCrashLine(5, "condition 5 (iii) check 2: the line's previousHash is GENESIS, but the "
                        + "instance has a retention anchor", hash);
            }
            if (event.sequence() != anchor.sequence() + 1) {
                return refuseCrashLine(5, "condition 5 (iii) check 3: the line's sequence is not the anchor's "
                        + "sequence + 1", hash);
            }
            if (!previous.equals(anchor.headHash())) {
                return refuseCrashLine(5, "condition 5 (iii) check 4: the line's previousHash is not the anchor's "
                        + "headHash", hash);
            }
            if (!anchorIsOldEnough(anchor, minimumRetention)) {
                return refuseCrashLine(5, "condition 5 (iii) check 5: the anchor is not old enough for the minimum "
                        + "retention " + minimumRetention, hash);
            }
            String contradiction = anchorContradiction(anchor, checkpoints);
            if (contradiction != null) {
                return refuseCrashLine(5, "condition 5 (iii) check 6: " + contradiction, hash);
            }
            if (event.timestamp().atZone(java.time.ZoneOffset.UTC).toLocalDate().isBefore(anchor.segmentDate())) {
                return refuseCrashLine(5, "condition 5 (iii) check 7: the line is dated before the anchor's "
                        + "segment date " + anchor.segmentDate(), hash);
            }
        }

        // 6: the store ends at a line boundary, so appending never terminates a line itself
        byte[] store = Files.readAllBytes(checkpointFile);
        if (store.length > 0 && store[store.length - 1] != '\n') {
            return refuseCrashLine(6, "condition 6: the checkpoint store's last byte is not a newline, so it ends "
                    + "in a torn line. Stop the server and let it terminate the line first.", hash);
        }

        // 7: the store vouches for the instance
        boolean hasBoot = checkpoints.stream().anyMatch(
                c -> c.kind() == AuditCheckpoint.Kind.BOOT && c.instanceId().equals(instance));
        if (!hasBoot && !noBootCheckpoint) {
            return refuseCrashLine(7, "condition 7 (a): the checkpoint store holds no BOOT checkpoint for instance "
                    + instance + ". In a deployment with a checkpoint sink, a missing BOOT is a strong sign of "
                    + "tampering, because BOOT is written and forced to disk before the instance's first record. "
                    + "Without a BOOT, condition 7 cannot be applied. Only for deployments with no checkpoint "
                    + "sink, or instances that ran before the store existed, --no-boot-checkpoint lets the "
                    + "command proceed, and you should investigate before using it.", hash);
        }
        AuditCheckpoint best = null;
        for (AuditCheckpoint c : checkpoints) {
            boolean head = c.kind() == AuditCheckpoint.Kind.BOOT || c.kind() == AuditCheckpoint.Kind.PERIODIC
                    || c.kind() == AuditCheckpoint.Kind.SHUTDOWN;
            if (head && c.instanceId().equals(instance) && c.sequence() >= event.sequence()
                    && (best == null || c.sequence() > best.sequence()
                    || (c.sequence() == best.sequence() && headRank(c.kind()) > headRank(best.kind())))) {
                best = c;
            }
        }
        if (best != null) {
            return refuseCrashLine(7, "condition 7 (b): the store holds a " + best.kind() + " checkpoint at sequence "
                    + best.sequence() + " for instance " + instance + ", at or above the line's sequence "
                    + event.sequence() + ", so the writer got past this line and it is not the writer's last, "
                    + "torn record. A power loss on a disk that does not honour fsync can cause this; extend "
                    + "retention by hand.", hash);
        }

        // 8: the keys supplied do not contradict the line's own hash
        if ("UNKEYED_ONLY_FORGER_SHAPE".equals(hash[0])) {
            return refuseCrashLine(8, "condition 8: the hash report is UNKEYED_ONLY_FORGER_SHAPE, the shape a "
                    + "forger without the key produces", hash);
        }
        java.time.LocalDate day = SegmentedFileAuditSink.segmentDate(segments.get(targetIndex));
        return new CrashLineVerdict(true, 0, "accepted", hash[0], hash[1], instance, event.sequence(), day,
                !hasBoot);
    }

    private static int headRank(AuditCheckpoint.Kind kind) {
        return switch (kind) {
            case SHUTDOWN -> 3;
            case PERIODIC -> 2;
            default -> 1;
        };
    }

    private static VerificationReport verify(byte[] content, List<AuditCheckpoint> checkpoints,
                                             Period minimumRetention, boolean lenientStart) {
        return replay(content, checkpoints, minimumRetention, lenientStart, null, null).report();
    }

    /** One replay's report together with the per-writer state it was built from. */
    private record Replayed(VerificationReport report, Map<String, WriterState> states, long fragmentOffset) {
    }

    /**
     * A reported verification and, per writer, the replay's state that the report's verdict for that writer
     * came from (its hashed records). A writer the report breaks without any replay having verified it (the
     * forger branch) maps to its state with {@code forger} set: its hashed set is empty.
     */
    private record Detailed(VerificationReport report, Map<String, Picked> picked, long fragmentOffset) {
    }

    private record Picked(WriterState state, boolean forger) {
    }

    private static VerificationReport verifyWithKeys(byte[] content, List<AuditCheckpoint> checkpoints,
                                                     Period minimumRetention, boolean lenientStart,
                                                     List<AuditChainKey> keys, boolean allowUnkeyedWriters) {
        return verifyWithKeys(content, checkpoints, minimumRetention, lenientStart, keys, allowUnkeyedWriters,
                null).report();
    }

    private static Detailed verifyWithKeys(byte[] content, List<AuditCheckpoint> checkpoints,
                                           Period minimumRetention, boolean lenientStart,
                                           List<AuditChainKey> keys, boolean allowUnkeyedWriters,
                                           Instrument instr) {
        java.util.Objects.requireNonNull(keys, "keys");
        if (keys.isEmpty()) {
            Replayed plainReplay = replay(content, checkpoints, minimumRetention, lenientStart, null, instr);
            VerificationReport plain = plainReplay.report();
            Map<String, Picked> pickedPlain = new LinkedHashMap<>();
            plainReplay.states().forEach((id, st) -> pickedPlain.put(id, new Picked(st, false)));
            if (!allowUnkeyedWriters) {
                return new Detailed(plain, pickedPlain, plainReplay.fragmentOffset());
            }
            // No key was given and unkeyed writers are allowed: every intact writer was verified unkeyed.
            // A broken writer verified nothing, so it is not marked as allowed.
            List<WriterResult> marked = plain.writers().stream()
                    .map(w -> w.broken() ? w : w.withKey(Optional.empty(), true)).toList();
            return new Detailed(new VerificationReport(marked, plain.anomalies(), plain.tail(),
                    plain.checkpointFindings()), pickedPlain, plainReplay.fragmentOffset());
        }
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (AuditChainKey key : keys) {
            if (!ids.add(java.util.Objects.requireNonNull(key, "key").id())) {
                throw new IllegalArgumentException("two audit chain keys share the id " + key.id());
            }
        }
        List<Replayed> keyed = new ArrayList<>();
        for (AuditChainKey key : keys) {
            keyed.add(replay(content, checkpoints, minimumRetention, lenientStart, key, instr));
        }
        Replayed unkeyed = replay(content, checkpoints, minimumRetention, lenientStart, null, instr);
        Replayed base = keyed.get(0);
        List<WriterResult> results = new ArrayList<>();
        Map<String, Picked> picked = new LinkedHashMap<>();
        for (int i = 0; i < base.report().writers().size(); i++) {
            WriterResult first = base.report().writers().get(i);
            String instance = first.instanceId();
            WriterResult chosen = null;
            Picked pick = null;
            for (int k = 0; k < keys.size() && chosen == null; k++) {
                WriterResult candidate = keyed.get(k).report().writers().get(i);
                if (!candidate.broken() && keyed.get(k).states().get(instance).recomputed > 0) {
                    chosen = candidate.withKey(Optional.of(keys.get(k).id()), false);
                    pick = new Picked(keyed.get(k).states().get(instance), false);
                }
            }
            if (chosen == null) {
                // Nothing of this writer was hashed (for example a lone non-GENESIS start): no key verified
                // it, so it carries no key label, and its structural finding stands as reported.
                for (int k = 0; k < keys.size() && chosen == null; k++) {
                    WriterResult candidate = keyed.get(k).report().writers().get(i);
                    if (!candidate.broken() && keyed.get(k).states().get(instance).recomputed == 0) {
                        chosen = candidate;
                        pick = new Picked(keyed.get(k).states().get(instance), false);
                    }
                }
            }
            if (chosen == null) {
                WriterResult plain = unkeyed.report().writers().get(i);
                // The mismatch flag reads the stored hashes, so it is the same under every key; what differs per
                // replay is whether the checkpoint break is that replay's reported break. Only such a replay
                // counts: a writer that failed on a hash first (unkeyed, forged) falls through to the unkeyed
                // and forger branches.
                boolean mismatch = keyed.stream().anyMatch(r -> r.states().get(instance).checkpointBreak);
                if (mismatch) {
                    // Report the replay that hashed furthest, not key 0's by default, and name its key.
                    int best = -1;
                    long most = -1;
                    for (int k = 0; k < keyed.size(); k++) {
                        if (!keyed.get(k).states().get(instance).checkpointBreak) {
                            continue;
                        }
                        long hashed = keyed.get(k).states().get(instance).recomputed;
                        if (hashed > most) {
                            most = hashed;
                            best = k;
                        }
                    }
                    // A replay that recomputed nothing verified nothing under its key, so it gets no label.
                    chosen = keyed.get(best).report().writers().get(i)
                            .withKey(most > 0 ? Optional.of(keys.get(best).id()) : Optional.empty(), false);
                    pick = new Picked(keyed.get(best).states().get(instance), false);
                } else if (!plain.broken() && allowUnkeyedWriters) {
                    chosen = plain.withKey(Optional.empty(), true);
                    pick = new Picked(unkeyed.states().get(instance), false);
                } else if (!plain.broken()) {
                    pick = new Picked(base.states().get(instance), true);
                    chosen = first.withBreak(new Break(first.firstSequence(), instance,
                            base.states().get(instance).firstOffset,
                            "this writer's chain verifies only as an unkeyed chain, but a key was required and "
                                    + "unkeyed writers were not allowed -- a forger who recomputed the chain "
                                    + "without the key produces exactly this shape"));
                } else {
                    // Name the break from the replay under which this writer verified furthest.
                    Break furthest = first.firstBreak().orElseThrow();
                    Replayed furthestReplay = base;
                    for (Replayed r : keyed) {
                        Break candidate = r.report().writers().get(i).firstBreak().orElseThrow();
                        if (candidate.byteOffset() > furthest.byteOffset()) {
                            furthest = candidate;
                            furthestReplay = r;
                        }
                    }
                    pick = new Picked(furthestReplay.states().get(instance), false);
                    chosen = first.withBreak(new Break(furthest.sequence(), instance, furthest.byteOffset(),
                            "this writer's chain verifies under none of the supplied keys and is not a valid "
                                    + "unkeyed chain either (checked against key ids " + String.join(", ", ids)
                                    + "): " + furthest.reason()));
                }
            }
            results.add(chosen);
            picked.put(instance, pick);
        }
        VerificationReport b = base.report();
        return new Detailed(new VerificationReport(results, b.anomalies(), b.tail(), b.checkpointFindings()),
                picked, base.fragmentOffset());
    }

    private static Replayed replay(byte[] content, List<AuditCheckpoint> checkpoints,
                                   Period minimumRetention, boolean lenientStart, AuditChainKey key,
                                   Instrument instr) {
        Map<String, WriterState> writers = new LinkedHashMap<>();
        Map<String, List<AuditCheckpoint>> retentionAnchors = new LinkedHashMap<>();
        for (AuditCheckpoint cp : checkpoints) {
            if (cp.kind() == AuditCheckpoint.Kind.RETENTION_ANCHOR) {
                retentionAnchors.computeIfAbsent(cp.instanceId(), k -> new ArrayList<>()).add(cp);
            }
        }
        Replay replay = new Replay(retentionAnchors, checkpoints, minimumRetention, lenientStart, key, instr,
                new long[] {-1});
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
                processLine(line, start, writers, anomalies, precedingAnomalyOffset, replay);
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
            noteFragment(replay, new String(content, start, content.length - start, StandardCharsets.UTF_8), start);
        }

        List<CheckpointFinding> findings = applyCheckpoints(writers, checkpoints, minimumRetention);
        for (WriterState state : writers.values()) {
            if (state.anchorRejection != null) {
                anomalies.add(new StructuralAnomaly(AnomalyType.RETENTION_ANCHOR_REJECTED, state.anchorRejection,
                        state.firstOffset, -1));
            }
        }

        List<WriterResult> results = new ArrayList<>();
        for (WriterState state : writers.values()) {
            results.add(state.toResult());
        }
        return new Replayed(new VerificationReport(results, anomalies, Optional.ofNullable(tail), findings), writers,
                replay.fragmentOffset()[0]);
    }

    /**
     * Compares each checkpointed writer with what survived. A hash that differs from the
     * checkpointed head at the same sequence marks that writer broken (exit 2). A writer whose
     * last surviving sequence is below its highest checkpointed sequence, or which has a
     * checkpoint past sequence 0 and no surviving record, is reported as a finding (exit 5).
     * {@link AuditCheckpoint.Kind#RETENTION_ANCHOR} is not compared as a head; it is used while
     * replaying (a chain that starts right after an anchor is intact) and to explain a writer
     * whose every record was purged.
     */
    private static List<CheckpointFinding> applyCheckpoints(Map<String, WriterState> writers,
                                                            List<AuditCheckpoint> checkpoints,
                                                            Period minimumRetention) {
        Map<String, Long> highest = new LinkedHashMap<>();
        for (AuditCheckpoint cp : checkpoints) {
            if (cp.kind() == AuditCheckpoint.Kind.RETENTION_ANCHOR
                    || cp.kind() == AuditCheckpoint.Kind.CRASH_LINE_ACCEPTED) {
                continue;
            }
            highest.merge(cp.instanceId(), cp.sequence(), Math::max);
            WriterState writer = writers.get(cp.instanceId());
            if (writer == null || cp.sequence() == 0) {
                continue;
            }
            String actual = writer.hashBySequence.get(cp.sequence());
            if (actual != null && !actual.equals(cp.headHash()) && !writer.checkpointMismatch) {
                writer.checkpointMismatch = true;
                if (!writer.broken) {
                    writer.broken = true;
                    writer.checkpointBreak = true;
                    writer.firstBreak = new Break(cp.sequence(), cp.instanceId(),
                            writer.seenSequences.get(cp.sequence()),
                            "its eventHash differs from the head hash the checkpoint file recorded for this writer "
                                    + "at the same sequence -- the record was replaced or rewritten after the "
                                    + "checkpoint was taken");
                }
            }
        }
        List<CheckpointFinding> findings = new ArrayList<>();
        for (Map.Entry<String, Long> e : highest.entrySet()) {
            WriterState writer = writers.get(e.getKey());
            long checkpointed = e.getValue();
            if (writer == null) {
                if (checkpointed > 0 && !anchoredPast(checkpoints, e.getKey(), checkpointed, minimumRetention)) {
                    findings.add(new CheckpointFinding(CheckpointFindingType.MISSING_WRITER, e.getKey(),
                            checkpointed, -1,
                            "MISSING_WRITER: writer " + e.getKey() + " has a checkpoint at sequence "
                                    + checkpointed + " but no record of it survives in the audit file -- every "
                                    + "record of this boot was deleted, or the wrong audit file was supplied."));
                }
                continue;
            }
            long last = writer.seenSequences.keySet().stream().mapToLong(Long::longValue).max().orElse(0);
            if (last < checkpointed) {
                findings.add(new CheckpointFinding(CheckpointFindingType.TRUNCATED_BEFORE_CHECKPOINT, e.getKey(),
                        checkpointed, last,
                        "TRUNCATED_BEFORE_CHECKPOINT: writer " + e.getKey() + " has a checkpoint at sequence "
                                + checkpointed + " but its last surviving record is sequence " + last
                                + " -- records at the tail of this writer's chain were deleted."));
            }
        }
        return findings;
    }

    /**
     * True if a believable retention anchor records that this writer's chain was purged through
     * {@code sequence}.
     */
    private static boolean anchoredPast(List<AuditCheckpoint> checkpoints, String instanceId, long sequence,
                                        Period minimumRetention) {
        return checkpoints.stream().anyMatch(cp -> cp.kind() == AuditCheckpoint.Kind.RETENTION_ANCHOR
                && cp.instanceId().equals(instanceId) && cp.sequence() >= sequence
                && anchorIsOldEnough(cp, minimumRetention) && anchorContradiction(cp, checkpoints) == null);
    }

    /**
     * An anchor stands for a legitimate purge only if it names the segment it covered and that
     * segment was at least {@code minimumRetention} old when the anchor was recorded. An anchor
     * with no segment date (written before the field existed) covers nothing.
     */
    private static boolean anchorIsOldEnough(AuditCheckpoint anchor, Period minimumRetention) {
        if (anchor.segmentDate() == null) {
            return false;
        }
        java.time.LocalDate recorded = anchor.recordedAt().atZone(java.time.ZoneOffset.UTC).toLocalDate();
        return !anchor.segmentDate().plus(minimumRetention).isAfter(recorded);
    }

    /**
     * Why a head checkpoint of the same writer shows the anchor's date is forged, or {@code null}.
     * An anchor for sequence n and segment date D says the writer had reached n by D. A BOOT,
     * PERIODIC or SHUTDOWN checkpoint at a lower sequence, recorded on a UTC date after D, says it
     * had not.
     */
    private static String anchorContradiction(AuditCheckpoint anchor, List<AuditCheckpoint> checkpoints) {
        if (anchor.segmentDate() == null) {
            return null;
        }
        for (AuditCheckpoint cp : checkpoints) {
            if (cp.kind() == AuditCheckpoint.Kind.RETENTION_ANCHOR
                    || cp.kind() == AuditCheckpoint.Kind.CRASH_LINE_ACCEPTED
                    || !cp.instanceId().equals(anchor.instanceId())
                    || cp.sequence() >= anchor.sequence()) {
                continue;
            }
            java.time.LocalDate recorded = cp.recordedAt().atZone(java.time.ZoneOffset.UTC).toLocalDate();
            if (recorded.isAfter(anchor.segmentDate())) {
                return "a " + cp.kind() + " checkpoint at sequence " + cp.sequence() + " was recorded on "
                        + recorded + ", after the anchor's segment date " + anchor.segmentDate()
                        + ", yet the anchor claims sequence " + anchor.sequence() + " by that date";
            }
        }
        return null;
    }

    private static void processLine(String line, long offset, Map<String, WriterState> writers,
                                      List<StructuralAnomaly> anomalies, Long precedingAnomalyOffset,
                                      Replay replay) {
        if (line.isEmpty()) {
            return;
        }
        if (line.charAt(line.length() - 1) == '\r') {
            // A serialized record never contains a raw CR (AuditRecordFormat escapes it), so this
            // is a torn write that a resumed writer terminated. Never parsed as a record.
            anomalies.add(new StructuralAnomaly(AnomalyType.INTERRUPTED_WRITE_FRAGMENT,
                    "the line at byte offset " + offset + " ends in a carriage return, the marker a resumed "
                            + "writer adds after an interrupted write (a process died mid-line and a new one "
                            + "terminated the fragment before appending). The fragment is not parsed and is not "
                            + "a record. Durable append-only storage is an operator responsibility this release "
                            + "does not enforce -- this is not tampering with an existing record. The records "
                            + "immediately before and after this line were verified independently.",
                    offset, -1));
            noteFragment(replay, line, offset);
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
            writer.noteUnhashed(offset);
            return;
        }
        if (writer.highestVersion > event.recordVersion()) {
            anomalies.add(new StructuralAnomaly(AnomalyType.VERSION_REGRESSION,
                    "VERSION_REGRESSION: writer " + event.instanceId() + " wrote a recordVersion "
                            + event.recordVersion() + " record at sequence " + event.sequence() + " (byte offset "
                            + offset + ") after a recordVersion " + writer.highestVersion + " record. A writer's "
                            + "version only ever increases, so this is a downgrade: investigate it as a possible "
                            + "forgery that avoids a field the newer version hashes.",
                    offset, -1));
        }
        writer.highestVersion = Math.max(writer.highestVersion, event.recordVersion());
        writer.seenSequences.put(event.sequence(), offset);
        writer.hashBySequence.put(event.sequence(), event.eventHash());
        writer.recordCount++;

        if (writer.broken) {
            writer.afterBreakCount++;
            writer.headHash = event.eventHash();
            writer.noteUnhashed(offset);
            return;
        }

        boolean firstRecordSeenForWriter = writer.firstSequence == null;
        if (firstRecordSeenForWriter) {
            writer.firstSequence = event.sequence();
            writer.firstOffset = offset;
            AuditCheckpoint anchor = null;
            if (!GENESIS.equals(event.previousHash())) {
                for (AuditCheckpoint candidate : replay.anchors().getOrDefault(event.instanceId(), List.of())) {
                    if (candidate.sequence() + 1 != event.sequence()
                            || !candidate.headHash().equals(event.previousHash())) {
                        continue;
                    }
                    String contradiction = anchorIsOldEnough(candidate, replay.minimumRetention())
                            ? anchorContradiction(candidate, replay.checkpoints()) : null;
                    boolean startsBeforeSegment = candidate.segmentDate() != null && event.timestamp()
                            .atZone(java.time.ZoneOffset.UTC).toLocalDate().isBefore(candidate.segmentDate());
                    if (anchorIsOldEnough(candidate, replay.minimumRetention()) && contradiction == null
                            && !startsBeforeSegment) {
                        anchor = candidate;
                        break;
                    }
                    writer.anchorRejection = anchorRejectionMessage(event, candidate, replay.minimumRetention(),
                            contradiction, startsBeforeSegment);
                }
            }
            if (anchor != null) {
                // The chain legitimately starts here: a purge recorded this writer's last purged
                // record, and this record follows it exactly. Fall through to the ordinary checks.
                writer.retentionAnchor = anchor;
                writer.anchorRejection = null;
            } else if (!GENESIS.equals(event.previousHash()) && !replay.lenientStart()) {
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
                    writer.noteUnhashed(offset);
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
                writer.linkBreak = true;
                writer.noteUnhashed(offset);
                return;
            }
        }

        if (writer.lastHash != null && !event.previousHash().equals(writer.lastHash)) {
            writer.broken = true;
            writer.firstBreak = new Break(event.sequence(), event.instanceId(), offset,
                    "its previousHash does not match the eventHash of the record that should precede it in this "
                            + "writer's chain -- the record it should chain from is missing or was altered");
            writer.headHash = event.eventHash();
            writer.linkBreak = true;
            writer.noteUnhashed(offset);
            return;
        }

        writer.recomputed++;
        String recomputed = replay.key() == null ? AuditEventHash.compute(event)
                : AuditEventHash.compute(event, replay.key());
        if (!recomputed.equals(event.eventHash())) {
            writer.broken = true;
            writer.firstBreak = new Break(event.sequence(), event.instanceId(), offset,
                    "its eventHash does not match AuditEventHash recomputed from its own stored fields -- its "
                            + "content was altered after it was written");
            writer.headHash = event.eventHash();
            writer.noteUnhashed(offset);
            return;
        }

        writer.lastHash = event.eventHash();
        writer.headHash = event.eventHash();
        if (replay.instr() != null) {
            writer.noteHashed(event, offset, replay.instr());
        }
    }

    /**
     * Notes a line the replay skipped without hashing: if it parses once exactly one trailing carriage
     * return is removed, it is a complete record that was never hashed, and its offset is kept (the lowest).
     * A line that does not parse is a crash fragment and is not noted.
     */
    private static void noteFragment(Replay replay, String line, long offset) {
        String body = !line.isEmpty() && line.charAt(line.length() - 1) == '\r'
                ? line.substring(0, line.length() - 1) : line;
        try {
            AuditRecordFormat.parse(body);
        } catch (RuntimeException e) {
            return;
        }
        if (replay.instr() != null && replay.instr().accepted().contains(offset)) {
            return; // an operator confirmed this exact line as a crash line
        }
        long[] lowest = replay.fragmentOffset();
        if (lowest[0] < 0 || offset < lowest[0]) {
            lowest[0] = offset;
        }
    }

    private static String anchorRejectionMessage(AuditEvent event, AuditCheckpoint anchor, Period minimumRetention,
                                                 String contradiction, boolean startsBeforeSegment) {
        if (contradiction != null || startsBeforeSegment) {
            return "RETENTION_ANCHOR_REJECTED: writer " + event.instanceId() + " starts at sequence "
                    + event.sequence() + " right after a retention anchor (sequence " + anchor.sequence()
                    + ", segment date " + anchor.segmentDate() + ", recorded " + anchor.recordedAt() + "), but "
                    + (contradiction != null ? contradiction
                            : "the first surviving record is dated before the anchor's segment date")
                    + ". The anchor's date cannot be genuine, so it does not explain the missing records: the chain "
                    + "is reported as it would be without it. Anyone able to append to the checkpoint file could "
                    + "have written it to hide a recent deletion.";
        }
        return "RETENTION_ANCHOR_REJECTED: writer " + event.instanceId() + " starts at sequence "
                + event.sequence() + " right after a retention anchor (sequence " + anchor.sequence()
                + ", recorded " + anchor.recordedAt() + "), but the anchor "
                + (anchor.segmentDate() == null
                        ? "names no segment date"
                        : "covers a segment dated " + anchor.segmentDate() + ", less than " + minimumRetention
                                + " before it was recorded")
                + ". A purge only deletes segments older than the retention period, so this anchor does not "
                + "explain the missing records: the chain is reported as it would be without it. Anyone able to "
                + "append to the checkpoint file could have written it to hide a recent deletion.";
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
        if (cause instanceof FieldCountMismatchException) {
            return new StructuralAnomaly(AnomalyType.FIELD_COUNT_MISMATCH,
                    "FIELD_COUNT_MISMATCH: the line at byte offset " + offset + " declares a recordVersion "
                            + "that does not match its field count (" + cause.getMessage() + "). A torn write "
                            + "cannot produce this shape, so it is a tampering signature: a field was added, "
                            + "removed or relabelled. Investigate this line directly. One known cause for logs "
                            + "written before 0.5.0: a legacy interrupted write followed by a restart, whose "
                            + "first record was appended onto the unterminated fragment. To check, see whether "
                            + "the trailing 20, 24 or 25 fields parse as a GENESIS record of a new instanceId, "
                            + "and whether the record after this line continues that writer.",
                    offset, -1);
        }
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

    /** A writer's hashed records within one segment: the first, the last and the latest record date. */
    private static final class Span {
        private final HashedRecord first;
        private HashedRecord last;
        private java.time.LocalDate latest;

        Span(HashedRecord first, HashedRecord last, java.time.LocalDate latest) {
            this.first = first;
            this.last = last;
            this.latest = latest;
        }
    }

    private static final class WriterState {
        private final String instanceId;
        private final Map<Long, Long> seenSequences = new LinkedHashMap<>();
        private final Map<Long, String> hashBySequence = new LinkedHashMap<>();
        private boolean checkpointMismatch;
        /** True when the writer's reported first break is the checkpoint break itself, not an earlier one. */
        private boolean checkpointBreak;
        private long recordCount;
        /** Records whose hash was recomputed and compared; zero means nothing verified this writer. */
        private long recomputed;
        private long afterBreakCount;
        private String lastHash;
        private String headHash;
        private boolean broken;
        private Break firstBreak;
        private Long firstSequence;
        private NonGenesisStart nonGenesisStart;
        private AuditCheckpoint retentionAnchor;
        private String anchorRejection;
        private long firstOffset;
        private int highestVersion;
        /** True when the reported first break is a link failure (or a bad start), not a content failure. */
        private boolean linkBreak;
        /** The lowest offset of a complete record of this writer that was parsed but not hashed, or -1. */
        private long unhashedComplete = -1;
        private HashedRecord lastHashed;
        /** Per segment index, this writer's first and last hashed record there and its latest record date. */
        private final java.util.TreeMap<Integer, Span> spans = new java.util.TreeMap<>();
        private final Map<Long, HashedRecord> probed = new LinkedHashMap<>();

        WriterState(String instanceId) {
            this.instanceId = instanceId;
        }

        void noteUnhashed(long offset) {
            if (unhashedComplete < 0 || offset < unhashedComplete) {
                unhashedComplete = offset;
            }
        }

        void noteHashed(AuditEvent event, long offset, Instrument instr) {
            HashedRecord record = new HashedRecord(event.sequence(), event.previousHash(), event.eventHash(), offset);
            lastHashed = record;
            java.time.LocalDate date = event.timestamp().atZone(java.time.ZoneOffset.UTC).toLocalDate();
            Span span = spans.computeIfAbsent(instr.segmentAt(offset), k -> new Span(record, record, date));
            span.last = record;
            if (date.isAfter(span.latest)) {
                span.latest = date;
            }
            if (instr.probes().getOrDefault(instanceId, java.util.Set.of()).contains(event.sequence())) {
                probed.put(event.sequence(), record);
            }
        }

        WriterResult toResult() {
            return new WriterResult(instanceId, recordCount, headHash, broken, Optional.ofNullable(firstBreak),
                    afterBreakCount, firstSequence, Optional.ofNullable(nonGenesisStart),
                    Optional.ofNullable(retentionAnchor));
        }
    }

    /** What kind of non-tampering structural anomaly a line represents. */
    public enum AnomalyType {
        /** A field-count mismatch: a torn write's fragment concatenated with a restarted writer's first record. */
        INTERRUPTED_WRITE_FRAGMENT(false),
        /** Two durable records sharing a sequence number for one writer: a sink-contract violation. */
        DUPLICATE_SEQUENCE(false),
        /** A parse failure that is not the known interrupted-write shape and cannot be ruled out as tampering. */
        UNPARSEABLE_RECORD(true),
        /** A retention anchor that matches a writer's start but is too recent, or undated, to be a purge's. */
        RETENTION_ANCHOR_REJECTED(true),
        /** A writer's {@code recordVersion} decreased within its chain: a downgrade, never a normal upgrade. */
        VERSION_REGRESSION(true),
        /** A line whose field count contradicts its declared {@code recordVersion}: tampering, never a torn write. */
        FIELD_COUNT_MISMATCH(true),
        /** A damaged checkpoint-file line left by a crash mid-write: not parsed, not a break, never skipped silently. */
        TORN_CHECKPOINT_LINE(false);

        private final boolean counted;

        AnomalyType(boolean counted) {
            this.counted = counted;
        }

        /**
         * True if this anomaly is counted as a break: it cannot be ruled out as tampering. The single
         * predicate behind {@link VerificationReport#hasBreak()}, {@link VerificationReport#hasStructuralAnomaly()}
         * and retention's purge boundary, so a new type must choose here and nowhere else.
         */
        public boolean isBreak() {
            return counted;
        }
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
                                Optional<NonGenesisStart> nonGenesisStart,
                                Optional<AuditCheckpoint> retentionAnchor, Optional<String> keyId,
                                boolean unkeyedAllowed) {

        /** As the full form for a result with no key information: unkeyed verification. */
        public WriterResult(String instanceId, long sequenceCount, String headHash, boolean broken,
                            Optional<Break> firstBreak, long afterBreakCount, long firstSequence,
                            Optional<NonGenesisStart> nonGenesisStart, Optional<AuditCheckpoint> retentionAnchor) {
            this(instanceId, sequenceCount, headHash, broken, firstBreak, afterBreakCount, firstSequence,
                    nonGenesisStart, retentionAnchor, Optional.empty(), false);
        }

        WriterResult withKey(Optional<String> keyId, boolean unkeyedAllowed) {
            return new WriterResult(instanceId, sequenceCount, headHash, broken, firstBreak, afterBreakCount,
                    firstSequence, nonGenesisStart, retentionAnchor, keyId, unkeyedAllowed);
        }

        WriterResult withBreak(Break newBreak) {
            return new WriterResult(instanceId, sequenceCount, headHash, true, Optional.of(newBreak),
                    afterBreakCount, firstSequence, nonGenesisStart, retentionAnchor, Optional.empty(), false);
        }

        public WriterResult(String instanceId, long sequenceCount, String headHash, boolean broken,
                            Optional<Break> firstBreak, long afterBreakCount, long firstSequence,
                            Optional<NonGenesisStart> nonGenesisStart) {
            this(instanceId, sequenceCount, headHash, broken, firstBreak, afterBreakCount, firstSequence,
                    nonGenesisStart, Optional.empty());
        }
    }

    /** What an external checkpoint contradicts about the surviving file. */
    public enum CheckpointFindingType {
        /** A writer's last surviving sequence is lower than its highest checkpointed sequence. */
        TRUNCATED_BEFORE_CHECKPOINT,
        /** A writer has a checkpoint past sequence 0 and no surviving records. */
        MISSING_WRITER
    }

    /**
     * One writer contradicted by the checkpoint file. {@code survivingSequence} is -1 when no
     * record survives.
     */
    public record CheckpointFinding(CheckpointFindingType type, String instanceId, long checkpointSequence,
                                    long survivingSequence, String message) {
    }

    /** The full result of one verification pass over a file. */
    public record VerificationReport(List<WriterResult> writers, List<StructuralAnomaly> anomalies,
                                      Optional<TailAnomaly> tail, List<CheckpointFinding> checkpointFindings) {

        public VerificationReport(List<WriterResult> writers, List<StructuralAnomaly> anomalies,
                                  Optional<TailAnomaly> tail) {
            this(writers, anomalies, tail, List.of());
        }

        /** How many writers verified only as unkeyed chains and were tolerated because the caller allowed it. */
        public long unkeyedWriterCount() {
            return writers.stream().filter(WriterResult::unkeyedAllowed).count();
        }

        /** True if the checkpoint file shows a writer's tail or whole boot missing. */
        public boolean hasCheckpointFinding() {
            return !checkpointFindings.isEmpty();
        }

        /** True if any writer's chain has a break, or any anomaly cannot be ruled out as tampering. */
        public boolean hasBreak() {
            return writers.stream().anyMatch(WriterResult::broken)
                    || anomalies.stream().anyMatch(a -> a.type().isBreak());
        }

        /**
         * True if any anomaly is a known non-tampering structural shape, or any writer's chain
         * does not start at GENESIS. Never true together with a break for the same finding: a
         * writer that both fails to start at GENESIS and later suffers a genuine mid-chain break
         * is reported as a break (the more severe finding), via {@link #hasBreak()}.
         */
        public boolean hasStructuralAnomaly() {
            return anomalies.stream().anyMatch(a -> !a.type().isBreak())
                    || writers.stream().anyMatch(w -> w.nonGenesisStart().isPresent());
        }
    }
}

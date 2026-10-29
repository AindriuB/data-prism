package io.github.aindriub.dataprism.audit.retention;

import io.github.aindriub.dataprism.audit.AuditChainKey;
import io.github.aindriub.dataprism.audit.AuditCheckpoint;
import io.github.aindriub.dataprism.audit.AuditCheckpointSink;
import io.github.aindriub.dataprism.audit.sink.SegmentedFileAuditSink;
import io.github.aindriub.dataprism.audit.verify.AuditChainVerifier;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.time.Period;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Deletes whole expired day-segments written by {@link SegmentedFileAuditSink},
 * after recording a {@link AuditCheckpoint.Kind#RETENTION_ANCHOR} for each
 * writer's last record in each segment about to go.
 *
 * <p>EU AI Act Art. 19 sets a floor of six months for automatically generated
 * logs, "unless provided otherwise in applicable Union or national law". A
 * retention below that floor is refused with {@code AUDIT_RETENTION_BELOW_MINIMUM}
 * unless {@code allowBelowMinimum} is passed; passing it is the operator's
 * legal responsibility, not something this class judges.
 *
 * <p>Purge is deletion: the records are gone, and an anchor keeps only a
 * sequence and a hash. Every chain position purge uses (an anchor's sequence and hash, a writer's first and
 * last record in a day) comes from the verifier's own replay: a record counts only if the replay recomputed
 * and matched its hash. A day is deleted only if
 * <ul>
 *   <li>its chain verifies, and it holds no line that parses as a complete record but was never hashed (a
 *       line ending in a carriage return, an unterminated tail, a duplicate sequence's second copy). A line
 *       that does not parse is a crash fragment and is tolerated;</li>
 *   <li>each writer's next record after the day, if one exists, has a {@code previousHash} equal to the
 *       hash of the day's last verified record of that writer, so what is deleted links to what stays;</li>
 *   <li>where the checkpoint store holds a {@code SHUTDOWN} for a writer, the records agree with it, which
 *       also stops the read for that writer at its shutdown;</li>
 *   <li>no verified record is dated after the day in its file's name.</li>
 * </ul>
 * Otherwise the day is kept, with every later expired segment, and the purge throws {@code
 * AUDIT_RETENTION_CHAIN_UNVERIFIED}. Anchors are written first, and if any anchor write
 * fails nothing is deleted, with one exception: a day an earlier purge anchored
 * and only part deleted is finished first. Its remaining segments are removed
 * only if, verified on their own (never joined to the next day), they show no break,
 * no structural anomaly, no unterminated tail and no unhashed complete record, each writer's first remaining
 * record starts at GENESIS or follows an earlier anchor, and each writer's next record chains from its
 * recorded anchor for the day; otherwise it takes the normal path. A failure
 * after that names the finished day in its message.
 *
 * <p>Residual: a writer that stopped without a {@code SHUTDOWN} checkpoint (a crash, or no checkpoint sink)
 * and never writes again has its last records deleted undetected if someone removes them cleanly at a line
 * boundary before a purge; periodic checkpoints compared by the full verifier are the defence. Deleting every
 * line of a writer from an expiring day is not detected by purge either. A crash can also leave a line that
 * parses but was never hashed; purge keeps that day, and every later expired day, until an operator acts.
 *
 * <p>Trust assumption: the retention anchor store and the {@code SHUTDOWN} checkpoints are trusted. Anyone who can
 * write to them can vouch for a chain's start and can make a day look partly
 * purged or cleanly closed; that was already so for the anchors that vouch for a chain's start.
 * A day recognised as partly purged is still verified, within the limits above, before it is deleted.
 */
public final class AuditRetention {

    public static final String BELOW_MINIMUM = "AUDIT_RETENTION_BELOW_MINIMUM";
    public static final String ANCHOR_FAILED = "AUDIT_RETENTION_ANCHOR_FAILED";

    public static final String CHAIN_UNVERIFIED = "AUDIT_RETENTION_CHAIN_UNVERIFIED";

    /** Four years, so every leap-year and month-length alignment is probed. */
    private static final LocalDate PROBE_START = LocalDate.of(2024, 1, 1);
    private static final int PROBE_DAYS = 1461;

    private final Path directory;
    private final Period retention;
    private final AuditCheckpointSink anchors;
    private final Clock clock;
    private final boolean allowBelowMinimum;
    private final List<AuditChainKey> chainKeys;
    private final boolean allowUnkeyedWriters;

    /** Deletes one segment; replaceable in tests to inject a delete failure. */
    @FunctionalInterface
    interface Deleter {
        void delete(Path segment) throws IOException;
    }

    Deleter deleter = Files::delete;

    /** Opens a segment for the boundary scan; replaceable in tests to count the segments read. */
    AuditChainVerifier.SegmentReader segmentReader = Files::readAllBytes;

    public AuditRetention(Path directory, Period retention, AuditCheckpointSink anchors, Clock clock) {
        this(directory, retention, anchors, clock, false);
    }

    public AuditRetention(Path directory, Period retention, AuditCheckpointSink anchors, Clock clock,
                          boolean allowBelowMinimum) {
        this(directory, retention, anchors, clock, allowBelowMinimum, List.of(), false);
    }

    /**
     * As above, and additionally verifies expiring segments with {@code chainKeys} the way {@link
     * AuditChainVerifier#verify(Path, Path, Period, List, boolean)} does: an expiring keyed segment whose
     * key is not supplied does not verify and is not deleted. An empty list means unkeyed.
     */
    public AuditRetention(Path directory, Period retention, AuditCheckpointSink anchors, Clock clock,
                          boolean allowBelowMinimum, List<AuditChainKey> chainKeys, boolean allowUnkeyedWriters) {
        this.chainKeys = List.copyOf(Objects.requireNonNull(chainKeys, "chainKeys"));
        this.allowUnkeyedWriters = allowUnkeyedWriters;
        this.directory = Objects.requireNonNull(directory, "directory");
        this.retention = Objects.requireNonNull(retention, "retention");
        this.anchors = Objects.requireNonNull(anchors, "anchors");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.allowBelowMinimum = allowBelowMinimum;
        if (!allowBelowMinimum && canBeShorterThanSixMonths(retention)) {
            throw new IllegalArgumentException(BELOW_MINIMUM + ": retention " + retention
                    + " is shorter than six months. EU AI Act Art. 19 requires at least six months unless "
                    + "other Union or national law provides otherwise; pass the explicit retention override "
                    + "only if that applies to you.");
        }
    }

    /**
     * True if, from some start date, {@code retention} reaches less far than six calendar months
     * in either direction. A day count is not safe by being "about six months": six calendar
     * months span 181 to 184 days, so P181D to P183D fall short from some dates.
     */
    static boolean canBeShorterThanSixMonths(Period retention) {
        for (int i = 0; i < PROBE_DAYS; i++) {
            LocalDate d = PROBE_START.plusDays(i);
            if (d.plus(retention).isBefore(d.plusMonths(6)) || d.minus(retention).isAfter(d.minusMonths(6))) {
                return true;
            }
        }
        return false;
    }

    /**
     * The first date that is no longer expired: segments dated strictly before it go. Refuses a
     * cutoff later than {@code today} minus six calendar months unless {@code allowBelowMinimum}.
     */
    static LocalDate cutoff(LocalDate today, Period retention, boolean allowBelowMinimum) {
        LocalDate cutoff = today.minus(retention);
        if (!allowBelowMinimum && cutoff.isAfter(today.minusMonths(6))) {
            throw new RetentionException(BELOW_MINIMUM + ": retention " + retention + " on " + today
                    + " would delete segments newer than six calendar months; nothing was deleted", null);
        }
        return cutoff;
    }

    /**
     * Deletes every segment dated strictly before today (UTC, by the clock) minus the retention
     * period, never today's, and returns the deleted files in date order.
     *
     * <p>Before anchoring, the expiring segments' chains are verified, and a segment whose chain
     * does not verify is not purged, nor is any later one: purge must never erase evidence of
     * tampering. Earlier verified segments are still purged, then {@link RetentionException}
     * ({@code AUDIT_RETENTION_CHAIN_UNVERIFIED}) is thrown naming the first refused segment.
     *
     * @throws RetentionException if an anchor cannot be recorded (nothing is deleted), a chain
     *                            does not verify, or a file cannot be deleted (anchors already
     *                            written stay valid)
     */
    public List<Path> purge() {
        LocalDate today = clock.instant().atZone(ZoneOffset.UTC).toLocalDate();
        LocalDate cutoff = cutoff(today, retention, allowBelowMinimum);

        List<Path> segments = new ArrayList<>();
        List<Path> retained = new ArrayList<>();
        try (Stream<Path> files = Files.list(directory)) {
            files.filter(Files::isRegularFile).forEach(f -> {
                LocalDate date = SegmentedFileAuditSink.segmentDate(f);
                if (date == null) {
                    return;
                }
                if (date.isBefore(cutoff) && date.isBefore(today)) {
                    segments.add(f);
                } else {
                    retained.add(f);
                }
            });
        } catch (IOException e) {
            throw new RetentionException(ANCHOR_FAILED + ": could not list " + directory, e);
        }

        segments.sort(SegmentedFileAuditSink.SEGMENT_ORDER);
        retained.sort(SegmentedFileAuditSink.SEGMENT_ORDER);
        List<Path> deleted = new ArrayList<>();
        Map<String, Shutdown> shutdowns = segments.isEmpty() ? Map.of() : readShutdowns("nothing was deleted");
        // A day an earlier run anchored and then failed to finish deleting is finished first, once its remainder verifies on its own.
        int partial = partlyPurgedDayLength(segments, retained, shutdowns);
        String finishedDay = null;
        if (partial > 0) {
            finishedDay = SegmentedFileAuditSink.segmentDate(segments.get(0)).toString();
            List<Path> rest = deleteDay(new ArrayList<>(segments.subList(0, partial)));
            deleted.addAll(rest);
            segments.subList(0, partial).clear();
        }
        String deletedNote = finishedDay == null ? "nothing was deleted"
                : "nothing was deleted beyond finishing the partly purged day " + finishedDay;
        int verified = segments.size();
        String unverified = null;
        AuditChainVerifier.HashedVerification hashed = null;
        List<Long> starts = new ArrayList<>();
        if (!segments.isEmpty()) {
            try {
                hashed = AuditChainVerifier.verifySegmentsHashed(segments, starts, chainKeys, allowUnkeyedWriters,
                        probesFor(shutdowns));
            } catch (IOException e) {
                throw new RetentionException(ANCHOR_FAILED + ": could not read the expiring segments", e);
            }
            long bad = firstBadOffset(hashed);
            if (bad >= 0) {
                verified = segmentAt(starts, bad);
                unverified = CHAIN_UNVERIFIED + ": the chain in " + segments.get(verified).getFileName()
                        + " does not verify, so it and every later expired segment were not purged; "
                        + "investigate it before purging, because purge would erase the evidence";
            }
            // Rule: a record dated after its file's day cannot have been written there.
            for (int i = 0; i < verified; i++) {
                LocalDate fileDay = SegmentedFileAuditSink.segmentDate(segments.get(i));
                java.util.Optional<LocalDate> latest = hashed.segments().get(i).latestRecordDate();
                if (latest.isPresent() && latest.get().isAfter(fileDay)) {
                    verified = i;
                    unverified = CHAIN_UNVERIFIED + ": " + segments.get(i).getFileName() + " holds a verified record "
                            + "dated " + latest.get() + ", later than the day in its file name (" + fileDay
                            + "), so it was not written there and may have been renamed; it and every later "
                            + "expired segment were not purged, because purge would erase the evidence";
                    break;
                }
            }
        }

        if (verified > 0) {
            int unanchored = firstUnanchoredStart(hashed, verified, earlierAnchors(deletedNote));
            if (unanchored >= 0) {
                verified = unanchored;
                unverified = CHAIN_UNVERIFIED + ": a writer's first expiring record in "
                        + segments.get(verified).getFileName() + " neither starts at GENESIS nor follows an earlier "
                        + "retention anchor exactly, so records before it were deleted or cut by hand; it and every "
                        + "later expired segment were not purged, because purge would erase the evidence";
            }
        }

        // A day's segments are one unit: purge all of them or none, so a refusal inside a day keeps the day.
        verified = alignToDay(segments, verified);

        // Each writer's next record after a day must chain from the day's last verified record.
        if (verified > 0) {
            List<BoundaryScan.Task> tasks = new ArrayList<>();
            int from = 0;
            while (from < verified) {
                LocalDate day = SegmentedFileAuditSink.segmentDate(segments.get(from));
                int to = from;
                while (to < verified && day.equals(SegmentedFileAuditSink.segmentDate(segments.get(to)))) {
                    to++;
                }
                for (Map.Entry<String, Integer> w : lastSegmentPerWriter(hashed, from, to).entrySet()) {
                    AuditChainVerifier.HashedRecord last = hashed.segments().get(w.getValue()).writers()
                            .get(w.getKey()).last();
                    tasks.add(new BoundaryScan.Task(from, to, w.getKey(), last.sequence(), last.eventHash(),
                            w.getValue(), shutdowns.get(w.getKey())));
                }
                from = to;
            }
            BoundaryScan scan = new BoundaryScan(segments, retained, segmentReader, starts,
                    hashed.probed(), false,
                    verified < segments.size() ? verified : Integer.MAX_VALUE);
            BoundaryScan.Refusal refusal = scan.run(tasks);
            if (refusal != null) {
                verified = refusal.dayStart();
                unverified = refusal.message();
            }
        }

        List<AuditCheckpoint> toAnchor = new ArrayList<>();
        int from = 0;
        while (from < verified) {
            LocalDate day = SegmentedFileAuditSink.segmentDate(segments.get(from));
            int to = from;
            while (to < verified && day.equals(SegmentedFileAuditSink.segmentDate(segments.get(to)))) {
                to++;
            }
            toAnchor.addAll(anchorsFor(hashed, from, to, day));
            from = to;
        }
        for (AuditCheckpoint anchor : toAnchor) {
            try {
                anchors.record(anchor);
            } catch (RuntimeException e) {
                throw new RetentionException(ANCHOR_FAILED + ": could not record the retention anchor for writer "
                        + anchor.instanceId() + " at sequence " + anchor.sequence() + "; " + deletedNote, e);
            }
        }

        int dayStart = 0;
        while (dayStart < verified) {
            LocalDate day = SegmentedFileAuditSink.segmentDate(segments.get(dayStart));
            int dayEnd = dayStart;
            while (dayEnd < verified && day.equals(SegmentedFileAuditSink.segmentDate(segments.get(dayEnd)))) {
                dayEnd++;
            }
            deleted.addAll(deleteDay(segments.subList(dayStart, dayEnd)));
            dayStart = dayEnd;
        }
        if (unverified != null) {
            throw new RetentionException(unverified, null);
        }
        return deleted;
    }

    /** The index of the segment holding byte {@code offset} of the concatenation that starts at {@code starts}. */
    private static int segmentAt(List<Long> starts, long offset) {
        int found = 0;
        for (int i = 0; i < starts.size(); i++) {
            if (starts.get(i) <= offset) {
                found = i;
            }
        }
        return found;
    }

    /** Moves {@code verified} back to the start of the day it falls inside, if it falls inside one. */
    private static int alignToDay(List<Path> segments, int verified) {
        while (verified > 0 && verified < segments.size()
                && SegmentedFileAuditSink.segmentDate(segments.get(verified - 1))
                .equals(SegmentedFileAuditSink.segmentDate(segments.get(verified)))) {
            verified--;
        }
        return verified;
    }

    /**
     * Deletes one day's segments, highest index first, and returns them in index order. If a delete fails,
     * what remains is a prefix of the day. The next purge recognises the prefix by the day's own
     * anchors, verifies it on its own, and finishes it (see {@link #partlyPurgedDayLength}).
     */
    private List<Path> deleteDay(List<Path> day) {
        for (int i = day.size() - 1; i >= 0; i--) {
            try {
                deleter.delete(day.get(i));
            } catch (IOException e) {
                throw new RetentionException("AUDIT_RETENTION_DELETE_FAILED: could not delete " + day.get(i), e);
            }
        }
        return List.copyOf(day);
    }

    /**
     * The number of leading segments that are the remains of a day an earlier purge anchored and part
     * deleted, or 0. The oldest remaining expired day is such a day only if it has at least one hashed
     * record and every writer with a hashed record in it has a recorded retention anchor for this very day whose sequence is
     * at least that writer's last remaining hashed record, and at least one writer's anchor is beyond it, which
     * proves records were deleted. A writer whose records all remain loses nothing. The anchors say the day
     * was once verified, not that its files are unchanged, so the remaining segments must then verify as
     * a chain on their own (never joined to the next day, under the same chain keys as the normal path)
     * with no break, no structural anomaly, no unterminated tail and no unhashed complete record, each writer's first remaining
     * record must start at GENESIS or follow an earlier anchor, and each writer's next record after the day
     * must chain from the writer's recorded anchor for it (the day's tail is already gone, so the anchor
     * stands for the last record). Anything else, including a missing or other-day anchor, takes the normal verification path.
     */
    private int partlyPurgedDayLength(List<Path> segments, List<Path> retained, Map<String, Shutdown> shutdowns) {
        if (segments.isEmpty()) {
            return 0;
        }
        LocalDate day = SegmentedFileAuditSink.segmentDate(segments.get(0));
        int n = 0;
        while (n < segments.size() && day.equals(SegmentedFileAuditSink.segmentDate(segments.get(n)))) {
            n++;
        }
        List<Path> prefix = segments.subList(0, n);
        List<Long> starts = new ArrayList<>();
        AuditChainVerifier.HashedVerification hashed;
        try {
            hashed = AuditChainVerifier.verifySegmentsHashed(prefix, starts, chainKeys, allowUnkeyedWriters,
                    probesFor(shutdowns));
        } catch (IOException e) {
            throw new RetentionException(ANCHOR_FAILED + ": could not read the expiring segments", e);
        }
        Map<String, Integer> lastSegment = lastSegmentPerWriter(hashed, 0, n);
        if (lastSegment.isEmpty()) {
            return 0;
        }
        List<AuditCheckpoint> earlier = earlierAnchors("nothing was deleted");
        boolean deletionProven = false;
        Map<String, AuditCheckpoint> best = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> w : lastSegment.entrySet()) {
            long remaining = hashed.segments().get(w.getValue()).writers().get(w.getKey()).last().sequence();
            AuditCheckpoint top = null;
            for (AuditCheckpoint a : earlier) {
                if (a.instanceId().equals(w.getKey()) && day.equals(a.segmentDate())
                        && (top == null || a.sequence() > top.sequence())) {
                    top = a;
                }
            }
            if (top == null || top.sequence() < remaining) {
                return 0;
            }
            deletionProven |= top.sequence() > remaining;
            best.put(w.getKey(), top);
        }
        if (!deletionProven) {
            return 0;
        }
        // The anchors only say the day was once verified. The files may have been edited since, so the
        // remainder is verified on its own (never joined to the next day) before it is deleted. A clean
        // partial deletion leaves no break, no structural anomaly (a duplicate sequence, say: a rewritten
        // sequence number shows up as one), no unterminated tail (its last record is never hashed), no
        // unhashed complete record and no unanchored start.
        if (firstBadOffset(hashed) >= 0 || hashed.report().hasStructuralAnomaly()
                || hashed.report().tail().isPresent() || firstUnanchoredStart(hashed, n, earlier) >= 0) {
            return 0;
        }
        for (int i = 0; i < n; i++) {
            LocalDate latest = hashed.segments().get(i).latestRecordDate().orElse(null);
            if (latest != null && latest.isAfter(day)) {
                return 0;
            }
        }
        List<BoundaryScan.Task> tasks = new ArrayList<>();
        for (Map.Entry<String, AuditCheckpoint> w : best.entrySet()) {
            // The day's tail is gone, so the anchor stands for the last record; its file is the day's first.
            tasks.add(new BoundaryScan.Task(0, n, w.getKey(), w.getValue().sequence(), w.getValue().headHash(),
                    0, shutdowns.get(w.getKey())));
        }
        BoundaryScan scan = new BoundaryScan(segments, retained, segmentReader, starts, hashed.probed(), true, Integer.MAX_VALUE);
        return scan.run(tasks) == null ? n : 0;
    }

    private List<AuditCheckpoint> earlierAnchors(String deletedNote) {
        try {
            return anchors.retentionAnchors();
        } catch (RuntimeException e) {
            throw new RetentionException(CHAIN_UNVERIFIED + ": could not read the earlier retention anchors, so "
                    + "the start of the expiring chain cannot be checked; " + deletedNote, e);
        }
    }

    /** A writer's {@code SHUTDOWN} checkpoint with the highest sequence; {@code conflict} if two disagree there. */
    record Shutdown(long sequence, String headHash, boolean conflict) {
    }

    private Map<String, Shutdown> readShutdowns(String deletedNote) {
        List<AuditCheckpoint> all;
        try {
            all = anchors.shutdownCheckpoints();
        } catch (RuntimeException e) {
            throw new RetentionException(CHAIN_UNVERIFIED + ": could not read the SHUTDOWN checkpoints, so the end "
                    + "of the expiring chain cannot be checked; " + deletedNote, e);
        }
        Map<String, Shutdown> byWriter = new HashMap<>();
        for (AuditCheckpoint cp : all) {
            if (cp.kind() != AuditCheckpoint.Kind.SHUTDOWN) {
                continue;
            }
            Shutdown old = byWriter.get(cp.instanceId());
            if (old == null || cp.sequence() > old.sequence()) {
                byWriter.put(cp.instanceId(), new Shutdown(cp.sequence(), cp.headHash(), false));
            } else if (cp.sequence() == old.sequence() && !cp.headHash().equals(old.headHash())) {
                byWriter.put(cp.instanceId(), new Shutdown(old.sequence(), old.headHash(), true));
            }
        }
        return byWriter;
    }

    /** The hashed record just above each {@code SHUTDOWN}, which the shutdown stop needs from the replay. */
    private static Map<String, Set<Long>> probesFor(Map<String, Shutdown> shutdowns) {
        Map<String, Set<Long>> probes = new HashMap<>();
        shutdowns.forEach((id, s) -> probes.put(id, Set.of(s.sequence() + 1)));
        return probes;
    }

    /**
     * For each writer with a hashed record in segments {@code [from, to)}, the index of the last of those
     * segments that holds one.
     */
    private static Map<String, Integer> lastSegmentPerWriter(AuditChainVerifier.HashedVerification hashed,
                                                             int from, int to) {
        Map<String, Integer> last = new LinkedHashMap<>();
        for (int i = from; i < to; i++) {
            for (String writer : hashed.segments().get(i).writers().keySet()) {
                last.put(writer, i);
            }
        }
        return last;
    }

    /**
     * The index of the first segment holding a writer's first expiring hashed record that neither has a
     * GENESIS previous hash nor follows an earlier anchor exactly (anchor sequence + 1 and anchor
     * hash equal to the record's previous hash), or -1. Such a start means the records before it
     * were deleted or cut other than by a purge.
     */
    private static int firstUnanchoredStart(AuditChainVerifier.HashedVerification hashed, int segmentCount,
                                            List<AuditCheckpoint> earlier) {
        String genesis = "0".repeat(64);
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < segmentCount; i++) {
            for (Map.Entry<String, AuditChainVerifier.WriterSpan> w : hashed.segments().get(i).writers().entrySet()) {
                AuditChainVerifier.HashedRecord first = w.getValue().first();
                if (!seen.add(w.getKey()) || genesis.equals(first.previousHash())) {
                    continue;
                }
                boolean follows = earlier.stream().anyMatch(a -> a.instanceId().equals(w.getKey())
                        && a.sequence() + 1 == first.sequence() && a.headHash().equals(first.previousHash()));
                if (!follows) {
                    return i;
                }
            }
        }
        return -1;
    }

    /**
     * The lowest byte offset at which the report shows tampering or holds a record the replay did not hash,
     * or -1 if it shows none.
     */
    private static long firstBadOffset(AuditChainVerifier.HashedVerification hashed) {
        AuditChainVerifier.VerificationReport report = hashed.report();
        long bad = Long.MAX_VALUE;
        for (AuditChainVerifier.WriterResult w : report.writers()) {
            if (w.broken()) {
                bad = Math.min(bad, w.firstBreak().map(AuditChainVerifier.Break::byteOffset).orElse(0L));
            }
        }
        for (AuditChainVerifier.StructuralAnomaly a : report.anomalies()) {
            // A duplicate sequence is not counted as a break by the verifier, but purge must not erase it: a
            // rewritten sequence number looks exactly like one, and the break that follows is blamed on a
            // later record, which would let the altered day be deleted. The verifier reports a duplicate at
            // the FIRST copy's offset, which this clause refuses from; the second copy is an unhashed
            // complete record, which the clause below refuses from.
            if (a.type().isBreak() || a.type() == AuditChainVerifier.AnomalyType.DUPLICATE_SEQUENCE) {
                bad = Math.min(bad, a.primaryOffset());
            }
        }
        // A line that parses as a complete record but was never hashed is not trusted: a crash fragment
        // does not parse and is not counted here.
        if (hashed.firstUnhashedCompleteOffset().isPresent()) {
            bad = Math.min(bad, hashed.firstUnhashedCompleteOffset().getAsLong());
        }
        return bad == Long.MAX_VALUE ? -1 : bad;
    }

    /** Anchors for one day, segments {@code [from, to)}: each writer's last hashed record across the day. */
    private List<AuditCheckpoint> anchorsFor(AuditChainVerifier.HashedVerification hashed, int from, int to,
                                             LocalDate segmentDate) {
        Map<String, AuditChainVerifier.HashedRecord> lastPerWriter = new LinkedHashMap<>();
        for (int i = from; i < to; i++) {
            hashed.segments().get(i).writers().forEach((id, span) -> lastPerWriter.put(id, span.last()));
        }
        List<AuditCheckpoint> result = new ArrayList<>();
        lastPerWriter.forEach((id, last) -> result.add(new AuditCheckpoint(AuditCheckpoint.Kind.RETENTION_ANCHOR, id,
                last.sequence(), last.eventHash(), clock.instant(), segmentDate)));
        return result;
    }

    /**
     * The link and shutdown checks across the end of each expiring day. One task per (day, writer), fed the
     * segments after the day in order: first the rest of the expiring ones, then, only while some writer is
     * still unresolved, the retained ones.
     */
    private static final class BoundaryScan {

        /** What a task does with the records it is fed. */
        private enum State { INIT, AWAIT_SHUTDOWN_RECORD, WATCH, DONE }

        /** A refused day: where it starts in the expiring list, and the message. */
        record Refusal(int dayStart, String message) {
        }

        /**
         * One writer's last hashed record {@code (lSequence, lHash)} in the day made of expiring segments
         * {@code [dayStart, dayEnd)}, held by segment {@code lSegment}.
         */
        static final class Task {
            final int dayStart;
            final int dayEnd;
            final String instanceId;
            final long lSequence;
            final String lHash;
            final int lSegment;
            final Shutdown shutdown;
            State state = State.INIT;
            boolean nextSeen;
            long startSequence;
            String chainHead;

            Task(int dayStart, int dayEnd, String instanceId, long lSequence, String lHash, int lSegment,
                 Shutdown shutdown) {
                this.dayStart = dayStart;
                this.dayEnd = dayEnd;
                this.instanceId = instanceId;
                this.lSequence = lSequence;
                this.lHash = lHash;
                this.lSegment = lSegment;
                this.shutdown = shutdown;
            }
        }

        private final List<Path> paths = new ArrayList<>();
        private final int retainedFrom;
        private final AuditChainVerifier.SegmentReader reader;
        private final List<Long> starts;
        private final Map<String, Map<Long, AuditChainVerifier.HashedRecord>> probed;
        private final boolean partial;
        /** Expiring segments from here on are kept for their own reasons and reported where they are. */
        private final int keptFrom;
        private int refusedDay = Integer.MAX_VALUE;
        private Refusal refusal;

        BoundaryScan(List<Path> expiring, List<Path> retained, AuditChainVerifier.SegmentReader reader,
                     List<Long> starts, Map<String, Map<Long, AuditChainVerifier.HashedRecord>> probed,
                     boolean partial, int keptFrom) {
            this.paths.addAll(expiring);
            this.retainedFrom = expiring.size();
            this.paths.addAll(retained);
            this.reader = reader;
            this.starts = starts;
            this.probed = probed;
            this.partial = partial;
            this.keptFrom = keptFrom;
        }

        /** The refusal of the lowest refused day, or {@code null} if every task resolves. */
        Refusal run(List<Task> tasks) {
            List<Task> live = new ArrayList<>(tasks);
            for (Task t : tasks) {
                if (t.shutdown != null && t.shutdown.conflict()) {
                    refuse(live, t, shutdownMessage(t, paths.get(t.lSegment)), t.dayStart);
                } else if (t.shutdown != null) {
                    t.startSequence = Math.max(t.lSequence, t.shutdown.sequence());
                    t.chainHead = t.lSequence >= t.shutdown.sequence() ? t.lHash : null;
                }
            }
            boolean boundaryDone = false;
            boolean scannedToEnd = true;
            for (int p = 0; p < paths.size(); p++) {
                if (p >= retainedFrom && !boundaryDone) {
                    boundaryDone = true;
                    atBoundary(live);
                }
                if (live.stream().noneMatch(BoundaryScan::needsRecords)) {
                    scannedToEnd = false;
                    break;
                }
                Set<String> wanted = new LinkedHashSet<>();
                for (Task t : live) {
                    if (t.dayEnd <= p && t.state != State.DONE) {
                        wanted.add(t.instanceId);
                    }
                }
                if (wanted.isEmpty()) {
                    continue;
                }
                List<AuditChainVerifier.BoundaryRecord> records;
                try {
                    records = AuditChainVerifier.boundaryRecords(paths.get(p), reader, wanted);
                } catch (IOException e) {
                    throw new RetentionException(ANCHOR_FAILED + ": could not read " + paths.get(p), e);
                }
                for (AuditChainVerifier.BoundaryRecord r : records) {
                    for (Task t : new ArrayList<>(live)) {
                        if (t.dayEnd <= p && t.instanceId.equals(r.instanceId()) && live.contains(t)) {
                            feed(live, t, r, p);
                        }
                    }
                }
            }
            if (!boundaryDone) {
                atBoundary(live);
            }
            if (scannedToEnd) {
                for (Task t : new ArrayList<>(live)) {
                    if (t.state == State.INIT && t.shutdown == null) {
                        t.state = State.DONE; // the residual: a writer with no SHUTDOWN and no later record
                    } else if (t.state == State.INIT || t.state == State.AWAIT_SHUTDOWN_RECORD) {
                        // The instance shut down after records that are no longer there.
                        refuse(live, t, shutdownMessage(t, paths.get(t.lSegment)), t.dayStart);
                    }
                }
            }
            return refusal;
        }

        private static boolean needsRecords(Task t) {
            return t.state == State.INIT || t.state == State.AWAIT_SHUTDOWN_RECORD;
        }

        /** Between the expiring and the retained segments: the shutdown stop for writers with no next record. */
        private void atBoundary(List<Task> live) {
            for (Task t : new ArrayList<>(live)) {
                if (t.state != State.INIT || t.shutdown == null || t.lSequence < t.shutdown.sequence()) {
                    continue;
                }
                Shutdown s = t.shutdown;
                if (t.lSequence == s.sequence()) {
                    if (!t.lHash.equals(s.headHash())) {
                        refuse(live, t, shutdownMessage(t, paths.get(t.lSegment)), t.dayStart);
                        continue;
                    }
                } else {
                    AuditChainVerifier.HashedRecord above = probed.getOrDefault(t.instanceId, Map.of())
                            .get(s.sequence() + 1);
                    if (above == null) {
                        if (!partial) {
                            refuse(live, t, shutdownMessage(t, paths.get(t.lSegment)), t.dayStart);
                            continue;
                        }
                    } else if (!above.previousHash().equals(s.headHash())) {
                        refuse(live, t, shutdownMessage(t, paths.get(segmentAt(starts, above.byteOffset()))),
                                t.dayStart);
                        continue;
                    }
                }
                t.state = State.WATCH;
            }
        }

        private void feed(List<Task> live, Task t, AuditChainVerifier.BoundaryRecord r, int segmentIndex) {
            Path segment = paths.get(segmentIndex);
            if (t.state == State.DONE) {
                return;
            }
            if (!t.nextSeen) {
                t.nextSeen = true;
                if (!r.previousHash().equals(t.lHash)) {
                    if (t.shutdown == null && segmentIndex > keptFrom) {
                        // A segment between the day and this record is kept for a break of its own, and holds
                        // whatever should have linked; the verifier reports it there, so the day can go.
                        t.state = State.DONE;
                        return;
                    }
                    refuse(live, t, CHAIN_UNVERIFIED + ": writer " + t.instanceId + "'s next record after "
                            + paths.get(t.lSegment).getFileName() + " does not chain from the last record verified "
                            + "there, so records between them were deleted or altered; it and every later expired "
                            + "segment were not purged, because purge would erase the evidence", t.dayStart);
                    return;
                }
                if (t.shutdown == null) {
                    t.state = State.DONE;
                    return;
                }
                t.state = t.lSequence >= t.shutdown.sequence() ? State.WATCH : State.AWAIT_SHUTDOWN_RECORD;
            }
            Shutdown s = t.shutdown;
            if (t.state == State.AWAIT_SHUTDOWN_RECORD) {
                if (r.sequence() == s.sequence()) {
                    if (r.endsInCarriageReturn() || !r.eventHash().equals(s.headHash())) {
                        refuse(live, t, shutdownMessage(t, segment), t.dayStart);
                        return;
                    }
                    t.chainHead = s.headHash();
                    t.state = State.WATCH;
                } else if (r.sequence() > s.sequence()) {
                    refuse(live, t, shutdownMessage(t, segment), t.dayStart);
                }
                return;
            }
            if (t.state == State.WATCH && r.sequence() > t.startSequence) {
                if (r.previousHash().equals(t.chainHead)) {
                    t.chainHead = r.eventHash();
                } else {
                    refuse(live, t, shutdownMessage(t, segment), t.dayStart);
                }
            }
        }

        private void refuse(List<Task> live, Task t, String message, int dayStart) {
            if (dayStart < refusedDay) {
                refusedDay = dayStart;
                refusal = new Refusal(dayStart, message);
            }
            live.removeIf(other -> other.dayStart >= refusedDay);
        }

        private static String shutdownMessage(Task t, Path segment) {
            return CHAIN_UNVERIFIED + ": writer " + t.instanceId + "'s SHUTDOWN checkpoint at sequence "
                    + t.shutdown.sequence() + " in the checkpoint store does not match its records in "
                    + segment.getFileName() + "; it and every later expired segment were not purged. Compare the "
                    + "checkpoint store (dataprism.audit.checkpoint.file-path) with the audit directory before purging.";
        }
    }

    /** A purge that could not complete. */
    public static final class RetentionException extends RuntimeException {

        RetentionException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}

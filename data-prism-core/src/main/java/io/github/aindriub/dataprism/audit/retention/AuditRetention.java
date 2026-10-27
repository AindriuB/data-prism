package io.github.aindriub.dataprism.audit.retention;

import io.github.aindriub.dataprism.audit.AuditChainKey;
import io.github.aindriub.dataprism.audit.AuditCheckpoint;
import io.github.aindriub.dataprism.audit.AuditCheckpointSink;
import io.github.aindriub.dataprism.audit.AuditEvent;
import io.github.aindriub.dataprism.audit.format.AuditRecordFormat;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
 * sequence and a hash. Anchors are written first, and if any anchor write
 * fails nothing is deleted, with one exception: a day an earlier purge anchored
 * and only part deleted is finished first. Its remaining segments are removed
 * only if, verified on their own (never joined to the next day), they show no break,
 * no structural anomaly and no unterminated tail, and each writer's first remaining
 * record starts at GENESIS or follows an earlier anchor; otherwise the normal path
 * refuses. This does not catch every edit to an anchored day: lines the verifier
 * skips unhashed, such as interrupted-write fragments, are a known gap. A failure
 * after that names the finished day in its message.
 *
 * <p>Trust assumption: the retention anchor store is trusted. Anyone who can
 * write to it can vouch for a chain's start and can make a day look partly
 * purged; that was already so for the anchors that vouch for a chain's start.
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
        try (Stream<Path> files = Files.list(directory)) {
            files.filter(Files::isRegularFile).forEach(f -> {
                LocalDate date = SegmentedFileAuditSink.segmentDate(f);
                if (date != null && date.isBefore(cutoff) && date.isBefore(today)) {
                    segments.add(f);
                }
            });
        } catch (IOException e) {
            throw new RetentionException(ANCHOR_FAILED + ": could not list " + directory, e);
        }

        segments.sort(SegmentedFileAuditSink.SEGMENT_ORDER);
        List<Path> deleted = new ArrayList<>();
        // A day an earlier run anchored and then failed to finish deleting is finished first, once its remainder verifies on its own.
        int partial = partlyPurgedDayLength(segments);
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
        if (!segments.isEmpty()) {
            List<Long> starts = new ArrayList<>();
            AuditChainVerifier.VerificationReport report;
            try {
                report = AuditChainVerifier.verifySegments(segments, starts, chainKeys, allowUnkeyedWriters);
            } catch (IOException e) {
                throw new RetentionException(ANCHOR_FAILED + ": could not read the expiring segments", e);
            }
            long bad = firstBadOffset(report);
            if (bad >= 0) {
                verified = 0;
                for (int i = 0; i < starts.size(); i++) {
                    if (starts.get(i) <= bad) {
                        verified = i;
                    }
                }
                unverified = CHAIN_UNVERIFIED + ": the chain in " + segments.get(verified).getFileName()
                        + " does not verify, so it and every later expired segment were not purged; "
                        + "investigate it before purging, because purge would erase the evidence";
            }
        }

        if (verified > 0) {
            int unanchored = firstUnanchoredStart(segments.subList(0, verified), earlierAnchors(deletedNote));
            if (unanchored >= 0) {
                verified = unanchored;
                unverified = CHAIN_UNVERIFIED + ": a writer's first expiring record in "
                        + segments.get(verified).getFileName() + " neither starts at GENESIS nor follows an earlier "
                        + "retention anchor exactly, so records before it were deleted or cut by hand; it and every "
                        + "later expired segment were not purged, because purge would erase the evidence";
            }
        }

        // A day's segments are one unit: purge all of them or none, so a refusal inside a day keeps the day.
        while (verified > 0 && verified < segments.size()
                && SegmentedFileAuditSink.segmentDate(segments.get(verified - 1))
                .equals(SegmentedFileAuditSink.segmentDate(segments.get(verified)))) {
            verified--;
        }

        List<AuditCheckpoint> toAnchor = new ArrayList<>();
        int from = 0;
        while (from < verified) {
            LocalDate day = SegmentedFileAuditSink.segmentDate(segments.get(from));
            int to = from;
            while (to < verified && day.equals(SegmentedFileAuditSink.segmentDate(segments.get(to)))) {
                to++;
            }
            toAnchor.addAll(anchorsFor(segments.subList(from, to), day));
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
     * deleted, or 0. The oldest remaining expired day is such a day only if it has at least one parseable
     * record and every writer in it has a recorded retention anchor for this very day whose sequence is
     * at least that writer's last remaining record, and at least one writer's anchor is beyond it, which
     * proves records were deleted. A writer whose records all remain loses nothing. The anchors say the day
     * was once verified, not that its files are unchanged, so the remaining segments must then verify as
     * a chain on their own (never joined to the next day, under the same chain keys as the normal path)
     * and each writer's first remaining record must start at GENESIS or follow an earlier anchor. Anything else, including a missing or other-day
     * anchor, takes the normal verification path, which refuses what it cannot vouch for.
     */
    private int partlyPurgedDayLength(List<Path> segments) {
        if (segments.isEmpty()) {
            return 0;
        }
        LocalDate day = SegmentedFileAuditSink.segmentDate(segments.get(0));
        int n = 0;
        while (n < segments.size() && day.equals(SegmentedFileAuditSink.segmentDate(segments.get(n)))) {
            n++;
        }
        Map<String, Long> lastRemaining = new LinkedHashMap<>();
        for (Path segment : segments.subList(0, n)) {
            List<String> lines;
            try {
                lines = Files.readAllLines(segment, java.nio.charset.StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new RetentionException(ANCHOR_FAILED + ": could not read " + segment, e);
            }
            for (String line : lines) {
                if (line.isEmpty()) {
                    continue;
                }
                try {
                    AuditEvent event = AuditRecordFormat.parse(line);
                    lastRemaining.merge(event.instanceId(), event.sequence(), Math::max);
                } catch (RuntimeException e) {
                    // a torn fragment carries no chain position
                }
            }
        }
        if (lastRemaining.isEmpty()) {
            return 0;
        }
        List<AuditCheckpoint> earlier = earlierAnchors("nothing was deleted");
        boolean deletionProven = false;
        for (Map.Entry<String, Long> w : lastRemaining.entrySet()) {
            long best = -1;
            for (AuditCheckpoint a : earlier) {
                if (a.instanceId().equals(w.getKey()) && day.equals(a.segmentDate())) {
                    best = Math.max(best, a.sequence());
                }
            }
            if (best < w.getValue()) {
                return 0;
            }
            deletionProven |= best > w.getValue();
        }
        if (!deletionProven) {
            return 0;
        }
        // The anchors only say the day was once verified. The files may have been edited since, so the
        // remainder is verified on its own (never joined to the next day) before it is deleted. That is a
        // check, not a proof: lines the verifier skips unhashed (interrupted-write fragments) are a known gap.
        List<Path> prefix = segments.subList(0, n);
        AuditChainVerifier.VerificationReport report;
        try {
            report = AuditChainVerifier.verifySegments(prefix, new ArrayList<>(), chainKeys, allowUnkeyedWriters);
        } catch (IOException e) {
            throw new RetentionException(ANCHOR_FAILED + ": could not read the expiring segments", e);
        }
        // Refuse on any break, structural anomaly (a duplicate sequence, say: a rewritten sequence number
        // shows up as one), unterminated tail (its last record is never hashed) or unanchored start. A clean
        // partial deletion leaves none of them.
        if (firstBadOffset(report) >= 0 || report.hasStructuralAnomaly() || report.tail().isPresent()
                || firstUnanchoredStart(prefix, earlier) >= 0) {
            return 0;
        }
        return n;
    }

    private List<AuditCheckpoint> earlierAnchors(String deletedNote) {
        try {
            return anchors.retentionAnchors();
        } catch (RuntimeException e) {
            throw new RetentionException(CHAIN_UNVERIFIED + ": could not read the earlier retention anchors, so "
                    + "the start of the expiring chain cannot be checked; " + deletedNote, e);
        }
    }

    /**
     * The index of the first segment holding a writer's first expiring record that neither has a
     * GENESIS previous hash nor follows an earlier anchor exactly (anchor sequence + 1 and anchor
     * hash equal to the record's previous hash), or -1. Such a start means the records before it
     * were deleted or cut other than by a purge.
     */
    private static int firstUnanchoredStart(List<Path> segments, List<AuditCheckpoint> earlier) {
        String genesis = "0".repeat(64);
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (int i = 0; i < segments.size(); i++) {
            List<String> lines;
            try {
                lines = Files.readAllLines(segments.get(i), java.nio.charset.StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new RetentionException(ANCHOR_FAILED + ": could not read " + segments.get(i), e);
            }
            for (String line : lines) {
                if (line.isEmpty()) {
                    continue;
                }
                AuditEvent event;
                try {
                    event = AuditRecordFormat.parse(line);
                } catch (RuntimeException e) {
                    continue;
                }
                if (!seen.add(event.instanceId()) || genesis.equals(event.previousHash())) {
                    continue;
                }
                boolean follows = earlier.stream().anyMatch(a -> a.instanceId().equals(event.instanceId())
                        && a.sequence() + 1 == event.sequence() && a.headHash().equals(event.previousHash()));
                if (!follows) {
                    return i;
                }
            }
        }
        return -1;
    }

    /** The lowest byte offset at which the report shows tampering, or -1 if it shows none. */
    private static long firstBadOffset(AuditChainVerifier.VerificationReport report) {
        long bad = Long.MAX_VALUE;
        for (AuditChainVerifier.WriterResult w : report.writers()) {
            if (w.broken()) {
                bad = Math.min(bad, w.firstBreak().map(AuditChainVerifier.Break::byteOffset).orElse(0L));
            }
        }
        for (AuditChainVerifier.StructuralAnomaly a : report.anomalies()) {
            // A duplicate sequence is not counted as a break by the verifier, but purge must not erase it: a
            // rewritten sequence number looks exactly like one, and the break that follows is blamed on a
            // later record, which would let the altered day be deleted.
            if (a.type().isBreak() || a.type() == AuditChainVerifier.AnomalyType.DUPLICATE_SEQUENCE) {
                bad = Math.min(bad, a.primaryOffset());
            }
        }
        return bad == Long.MAX_VALUE ? -1 : bad;
    }

    /** Anchors for one day: each writer's last record across all of the day's segments. */
    private List<AuditCheckpoint> anchorsFor(List<Path> daySegments, LocalDate segmentDate) {
        Map<String, AuditEvent> lastPerWriter = new LinkedHashMap<>();
        List<String> lines = new ArrayList<>();
        for (Path segment : daySegments) {
            try {
                lines.addAll(Files.readAllLines(segment, java.nio.charset.StandardCharsets.UTF_8));
            } catch (IOException e) {
                throw new RetentionException(ANCHOR_FAILED + ": could not read " + segment, e);
            }
        }
        for (String line : lines) {
            if (line.isEmpty()) {
                continue;
            }
            AuditEvent event;
            try {
                event = AuditRecordFormat.parse(line);
            } catch (RuntimeException e) {
                continue; // a torn fragment carries no chain position to anchor
            }
            lastPerWriter.merge(event.instanceId(), event,
                    (old, next) -> next.sequence() >= old.sequence() ? next : old);
        }
        List<AuditCheckpoint> result = new ArrayList<>();
        for (AuditEvent last : lastPerWriter.values()) {
            result.add(new AuditCheckpoint(AuditCheckpoint.Kind.RETENTION_ANCHOR, last.instanceId(),
                    last.sequence(), last.eventHash(), clock.instant(), segmentDate));
        }
        return result;
    }

    /** A purge that could not complete. */
    public static final class RetentionException extends RuntimeException {

        RetentionException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}

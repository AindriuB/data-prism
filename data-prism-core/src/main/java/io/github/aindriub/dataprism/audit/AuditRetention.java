package io.github.aindriub.dataprism.audit;

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
 * fails nothing is deleted.
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

    public AuditRetention(Path directory, Period retention, AuditCheckpointSink anchors, Clock clock) {
        this(directory, retention, anchors, clock, false);
    }

    public AuditRetention(Path directory, Period retention, AuditCheckpointSink anchors, Clock clock,
                          boolean allowBelowMinimum) {
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
    private static boolean canBeShorterThanSixMonths(Period retention) {
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

        Map<LocalDate, Path> expired = new java.util.TreeMap<>();
        try (Stream<Path> files = Files.list(directory)) {
            files.filter(Files::isRegularFile).forEach(f -> {
                LocalDate date = SegmentedFileAuditSink.segmentDate(f);
                if (date != null && date.isBefore(cutoff) && date.isBefore(today)) {
                    expired.put(date, f);
                }
            });
        } catch (IOException e) {
            throw new RetentionException(ANCHOR_FAILED + ": could not list " + directory, e);
        }

        List<LocalDate> dates = new ArrayList<>(expired.keySet());
        List<Path> segments = new ArrayList<>(expired.values());
        int verified = segments.size();
        String unverified = null;
        if (!segments.isEmpty()) {
            List<Long> starts = new ArrayList<>();
            AuditChainVerifier.VerificationReport report;
            try {
                report = AuditChainVerifier.verifySegments(segments, starts);
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
            int unanchored = firstUnanchoredStart(segments.subList(0, verified), earlierAnchors());
            if (unanchored >= 0) {
                verified = unanchored;
                unverified = CHAIN_UNVERIFIED + ": a writer's first expiring record in "
                        + segments.get(verified).getFileName() + " neither starts at GENESIS nor follows an earlier "
                        + "retention anchor exactly, so records before it were deleted or cut by hand; it and every "
                        + "later expired segment were not purged, because purge would erase the evidence";
            }
        }

        List<AuditCheckpoint> toAnchor = new ArrayList<>();
        for (int i = 0; i < verified; i++) {
            toAnchor.addAll(anchorsFor(segments.get(i), dates.get(i)));
        }
        for (AuditCheckpoint anchor : toAnchor) {
            try {
                anchors.record(anchor);
            } catch (RuntimeException e) {
                throw new RetentionException(ANCHOR_FAILED + ": could not record the retention anchor for writer "
                        + anchor.instanceId() + " at sequence " + anchor.sequence() + "; nothing was deleted", e);
            }
        }

        List<Path> deleted = new ArrayList<>();
        for (Path segment : segments.subList(0, verified)) {
            try {
                Files.delete(segment);
            } catch (IOException e) {
                throw new RetentionException("AUDIT_RETENTION_DELETE_FAILED: could not delete " + segment, e);
            }
            deleted.add(segment);
        }
        if (unverified != null) {
            throw new RetentionException(unverified, null);
        }
        return deleted;
    }

    private List<AuditCheckpoint> earlierAnchors() {
        try {
            return anchors.retentionAnchors();
        } catch (RuntimeException e) {
            throw new RetentionException(CHAIN_UNVERIFIED + ": could not read the earlier retention anchors, so "
                    + "the start of the expiring chain cannot be checked; nothing was deleted", e);
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
            if (a.type() == AuditChainVerifier.AnomalyType.UNPARSEABLE_RECORD
                    || a.type() == AuditChainVerifier.AnomalyType.FIELD_COUNT_MISMATCH) {
                bad = Math.min(bad, a.primaryOffset());
            }
        }
        return bad == Long.MAX_VALUE ? -1 : bad;
    }

    private List<AuditCheckpoint> anchorsFor(Path segment, LocalDate segmentDate) {
        Map<String, AuditEvent> lastPerWriter = new LinkedHashMap<>();
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

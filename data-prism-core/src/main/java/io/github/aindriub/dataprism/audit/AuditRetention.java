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

    private static final LocalDate MINIMUM_PROBE_START = LocalDate.of(2025, 1, 1);
    private static final LocalDate MINIMUM_PROBE_END = LocalDate.of(2025, 7, 1);

    private final Path directory;
    private final Period retention;
    private final AuditCheckpointSink anchors;
    private final Clock clock;

    public AuditRetention(Path directory, Period retention, AuditCheckpointSink anchors, Clock clock) {
        this(directory, retention, anchors, clock, false);
    }

    public AuditRetention(Path directory, Period retention, AuditCheckpointSink anchors, Clock clock,
                          boolean allowBelowMinimum) {
        this.directory = Objects.requireNonNull(directory, "directory");
        this.retention = Objects.requireNonNull(retention, "retention");
        this.anchors = Objects.requireNonNull(anchors, "anchors");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (!allowBelowMinimum && MINIMUM_PROBE_START.plus(retention).isBefore(MINIMUM_PROBE_END)) {
            throw new IllegalArgumentException(BELOW_MINIMUM + ": retention " + retention
                    + " is shorter than six months. EU AI Act Art. 19 requires at least six months unless "
                    + "other Union or national law provides otherwise; pass the explicit retention override "
                    + "only if that applies to you.");
        }
    }

    /**
     * Deletes every segment dated strictly before today (UTC, by the clock) minus the retention
     * period, never today's, and returns the deleted files in date order.
     *
     * @throws RetentionException if an anchor cannot be recorded (nothing is deleted) or a
     *                            file cannot be deleted (anchors already written stay valid)
     */
    public List<Path> purge() {
        LocalDate today = clock.instant().atZone(ZoneOffset.UTC).toLocalDate();
        LocalDate cutoff = today.minus(retention);

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

        List<AuditCheckpoint> toAnchor = new ArrayList<>();
        for (Path segment : expired.values()) {
            toAnchor.addAll(anchorsFor(segment));
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
        for (Path segment : expired.values()) {
            try {
                Files.delete(segment);
            } catch (IOException e) {
                throw new RetentionException("AUDIT_RETENTION_DELETE_FAILED: could not delete " + segment, e);
            }
            deleted.add(segment);
        }
        return deleted;
    }

    private List<AuditCheckpoint> anchorsFor(Path segment) {
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
                    last.sequence(), last.eventHash(), clock.instant()));
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

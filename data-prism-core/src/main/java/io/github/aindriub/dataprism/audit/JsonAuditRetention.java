package io.github.aindriub.dataprism.audit;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.time.Period;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Deletes whole expired day-segments of the JSON projection, {@code audit-YYYY-MM-DD.ndjson}, as
 * written by {@link SegmentedJsonAuditSink}. A segment goes if its date is strictly before today
 * (UTC, by the clock) minus the retention period; today's segment never does.
 *
 * <p>The six-month floor and its override are {@link AuditRetention}'s: a retention that can be
 * shorter than six calendar months is refused with {@code AUDIT_RETENTION_BELOW_MINIMUM} unless
 * {@code allowBelowMinimum} is passed. The projection is not chained, so unlike
 * {@link AuditRetention} this writes no anchors and verifies nothing; it never touches the
 * authoritative {@code .log} segments.
 */
public final class JsonAuditRetention {

    private static final Pattern SEGMENT_NAME = Pattern.compile("audit-(\\d{4}-\\d{2}-\\d{2})\\.ndjson");

    private final Path directory;
    private final Period retention;
    private final Clock clock;
    private final boolean allowBelowMinimum;

    public JsonAuditRetention(Path directory, Period retention, Clock clock) {
        this(directory, retention, clock, false);
    }

    public JsonAuditRetention(Path directory, Period retention, Clock clock, boolean allowBelowMinimum) {
        this.directory = Objects.requireNonNull(directory, "directory");
        this.retention = Objects.requireNonNull(retention, "retention");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.allowBelowMinimum = allowBelowMinimum;
        if (!allowBelowMinimum && AuditRetention.canBeShorterThanSixMonths(retention)) {
            throw new IllegalArgumentException(AuditRetention.BELOW_MINIMUM + ": retention " + retention
                    + " is shorter than six months. EU AI Act Art. 19 requires at least six months unless "
                    + "other Union or national law provides otherwise; pass the explicit retention override "
                    + "only if that applies to you.");
        }
    }

    /** Deletes the expired segments and returns them in date order. */
    public List<Path> purge() {
        LocalDate today = clock.instant().atZone(ZoneOffset.UTC).toLocalDate();
        LocalDate cutoff = AuditRetention.cutoff(today, retention, allowBelowMinimum);

        Map<LocalDate, Path> expired = new TreeMap<>();
        try (Stream<Path> files = Files.list(directory)) {
            files.filter(Files::isRegularFile).forEach(f -> {
                LocalDate date = segmentDate(f);
                if (date != null && date.isBefore(cutoff) && date.isBefore(today)) {
                    expired.put(date, f);
                }
            });
        } catch (IOException e) {
            throw new AuditRetention.RetentionException("AUDIT_RETENTION_DELETE_FAILED: could not list "
                    + directory, e);
        }

        List<Path> deleted = new ArrayList<>();
        for (Path segment : expired.values()) {
            try {
                Files.delete(segment);
            } catch (IOException e) {
                throw new AuditRetention.RetentionException("AUDIT_RETENTION_DELETE_FAILED: could not delete "
                        + segment, e);
            }
            deleted.add(segment);
        }
        return deleted;
    }

    private static LocalDate segmentDate(Path file) {
        Matcher m = SEGMENT_NAME.matcher(file.getFileName().toString());
        if (!m.matches()) {
            return null;
        }
        try {
            return LocalDate.parse(m.group(1));
        } catch (RuntimeException e) {
            return null;
        }
    }
}

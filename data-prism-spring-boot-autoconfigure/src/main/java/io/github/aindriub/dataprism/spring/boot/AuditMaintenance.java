package io.github.aindriub.dataprism.spring.boot;

import io.github.aindriub.dataprism.audit.AuditRecorder;
import io.github.aindriub.dataprism.audit.retention.AuditRetention;
import io.github.aindriub.dataprism.core.metrics.Metric;
import io.github.aindriub.dataprism.core.metrics.PrivacyMetrics;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Scheduled audit upkeep: periodic checkpoints and the daily retention purge. A failure is logged
 * and the schedule carries on, because a thrown task would silently cancel every later run. A
 * failed checkpoint still refuses audited calls until one succeeds (owner decision D7), and a
 * failed purge deletes nothing, and is made visible: a counter named for its refusal code, and an
 * {@code auditIntegrity} health status of DOWN carrying only the code and a segment date, until a
 * later purge succeeds. Nothing else from the failure (paths, writer ids) is exposed.
 */
final class AuditMaintenance implements AutoCloseable {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(AuditMaintenance.class);
    private static final Duration FIRST_CHECKPOINT_AFTER = Duration.ofSeconds(10);

    private static final Pattern CODE = Pattern.compile("^([A-Z][A-Z_]+):");
    private static final Pattern SEGMENT = Pattern.compile("audit-(\\d{4}-\\d{2}-\\d{2})\\.log");

    /** A failed purge's code and, when the failure names a segment, its date. */
    private record Failure(String code, String segmentDate) { }

    private final ScheduledExecutorService scheduler;
    private final AuditRetention retention;
    private final PrivacyMetrics metrics;
    private volatile Failure failure;

    AuditMaintenance(AuditRecorder recorder, AuditRetention retention, Duration interval, PrivacyMetrics metrics) {
        this.retention = retention;
        this.metrics = metrics;
        if (recorder == null && retention == null) {
            scheduler = null;
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "dataprism-audit-maintenance");
            t.setDaemon(true);
            return t;
        });
        if (retention != null) {
            purge();
            scheduler.scheduleWithFixedDelay(this::purge, 24, 24, TimeUnit.HOURS);
        }
        if (recorder != null) {
            // Soon after boot, so the window before the first PERIODIC anchor is short.
            long first = Math.min(interval.toMillis(), FIRST_CHECKPOINT_AFTER.toMillis());
            scheduler.scheduleWithFixedDelay(() -> checkpoint(recorder), first, interval.toMillis(),
                    TimeUnit.MILLISECONDS);
        }
    }

    /** Runs one purge; a failure is logged, counted and reported as health DOWN, never rethrown. */
    void purge() {
        try {
            retention.purge();
            failure = null;
        } catch (RuntimeException e) {
            String message = e.getMessage() == null ? "" : e.getMessage();
            Matcher code = CODE.matcher(message);
            Matcher date = SEGMENT.matcher(message);
            Failure f = new Failure(code.find() ? code.group(1) : "AUDIT_RETENTION_FAILED",
                    date.find() ? date.group(1) : null);
            failure = f;
            metrics.increment(metricFor(f.code()));
            LOG.error("audit retention purge did not complete ({}); nothing further was deleted", f.code(), e);
        }
    }

    private static Metric metricFor(String code) {
        return switch (code) {
            case AuditRetention.CHAIN_UNVERIFIED -> Metric.AUDIT_RETENTION_CHAIN_UNVERIFIED;
            case AuditRetention.ANCHOR_FAILED -> Metric.AUDIT_RETENTION_ANCHOR_FAILED;
            case "AUDIT_RETENTION_DELETE_FAILED" -> Metric.AUDIT_RETENTION_DELETE_FAILED;
            default -> Metric.AUDIT_RETENTION_FAILED;
        };
    }

    /** The failed purge's code, or null if the last purge succeeded. Kept free of Actuator types. */
    String failureCode() {
        Failure f = failure;
        return f == null ? null : f.code();
    }

    /** The date of the first refused segment, or null if none is named. */
    String failureSegmentDate() {
        Failure f = failure;
        return f == null ? null : f.segmentDate();
    }

    private static void checkpoint(AuditRecorder recorder) {
        try {
            recorder.checkpoint();
        } catch (RuntimeException e) {
            LOG.error("periodic audit checkpoint failed; audited calls are refused until one succeeds", e);
        }
    }

    @Override
    public void close() throws InterruptedException {
        if (scheduler != null) {
            scheduler.shutdown();
            scheduler.awaitTermination(10, TimeUnit.SECONDS);
        }
    }
}

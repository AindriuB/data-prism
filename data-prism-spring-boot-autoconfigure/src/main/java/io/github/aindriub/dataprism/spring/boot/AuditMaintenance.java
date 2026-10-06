package io.github.aindriub.dataprism.spring.boot;

import io.github.aindriub.dataprism.audit.AuditRecorder;
import io.github.aindriub.dataprism.audit.AuditRetention;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Scheduled audit upkeep: periodic checkpoints and the daily retention purge. A failure is logged
 * and the schedule carries on, because a thrown task would silently cancel every later run. A
 * failed checkpoint still refuses audited calls until one succeeds (owner decision D7), and a
 * failed purge deletes nothing.
 */
final class AuditMaintenance implements AutoCloseable {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(AuditMaintenance.class);
    private static final Duration FIRST_CHECKPOINT_AFTER = Duration.ofSeconds(10);

    private final ScheduledExecutorService scheduler;

    AuditMaintenance(AuditRecorder recorder, AuditRetention retention, Duration interval) {
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
            purge(retention);
            scheduler.scheduleWithFixedDelay(() -> purge(retention), 24, 24, TimeUnit.HOURS);
        }
        if (recorder != null) {
            // Soon after boot, so the window before the first PERIODIC anchor is short.
            long first = Math.min(interval.toMillis(), FIRST_CHECKPOINT_AFTER.toMillis());
            scheduler.scheduleWithFixedDelay(() -> checkpoint(recorder), first, interval.toMillis(),
                    TimeUnit.MILLISECONDS);
        }
    }

    private static void purge(AuditRetention retention) {
        try {
            retention.purge();
        } catch (RuntimeException e) {
            LOG.error("audit retention purge did not complete; nothing further was deleted", e);
        }
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

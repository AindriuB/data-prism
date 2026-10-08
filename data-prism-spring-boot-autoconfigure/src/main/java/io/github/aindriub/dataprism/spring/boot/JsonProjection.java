package io.github.aindriub.dataprism.spring.boot;

import io.github.aindriub.dataprism.audit.AuditEvent;
import io.github.aindriub.dataprism.audit.AuditSink;
import io.github.aindriub.dataprism.audit.retention.JsonAuditRetention;
import io.github.aindriub.dataprism.audit.sink.SegmentedJsonAuditSink;

import java.io.Closeable;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The JSON projection of the audit: writes each event to the {@link SegmentedJsonAuditSink} and
 * runs its purge, once now, then every 24 hours, as {@link AuditMaintenance} does for the native
 * segments. A failed purge is logged and never stops the native one. Closing is idempotent, so
 * both the owning {@code TeeAuditSink} and the context may close it.
 */
final class JsonProjection implements Closeable {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(JsonProjection.class);
    private final SegmentedJsonAuditSink json;
    private final java.util.concurrent.ScheduledExecutorService scheduler;
    private final AtomicBoolean closed = new AtomicBoolean();

    JsonProjection(SegmentedJsonAuditSink json, JsonAuditRetention retention) {
        this.json = json;
        scheduler = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "dataprism-audit-json-retention");
            t.setDaemon(true);
            return t;
        });
        purge(retention);
        scheduler.scheduleWithFixedDelay(() -> purge(retention), 24, 24, java.util.concurrent.TimeUnit.HOURS);
    }

    private static void purge(JsonAuditRetention retention) {
        try {
            retention.purge();
        } catch (RuntimeException e) {
            LOG.error("JSON audit projection purge did not complete; the native audit is unaffected", e);
        }
    }

    /**
     * The view a {@code TeeAuditSink} writes to and closes. The bean itself is deliberately not an
     * {@link AuditSink}: a second {@code AuditSink} bean would make the audit sink ambiguous.
     */
    AuditSink asSink() {
        return new Sink();
    }

    private final class Sink implements AuditSink, Closeable {
        @Override
        public void record(AuditEvent event) {
            json.record(event);
        }

        @Override
        public void close() throws IOException {
            JsonProjection.this.close();
        }
    }

    @Override
    public void close() throws IOException {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        scheduler.shutdown();
        try {
            scheduler.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        json.close();
    }
}

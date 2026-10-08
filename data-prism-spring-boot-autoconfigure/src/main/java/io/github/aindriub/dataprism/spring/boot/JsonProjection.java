package io.github.aindriub.dataprism.spring.boot;

import io.github.aindriub.dataprism.audit.AuditSink;
import io.github.aindriub.dataprism.audit.retention.JsonAuditRetention;
import io.github.aindriub.dataprism.audit.sink.SegmentedJsonAuditSink;

/**
 * Owns the close of both sinks of a JSON-projected audit sink, and runs the projection's purge:
 * once now, then every 24 hours, as {@link AuditMaintenance} does for the native segments. A
 * failed purge is logged and never stops the native one.
 */
final class JsonProjection implements org.springframework.beans.factory.DisposableBean {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(JsonProjection.class);
    private final AuditSink primary;
    private final SegmentedJsonAuditSink json;
    private final java.util.concurrent.ScheduledExecutorService scheduler;

    JsonProjection(AuditSink primary, SegmentedJsonAuditSink json, JsonAuditRetention retention) {
        this.primary = primary;
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

    @Override
    public void destroy() throws Exception {
        scheduler.shutdown();
        scheduler.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS);
        try {
            json.close();
        } finally {
            if (primary instanceof java.io.Closeable closeable) {
                closeable.close();
            }
        }
    }
}

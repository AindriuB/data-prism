package io.github.aindriub.dataprism.audit.sink;

import io.github.aindriub.dataprism.audit.AuditEvent;
import io.github.aindriub.dataprism.audit.AuditRecorder;
import io.github.aindriub.dataprism.audit.AuditSink;
import java.io.Closeable;
import java.io.IOException;
import java.util.Objects;

/**
 * Writes each event to the authoritative {@code primary} sink, then to a {@code projection}.
 *
 * <p>If the primary throws, the projection is not called and the exception propagates; the tee
 * stays usable, because {@link AuditRecorder} rolls its sequence back and the primary's own
 * poisoning decides whether it takes more writes. If the projection throws, the primary already
 * holds the event but the recorder will roll back and hand the same sequence number to the next
 * event. So the tee poisons itself: this and every later {@link #record} throws
 * {@code AUDIT_PROJECTION_FAILED} before touching the primary, until the process restarts. That
 * is what stops a second record being written under a sequence already on disk.
 *
 * <p>{@link #close()} closes the projection, then the primary, each only if it is {@link Closeable};
 * the primary is closed even if the projection's close throws. Idempotent: later calls do nothing.
 * A {@link #record} racing shutdown writes the primary first: a closed projection then poisons the tee
 * after the durable write, and a closed primary throws before the projection is called (fails closed).
 */
public final class TeeAuditSink implements AuditSink, Closeable {

    private final AuditSink primary;
    private final AuditSink projection;
    private volatile Throwable poisonedBy;
    private boolean closed;

    public TeeAuditSink(AuditSink primary, AuditSink projection) {
        this.primary = Objects.requireNonNull(primary, "primary");
        this.projection = Objects.requireNonNull(projection, "projection");
    }

    @Override
    public synchronized void record(AuditEvent event) {
        if (poisonedBy != null) {
            throw new ProjectionFailedException(poisonedBy);
        }
        primary.record(event);
        try {
            projection.record(event);
        } catch (RuntimeException e) {
            poisonedBy = e;
            throw new ProjectionFailedException(e);
        } catch (Throwable t) {
            // an Error, or a checked exception thrown sneakily, after the primary write leaves the same
            // sequence-reuse hazard; poison, then let the original throwable propagate unchanged
            poisonedBy = t;
            throw t;
        }
    }

    @Override
    public synchronized void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        try {
            if (projection instanceof Closeable closeable) {
                closeable.close();
            }
        } finally {
            if (primary instanceof Closeable closeable) {
                closeable.close();
            }
        }
    }

    /** The projection failed; the message is the code alone, and the cause carries the detail. */
    public static final class ProjectionFailedException extends RuntimeException {
        ProjectionFailedException(Throwable cause) {
            super("AUDIT_PROJECTION_FAILED", cause);
        }
    }
}

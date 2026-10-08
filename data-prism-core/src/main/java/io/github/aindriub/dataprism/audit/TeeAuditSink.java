package io.github.aindriub.dataprism.audit;

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
 */
public final class TeeAuditSink implements AuditSink {

    private final AuditSink primary;
    private final AuditSink projection;
    private volatile Throwable poisonedBy;

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

    /** The projection failed; the message is the code alone, and the cause carries the detail. */
    public static final class ProjectionFailedException extends RuntimeException {
        ProjectionFailedException(Throwable cause) {
            super("AUDIT_PROJECTION_FAILED", cause);
        }
    }
}

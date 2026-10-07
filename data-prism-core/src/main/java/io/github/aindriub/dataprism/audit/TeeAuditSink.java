package io.github.aindriub.dataprism.audit;

/** Stub: red phase. */
public final class TeeAuditSink implements AuditSink {

    public TeeAuditSink(AuditSink primary, AuditSink projection) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void record(AuditEvent event) {
        throw new UnsupportedOperationException();
    }

    public static final class ProjectionFailedException extends RuntimeException {
        ProjectionFailedException(Throwable cause) {
            super("AUDIT_PROJECTION_FAILED", cause);
        }
    }
}

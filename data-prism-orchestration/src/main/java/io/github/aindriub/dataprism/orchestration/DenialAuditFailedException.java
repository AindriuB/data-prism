package io.github.aindriub.dataprism.orchestration;

/**
 * Thrown by the orchestrator when the {@code DENY} audit event for a refused call could not be written.
 * The call is refused either way; what is lost is the record, so the caller must not answer as though
 * the denial had been audited.
 *
 * <p>Carries only the stable code {@link #CODE} in its message. The sink's failure is never the
 * {@link #getCause() cause}, because a sink's message can name an operator file path and a cause
 * chain is walked and rendered by some transports. It is kept as a
 * {@link Throwable#addSuppressed(Throwable) suppressed} exception for the server's own log.
 */
public final class DenialAuditFailedException extends RuntimeException {

    /** The stable code, the same one the tools and the re-identification service answer. */
    public static final String CODE = "AUDIT_UNAVAILABLE";

    DenialAuditFailedException(Throwable auditFailure) {
        super(CODE);
        if (auditFailure != null) {
            addSuppressed(auditFailure);
        }
    }
}

package io.github.aindriub.dataprism.audit;

/**
 * Thrown by {@link AuditRecorder#record(AuditEntry)} while the last checkpoint
 * write failed and no later {@link AuditRecorder#checkpoint()} has succeeded.
 * The refused record consumed no sequence number and did not advance the chain.
 */
public final class AuditCheckpointUnavailableException extends RuntimeException {

    public AuditCheckpointUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}

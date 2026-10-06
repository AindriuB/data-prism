package io.github.aindriub.dataprism.orchestration;

import io.github.aindriub.dataprism.core.PrivacyRefusedException;

import java.util.Objects;

/**
 * A refusal the orchestrator has already written to the audit trail as a
 * {@code DENY:<code>} event, carrying that event's correlation id so the caller can return it.
 *
 * <p>It is a {@link PrivacyRefusedException}, so every existing
 * {@code catch (PrivacyRefusedException)} keeps working, with the original
 * code, path and message. A failure that was not itself a privacy refusal (a
 * source or scrubber fault) is also audited, as {@code DENY:REQUEST_FAILED}, and
 * surfaces as code {@link #REQUEST_FAILED} with the original as cause; its message is fixed and
 * never repeats the original's, which can carry a payload fragment.
 */
public final class AuditedRefusalException extends PrivacyRefusedException {

    /** Code for an audited failure that was not a privacy refusal. */
    public static final String REQUEST_FAILED = "REQUEST_FAILED";

    private final String correlationId;
    private final String message;

    AuditedRefusalException(PrivacyRefusedException original, String correlationId) {
        super(original.code(), original.path(), "audited refusal");
        this.correlationId = Objects.requireNonNull(correlationId, "correlationId");
        this.message = original.getMessage();
        initCause(original);
    }

    AuditedRefusalException(RuntimeException original, String entityType, String correlationId) {
        super(REQUEST_FAILED, entityType, "the request could not be completed");
        this.correlationId = Objects.requireNonNull(correlationId, "correlationId");
        this.message = REQUEST_FAILED + " at " + entityType + ": the request could not be completed";
        initCause(original);
    }

    /** The correlation id of the DENY audit event written for this refusal. */
    public String correlationId() {
        return correlationId;
    }

    @Override
    public String getMessage() {
        return message;
    }
}

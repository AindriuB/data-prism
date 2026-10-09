package io.github.aindriub.dataprism.mcp;

import io.github.aindriub.dataprism.audit.AuditedEntityTypes;
import io.github.aindriub.dataprism.core.correlation.CorrelationMdc;
import io.github.aindriub.dataprism.orchestration.ParameterFingerprinter;
import io.github.aindriub.dataprism.security.ToolAdmission;

import java.util.Objects;

/**
 * What {@link GetEntityContextTool}, {@link CompareEntitySourcesTool} and the
 * {@link DataPrismMcpServer} factories need beyond their collaborators.
 *
 * <p>There is deliberately no default admission. Every caller names
 * {@link Builder#noAdmission()} or a real policy through {@link Builder#admission}, so
 * approvals are never turned off by omission: {@link #defaults()} returns a
 * {@link Builder} whose {@link Builder#build()} refuses until one of the two was called.
 *
 * <p>Any admission other than the shared {@code ToolAdmission.none()} instance needs a
 * {@code fingerprinter}, or every approval would bind to the same empty fingerprint and
 * one approval would cover every call. The check is in the compact constructor, so it
 * holds on every public path that accepts the record. {@code ToolAdmission.none()} returns
 * one shared instance, recognised here by identity: {@link Builder#noAdmission()} and
 * {@code .admission(ToolAdmission.none(), null)} are the same thing, and a subclass or any
 * other instance can never match, so a real policy always needs a fingerprinter.
 *
 * @param admission              checked after scope resolution and before the orchestrator; any
 *                               failure to evaluate it refuses the call
 * @param fingerprinter          binds an approval to the call's arguments; {@code null} only for
 *                               {@code ToolAdmission.none()}
 * @param correlationRequirement what the transport context's external correlation id must satisfy
 * @param mdc                    opened around the whole call so every log line on the handler
 *                               thread, denials included, carries the validated external id
 * @param entityTypes            decides what the audit record's {@code entityType} holds
 */
public record ToolOptions(ToolAdmission admission, ParameterFingerprinter fingerprinter,
                          CorrelationRequirement correlationRequirement, CorrelationMdc mdc,
                          AuditedEntityTypes entityTypes) {

    /** The shared {@code ToolAdmission.none()}: no pause, no limit, no approval. */
    private static final ToolAdmission NO_ADMISSION = ToolAdmission.none();

    public ToolOptions {
        Objects.requireNonNull(admission, "admission");
        Objects.requireNonNull(correlationRequirement, "correlationRequirement");
        Objects.requireNonNull(mdc, "mdc");
        Objects.requireNonNull(entityTypes, "entityTypes");
        if (fingerprinter == null && admission != NO_ADMISSION) {
            throw new NullPointerException("fingerprinter is required with any admission other than "
                    + "ToolAdmission.none(), or every approval binds to the same empty fingerprint");
        }
    }

    /**
     * A builder holding {@link CorrelationRequirement#OPTIONAL}, {@link CorrelationMdc#off()} and
     * {@link AuditedEntityTypes#shape()}. Admission is not defaulted: {@link Builder#build()}
     * fails until the caller has named one.
     */
    public static Builder defaults() {
        return new Builder();
    }

    public static final class Builder {

        private ToolAdmission admission;
        private ParameterFingerprinter fingerprinter;
        private CorrelationRequirement correlationRequirement = CorrelationRequirement.OPTIONAL;
        private CorrelationMdc mdc = CorrelationMdc.off();
        private AuditedEntityTypes entityTypes = AuditedEntityTypes.shape();

        private Builder() {
        }

        /** Names the absence of oversight explicitly: no pause, no rate limit, no approval. */
        public Builder noAdmission() {
            this.admission = NO_ADMISSION;
            this.fingerprinter = null;
            return this;
        }

        /**
         * An admission; {@code fingerprinter} is required unless it is
         * {@code ToolAdmission.none()}, and checked by {@link #build()}.
         */
        public Builder admission(ToolAdmission admission, ParameterFingerprinter fingerprinter) {
            this.admission = admission;
            this.fingerprinter = fingerprinter;
            return this;
        }

        public Builder correlationRequirement(CorrelationRequirement correlationRequirement) {
            this.correlationRequirement = correlationRequirement;
            return this;
        }

        public Builder mdc(CorrelationMdc mdc) {
            this.mdc = mdc;
            return this;
        }

        public Builder entityTypes(AuditedEntityTypes entityTypes) {
            this.entityTypes = entityTypes;
            return this;
        }

        /** @throws NullPointerException if no admission was named, or a real one has no fingerprinter */
        public ToolOptions build() {
            return new ToolOptions(admission, fingerprinter, correlationRequirement, mdc, entityTypes);
        }
    }
}

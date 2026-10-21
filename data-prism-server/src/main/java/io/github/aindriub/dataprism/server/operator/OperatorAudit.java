package io.github.aindriub.dataprism.server.operator;

import io.github.aindriub.dataprism.audit.AuditEntry;
import io.github.aindriub.dataprism.audit.AuditRecorder;
import io.github.aindriub.dataprism.security.AuthenticatedCaller;
import io.github.aindriub.dataprism.core.metrics.PrivacyMetrics;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** One audit event per operator action, tool {@code operator:<action>}, naming the operator. */
@Component
@ConditionalOnProperty(prefix = "dataprism.operator", name = "enabled", havingValue = "true")
final class OperatorAudit {

    private final AuditRecorder recorder;
    private final PrivacyMetrics metrics;

    OperatorAudit(AuditRecorder recorder) {
        this.recorder = recorder;
        this.metrics = PrivacyMetrics.none();
    }

    @Autowired
    OperatorAudit(AuditRecorder recorder, ObjectProvider<PrivacyMetrics> metrics) {
        this.recorder = recorder;
        this.metrics = metrics.getIfAvailable(PrivacyMetrics::none);
    }

    /** @throws OperatorException 503 {@code AUDIT_UNAVAILABLE} when the event cannot be written */
    void record(AuthenticatedCaller caller, String action, String entityType, String scopeId, String decision,
                String approvalId, String approverId) {
        try {
            recorder.record(entry(caller, action, entityType, scopeId, decision, approvalId, approverId));
        } catch (RuntimeException unavailable) {
            throw new OperatorException(HttpStatus.SERVICE_UNAVAILABLE, "AUDIT_UNAVAILABLE");
        }
    }

    /**
     * As {@link #record(AuthenticatedCaller, String, String, String, String, String, String)}, under a
     * correlation id the caller chose, so a later event of the same call can be linked to this one.
     *
     * @throws OperatorException 503 {@code AUDIT_UNAVAILABLE} when the event cannot be written
     */
    void record(AuthenticatedCaller caller, String action, String entityType, String scopeId, String decision,
                String approvalId, String approverId, String correlationId) {
        try {
            recorder.record(entry(caller, action, entityType, scopeId, decision, approvalId, approverId,
                    correlationId));
        } catch (RuntimeException unavailable) {
            throw new OperatorException(HttpStatus.SERVICE_UNAVAILABLE, "AUDIT_UNAVAILABLE");
        }
    }

    /**
     * A refusal linked to an earlier event of the same call by its correlation id. The refusal stands whether
     * or not the event could be written.
     *
     * @return whether the event was written
     */
    boolean recordDenial(AuthenticatedCaller caller, String action, String entityType, String scopeId,
                         String code, String approvalId, String correlationId) {
        try {
            recorder.record(entry(caller, action, entityType, scopeId, "DENY:" + code, approvalId, "",
                    correlationId));
            return true;
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    /** For a refusal: the refusal stands whether or not the event could be written. */
    void recordDenial(AuthenticatedCaller caller, String action, String entityType, String scopeId, String code,
                      String approvalId) {
        try {
            recorder.record(entry(caller, action, entityType, scopeId, "DENY:" + code, approvalId, ""));
        } catch (RuntimeException unavailable) {
            // still a refusal
        }
    }

    private static AuditEntry entry(AuthenticatedCaller caller, String action, String entityType, String scopeId,
                                    String decision, String approvalId, String approverId) {
        return entry(caller, action, entityType, scopeId, decision, approvalId, approverId,
                UUID.randomUUID().toString());
    }

    private static AuditEntry entry(AuthenticatedCaller caller, String action, String entityType, String scopeId,
                                    String decision, String approvalId, String approverId, String correlationId) {
        return new AuditEntry(caller.principalId(), caller.clientId(), "operator:" + action, entityType, "", "", "",
                scopeId, caller.purpose(), caller.caseId(), decision, Set.of(), Set.of(),
                correlationId, Map.of(), approvalId, approverId);
    }
}

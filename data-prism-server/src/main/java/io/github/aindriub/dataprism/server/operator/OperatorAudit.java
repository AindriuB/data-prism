package io.github.aindriub.dataprism.server.operator;

import io.github.aindriub.dataprism.audit.AuditEntry;
import io.github.aindriub.dataprism.audit.AuditRecorder;
import io.github.aindriub.dataprism.security.AuthenticatedCaller;
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

    OperatorAudit(AuditRecorder recorder) {
        this.recorder = recorder;
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
        return new AuditEntry(caller.principalId(), caller.clientId(), "operator:" + action, entityType, "", "", "",
                scopeId, caller.purpose(), caller.caseId(), decision, Set.of(), Set.of(),
                UUID.randomUUID().toString(), Map.of(), approvalId, approverId);
    }
}

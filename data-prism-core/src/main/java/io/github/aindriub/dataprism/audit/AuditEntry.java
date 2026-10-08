package io.github.aindriub.dataprism.audit;

import java.util.Map;
import java.util.Set;

/**
 * Every caller-supplied field of an audit record; {@link AuditRecorder} adds the
 * id, timestamp and chain fields. {@code fieldDispositions} names field paths and
 * actions, never values; see {@link AuditEvent}.
 */
public record AuditEntry(
        String principalId,
        String clientId,
        String tool,
        String entityType,
        String subjectPseudonym,
        String parameterFingerprint,
        String privacyProfile,
        String scopeId,
        String purpose,
        String caseId,
        String policyDecision,
        Set<String> sourceSystems,
        Set<String> rejectedArguments,
        String correlationId,
        Map<String, String> fieldDispositions,
        String approvalId,
        String approverId,
        String externalCorrelationId) {

    /** The pre-version-3 shape: no external correlation id. */
    public AuditEntry(String principalId, String clientId, String tool, String entityType,
                      String subjectPseudonym, String parameterFingerprint, String privacyProfile,
                      String scopeId, String purpose, String caseId, String policyDecision,
                      Set<String> sourceSystems, Set<String> rejectedArguments, String correlationId,
                      Map<String, String> fieldDispositions, String approvalId, String approverId) {
        this(principalId, clientId, tool, entityType, subjectPseudonym, parameterFingerprint, privacyProfile,
                scopeId, purpose, caseId, policyDecision, sourceSystems, rejectedArguments, correlationId,
                fieldDispositions, approvalId, approverId, "");
    }
}

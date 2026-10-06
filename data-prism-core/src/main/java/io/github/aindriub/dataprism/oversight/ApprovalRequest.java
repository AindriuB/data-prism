package io.github.aindriub.dataprism.oversight;

import java.time.Instant;

/**
 * A request for a human to approve one action.
 *
 * <p>There is deliberately no field that could hold a resolved subject id. The
 * request carries the synthetic value the requester already holds, and nothing
 * the approval flow itself learns about the real subject.
 */
public record ApprovalRequest(
        String approvalId,
        Kind kind,
        String requesterPrincipalId,
        String requesterClientId,
        String scopeId,
        String tool,
        String bindingFingerprint,
        String namespace,
        String syntheticValue,
        String purpose,
        String caseId,
        Instant createdAt,
        Instant expiresAt,
        Status status,
        String approverPrincipalId,
        Instant decidedAt) {

    public enum Kind { TOOL_CALL, REIDENTIFICATION }

    public enum Status { PENDING, APPROVED, REJECTED, CONSUMED, EXPIRED }

    public ApprovalRequest withDecision(Status newStatus, String approver, Instant at) {
        return new ApprovalRequest(approvalId, kind, requesterPrincipalId, requesterClientId, scopeId, tool,
                bindingFingerprint, namespace, syntheticValue, purpose, caseId, createdAt, expiresAt,
                newStatus, approver, at);
    }

    public ApprovalRequest withStatus(Status newStatus) {
        return new ApprovalRequest(approvalId, kind, requesterPrincipalId, requesterClientId, scopeId, tool,
                bindingFingerprint, namespace, syntheticValue, purpose, caseId, createdAt, expiresAt,
                newStatus, approverPrincipalId, decidedAt);
    }
}

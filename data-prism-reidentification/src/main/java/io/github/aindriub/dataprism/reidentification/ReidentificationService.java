package io.github.aindriub.dataprism.reidentification;

import io.github.aindriub.dataprism.annotations.PrivacyNamespace;
import io.github.aindriub.dataprism.audit.AuditEntry;
import io.github.aindriub.dataprism.audit.AuditRecorder;
import io.github.aindriub.dataprism.hazelcast.ScopeIdentityIndex;
import io.github.aindriub.dataprism.oversight.ApprovalRefusedException;
import io.github.aindriub.dataprism.oversight.ApprovalRequest;
import io.github.aindriub.dataprism.oversight.ApprovalRequest.Kind;
import io.github.aindriub.dataprism.oversight.ApprovalRequest.Status;
import io.github.aindriub.dataprism.oversight.ApprovalStore;
import io.github.aindriub.dataprism.security.AuthenticatedCaller;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The one controlled path from a pseudonym back to a subject id
 * (docs/design-review.md section B1).
 *
 * <p>Every step is made by an authenticated caller and writes an audit event
 * naming that caller; no event ever carries the subject id. When four-eyes is
 * on, nothing resolves until a second, distinct principal approves. This class
 * is the only permitted caller of {@code ScopeIdentityIndex.subjectFor}, and it
 * must never be reachable from an MCP tool; both are enforced by
 * {@code ArchitectureTest}. Every failure is a refusal.
 */
public final class ReidentificationService {

    static final String TOOL = "reidentify";

    private final SubjectLookup lookup;
    private final ApprovalStore approvals;
    private final AuditRecorder audit;
    private final ReidentificationPolicy policy;
    private final Clock clock;

    public ReidentificationService(ScopeIdentityIndex index, ApprovalStore approvals, AuditRecorder audit,
                                   ReidentificationPolicy policy, Clock clock) {
        this(Objects.requireNonNull(index, "index")::subjectFor, approvals, audit, policy, clock);
    }

    public ReidentificationService(SubjectLookup lookup, ApprovalStore approvals, AuditRecorder audit,
                                   ReidentificationPolicy policy, Clock clock) {
        this.lookup = Objects.requireNonNull(lookup, "lookup");
        this.approvals = Objects.requireNonNull(approvals, "approvals");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public ReidentificationOutcome request(AuthenticatedCaller caller, ReidentificationRequest request) {
        String purpose = request.purpose() == null ? "" : request.purpose();
        String caseId = request.caseId() == null ? "" : request.caseId();
        if (!policy.grants(caller.roles(), Permission.REQUEST)) {
            return refuse(caller, request, purpose, caseId, "", "", "REIDENTIFICATION_NOT_PERMITTED");
        }
        if (purpose.isBlank()) {
            return refuse(caller, request, purpose, caseId, "", "", "PURPOSE_REQUIRED");
        }
        if (!policy.purposes().contains(purpose)) {
            return refuse(caller, request, purpose, caseId, "", "", "PURPOSE_NOT_ALLOWED");
        }
        try {
            if (policy.fourEyes()) {
                return open(caller, request, purpose, caseId);
            }
            return resolve(caller, request, purpose, caseId, "", "");
        } catch (RuntimeException unavailable) {
            return refuse(caller, request, purpose, caseId, "", "", "REIDENTIFICATION_UNAVAILABLE");
        }
    }

    public ReidentificationOutcome approve(AuthenticatedCaller caller, String approvalId) {
        Optional<ApprovalRequest> found;
        try {
            found = approvals.find(approvalId).filter(a -> a.kind() == Kind.REIDENTIFICATION);
        } catch (RuntimeException unavailable) {
            return refuseApproval(caller, null, approvalId, "REIDENTIFICATION_UNAVAILABLE", true);
        }
        if (!policy.grants(caller.roles(), Permission.APPROVE)) {
            return refuseApproval(caller, found.orElse(null), approvalId, "REIDENTIFICATION_NOT_PERMITTED", true);
        }
        if (found.isEmpty()) {
            return refuseApproval(caller, null, approvalId, "APPROVAL_NOT_FOUND", true);
        }
        ApprovalRequest pending = found.get();
        try {
            approvals.approve(approvalId, caller.principalId(), clock.instant());
        } catch (ApprovalRefusedException e) {
            return refuseApproval(caller, pending, approvalId, switch (e.code()) {
                case UNKNOWN_APPROVAL -> "APPROVAL_NOT_FOUND";
                case NOT_PENDING -> "APPROVAL_NOT_PENDING";
                case EXPIRED -> "APPROVAL_EXPIRED";
                case SELF_APPROVAL -> "SELF_APPROVAL";
                case TOO_MANY_PENDING -> "REIDENTIFICATION_UNAVAILABLE";
            }, true);
        } catch (RuntimeException unavailable) {
            return refuseApproval(caller, pending, approvalId, "REIDENTIFICATION_UNAVAILABLE", true);
        }
        try {
            record(pending, "ALLOW:APPROVED", pending.approvalId(), caller.principalId());
        } catch (RuntimeException auditDown) {
            revoke(pending);
            return new ReidentificationOutcome.Refused("AUDIT_UNAVAILABLE");
        }
        return new ReidentificationOutcome.Approved();
    }

    public ReidentificationOutcome collect(AuthenticatedCaller caller, String approvalId) {
        ApprovalRequest approval;
        try {
            Optional<ApprovalRequest> found = approvals.find(approvalId).filter(a -> a.kind() == Kind.REIDENTIFICATION);
            if (found.isEmpty()) {
                return refuseApproval(caller, null, approvalId, "APPROVAL_NOT_FOUND", false);
            }
            approval = found.get();
        } catch (RuntimeException unavailable) {
            return refuseApproval(caller, null, approvalId, "REIDENTIFICATION_UNAVAILABLE", false);
        }
        if (!approval.requesterPrincipalId().equals(caller.principalId())) {
            return refuseApproval(caller, approval, approvalId, "NOT_REQUESTER", false);
        }
        if (!policy.grants(caller.roles(), Permission.REQUEST)) {
            return refuseApproval(caller, approval, approvalId, "REIDENTIFICATION_NOT_PERMITTED", false);
        }
        Instant now = clock.instant();
        if (!now.isBefore(approval.expiresAt())) {
            return refuseApproval(caller, approval, approvalId, "APPROVAL_EXPIRED", false);
        }
        if (approval.status() != Status.APPROVED) {
            return refuseApproval(caller, approval, approvalId, "APPROVAL_NOT_APPROVED", false);
        }
        if (!approval.bindingFingerprint().equals(fingerprint(approval.scopeId(), approval.namespace(),
                approval.syntheticValue(), approval.purpose(), approval.caseId(), approval.approvalId()))) {
            return refuseApproval(caller, approval, approvalId, "APPROVAL_NOT_APPROVED", false);
        }
        try {
            Optional<ApprovalRequest> consumed = approvals.consumeApproved(Kind.REIDENTIFICATION,
                    approval.requesterPrincipalId(), approval.scopeId(), approval.tool(),
                    approval.bindingFingerprint(), now);
            if (consumed.isEmpty() || !consumed.get().approvalId().equals(approvalId)) {
                return refuseApproval(caller, approval, approvalId, "APPROVAL_NOT_APPROVED", false);
            }
            ReidentificationRequest original = new ReidentificationRequest(approval.scopeId(),
                    PrivacyNamespace.valueOf(approval.namespace()),
                    approval.syntheticValue(), approval.purpose(), approval.caseId());
            return resolve(caller, original, approval.purpose(), approval.caseId(), approval.approvalId(),
                    approval.approverPrincipalId() == null ? "" : approval.approverPrincipalId());
        } catch (RuntimeException unavailable) {
            return refuseApproval(caller, approval, approvalId, "REIDENTIFICATION_UNAVAILABLE", false);
        }
    }

    private ReidentificationOutcome open(AuthenticatedCaller caller, ReidentificationRequest request,
                                         String purpose, String caseId) {
        Instant now = clock.instant();
        // The binding includes the approval's own id, so exactly one approval can ever match it and
        // consuming by binding is consuming by id.
        String approvalId = UUID.randomUUID().toString();
        String fingerprint = fingerprint(request.scopeId(), request.namespace().name(), request.syntheticValue(),
                purpose, caseId, approvalId);
        ApprovalRequest created;
        try {
            created = approvals.create(new ApprovalRequest(approvalId,
                    Kind.REIDENTIFICATION, caller.principalId(), caller.clientId(), request.scopeId(), TOOL,
                    fingerprint, request.namespace().name(), request.syntheticValue(), purpose, caseId, now,
                    now.plus(policy.approvalTtl()), Status.PENDING, null, null), policy.maxPendingPerRequester());
        } catch (ApprovalRefusedException tooMany) {
            if (tooMany.code() != ApprovalRefusedException.Code.TOO_MANY_PENDING) {
                throw tooMany;
            }
            try {
                audit.record(entry(caller, request, purpose, caseId, "DENY:TOO_MANY_PENDING", "", ""));
            } catch (RuntimeException auditDown) {
                return new ReidentificationOutcome.Refused("AUDIT_UNAVAILABLE");
            }
            return new ReidentificationOutcome.Refused("TOO_MANY_PENDING");
        }
        try {
            audit.record(entry(caller, request, purpose, caseId, "ALLOW:REQUESTED", approvalId, "", fingerprint));
        } catch (RuntimeException auditDown) {
            try {
                approvals.reject(created.approvalId(), caller.principalId(), clock.instant());
            } catch (RuntimeException undoFailed) {
                // fail closed: an unaudited pending approval still needs an approver, and its audit trail is absent
            }
            return new ReidentificationOutcome.Refused("AUDIT_UNAVAILABLE");
        }
        return new ReidentificationOutcome.PendingApproval(approvalId);
    }

    private ReidentificationOutcome resolve(AuthenticatedCaller caller, ReidentificationRequest request,
                                            String purpose, String caseId, String approvalId, String approverId) {
        Optional<String> subject = lookup.subjectFor(request.scopeId(), request.namespace(),
                request.syntheticValue());
        if (subject.isEmpty()) {
            return refuse(caller, request, purpose, caseId, approvalId, approverId, "REIDENTIFICATION_NOT_FOUND");
        }
        try {
            audit.record(entry(caller, request, purpose, caseId, "ALLOW:RESOLVED", approvalId, approverId));
        } catch (RuntimeException auditDown) {
            return new ReidentificationOutcome.Refused("AUDIT_UNAVAILABLE");
        }
        return new ReidentificationOutcome.Resolved(subject.get());
    }

    private ReidentificationOutcome refuseApproval(AuthenticatedCaller caller, ApprovalRequest approval,
                                                   String approvalId, String code, boolean approving) {
        String scope = approval == null ? "" : approval.scopeId();
        String namespace = approval == null ? "" : approval.namespace();
        String synthetic = approval == null ? "" : approval.syntheticValue();
        String purpose = approval == null ? "" : approval.purpose();
        String caseId = approval == null ? "" : approval.caseId();
        try {
            audit.record(new AuditEntry(caller.principalId(), caller.clientId(), TOOL, namespace, synthetic, "",
                    "", scope, purpose, caseId, "DENY:" + code, Set.of(), Set.of(), UUID.randomUUID().toString(),
                    Map.of(), approvalId == null ? "" : approvalId, approving ? caller.principalId() : ""));
        } catch (RuntimeException auditDown) {
            // still a refusal
        }
        return new ReidentificationOutcome.Refused(code);
    }

    private ReidentificationOutcome refuse(AuthenticatedCaller caller, ReidentificationRequest request,
                                           String purpose, String caseId, String approvalId, String approverId,
                                           String code) {
        try {
            audit.record(entry(caller, request, purpose, caseId, "DENY:" + code, approvalId, approverId));
        } catch (RuntimeException auditDown) {
            // still a refusal
        }
        return new ReidentificationOutcome.Refused(code);
    }

    private void record(ApprovalRequest approval, String decision, String approvalId,
                        String approverId) {
        audit.record(new AuditEntry(approval.requesterPrincipalId(), approval.requesterClientId(), TOOL,
                approval.namespace(), approval.syntheticValue(), approval.bindingFingerprint(), "",
                approval.scopeId(), approval.purpose(), approval.caseId(), decision, Set.of(), Set.of(),
                UUID.randomUUID().toString(), Map.of(), approvalId, approverId));
    }

    private AuditEntry entry(AuthenticatedCaller caller, ReidentificationRequest request, String purpose,
                             String caseId, String decision, String approvalId, String approverId) {
        return entry(caller, request, purpose, caseId, decision, approvalId, approverId,
                fingerprint(request.scopeId(), request.namespace().name(), request.syntheticValue(), purpose,
                        caseId, approvalId));
    }

    private AuditEntry entry(AuthenticatedCaller caller, ReidentificationRequest request, String purpose,
                             String caseId, String decision, String approvalId, String approverId,
                             String fingerprint) {
        return new AuditEntry(caller.principalId(), caller.clientId(), TOOL, request.namespace().name(),
                request.syntheticValue(), fingerprint, "", request.scopeId(), purpose, caseId, decision,
                Set.of(), Set.of(), UUID.randomUUID().toString(), Map.of(), approvalId, approverId);
    }

    /** Undoes an approval whose audit write failed, so nothing unaudited stays usable. */
    private void revoke(ApprovalRequest approval) {
        try {
            approvals.consumeApproved(Kind.REIDENTIFICATION, approval.requesterPrincipalId(), approval.scopeId(),
                    approval.tool(), approval.bindingFingerprint(), clock.instant());
        } catch (RuntimeException undoFailed) {
            // nothing more can be done; the caller is told the step was refused
        }
    }

    private static String fingerprint(String scopeId, String namespace, String synthetic, String purpose,
                                      String caseId, String approvalId) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String part : new String[] {scopeId, namespace, synthetic, purpose, caseId, approvalId}) {
                byte[] bytes = part.getBytes(StandardCharsets.UTF_8);
                digest.update((bytes.length + ":").getBytes(StandardCharsets.UTF_8));
                digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}

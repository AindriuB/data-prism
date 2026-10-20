package io.github.aindriub.dataprism.reidentification;

import io.github.aindriub.dataprism.annotations.PrivacyNamespace;
import io.github.aindriub.dataprism.audit.AuditEntry;
import io.github.aindriub.dataprism.audit.AuditRecorder;
import io.github.aindriub.dataprism.core.metrics.Metric;
import io.github.aindriub.dataprism.core.metrics.PrivacyMetrics;
import io.github.aindriub.dataprism.core.refusal.ClusterLockTimeoutException;
import io.github.aindriub.dataprism.core.refusal.ClusterOperationRefusedException;
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
    private final PrivacyMetrics metrics;

    public ReidentificationService(ScopeIdentityIndex index, ApprovalStore approvals, AuditRecorder audit,
                                   ReidentificationPolicy policy, Clock clock) {
        this(Objects.requireNonNull(index, "index")::subjectFor, approvals, audit, policy, clock);
    }

    public ReidentificationService(ScopeIdentityIndex index, ApprovalStore approvals, AuditRecorder audit,
                                   ReidentificationPolicy policy, Clock clock, PrivacyMetrics metrics) {
        this(Objects.requireNonNull(index, "index")::subjectFor, approvals, audit, policy, clock, metrics);
    }

    public ReidentificationService(SubjectLookup lookup, ApprovalStore approvals, AuditRecorder audit,
                                   ReidentificationPolicy policy, Clock clock) {
        this(lookup, approvals, audit, policy, clock, PrivacyMetrics.none());
    }

    public ReidentificationService(SubjectLookup lookup, ApprovalStore approvals, AuditRecorder audit,
                                   ReidentificationPolicy policy, Clock clock, PrivacyMetrics metrics) {
        this.metrics = Objects.requireNonNull(metrics, "metrics");
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
            return refuse(caller, request, purpose, caseId, "", "", unavailableCode(unavailable));
        }
    }

    public ReidentificationOutcome approve(AuthenticatedCaller caller, String approvalId) {
        Optional<ApprovalRequest> found;
        try {
            found = approvals.find(approvalId).filter(a -> a.kind() == Kind.REIDENTIFICATION);
        } catch (RuntimeException unavailable) {
            return refuseApproval(caller, null, approvalId, unavailableCode(unavailable), true);
        }
        if (!policy.grants(caller.roles(), Permission.APPROVE)) {
            return refuseApproval(caller, found.orElse(null), approvalId, "REIDENTIFICATION_NOT_PERMITTED", true);
        }
        if (found.isEmpty()) {
            return refuseApproval(caller, null, approvalId, "APPROVAL_NOT_FOUND", true);
        }
        ApprovalRequest pending = found.get();
        Instant now = clock.instant();
        // Everything the store would refuse is refused here first, so a refusal is one DENY and no ALLOW.
        String precheck = precheck(pending, caller, now);
        if (precheck != null) {
            return refuseApproval(caller, pending, approvalId, precheck, true);
        }
        // Audit first: the decision can take effect only after its ALLOW:APPROVED is written. If the write
        // fails nothing has been committed, so there is nothing to undo and nothing that could be used.
        String correlationId = UUID.randomUUID().toString();
        try {
            record(pending, "ALLOW:APPROVED", pending.approvalId(), caller.principalId(), correlationId);
        } catch (RuntimeException auditDown) {
            metrics.increment(Metric.APPROVAL_DECISION_AUDIT_UNAVAILABLE);
            return new ReidentificationOutcome.Refused("AUDIT_UNAVAILABLE");
        }
        try {
            approvals.approve(approvalId, caller.principalId(), now);
            return new ReidentificationOutcome.Approved();
        } catch (ApprovalRefusedException e) {
            return denyLinked(caller, pending, approvalId, switch (e.code()) {
                case UNKNOWN_APPROVAL -> "APPROVAL_NOT_FOUND";
                case NOT_PENDING -> "APPROVAL_NOT_PENDING";
                case EXPIRED -> "APPROVAL_EXPIRED";
                case SELF_APPROVAL -> "SELF_APPROVAL";
                case TOO_MANY_PENDING -> "REIDENTIFICATION_UNAVAILABLE";
            }, correlationId, false);
        } catch (ClusterLockTimeoutException | ClusterOperationRefusedException beforeCommit) {
            // Raised by the entry's lock, before the commit: the decision did not apply.
            return denyLinked(caller, pending, approvalId, unavailableCode(beforeCommit), correlationId, false);
        } catch (RuntimeException unknown) {
            // The outcome is unknown (a lost reply): read the approval back before deciding what to say.
            if (applied(approvalId, caller.principalId())) {
                return new ReidentificationOutcome.Approved();
            }
            return denyLinked(caller, pending, approvalId, "REIDENTIFICATION_UNAVAILABLE", correlationId, true);
        }
    }

    /** The code the store would refuse an approve with, judged on the value just read; null when it would not. */
    private static String precheck(ApprovalRequest approval, AuthenticatedCaller caller, Instant now) {
        if (approval.status() != Status.PENDING) {
            return "APPROVAL_NOT_PENDING";
        }
        if (!now.isBefore(approval.expiresAt())) {
            return "APPROVAL_EXPIRED";
        }
        if (approval.requesterPrincipalId().equals(caller.principalId())) {
            return "SELF_APPROVAL";
        }
        return null;
    }

    /** Whether the approval now shows this caller's approval, so an approve whose reply was lost did apply. */
    private boolean applied(String approvalId, String approverId) {
        try {
            return approvals.find(approvalId)
                    .filter(a -> a.status() == Status.APPROVED || a.status() == Status.CONSUMED)
                    .filter(a -> approverId.equals(a.approverPrincipalId()))
                    .isPresent();
        } catch (RuntimeException unreadable) {
            return false;
        }
    }

    /**
     * The decision did not apply, or its outcome is unknown: refuse with its own code and write the DENY under the
     * correlation id of the ALLOW that preceded it. A DENY that cannot be written does not change the answer.
     */
    private ReidentificationOutcome denyLinked(AuthenticatedCaller caller, ApprovalRequest approval,
                                               String approvalId, String code, String correlationId,
                                               boolean outcomeUnknown) {
        boolean written = auditDeny(caller, approval, approvalId, code, true, correlationId);
        if (outcomeUnknown || !written) {
            metrics.increment(Metric.APPROVAL_DECISION_UNCONFIRMED);
        }
        return new ReidentificationOutcome.Refused(code);
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
            return refuseApproval(caller, null, approvalId, unavailableCode(unavailable), false);
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
            return refuseApproval(caller, approval, approvalId, unavailableCode(unavailable), false);
        }
    }

    /**
     * The code for a store or lookup failure: the cluster's own code when it refused the operation or a lock timed
     * out, so a caller can tell a retry-later from a fault, and {@code REIDENTIFICATION_UNAVAILABLE} for anything
     * else. Never the exception's message.
     */
    private static String unavailableCode(RuntimeException failure) {
        if (failure instanceof ClusterOperationRefusedException) {
            return ClusterOperationRefusedException.CODE;
        }
        if (failure instanceof ClusterLockTimeoutException) {
            return ClusterLockTimeoutException.CODE;
        }
        return "REIDENTIFICATION_UNAVAILABLE";
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
        auditDeny(caller, approval, approvalId, code, approving, UUID.randomUUID().toString());
        return new ReidentificationOutcome.Refused(code);
    }

    private boolean auditDeny(AuthenticatedCaller caller, ApprovalRequest approval, String approvalId, String code,
                              boolean approving, String correlationId) {
        String scope = approval == null ? "" : approval.scopeId();
        String namespace = approval == null ? "" : approval.namespace();
        String synthetic = approval == null ? "" : approval.syntheticValue();
        String purpose = approval == null ? "" : approval.purpose();
        String caseId = approval == null ? "" : approval.caseId();
        try {
            audit.record(new AuditEntry(caller.principalId(), caller.clientId(), TOOL, namespace, synthetic, "",
                    "", scope, purpose, caseId, "DENY:" + code, Set.of(), Set.of(), correlationId,
                    Map.of(), approvalId == null ? "" : approvalId, approving ? caller.principalId() : ""));
            return true;
        } catch (RuntimeException auditDown) {
            // still a refusal
            return false;
        }
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
                        String approverId, String correlationId) {
        audit.record(new AuditEntry(approval.requesterPrincipalId(), approval.requesterClientId(), TOOL,
                approval.namespace(), approval.syntheticValue(), approval.bindingFingerprint(), "",
                approval.scopeId(), approval.purpose(), approval.caseId(), decision, Set.of(), Set.of(),
                correlationId, Map.of(), approvalId, approverId));
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

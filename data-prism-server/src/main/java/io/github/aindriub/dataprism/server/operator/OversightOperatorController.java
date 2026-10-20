package io.github.aindriub.dataprism.server.operator;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.github.aindriub.dataprism.core.metrics.Metric;
import io.github.aindriub.dataprism.core.metrics.PrivacyMetrics;
import io.github.aindriub.dataprism.core.refusal.ClusterLockTimeoutException;
import io.github.aindriub.dataprism.core.refusal.ClusterOperationRefusedException;
import io.github.aindriub.dataprism.oversight.ApprovalRefusedException;
import io.github.aindriub.dataprism.oversight.ApprovalRequest;
import io.github.aindriub.dataprism.oversight.ApprovalRequest.Kind;
import io.github.aindriub.dataprism.oversight.ApprovalStore;
import io.github.aindriub.dataprism.oversight.OversightSnapshot;
import io.github.aindriub.dataprism.oversight.OversightState;
import io.github.aindriub.dataprism.security.AuthenticatedCaller;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Kill switch, per-tool and per-scope pause, and tool-call approvals. Served only on the operator
 * port. Every action writes one audit event naming the operator.
 *
 * <p>Order matters for fail-closed: a restrictive action (pause, reject) takes effect and is then
 * audited; a permissive one (resume) is audited first and takes effect only if the event was
 * written; an approval is audited first and committed only if the event was written, with a
 * linked {@code DENY} under the same correlation id when it then did not apply.
 */
@RestController
@ConditionalOnProperty(prefix = "dataprism.operator", name = "enabled", havingValue = "true")
final class OversightOperatorController {

    private static final int MAX_NAME = 256;

    private final OperatorCallers callers;
    private final OperatorAudit audit;
    private final OversightState state;
    private final ApprovalStore approvals;
    private final Clock clock;
    private final PrivacyMetrics metrics;

    OversightOperatorController(OperatorCallers callers, OperatorAudit audit, OversightState state,
                                ApprovalStore approvals, Clock clock, ObjectProvider<PrivacyMetrics> metrics) {
        this.metrics = metrics.getIfAvailable(PrivacyMetrics::none);
        this.callers = callers;
        this.audit = audit;
        this.state = state;
        this.approvals = approvals;
        this.clock = clock;
    }

    record PauseRequest(String target, String name) { }

    /**
     * What an approver sees. For a re-identification request that includes the synthetic value and its
     * namespace, so the approver can tell what is being asked for; the subject id is never part of an
     * approval and so never appears. Both are absent for a tool-call approval.
     *
     * <p>{@code status} is the effective status: a stored {@code PENDING} whose expiry has passed is shown as
     * {@code EXPIRED}, judged as the stores judge it. The approver's identity is not part of the view.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record ApprovalView(String approvalId, String kind, String status, String requesterPrincipalId,
                        String requesterClientId, String scopeId, String tool, String purpose, String caseId,
                        Instant createdAt, Instant expiresAt, String namespace, String syntheticValue) {
        static ApprovalView of(ApprovalRequest a, Instant now) {
            boolean reidentification = a.kind() == Kind.REIDENTIFICATION;
            boolean lapsed = a.status() == ApprovalRequest.Status.PENDING && !now.isBefore(a.expiresAt());
            String status = lapsed ? ApprovalRequest.Status.EXPIRED.name() : a.status().name();
            return new ApprovalView(a.approvalId(), a.kind().name(), status, a.requesterPrincipalId(),
                    a.requesterClientId(), a.scopeId(), a.tool(), a.purpose(), a.caseId(), a.createdAt(),
                    a.expiresAt(), reidentification ? a.namespace() : null,
                    reidentification ? a.syntheticValue() : null);
        }
    }

    @PostMapping("/operator/pause")
    Map<String, Object> pause(@RequestBody PauseRequest body) {
        AuthenticatedCaller caller = caller();
        Target target = target(caller, "pause", body);
        try {
            switch (target.type()) {
                case ALL -> state.pauseAll();
                case TOOL -> state.pauseTool(target.name());
                case SCOPE -> state.pauseScope(target.name());
            }
        } catch (RuntimeException unavailable) {
            throw unavailable(caller, "pause", target, unavailable);
        }
        appliedAudit(() -> audit.record(caller, "pause", target.entityType(), target.scopeId(), "ALLOW:PAUSED",
                "", ""));
        return Map.of("status", "PAUSED", "target", target.type().name());
    }

    @PostMapping("/operator/resume")
    Map<String, Object> resume(@RequestBody PauseRequest body) {
        AuthenticatedCaller caller = caller();
        Target target = target(caller, "resume", body);
        audit.record(caller, "resume", target.entityType(), target.scopeId(), "ALLOW:RESUMED", "", "");
        try {
            switch (target.type()) {
                case ALL -> state.resumeAll();
                case TOOL -> state.resumeTool(target.name());
                case SCOPE -> state.resumeScope(target.name());
            }
        } catch (RuntimeException unavailable) {
            throw unavailable(caller, "resume", target, unavailable);
        }
        return Map.of("status", "RESUMED", "target", target.type().name());
    }

    @GetMapping("/operator/state")
    Map<String, Object> state() {
        AuthenticatedCaller caller = caller();
        audit.record(caller, "state", "", "", "ALLOW:READ", "", "");
        OversightSnapshot snapshot;
        try {
            snapshot = state.snapshot();
        } catch (RuntimeException unavailable) {
            throw new OperatorException(HttpStatus.SERVICE_UNAVAILABLE, unavailableCode(unavailable));
        }
        return Map.of("allPaused", snapshot.allPaused(), "pausedTools", new TreeSet<>(snapshot.pausedTools()),
                "pausedScopes", new TreeSet<>(snapshot.pausedScopes()));
    }

    @GetMapping("/operator/approvals")
    Map<String, Object> approvals() {
        AuthenticatedCaller caller = caller();
        audit.record(caller, "approvals", "", "", "ALLOW:READ", "", "");
        List<ApprovalRequest> pending;
        try {
            pending = approvals.pending(clock.instant());
        } catch (RuntimeException unavailable) {
            throw new OperatorException(HttpStatus.SERVICE_UNAVAILABLE, unavailableCode(unavailable));
        }
        // Never the binding fingerprint, and never a subject id (an approval has no field for one).
        Instant now = clock.instant();
        return Map.of("approvals", pending.stream().map(a -> ApprovalView.of(a, now)).toList());
    }

    @GetMapping("/operator/approvals/{id}")
    ApprovalView approval(@PathVariable("id") String id) {
        AuthenticatedCaller caller = caller();
        ApprovalRequest found;
        try {
            found = approvals.find(id).orElse(null);
        } catch (RuntimeException unavailable) {
            String code = unavailableCode(unavailable);
            audit.recordDenial(caller, "approval", "", "", code, "");
            throw new OperatorException(HttpStatus.SERVICE_UNAVAILABLE, code);
        }
        if (found == null) {
            audit.recordDenial(caller, "approval", "", "", "APPROVAL_NOT_FOUND", "");
            throw new OperatorException(HttpStatus.NOT_FOUND, "APPROVAL_NOT_FOUND");
        }
        audit.record(caller, "approval", found.tool(), found.scopeId(), "ALLOW:READ", id, "");
        return ApprovalView.of(found, clock.instant());
    }

    @PostMapping("/operator/approvals/{id}/approve")
    Map<String, Object> approve(@PathVariable("id") String id) {
        AuthenticatedCaller caller = caller();
        ApprovalRequest found = toolCall(caller, "approve", id);
        Instant now = clock.instant();
        // Everything the store would refuse is refused here first, so a refusal is one DENY and no ALLOW.
        ApprovalRefusedException.Code precheck = precheck(found, caller, now);
        if (precheck != null) {
            throw denied(caller, "approve", found, new ApprovalRefusedException(precheck));
        }
        // Audit first: the decision can take effect only after its ALLOW:APPROVED is written. If the write
        // fails nothing has been committed, so there is nothing to undo and nothing that could be used.
        String correlationId = UUID.randomUUID().toString();
        try {
            audit.record(caller, "approve", found.tool(), found.scopeId(), "ALLOW:APPROVED", id,
                    caller.principalId(), correlationId);
        } catch (OperatorException auditDown) {
            metrics.increment(Metric.APPROVAL_DECISION_AUDIT_UNAVAILABLE);
            throw auditDown;
        }
        try {
            approvals.approve(id, caller.principalId(), now);
        } catch (ApprovalRefusedException refused) {
            throw deniedLinked(caller, found, refusalOf(refused), correlationId, false);
        } catch (ClusterLockTimeoutException | ClusterOperationRefusedException beforeCommit) {
            // Raised by the entry's lock, before the commit: the decision did not apply.
            throw deniedLinked(caller, found, new OperatorException(HttpStatus.SERVICE_UNAVAILABLE,
                    unavailableCode(beforeCommit)), correlationId, false);
        } catch (RuntimeException unknown) {
            // The outcome is unknown (a lost reply): read the approval back before deciding what to say.
            if (!applied(id, caller.principalId())) {
                throw deniedLinked(caller, found, new OperatorException(HttpStatus.SERVICE_UNAVAILABLE,
                        "OVERSIGHT_UNAVAILABLE"), correlationId, true);
            }
        }
        return Map.of("status", "APPROVED", "approvalId", id);
    }

    /** The code the store would refuse an approve with, judged on the value just read; null when it would not. */
    private static ApprovalRefusedException.Code precheck(ApprovalRequest approval, AuthenticatedCaller caller,
                                                          Instant now) {
        if (approval.status() != ApprovalRequest.Status.PENDING) {
            return ApprovalRefusedException.Code.NOT_PENDING;
        }
        if (!now.isBefore(approval.expiresAt())) {
            return ApprovalRefusedException.Code.EXPIRED;
        }
        if (approval.requesterPrincipalId().equals(caller.principalId())) {
            return ApprovalRefusedException.Code.SELF_APPROVAL;
        }
        return null;
    }

    /** Whether the approval now shows this caller's approval, so an approve whose reply was lost did apply. */
    private boolean applied(String id, String approverId) {
        try {
            return approvals.find(id)
                    .filter(a -> a.status() == ApprovalRequest.Status.APPROVED
                            || a.status() == ApprovalRequest.Status.CONSUMED)
                    .filter(a -> approverId.equals(a.approverPrincipalId()))
                    .isPresent();
        } catch (RuntimeException unreadable) {
            return false;
        }
    }

    /**
     * The approve did not apply, or its outcome is unknown: writes the DENY under the correlation id of the
     * ALLOW that preceded it. A DENY that cannot be written does not change the answer.
     */
    private OperatorException deniedLinked(AuthenticatedCaller caller, ApprovalRequest found,
                                           OperatorException answer, String correlationId, boolean outcomeUnknown) {
        boolean written = audit.recordDenial(caller, "approve", found.tool(), found.scopeId(), answer.code(),
                found.approvalId(), correlationId);
        if (outcomeUnknown || !written) {
            metrics.increment(Metric.APPROVAL_DECISION_UNCONFIRMED);
        }
        return answer;
    }

    @PostMapping("/operator/approvals/{id}/reject")
    Map<String, Object> reject(@PathVariable("id") String id) {
        AuthenticatedCaller caller = caller();
        ApprovalRequest found = toolCall(caller, "reject", id);
        try {
            approvals.reject(id, caller.principalId(), clock.instant());
        } catch (ApprovalRefusedException refused) {
            throw denied(caller, "reject", found, refused);
        } catch (RuntimeException unavailable) {
            String code = unavailableCode(unavailable);
            audit.recordDenial(caller, "reject", found.tool(), found.scopeId(), code, id);
            throw new OperatorException(HttpStatus.SERVICE_UNAVAILABLE, code);
        }
        appliedAudit(() -> audit.record(caller, "reject", found.tool(), found.scopeId(), "ALLOW:REJECTED", id,
                caller.principalId()));
        return Map.of("status", "REJECTED", "approvalId", id);
    }

    /**
     * Runs the audit write for an action that has already taken effect. If it fails the operator is told
     * so with a distinct code, not that the action failed: the pause or rejection is in force.
     */
    private static void appliedAudit(Runnable write) {
        try {
            write.run();
        } catch (OperatorException auditDown) {
            throw new OperatorException(HttpStatus.SERVICE_UNAVAILABLE, "APPLIED_AUDIT_UNAVAILABLE");
        }
    }

    // ---- helpers -------------------------------------------------------------------------------

    private AuthenticatedCaller caller() {
        return callers.current().orElseThrow(
                () -> new OperatorException(HttpStatus.UNAUTHORIZED, "OPERATOR_IDENTITY_UNAVAILABLE"));
    }

    private ApprovalRequest toolCall(AuthenticatedCaller caller, String action, String id) {
        try {
            ApprovalRequest found = approvals.find(id).filter(a -> a.kind() == Kind.TOOL_CALL).orElse(null);
            if (found == null) {
                audit.recordDenial(caller, action, "", "", "APPROVAL_NOT_FOUND", "");
                throw new OperatorException(HttpStatus.NOT_FOUND, "APPROVAL_NOT_FOUND");
            }
            return found;
        } catch (OperatorException e) {
            throw e;
        } catch (RuntimeException unavailable) {
            String code = unavailableCode(unavailable);
            audit.recordDenial(caller, action, "", "", code, "");
            throw new OperatorException(HttpStatus.SERVICE_UNAVAILABLE, code);
        }
    }

    private OperatorException denied(AuthenticatedCaller caller, String action, ApprovalRequest found,
                                     ApprovalRefusedException refused) {
        OperatorException answer = refusalOf(refused);
        audit.recordDenial(caller, action, found.tool(), found.scopeId(), answer.code(), found.approvalId());
        return answer;
    }

    private static OperatorException refusalOf(ApprovalRefusedException refused) {
        String code;
        HttpStatus status;
        switch (refused.code()) {
            case UNKNOWN_APPROVAL -> { code = "APPROVAL_NOT_FOUND"; status = HttpStatus.NOT_FOUND; }
            case NOT_PENDING -> { code = "APPROVAL_NOT_PENDING"; status = HttpStatus.CONFLICT; }
            case EXPIRED -> { code = "APPROVAL_EXPIRED"; status = HttpStatus.CONFLICT; }
            case SELF_APPROVAL -> { code = "SELF_APPROVAL"; status = HttpStatus.CONFLICT; }
            default -> { code = "OVERSIGHT_UNAVAILABLE"; status = HttpStatus.SERVICE_UNAVAILABLE; }
        }
        return new OperatorException(status, code);
    }

    private OperatorException unavailable(AuthenticatedCaller caller, String action, Target target,
                                          RuntimeException failure) {
        String code = unavailableCode(failure);
        audit.recordDenial(caller, action, target.entityType(), target.scopeId(), code, "");
        return new OperatorException(HttpStatus.SERVICE_UNAVAILABLE, code);
    }

    /**
     * The code for a store or state failure: the cluster's own code when it refused the operation or a lock timed
     * out, and {@code OVERSIGHT_UNAVAILABLE} for anything else. Never the exception's message.
     */
    private static String unavailableCode(RuntimeException failure) {
        if (failure instanceof ClusterOperationRefusedException) {
            return ClusterOperationRefusedException.CODE;
        }
        if (failure instanceof ClusterLockTimeoutException) {
            return ClusterLockTimeoutException.CODE;
        }
        return "OVERSIGHT_UNAVAILABLE";
    }

    private Target target(AuthenticatedCaller caller, String action, PauseRequest body) {
        TargetType type = null;
        if (body != null && body.target() != null) {
            for (TargetType candidate : TargetType.values()) {
                if (candidate.name().equals(body.target())) {
                    type = candidate;
                }
            }
        }
        String name = body == null || body.name() == null ? "" : body.name();
        boolean valid = type != null && (type == TargetType.ALL ? name.isEmpty()
                : !name.isBlank() && name.length() <= MAX_NAME && name.chars().noneMatch(Character::isISOControl));
        if (!valid) {
            audit.recordDenial(caller, action, "", "", "INVALID_REQUEST", "");
            throw new OperatorException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST");
        }
        return new Target(type, name);
    }

    private enum TargetType { ALL, TOOL, SCOPE }

    private record Target(TargetType type, String name) {
        String entityType() {
            return switch (type) {
                case ALL -> "ALL";
                case TOOL -> name;
                case SCOPE -> "";
            };
        }

        String scopeId() {
            return type == TargetType.SCOPE ? name : "";
        }
    }
}

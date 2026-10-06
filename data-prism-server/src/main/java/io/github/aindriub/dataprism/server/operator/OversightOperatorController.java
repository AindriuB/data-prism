package io.github.aindriub.dataprism.server.operator;

import io.github.aindriub.dataprism.oversight.ApprovalRefusedException;
import io.github.aindriub.dataprism.oversight.ApprovalRequest;
import io.github.aindriub.dataprism.oversight.ApprovalRequest.Kind;
import io.github.aindriub.dataprism.oversight.ApprovalStore;
import io.github.aindriub.dataprism.oversight.OversightSnapshot;
import io.github.aindriub.dataprism.oversight.OversightState;
import io.github.aindriub.dataprism.security.AuthenticatedCaller;
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

/**
 * Kill switch, per-tool and per-scope pause, and tool-call approvals. Served only on the operator
 * port. Every action writes one audit event naming the operator.
 *
 * <p>Order matters for fail-closed: a restrictive action (pause, reject) takes effect and is then
 * audited; a permissive one (resume) is audited first and takes effect only if the event was
 * written; an approval is made, audited, and undone if the audit write fails.
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

    OversightOperatorController(OperatorCallers callers, OperatorAudit audit, OversightState state,
                                ApprovalStore approvals, Clock clock) {
        this.callers = callers;
        this.audit = audit;
        this.state = state;
        this.approvals = approvals;
        this.clock = clock;
    }

    record PauseRequest(String target, String name) { }

    record ApprovalView(String approvalId, String kind, String requesterPrincipalId, String requesterClientId,
                        String scopeId, String tool, String purpose, String caseId, Instant createdAt,
                        Instant expiresAt) { }

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
            throw unavailable(caller, "pause", target);
        }
        audit.record(caller, "pause", target.entityType(), target.scopeId(), "ALLOW:PAUSED", "", "");
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
            throw unavailable(caller, "resume", target);
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
            throw new OperatorException(HttpStatus.SERVICE_UNAVAILABLE, "OVERSIGHT_UNAVAILABLE");
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
            throw new OperatorException(HttpStatus.SERVICE_UNAVAILABLE, "OVERSIGHT_UNAVAILABLE");
        }
        // Deliberately no synthetic value, namespace or binding fingerprint: what is listed is who is
        // asking, for which tool and scope, for how long.
        return Map.of("approvals", pending.stream().map(a -> new ApprovalView(a.approvalId(), a.kind().name(),
                a.requesterPrincipalId(), a.requesterClientId(), a.scopeId(), a.tool(), a.purpose(), a.caseId(),
                a.createdAt(), a.expiresAt())).toList());
    }

    @PostMapping("/operator/approvals/{id}/approve")
    Map<String, Object> approve(@PathVariable("id") String id) {
        AuthenticatedCaller caller = caller();
        ApprovalRequest found = toolCall(caller, "approve", id);
        try {
            approvals.approve(id, caller.principalId(), clock.instant());
        } catch (ApprovalRefusedException refused) {
            throw denied(caller, "approve", found, refused);
        } catch (RuntimeException unavailable) {
            audit.recordDenial(caller, "approve", found.tool(), found.scopeId(), "OVERSIGHT_UNAVAILABLE", id);
            throw new OperatorException(HttpStatus.SERVICE_UNAVAILABLE, "OVERSIGHT_UNAVAILABLE");
        }
        try {
            audit.record(caller, "approve", found.tool(), found.scopeId(), "ALLOW:APPROVED", id,
                    caller.principalId());
        } catch (OperatorException auditDown) {
            revoke(found);
            throw auditDown;
        }
        return Map.of("status", "APPROVED", "approvalId", id);
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
            audit.recordDenial(caller, "reject", found.tool(), found.scopeId(), "OVERSIGHT_UNAVAILABLE", id);
            throw new OperatorException(HttpStatus.SERVICE_UNAVAILABLE, "OVERSIGHT_UNAVAILABLE");
        }
        audit.record(caller, "reject", found.tool(), found.scopeId(), "ALLOW:REJECTED", id, caller.principalId());
        return Map.of("status", "REJECTED", "approvalId", id);
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
            audit.recordDenial(caller, action, "", "", "OVERSIGHT_UNAVAILABLE", "");
            throw new OperatorException(HttpStatus.SERVICE_UNAVAILABLE, "OVERSIGHT_UNAVAILABLE");
        }
    }

    private OperatorException denied(AuthenticatedCaller caller, String action, ApprovalRequest found,
                                     ApprovalRefusedException refused) {
        String code;
        HttpStatus status;
        switch (refused.code()) {
            case UNKNOWN_APPROVAL -> { code = "APPROVAL_NOT_FOUND"; status = HttpStatus.NOT_FOUND; }
            case NOT_PENDING -> { code = "APPROVAL_NOT_PENDING"; status = HttpStatus.CONFLICT; }
            case EXPIRED -> { code = "APPROVAL_EXPIRED"; status = HttpStatus.CONFLICT; }
            case SELF_APPROVAL -> { code = "SELF_APPROVAL"; status = HttpStatus.CONFLICT; }
            default -> { code = "OVERSIGHT_UNAVAILABLE"; status = HttpStatus.SERVICE_UNAVAILABLE; }
        }
        audit.recordDenial(caller, action, found.tool(), found.scopeId(), code, found.approvalId());
        return new OperatorException(status, code);
    }

    /** Undoes an approval whose audit write failed, so nothing unaudited stays usable. */
    private void revoke(ApprovalRequest approval) {
        try {
            approvals.consumeApproved(Kind.TOOL_CALL, approval.requesterPrincipalId(), approval.scopeId(),
                    approval.tool(), approval.bindingFingerprint(), clock.instant());
        } catch (RuntimeException undoFailed) {
            // nothing more can be done; the operator is told the step failed
        }
    }

    private OperatorException unavailable(AuthenticatedCaller caller, String action, Target target) {
        audit.recordDenial(caller, action, target.entityType(), target.scopeId(), "OVERSIGHT_UNAVAILABLE", "");
        return new OperatorException(HttpStatus.SERVICE_UNAVAILABLE, "OVERSIGHT_UNAVAILABLE");
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

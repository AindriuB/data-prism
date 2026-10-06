package io.github.aindriub.dataprism.security;

import io.github.aindriub.dataprism.oversight.ApprovalRequest;
import io.github.aindriub.dataprism.oversight.ApprovalRequest.Kind;
import io.github.aindriub.dataprism.oversight.ApprovalRequest.Status;
import io.github.aindriub.dataprism.oversight.ApprovalStore;
import io.github.aindriub.dataprism.oversight.CallerRateLimiter;
import io.github.aindriub.dataprism.oversight.InMemoryApprovalStore;
import io.github.aindriub.dataprism.oversight.InMemoryCallerRateLimiter;
import io.github.aindriub.dataprism.oversight.InMemoryOversightState;
import io.github.aindriub.dataprism.oversight.OversightState;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * One admission check for an authenticated, authorised call: pauses, per-caller
 * rate limit, then human approval for high-impact tools. Any
 * {@link RuntimeException} from the backing state refuses the call.
 */
public class ToolAdmission {

    private final OversightState state;
    private final ApprovalStore approvals;
    private final CallerRateLimiter limiter;
    private final OversightPolicy policy;
    private final Clock clock;

    public ToolAdmission(OversightState state, ApprovalStore approvals, CallerRateLimiter limiter,
                         OversightPolicy policy, Clock clock) {
        this.state = Objects.requireNonNull(state, "state");
        this.approvals = Objects.requireNonNull(approvals, "approvals");
        this.limiter = Objects.requireNonNull(limiter, "limiter");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * The no-oversight-configured behaviour: admits every call. Equivalent to the
     * behaviour before admission existed.
     */
    public static ToolAdmission none() {
        return new ToolAdmission(new InMemoryOversightState(), new InMemoryApprovalStore(),
                new InMemoryCallerRateLimiter(), OversightPolicy.none(), Clock.systemUTC());
    }


    public AdmissionDecision admit(AuthenticatedCaller caller, String tool, String scopeId,
                                   String bindingFingerprint) {
        try {
            if (state.allPaused()) {
                return AdmissionDecision.refuse("DATAPRISM_PAUSED");
            }
            if (state.toolPaused(tool)) {
                return AdmissionDecision.refuse("TOOL_PAUSED");
            }
            if (state.scopePaused(scopeId)) {
                return AdmissionDecision.refuse("SCOPE_PAUSED");
            }
            Instant now = clock.instant();
            if (policy.callerRequestLimit().isPresent()
                    && !limiter.tryAcquire(caller.principalId(), policy.callerRequestLimit().getAsInt(),
                    policy.callerWindow(), now)) {
                return AdmissionDecision.refuse("CALLER_RATE_LIMITED");
            }
            if (!policy.approvalRequiredTools().contains(tool)) {
                return AdmissionDecision.admit();
            }
            return approvalStep(caller, tool, scopeId, bindingFingerprint, now);
        } catch (RuntimeException e) {
            return AdmissionDecision.refuse("OVERSIGHT_UNAVAILABLE");
        }
    }

    private AdmissionDecision approvalStep(AuthenticatedCaller caller, String tool, String scopeId,
                                           String fingerprint, Instant now) {
        Optional<ApprovalRequest> consumed = approvals.consumeApproved(
                Kind.TOOL_CALL, caller.principalId(), scopeId, tool, fingerprint, now);
        if (consumed.isPresent()) {
            ApprovalRequest r = consumed.get();
            return new AdmissionDecision(true, null, r.approvalId(), r.approverPrincipalId());
        }
        Optional<ApprovalRequest> pending = approvals.findPending(
                Kind.TOOL_CALL, caller.principalId(), scopeId, tool, fingerprint, now);
        if (pending.isPresent()) {
            return new AdmissionDecision(false, "APPROVAL_PENDING", pending.get().approvalId(), null);
        }
        ApprovalRequest created = approvals.create(new ApprovalRequest(
                UUID.randomUUID().toString(), Kind.TOOL_CALL, caller.principalId(), caller.clientId(),
                scopeId, tool, fingerprint, null, null, caller.purpose(), caller.caseId(),
                now, now.plus(policy.approvalTtl()), Status.PENDING, null, null));
        return new AdmissionDecision(false, "APPROVAL_REQUIRED", created.approvalId(), null);
    }
}

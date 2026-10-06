package io.github.aindriub.dataprism.oversight;

import io.github.aindriub.dataprism.oversight.ApprovalRequest.Kind;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Pending and decided approval requests. Any {@link RuntimeException} from an
 * implementation means "unavailable", and callers fail closed.
 */
public interface ApprovalStore {

    /**
     * @throws IllegalArgumentException if the request is not {@code PENDING} or its id is already stored in
     *                                  any status, in any scope; stored state is unchanged
     */
    ApprovalRequest create(ApprovalRequest pending);

    Optional<ApprovalRequest> find(String approvalId);

    Optional<ApprovalRequest> findPending(Kind kind, String requesterPrincipalId, String scopeId, String tool,
                                          String bindingFingerprint, Instant now);

    /**
     * @throws ApprovalRefusedException when unknown, not pending, expired, or approved by its requester
     * @throws NullPointerException     for a null approver, before any state changes
     */
    ApprovalRequest approve(String approvalId, String approverPrincipalId, Instant now);

    /** @throws ApprovalRefusedException when unknown, not pending or expired */
    ApprovalRequest reject(String approvalId, String approverPrincipalId, Instant now);

    /** Atomically moves a matching approved, unexpired request to CONSUMED. Succeeds once. */
    Optional<ApprovalRequest> consumeApproved(Kind kind, String requesterPrincipalId, String scopeId, String tool,
                                              String bindingFingerprint, Instant now);

    List<ApprovalRequest> pending(Instant now);

    void forgetScope(String scopeId);
}

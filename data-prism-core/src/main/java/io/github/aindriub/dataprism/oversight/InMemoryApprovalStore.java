package io.github.aindriub.dataprism.oversight;

import io.github.aindriub.dataprism.oversight.ApprovalRefusedException.Code;
import io.github.aindriub.dataprism.oversight.ApprovalRequest.Kind;
import io.github.aindriub.dataprism.oversight.ApprovalRequest.Status;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Per-instance approvals. A deployment with several instances uses the Hazelcast implementation. */
public final class InMemoryApprovalStore implements ApprovalStore {

    private final Map<String, ApprovalRequest> requests = new LinkedHashMap<>();

    @Override
    public synchronized ApprovalRequest create(ApprovalRequest pending) {
        Objects.requireNonNull(pending, "pending");
        if (pending.status() != Status.PENDING) {
            throw new IllegalArgumentException("a new approval must be PENDING");
        }
        if (requests.containsKey(pending.approvalId())) {
            throw new IllegalArgumentException("duplicate approval id");
        }
        requests.put(pending.approvalId(), pending);
        return pending;
    }

    @Override
    public synchronized ApprovalRequest create(ApprovalRequest pending, int maxLivePendingPerRequester) {
        if (maxLivePendingPerRequester <= 0) {
            throw new IllegalArgumentException("maxLivePendingPerRequester must be positive");
        }
        Objects.requireNonNull(pending, "pending");
        if (pending.status() != Status.PENDING) {
            throw new IllegalArgumentException("a new approval must be PENDING");
        }
        long live = requests.values().stream()
                .filter(r -> r.status() == Status.PENDING
                        && r.kind() == pending.kind()
                        && Objects.equals(r.requesterPrincipalId(), pending.requesterPrincipalId())
                        && pending.createdAt().isBefore(r.expiresAt()))
                .count();
        if (live >= maxLivePendingPerRequester) {
            throw new ApprovalRefusedException(Code.TOO_MANY_PENDING);
        }
        return create(pending);
    }

    @Override
    public synchronized Optional<ApprovalRequest> find(String approvalId) {
        return Optional.ofNullable(requests.get(approvalId));
    }

    @Override
    public synchronized Optional<ApprovalRequest> findPending(Kind kind, String principal, String scopeId,
                                                              String tool, String fingerprint, Instant now) {
        return match(Status.PENDING, kind, principal, scopeId, tool, fingerprint, now);
    }

    @Override
    public synchronized ApprovalRequest approve(String approvalId, String approver, Instant now) {
        Objects.requireNonNull(approver, "approver");
        ApprovalRequest request = pendingOrRefuse(approvalId, now);
        if (Objects.equals(request.requesterPrincipalId(), approver)) {
            throw new ApprovalRefusedException(Code.SELF_APPROVAL);
        }
        return store(request.withDecision(Status.APPROVED, approver, now));
    }

    @Override
    public synchronized ApprovalRequest reject(String approvalId, String approver, Instant now) {
        return store(pendingOrRefuse(approvalId, now).withDecision(Status.REJECTED, approver, now));
    }

    @Override
    public synchronized Optional<ApprovalRequest> consumeApproved(Kind kind, String principal, String scopeId,
                                                                  String tool, String fingerprint, Instant now) {
        return match(Status.APPROVED, kind, principal, scopeId, tool, fingerprint, now)
                .map(r -> store(r.withStatus(Status.CONSUMED)));
    }

    @Override
    public synchronized List<ApprovalRequest> pending(Instant now) {
        return requests.values().stream()
                .filter(r -> r.status() == Status.PENDING && now.isBefore(r.expiresAt()))
                .toList();
    }

    @Override
    public synchronized void forgetScope(String scopeId) {
        requests.values().removeIf(r -> Objects.equals(r.scopeId(), scopeId));
    }

    private ApprovalRequest pendingOrRefuse(String approvalId, Instant now) {
        ApprovalRequest request = requests.get(approvalId);
        if (request == null) {
            throw new ApprovalRefusedException(Code.UNKNOWN_APPROVAL);
        }
        if (request.status() != Status.PENDING) {
            throw new ApprovalRefusedException(Code.NOT_PENDING);
        }
        if (!now.isBefore(request.expiresAt())) {
            store(request.withStatus(Status.EXPIRED));
            throw new ApprovalRefusedException(Code.EXPIRED);
        }
        return request;
    }

    private Optional<ApprovalRequest> match(Status status, Kind kind, String principal, String scopeId,
                                            String tool, String fingerprint, Instant now) {
        return requests.values().stream()
                .filter(r -> r.status() == status
                        && r.kind() == kind
                        && Objects.equals(r.requesterPrincipalId(), principal)
                        && Objects.equals(r.scopeId(), scopeId)
                        && Objects.equals(r.tool(), tool)
                        && Objects.equals(r.bindingFingerprint(), fingerprint)
                        && now.isBefore(r.expiresAt()))
                .findFirst();
    }

    private ApprovalRequest store(ApprovalRequest request) {
        requests.put(request.approvalId(), request);
        return request;
    }
}

package io.github.aindriub.dataprism.hazelcast;

import com.hazelcast.map.EntryProcessor;
import com.hazelcast.map.ExtendedMapEntry;
import com.hazelcast.map.IMap;
import io.github.aindriub.dataprism.core.metrics.Metric;
import io.github.aindriub.dataprism.core.metrics.PrivacyMetrics;
import io.github.aindriub.dataprism.core.refusal.ClusterLockTimeoutException;
import io.github.aindriub.dataprism.core.refusal.ClusterOperationRefusedException;
import io.github.aindriub.dataprism.core.refusal.PrivacyRefusedException;
import io.github.aindriub.dataprism.oversight.ApprovalRefusedException;
import io.github.aindriub.dataprism.oversight.ApprovalRefusedException.Code;
import io.github.aindriub.dataprism.oversight.ApprovalRequest;
import io.github.aindriub.dataprism.oversight.ApprovalRequest.Kind;
import io.github.aindriub.dataprism.oversight.ApprovalRequest.Status;
import io.github.aindriub.dataprism.oversight.ApprovalStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Map;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Approvals shared across the cluster, so one granted on one instance is
 * consumable on any instance, exactly once.
 *
 * <p>Fails closed: a cluster failure is never turned into success. {@link #create} reports one as
 * {@link ClusterOperationRefusedException} (a lost write quorum keeps its own exception) and the other calls let it
 * propagate. Only the undo and clean-up steps after a change that has already happened swallow a failure, and they
 * can only leave an id unusable or a requester over-counted until expiry. Entries are stored
 * as strings, like every other key and value in the cluster, so no serialiser
 * has to agree across members. A record holds principal ids, purpose, case id,
 * fingerprint, namespace and the synthetic value, and no subject id, because
 * {@link ApprovalRequest} has no field for one. Each entry lives until the
 * request's {@code expiresAt}, then the cluster drops it.
 *
 * <p>A state change is a compare-and-set: it replaces the exact value it read, so of any number of
 * callers racing on one entry, on any members, one wins. That is what makes {@link #consumeApproved}
 * succeed once. It holds the entry's lock as well, taken with a timeout, because a mutating call on a
 * key someone else has locked would otherwise wait for that lock for ever; the lock only bounds the
 * wait, correctness does not rest on it. A held lock refuses the call with
 * {@link ClusterLockTimeoutException}. Every lock has a lease of five times the timeout. Every lock-aware call on
 * the approval maps runs while this thread holds the key's lock, so none waits for a lock another member holds
 * beyond the timeout; a call that takes several locks waits at most the timeout for each. A decision commits the new
 * value and its expiry in one step on the entry, so it either takes effect with its expiry or not at all. After it
 * has committed, the release of the requester's pending slot is bounded too, and when it cannot be done the slot
 * stays counted (an over-count, which the sweep removes) and {@link Metric#APPROVAL_RELEASE_SKIPPED} is incremented;
 * a decision that committed is never reported as failed by this store's own steps. One residual remains: when the
 * response of the remote compare-and-set is lost or times out (for example {@code OperationTimeoutException}), the
 * call throws although the change may have happened. A retried call that finds its own value is recognised in
 * {@code decide}, but a lost response cannot be. A caller must re-read the status with {@link #find} before treating
 * a failed {@code approve} or {@code reject} as final. Creation rests on
 * {@code putIfAbsent} too: an id is claimed in {@link PrivacyCluster#APPROVAL_ID_MAP} (a claim is valid while the
 * entry it names exists, with no clock involved; see {@link ClaimEntries} for why a paused creator cannot get a
 * duplicate past a takeover), and the per-requester cap
 * is one entry processor on {@link PrivacyCluster#APPROVAL_PENDING_MAP}, so neither check depends on a lock still
 * being held. Each creation carries a random token, kept in its entry, its id claim and its pending slot, and every
 * release, removal and takeover compares it, so a clean-up that belongs to one creation of an id never touches
 * a later creation of the same id in the same scope. An entry is usable only while the claim for its id names its
 * entry key and token; otherwise it is treated as unknown. Under a partition each side can still change its own
 * copy once, which the split-brain protection and {@link ApprovalMergePolicy} on the map narrow (see
 * {@code docs/multiple-instances.md}).
 */
public final class HazelcastApprovalStore implements ApprovalStore {

    private static final Logger log = LoggerFactory.getLogger(HazelcastApprovalStore.class);

    /**
     * How long an entry we mark expired stays visible, so a late approver sees EXPIRED.
     */
    private static final Duration DEFAULT_RETENTION = Duration.ofMinutes(1);

    private final PrivacyCluster cluster;
    private final Duration lockTimeout;
    private final Duration retention;
    private final PrivacyMetrics metrics;

    /** With the default lock timeout of two seconds. */
    public HazelcastApprovalStore(PrivacyCluster cluster) {
        this(cluster, ClusterLocks.DEFAULT_TIMEOUT);
    }

    /**
     * @param lockTimeout how long a call waits for a lock before it refuses, {@code PT100MS} to {@code PT30S}
     * @throws IllegalArgumentException if the timeout is outside that range
     */
    public HazelcastApprovalStore(PrivacyCluster cluster, Duration lockTimeout) {
        this(cluster, lockTimeout, PrivacyMetrics.none());
    }

    /**
     * @param metrics receives {@link Metric#APPROVAL_RELEASE_SKIPPED}; a failure of the metrics never fails a call
     * @throws IllegalArgumentException if the timeout is outside {@code PT100MS..PT30S}
     */
    public HazelcastApprovalStore(PrivacyCluster cluster, Duration lockTimeout, PrivacyMetrics metrics) {
        this(cluster, lockTimeout, metrics, true);
    }

    private HazelcastApprovalStore(PrivacyCluster cluster, Duration lockTimeout, PrivacyMetrics metrics,
                                   boolean tellClusterTheTimeout) {
        this.cluster = Objects.requireNonNull(cluster, "cluster");
        this.lockTimeout = ClusterLocks.checked(lockTimeout);
        this.retention = DEFAULT_RETENTION;
        this.metrics = FailSafeMetrics.wrap(metrics);
        if (tellClusterTheTimeout) {
            cluster.useLockTimeout(this.lockTimeout);
        }
    }

    /**
     * A store that only ends scopes. It does not tell the cluster's sweeps which lock timeout is in use, because
     * the store that serves the application already did.
     */
    static HazelcastApprovalStore forScopeEnd(PrivacyCluster cluster, Duration lockTimeout, PrivacyMetrics metrics) {
        return new HazelcastApprovalStore(cluster, lockTimeout, metrics, false);
    }

    /**
     * @throws IllegalArgumentException if the request is not {@code PENDING} or its id is already used
     * @throws ClusterOperationRefusedException if the cluster rejected a lock or an index operation
     */
    @Override
    public ApprovalRequest create(ApprovalRequest pending) {
        Objects.requireNonNull(pending, "pending");
        return admit(pending, Integer.MAX_VALUE);
    }

    /**
     * Creates unless the requester already has {@code maxLivePendingPerRequester} live pending approvals of this
     * kind. The count lives in one entry per (kind, requester) that an entry processor changes, so the check and
     * the add are one step and no lock has to outlive its lease for the cap to hold.
     *
     * @throws ApprovalRefusedException         {@code TOO_MANY_PENDING} at the cap
     * @throws ClusterOperationRefusedException if the cluster rejected a lock or an index or processor operation
     */
    @Override
    public ApprovalRequest create(ApprovalRequest pending, int maxLivePendingPerRequester) {
        if (maxLivePendingPerRequester <= 0) {
            throw new IllegalArgumentException("maxLivePendingPerRequester must be positive");
        }
        Objects.requireNonNull(pending, "pending");
        return admit(pending, maxLivePendingPerRequester);
    }

    /**
     * Claims the id, then reserves the cap, then inserts, and undoes in reverse order when a step fails. A failed
     * undo can leave an id unusable or a requester over-counted until expiry; no undo or release removes a claim,
     * a slot or an entry that another creation of the same id owns, because each carries this call's random token
     * and is removed only if it still has it (see {@link PendingSet} and {@link ClaimEntries}). The locks it
     * takes on the keys it writes only bound the wait.
     *
     * <p>If the claim cannot be read back after the insert, the outcome is unknown. The call then tries to remove
     * its own entry by token, while it still holds the entry's lock, and refuses with
     * {@link ClusterOperationRefusedException}. If that removal fails too, the entry stays. It is usable only while
     * the claim for its id names it (see {@link #find}), and then it is a {@code PENDING} approval that still needs
     * an operator's decision: it counts against the requester's cap and ends on its expiry.
     */
    private ApprovalRequest admit(ApprovalRequest pending, int cap) {
        if (pending.status() != Status.PENDING) {
            throw new IllegalArgumentException("a new approval must be PENDING");
        }
        IMap<String, String> approvals = approvals();
        IMap<String, String> claims = idClaims();
        IMap<String, String> pendingSets = pendingSets();
        String id = pending.approvalId();
        String claimKey = keys().claim(id);
        String entryKey = keys().approval(pending.scopeId(), id);
        String pendingKey = pendingKey(pending.kind(), pending.requesterPrincipalId());
        long ttl = Math.max(1L, Duration.between(pending.createdAt(), pending.expiresAt()).toMillis());
        String token = UUID.randomUUID().toString();
        String owner = PendingSet.ownerOf(pending.scopeId(), token);
        String mine = ClaimEntries.value(entryKey, token);
        String encoded = Codec.encode(pending, token);
        try (HeldLocks locks = new HeldLocks()) {
            locks.take(claims, claimKey);
            locks.take(pendingSets, pendingKey);
            locks.take(approvals, entryKey);
            if (!cluster(() -> ClaimEntries.claim(claims, approvals, claimKey, entryKey, token))) {
                throw new IllegalArgumentException("duplicate approval id");
            }
            boolean reserved;
            try {
                reserved = cluster(() -> pendingSets.executeOnKey(pendingKey,
                        new PendingSet.Reserve(id, owner, pending.createdAt(), pending.expiresAt(), cap)));
            } catch (RuntimeException failed) {
                unclaim(claims, claimKey, entryKey, token);
                throw failed;
            }
            if (!reserved) {
                unclaim(claims, claimKey, entryKey, token);
                throw new ApprovalRefusedException(Code.TOO_MANY_PENDING);
            }
            // The claim may have been taken over since this caller claimed it. Then the slot reserved above may be
            // the new owner's: leave it (an over-count is the safe side) and do not insert.
            if (!mine.equals(cluster(() -> claims.get(claimKey)))) {
                throw new IllegalArgumentException("duplicate approval id");
            }
            String existing;
            try {
                existing = cluster(() -> approvals.putIfAbsent(entryKey, encoded, ttl, TimeUnit.MILLISECONDS));
            } catch (RuntimeException failed) {
                // Only a confirmed absence is undone: if the insert may have happened, keeping the claim and the
                // count errs towards refusing.
                if (confirmedAbsent(approvals, entryKey)) {
                    unreserve(pendingSets, pendingKey, id, owner);
                    unclaim(claims, claimKey, entryKey, token);
                }
                throw failed;
            }
            if (existing != null) {
                // An entry is already here. Its id stays claimed and counted, as it was before this call.
                throw new IllegalArgumentException("duplicate approval id");
            }
            // The claim may have been taken over while this caller was stopped before the insert (see
            // ClaimEntries). Keep the entry only if the claim still names it. The id stays counted: it may now
            // be the new owner's, and an over-count is the safe side.
            String after;
            try {
                after = cluster(() -> claims.get(claimKey));
            } catch (RuntimeException unknown) {
                withdraw(approvals, entryKey, token);
                throw unknown;
            }
            if (!mine.equals(after)) {
                withdraw(approvals, entryKey, token);
                throw new IllegalArgumentException("duplicate approval id");
            }
        }
        return pending;
    }

    private String pendingKey(Kind kind, String requesterPrincipalId) {
        return keys().pending(kind, requesterPrincipalId);
    }

    private static boolean confirmedAbsent(IMap<String, String> approvals, String entryKey) {
        try {
            return !approvals.containsKey(entryKey);
        } catch (RuntimeException unknown) {
            return false;
        }
    }

    /**
     * Removes this caller's own entry, whatever its status, after it lost its claim or could not confirm it. Only an
     * entry with this creation's token goes; one from another creation of the same id stays. The caller holds the
     * entry's lock. A failure leaves the entry, which every lookup refuses unless the claim for its id names it.
     */
    private static void withdraw(IMap<String, String> approvals, String entryKey, String token) {
        try {
            for (int attempt = 0; attempt < 3; attempt++) {
                String raw = approvals.get(entryKey);
                if (raw == null || !token.equals(Codec.tokenOrNull(raw))) {
                    return;
                }
                if (approvals.remove(entryKey, raw)) {
                    return;
                }
            }
        } catch (RuntimeException ignored) {
            // The entry is usable only while its claim names it (see valid), which fails closed.
        }
    }

    /**
     * Releases a claim only if it is still this creation's. The caller holds the id's lock. Best effort: a failure
     * leaves the id unusable.
     */
    private static void unclaim(IMap<String, String> claims, String id, String entryKey, String token) {
        try {
            ClaimEntries.release(claims, id, entryKey, token);
        } catch (RuntimeException ignored) {
            // The claim is swept once its approval is gone; the id is unusable until then, never reusable early.
        }
    }

    /**
     * Best effort, and only for the slot this creation owns: a failure leaves the requester over-counted until
     * expiry. The caller holds the pending key's lock.
     */
    private static void unreserve(IMap<String, String> pendingSets, String pendingKey, String approvalId,
                                  String owner) {
        try {
            pendingSets.executeOnKey(pendingKey, new PendingSet.Release(approvalId, owner));
        } catch (RuntimeException ignored) {
            // The id is dropped by the reserve prune once it expires, and by the sweep after that.
        }
    }

    /**
     * Any failure of a cluster call that is not already a refusal becomes {@link ClusterOperationRefusedException},
     * with no key, scope or id in its message. A lost write quorum keeps its own exception, which the
     * deployment documentation names.
     */
    private static <T> T cluster(Supplier<T> call) {
        try {
            return call.get();
        } catch (RuntimeException failure) {
            throw ClusterRefusals.mapped(failure);
        }
    }

    /** Bounded locks, released in reverse order. They bound waits only; nothing relies on still holding one. */
    private final class HeldLocks implements AutoCloseable {
        private final Deque<Runnable> releases = new ArrayDeque<>();

        void take(IMap<String, String> map, String key) {
            try {
                ClusterLocks.acquire(map, key, lockTimeout);
            } catch (RuntimeException failure) {
                close();
                throw ClusterRefusals.mapped(failure);
            }
            releases.push(() -> ClusterLocks.release(map, key));
        }

        @Override
        public void close() {
            while (!releases.isEmpty()) {
                releases.pop().run();
            }
        }
    }

    /**
     * Removes an approval that left {@code PENDING} from its requester's count, after the decision has committed.
     * It takes the pending key's lock, waiting only for what is left of {@code deadlineNanos}. If it cannot, or the
     * release fails, the slot stays counted (an over-count, which the sweep removes), the metric is incremented,
     * and the decision stands.
     *
     * @return false if the release was skipped
     */
    private boolean leavePending(ApprovalRequest request, String token, long deadlineNanos) {
        IMap<String, String> pendingSets;
        String key;
        try {
            pendingSets = pendingSets();
            key = pendingKey(request.kind(), request.requesterPrincipalId());
        } catch (RuntimeException failed) {
            metrics.increment(Metric.APPROVAL_RELEASE_SKIPPED);
            throw ClusterRefusals.mapped(failed);
        }
        if (!lockOrSkip(pendingSets, key, deadlineNanos)) {
            return false;
        }
        try {
            pendingSets.executeOnKey(key, new PendingSet.Release(request.approvalId(),
                    PendingSet.ownerOf(request.scopeId(), token)));
            return true;
        } catch (RuntimeException failed) {
            metrics.increment(Metric.APPROVAL_RELEASE_SKIPPED);
            throw ClusterRefusals.mapped(failed);
        } finally {
            ClusterLocks.release(pendingSets, key);
        }
    }

    /** Releases the claim of a deleted entry, bounded like {@link #leavePending}; false if it was skipped. */
    private boolean releaseClaim(String approvalId, String entryKey, String token, long deadlineNanos) {
        String id = keys().claim(approvalId);
        IMap<String, String> claims;
        try {
            claims = idClaims();
        } catch (RuntimeException failed) {
            metrics.increment(Metric.APPROVAL_RELEASE_SKIPPED);
            throw ClusterRefusals.mapped(failed);
        }
        if (!lockOrSkip(claims, id, deadlineNanos)) {
            return false;
        }
        try {
            ClaimEntries.release(claims, id, entryKey, token);
            return true;
        } catch (RuntimeException failed) {
            metrics.increment(Metric.APPROVAL_RELEASE_SKIPPED);
            throw ClusterRefusals.mapped(failed);
        } finally {
            ClusterLocks.release(claims, id);
        }
    }

    /**
     * Takes a lock for a release after a commit, waiting only for what is left of the deadline.
     * Every failure is counted. Only a lock timeout skips the release; any other failure (a refusal by the cluster,
     * or a Hazelcast fault) is not a timeout and is thrown as a refusal, so the caller can report it.
     */
    private boolean lockOrSkip(IMap<String, String> map, String key, long deadlineNanos) {
        try {
            ClusterLocks.acquireUntil(map, key, deadlineNanos, lockTimeout);
            return true;
        } catch (ClusterLockTimeoutException timedOut) {
            metrics.increment(Metric.APPROVAL_RELEASE_SKIPPED);
            return false;
        } catch (PrivacyRefusedException refused) {
            metrics.increment(Metric.APPROVAL_RELEASE_SKIPPED);
            throw refused;
        } catch (RuntimeException failed) {
            // A cluster fault on a guard lock is not a timeout: refuse it, so the caller reports it as one.
            metrics.increment(Metric.APPROVAL_RELEASE_SKIPPED);
            throw ClusterRefusals.mapped(failed);
        }
    }

    /**
     * The approval with this id, if there is one and the claim for its id names its entry key and token. An entry
     * the claim does not name (a withdrawal that failed, a claim lost after the insert, a heal that paired it with
     * another creation's claim) or one that cannot be read is unknown.
     */
    @Override
    public Optional<ApprovalRequest> find(String approvalId) {
        return locate(approvalId).map(Stored::request);
    }

    @Override
    public Optional<ApprovalRequest> findPending(Kind kind, String principal, String scopeId, String tool,
                                                 String fingerprint, Instant now) {
        return matches(Status.PENDING, kind, principal, scopeId, tool, fingerprint, now).stream().findFirst();
    }

    @Override
    public ApprovalRequest approve(String approvalId, String approver, Instant now) {
        Objects.requireNonNull(approver, "approver");
        return decide(approvalId, now, request -> {
            if (Objects.equals(request.requesterPrincipalId(), approver)) {
                throw new ApprovalRefusedException(Code.SELF_APPROVAL);
            }
            return request.withDecision(Status.APPROVED, approver, now);
        });
    }

    @Override
    public ApprovalRequest reject(String approvalId, String approver, Instant now) {
        return decide(approvalId, now, request -> request.withDecision(Status.REJECTED, approver, now));
    }

    @Override
    public Optional<ApprovalRequest> consumeApproved(Kind kind, String principal, String scopeId, String tool,
                                                     String fingerprint, Instant now) {
        IMap<String, String> approvals = approvals();
        for (String key : keysOf(scopeId)) {
            // Reads never wait on a lock, so only an entry that matches is locked.
            Optional<Stored> seen = valid(key, approvals.get(key));
            if (seen.isEmpty() || !isMatch(seen.get().request(), Status.APPROVED, kind, principal, scopeId, tool,
                    fingerprint, now)) {
                continue;
            }
            ClusterLocks.acquire(approvals, key, lockTimeout);
            try {
                String raw = approvals.get(key);
                Optional<Stored> current = valid(key, raw);
                if (current.isEmpty()) {
                    continue;
                }
                ApprovalRequest request = current.get().request();
                if (isMatch(request, Status.APPROVED, kind, principal, scopeId, tool, fingerprint, now)) {
                    ApprovalRequest consumed = request.withStatus(Status.CONSUMED);
                    // Only the caller whose compare-and-set sees the value it read has consumed it.
                    if (commit(approvals, key, raw, consumed, current.get().token(), now)) {
                        return Optional.of(consumed);
                    }
                }
            } finally {
                ClusterLocks.release(approvals, key);
            }
        }
        return Optional.empty();
    }

    @Override
    public List<ApprovalRequest> pending(Instant now) {
        List<ApprovalRequest> found = new ArrayList<>();
        for (Map.Entry<String, String> entry : approvals().entrySet()) {
            Optional<Stored> stored = valid(entry.getKey(), entry.getValue());
            if (stored.isPresent() && stored.get().request().status() == Status.PENDING
                    && now.isBefore(stored.get().request().expiresAt())) {
                found.add(stored.get().request());
            }
        }
        return found;
    }

    /**
     * Removes a scope's approvals with their id claims and pending counts, so the ids and the requesters' slots
     * are free again. Shared with {@link ScopeIdentityIndex#endScope}. Each entry is handled in order: its lock is
     * taken (for at most the lock timeout), the entry is removed by compare-and-set on the value read, and only then
     * are its pending slot and its claim released, each under its own bounded lock and by the entry's token. A guard
     * is never released while its entry exists.
     *
     * @throws ClusterLockTimeoutException after the last entry, if the lock of any entry timed out: that entry, its
     *                                     claim and its slot are left intact. State is over-counted, never
     *                                     half-released. An entry that cannot be read is removed and counted in
     *                                     {@link Metric#APPROVAL_RELEASE_SKIPPED}, and its claim and slot are
     *                                     left. A release skipped after the entry was removed is also
     *                                     counted in {@link Metric#APPROVAL_RELEASE_SKIPPED} and makes the call
     *                                     throw, so the caller learns the slot or claim was left behind
     */
    @Override
    public void forgetScope(String scopeId) {
        forgetScope(scopeId, System.nanoTime() + lockTimeout.toNanos());
    }

    /**
     * As {@link #forgetScope(String)}, with its lock waits bounded by a deadline the caller fixed ({@link
     * System#nanoTime()} units) so several steps can share one. A refusal or Hazelcast failure on one entry does not
     * stop the others; the first is thrown after the last entry, ahead of a lock timeout.
     */
    void forgetScope(String scopeId, long deadlineNanos) {
        String prefix = keys().approvalPrefix(scopeId);
        int skipped = 0;
        RuntimeException failure = null;
        for (String key : ScopeKeys.keysWithPrefix(approvals(), prefix)) {
            try {
                if (!forgetOne(key, deadlineNanos)) {
                    skipped++;
                }
            } catch (ClusterLockTimeoutException held) {
                skipped++;
            } catch (RuntimeException failed) {
                failure = failure != null ? failure : ClusterRefusals.mapped(failed);
            }
        }
        if (failure != null) {
            throw failure;
        }
        if (skipped > 0) {
            throw new ClusterLockTimeoutException();
        }
    }

    /**
     * Returns false if the entry's lock timed out and the entry was left as it is, or if the entry was removed but
     * its pending slot or its claim could not be released within the deadline.
     */
    private boolean forgetOne(String key, long deadlineNanos) {
        IMap<String, String> approvals = approvals();
        try {
            ClusterLocks.acquireUntil(approvals, key, deadlineNanos, lockTimeout);
        } catch (ClusterLockTimeoutException timedOut) {
            return false;
        }
        Stored stored = null;
        try {
            String raw = approvals.get(key);
            if (raw == null) {
                return true;
            }
            try {
                stored = Codec.decodeStored(raw);
            } catch (RuntimeException unreadable) {
                // No token, so nothing it guards can be released by token. Remove it; its claim and slot stay,
                // which is counted and reported to the caller.
                if (!approvals.remove(key, raw) && approvals.get(key) != null) {
                    return false;
                }
                metrics.increment(Metric.APPROVAL_RELEASE_SKIPPED);
                return false;
            }
            // A remove that reports false for an entry that is gone (a retried call, or a change after the read)
            // is not a lock timeout: the entry is gone, so its guards are released by token below.
            if (!approvals.remove(key, raw) && approvals.get(key) != null) {
                return false;
            }
        } finally {
            ClusterLocks.release(approvals, key);
        }
        // Both releases are tried even if the first is skipped, and a skip of either is reported to the caller.
        RuntimeException refused = null;
        boolean slot = false;
        boolean claim = false;
        try {
            slot = leavePending(stored.request(), stored.token(), deadlineNanos);
        } catch (RuntimeException failed) {
            refused = failed;
        }
        try {
            claim = releaseClaim(stored.request().approvalId(), key, stored.token(), deadlineNanos);
        } catch (RuntimeException failed) {
            refused = refused != null ? refused : failed;
        }
        if (refused != null) {
            throw refused;
        }
        return slot && claim;
    }

    private ApprovalRequest decide(String approvalId, Instant now, Function<ApprovalRequest, ApprovalRequest> next) {
        IMap<String, String> approvals = approvals();
        Stored located = locate(approvalId).orElseThrow(() -> new ApprovalRefusedException(Code.UNKNOWN_APPROVAL));
        String key = keys().approval(located.request().scopeId(), located.request().approvalId());
        ApprovalRequest request;
        String token;
        ApprovalRequest decided;
        ClusterLocks.acquire(approvals, key, lockTimeout);
        try {
            String raw = approvals.get(key);
            Stored stored = valid(key, raw).orElseThrow(() -> new ApprovalRefusedException(Code.UNKNOWN_APPROVAL));
            request = stored.request();
            token = stored.token();
            if (request.status() != Status.PENDING) {
                throw new ApprovalRefusedException(Code.NOT_PENDING);
            }
            if (!now.isBefore(request.expiresAt())) {
                decided = null;
                if (!committed(approvals, key, raw, request.withStatus(Status.EXPIRED), token, now)) {
                    // Someone decided between the read and the commit; their release is the only one.
                    throw new ApprovalRefusedException(Code.NOT_PENDING);
                }
            } else {
                decided = next.apply(request);
                if (!committed(approvals, key, raw, decided, token, now)) {
                    // Someone changed the entry between the read and the commit: they decided first.
                    throw new ApprovalRefusedException(Code.NOT_PENDING);
                }
            }
        } finally {
            ClusterLocks.release(approvals, key);
        }
        // The entry's lock is released before the pending key is touched, so no thread holds the two in the order
        // opposite to admit. The decision has committed: nothing below can fail the call.
        try {
            leavePending(request, token, System.nanoTime() + lockTimeout.toNanos());
        } catch (RuntimeException failed) {
            // Any failure here, a lost quorum included, is counted by leavePending or lockOrSkip. The slot stays
            // counted until the sweep and the decision stands. Only the class is logged: a message can echo a key.
            log.warn("approval pending-slot release failed after the decision committed; cause {}",
                    failed.getClass().getSimpleName());
        }
        if (decided == null) {
            throw new ApprovalRefusedException(Code.EXPIRED);
        }
        return decided;
    }

    private List<ApprovalRequest> matches(Status status, Kind kind, String principal, String scopeId,
                                          String tool, String fingerprint, Instant now) {
        IMap<String, String> approvals = approvals();
        List<ApprovalRequest> found = new ArrayList<>();
        for (String key : keysOf(scopeId)) {
            Optional<Stored> stored = valid(key, approvals.get(key));
            if (stored.isPresent()
                    && isMatch(stored.get().request(), status, kind, principal, scopeId, tool, fingerprint, now)) {
                found.add(stored.get().request());
            }
        }
        return found;
    }

    private static boolean isMatch(ApprovalRequest r, Status status, Kind kind, String principal, String scopeId,
                                   String tool, String fingerprint, Instant now) {
        return r.status() == status
                && r.kind() == kind
                && Objects.equals(r.requesterPrincipalId(), principal)
                && Objects.equals(r.scopeId(), scopeId)
                && Objects.equals(r.tool(), tool)
                && Objects.equals(r.bindingFingerprint(), fingerprint)
                && now.isBefore(r.expiresAt());
    }

    /** A stored approval with the token of the creation that wrote it. */
    private record Stored(ApprovalRequest request, String token) {
    }

    /**
     * The stored approval at {@code entryKey}, if {@code raw} can be read and the claim for its id names exactly this
     * entry key and token. The claim is read with {@code get}, which never waits. Anything else is unknown.
     */
    private Optional<Stored> valid(String entryKey, String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        Stored stored;
        try {
            stored = Codec.decodeStored(raw);
        } catch (RuntimeException unreadable) {
            return Optional.empty();
        }
        ApprovalRequest request = stored.request();
        if (!entryKey.equals(keys().approval(request.scopeId(), request.approvalId()))) {
            return Optional.empty();
        }
        String claim = idClaims().get(keys().claim(request.approvalId()));
        return ClaimEntries.value(entryKey, stored.token()).equals(claim) ? Optional.of(stored) : Optional.empty();
    }

    /**
     * Replaces {@code key}'s value with {@code next} only if it is still {@code raw}, in one step that also gives it
     * the expiry it needs (a plain replace would reset it to none). Either the new value is in place with its
     * expiry, or nothing changed. Returns whether this caller made the change. The caller holds the entry's lock.
     */
    private boolean commit(IMap<String, String> approvals, String key, String raw, ApprovalRequest next,
                           String token, Instant now) {
        Duration remaining = Duration.between(now, next.expiresAt());
        if (remaining.isNegative() || remaining.isZero()) {
            remaining = retention;
        }
        return Boolean.TRUE.equals(approvals.executeOnKey(key,
                new Commit(raw, Codec.encode(next, token), Math.max(1L, remaining.toMillis()))));
    }

    /**
     * {@link #commit}, for a decision. A remote call can be retried after the first attempt already applied (a member
     * left, then the invocation was retried against the backup), and the retry finds its own value and returns false.
     * The caller holds the entry's lock, so only this decision could have written exactly that value: if the entry
     * now holds it, the decision committed. Not used by {@link #consumeApproved}, where two consumers write the same
     * value and a false must keep refusing.
     */
    private boolean committed(IMap<String, String> approvals, String key, String raw, ApprovalRequest next,
                              String token, Instant now) {
        return commit(approvals, key, raw, next, token, now)
                || Codec.encode(next, token).equals(approvals.get(key));
    }

    /** Compares the stored value with the one read, and if equal sets the new value together with its expiry. */
    record Commit(String expected, String next, long ttlMillis) implements EntryProcessor<String, String, Boolean> {
        @Override
        public Boolean process(Map.Entry<String, String> entry) {
            if (!expected.equals(entry.getValue())) {
                return false;
            }
            ((ExtendedMapEntry<String, String>) entry).setValue(next, ttlMillis, TimeUnit.MILLISECONDS);
            return true;
        }
    }

    private List<String> keysOf(String scopeId) {
        String prefix = keys().approvalPrefix(scopeId);
        return ScopeKeys.keysWithPrefix(approvals(), prefix);
    }

    /**
     * The one usable entry for an id: the entry its claim names, if that entry is there and still names this claim
     * back. One id has one claim across every scope (the claim key has no scope token), so this is a single get of
     * the claim and a single get of the entry it names; an id never matches several entries.
     */
    private Optional<Stored> locate(String approvalId) {
        String claim = idClaims().get(keys().claim(approvalId));
        Optional<ClaimEntries.Named> named = ClaimEntries.read(claim);
        if (named.isEmpty()) {
            return Optional.empty();
        }
        String entryKey = named.get().entryKey();
        return valid(entryKey, approvals().get(entryKey))
                .filter(stored -> stored.request().approvalId().equals(approvalId));
    }

    private ScopeKeys keys() {
        return cluster.scopeKeys();
    }

    private IMap<String, String> idClaims() {
        return cluster.instance().getMap(PrivacyCluster.APPROVAL_ID_MAP);
    }

    private IMap<String, String> pendingSets() {
        return cluster.instance().getMap(PrivacyCluster.APPROVAL_PENDING_MAP);
    }

    private IMap<String, String> approvals() {
        return cluster.instance().getMap(PrivacyCluster.APPROVAL_MAP);
    }

    /** Length-free, NUL-separated, with backslash escaping; a null field is a lone tilde. */
    static final class Codec {

        private static final char SEP = 0;

        private Codec() {
        }

        /** Seventeen fields: the sixteen of the request, then the random creation token. */
        private static final int FIELDS = 17;

        /** Length of a token, {@code UUID.randomUUID().toString()}. */
        private static final int TOKEN_LENGTH = 36;

        static String encode(ApprovalRequest r, String token) {
            return String.join(String.valueOf(SEP),
                    field(r.approvalId()), field(r.kind().name()), field(r.requesterPrincipalId()),
                    field(r.requesterClientId()), field(r.scopeId()), field(r.tool()),
                    field(r.bindingFingerprint()), field(r.namespace()), field(r.syntheticValue()),
                    field(r.purpose()), field(r.caseId()), field(r.createdAt().toString()),
                    field(r.expiresAt().toString()), field(r.status().name()),
                    field(r.approverPrincipalId()),
                    field(r.decidedAt() == null ? null : r.decidedAt().toString()),
                    field(token));
        }

        /** @throws IllegalStateException if the value is not in the current form (for example an older one) */
        static ApprovalRequest decode(String raw) {
            return decodeStored(raw).request();
        }

        /** The creation token of a stored value, or null if it cannot be read. */
        static String tokenOrNull(String raw) {
            try {
                return tokenOf(raw);
            } catch (RuntimeException unreadable) {
                return null;
            }
        }

        /** @throws IllegalStateException if the value is not in the current form */
        static String tokenOf(String raw) {
            String[] f = raw.split(String.valueOf(SEP), -1);
            if (f.length != FIELDS) {
                throw new IllegalStateException("unreadable approval entry");
            }
            String token = f[16].equals("~") ? null : value(f[16]);
            if (token == null || token.length() != TOKEN_LENGTH) {
                throw new IllegalStateException("unreadable approval entry");
            }
            return token;
        }

        static Stored decodeStored(String raw) {
            String token = tokenOf(raw);
            String[] f = raw.split(String.valueOf(SEP), -1);
            return new Stored(new ApprovalRequest(value(f[0]), Kind.valueOf(value(f[1])), value(f[2]), value(f[3]),
                    value(f[4]), value(f[5]), value(f[6]), value(f[7]), value(f[8]), value(f[9]),
                    value(f[10]), Instant.parse(value(f[11])), Instant.parse(value(f[12])),
                    Status.valueOf(value(f[13])), value(f[14]),
                    f[15].equals("~") ? null : Instant.parse(value(f[15]))), token);
        }

        private static String field(String value) {
            return value == null ? "~" : "=" + value.replace("\\", "\\\\").replace("\0", "\\0");
        }

        private static String value(String field) {
            if (field.equals("~")) {
                return null;
            }
            StringBuilder out = new StringBuilder();
            for (int i = 1; i < field.length(); i++) {
                char c = field.charAt(i);
                if (c == '\\') {
                    out.append(field.charAt(++i) == '0' ? '\0' : '\\');
                } else {
                    out.append(c);
                }
            }
            return out.toString();
        }
    }
}

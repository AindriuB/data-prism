package io.github.aindriub.dataprism.hazelcast;

import com.hazelcast.map.IMap;
import com.hazelcast.splitbrainprotection.SplitBrainProtectionException;
import io.github.aindriub.dataprism.core.refusal.ClusterLockTimeoutException;
import io.github.aindriub.dataprism.core.refusal.PrivacyRefusedException;
import io.github.aindriub.dataprism.oversight.ApprovalRefusedException;
import io.github.aindriub.dataprism.oversight.ApprovalRefusedException.Code;
import io.github.aindriub.dataprism.oversight.ApprovalRequest;
import io.github.aindriub.dataprism.oversight.ApprovalRequest.Kind;
import io.github.aindriub.dataprism.oversight.ApprovalRequest.Status;
import io.github.aindriub.dataprism.oversight.ApprovalStore;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
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
 * {@link ClusterLockTimeoutException}. Every lock has a lease of five times the timeout. Creation rests on
 * {@code putIfAbsent} too: an id is claimed in {@link PrivacyCluster#APPROVAL_ID_MAP} (a claim is valid while the
 * entry it names exists, with no clock involved; see {@link ClaimEntries} for why a paused creator cannot get a
 * duplicate past a takeover), and the per-requester cap
 * is one entry processor on {@link PrivacyCluster#APPROVAL_PENDING_MAP}, so neither check depends on a lock still
 * being held. Under a partition each side can still change its own copy once,
 * which the split-brain protection and {@link ApprovalMergePolicy} on the map narrow (see
 * {@code docs/multiple-instances.md}).
 */
public final class HazelcastApprovalStore implements ApprovalStore {

    /**
     * How long an entry we mark expired stays visible, so a late approver sees EXPIRED.
     */
    private static final Duration DEFAULT_RETENTION = Duration.ofMinutes(1);

    private final PrivacyCluster cluster;
    private final Duration lockTimeout;
    private final Duration retention;

    /** With the default lock timeout of two seconds. */
    public HazelcastApprovalStore(PrivacyCluster cluster) {
        this(cluster, ClusterLocks.DEFAULT_TIMEOUT);
    }

    /**
     * @param lockTimeout how long a call waits for a lock before it refuses, {@code PT100MS} to {@code PT30S}
     * @throws IllegalArgumentException if the timeout is outside that range
     */
    public HazelcastApprovalStore(PrivacyCluster cluster, Duration lockTimeout) {
        this.cluster = Objects.requireNonNull(cluster, "cluster");
        this.lockTimeout = ClusterLocks.checked(lockTimeout);
        this.retention = DEFAULT_RETENTION;
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
     * undo can leave an id unusable or a requester over-counted until expiry; it never under-counts. The locks it
     * takes on the keys it writes only bound the wait.
     */
    private ApprovalRequest admit(ApprovalRequest pending, int cap) {
        if (pending.status() != Status.PENDING) {
            throw new IllegalArgumentException("a new approval must be PENDING");
        }
        IMap<String, String> approvals = approvals();
        IMap<String, String> claims = idClaims();
        IMap<String, String> pendingSets = pendingSets();
        String id = pending.approvalId();
        String entryKey = ScopeKeys.approval(pending.scopeId(), id);
        String pendingKey = pendingKey(pending.kind(), pending.requesterPrincipalId());
        long ttl = Math.max(1L, Duration.between(pending.createdAt(), pending.expiresAt()).toMillis());
        String encoded = Codec.encode(pending);
        try (HeldLocks locks = new HeldLocks()) {
            locks.take(claims, id);
            locks.take(pendingSets, pendingKey);
            locks.take(approvals, entryKey);
            if (!cluster(() -> ClaimEntries.claim(claims, approvals, id, entryKey))) {
                throw new IllegalArgumentException("duplicate approval id");
            }
            boolean reserved;
            try {
                reserved = cluster(() -> pendingSets.executeOnKey(pendingKey,
                        new PendingSet.Reserve(id, pending.createdAt(), pending.expiresAt(), cap)));
            } catch (RuntimeException failed) {
                unclaim(claims, id, entryKey);
                throw failed;
            }
            if (!reserved) {
                unclaim(claims, id, entryKey);
                throw new ApprovalRefusedException(Code.TOO_MANY_PENDING);
            }
            String existing;
            try {
                existing = cluster(() -> approvals.putIfAbsent(entryKey, encoded, ttl, TimeUnit.MILLISECONDS));
            } catch (RuntimeException failed) {
                // Only a confirmed absence is undone: if the insert may have happened, keeping the claim and the
                // count errs towards refusing.
                if (confirmedAbsent(approvals, entryKey)) {
                    unreserve(pendingSets, pending);
                    unclaim(claims, id, entryKey);
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
            if (!entryKey.equals(cluster(() -> claims.get(id)))) {
                withdraw(approvals, entryKey, encoded);
                throw new IllegalArgumentException("duplicate approval id");
            }
        }
        return pending;
    }

    /**
     * Key of a (kind, requester) entry in the pending map. A map of its own, so it can equal no approval key,
     * and a null requester ({@code ~}) differs from any named one ({@code =} first).
     */
    static String pendingKey(Kind kind, String requesterPrincipalId) {
        return kind.name() + "\0" + (requesterPrincipalId == null ? "~" : "=" + requesterPrincipalId);
    }

    private static boolean confirmedAbsent(IMap<String, String> approvals, String entryKey) {
        try {
            return !approvals.containsKey(entryKey);
        } catch (RuntimeException unknown) {
            return false;
        }
    }

    /** Removes this caller's own entry after it lost its claim. A failure leaves two entries, which every lookup refuses. */
    private static void withdraw(IMap<String, String> approvals, String entryKey, String encoded) {
        try {
            approvals.remove(entryKey, encoded);
        } catch (RuntimeException ignored) {
            // Two entries with one id make find and decide refuse (UNKNOWN_APPROVAL), which fails closed.
        }
    }

    /** Releases a claim only if it is still this approval's. Best effort: a failure leaves the id unusable. */
    private static void unclaim(IMap<String, String> claims, String id, String entryKey) {
        try {
            ClaimEntries.release(claims, id, entryKey);
        } catch (RuntimeException ignored) {
            // The claim expires with the approval it guards; the id is unusable until then, never reusable early.
        }
    }

    /** Best effort: a failure leaves the requester over-counted until the approval's expiry, never under-counted. */
    private static void unreserve(IMap<String, String> pendingSets, ApprovalRequest request) {
        try {
            pendingSets.executeOnKey(pendingKey(request.kind(), request.requesterPrincipalId()),
                    new PendingSet.Release(request.approvalId()));
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
        } catch (PrivacyRefusedException | SplitBrainProtectionException refusal) {
            throw refusal;
        } catch (RuntimeException failure) {
            throw new ClusterOperationRefusedException();
        }
    }

    /** Bounded locks, released in reverse order. They bound waits only; nothing relies on still holding one. */
    private final class HeldLocks implements AutoCloseable {
        private final Deque<Runnable> releases = new ArrayDeque<>();

        void take(IMap<String, String> map, String key) {
            try {
                ClusterLocks.acquire(map, key, lockTimeout);
            } catch (PrivacyRefusedException | SplitBrainProtectionException refusal) {
                close();
                throw refusal;
            } catch (RuntimeException failure) {
                close();
                throw new ClusterOperationRefusedException();
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

    /** Removes an approval that left {@code PENDING} from its requester's count. Best effort, as above. */
    private void leavePending(ApprovalRequest request) {
        unreserve(pendingSets(), request);
    }

    @Override
    public Optional<ApprovalRequest> find(String approvalId) {
        return locate(approvalId).map(key -> approvals().get(key)).map(Codec::decode);
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
            String seen = approvals.get(key);
            if (seen == null || !isMatch(Codec.decode(seen), Status.APPROVED, kind, principal, scopeId, tool,
                    fingerprint, now)) {
                continue;
            }
            ClusterLocks.acquire(approvals, key, lockTimeout);
            try {
                String raw = approvals.get(key);
                if (raw == null) {
                    continue;
                }
                ApprovalRequest request = Codec.decode(raw);
                if (isMatch(request, Status.APPROVED, kind, principal, scopeId, tool, fingerprint, now)) {
                    ApprovalRequest consumed = request.withStatus(Status.CONSUMED);
                    // Only the caller whose replace sees the value it read has consumed it.
                    if (swap(approvals, key, raw, consumed, now)) {
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
        for (String raw : approvals().values()) {
            ApprovalRequest request = Codec.decode(raw);
            if (request.status() == Status.PENDING && now.isBefore(request.expiresAt())) {
                found.add(request);
            }
        }
        return found;
    }

    @Override
    public void forgetScope(String scopeId) {
        forget(cluster, scopeId);
    }

    /**
     * Removes a scope's approvals with their id claims and pending counts, so the ids and the requesters' slots
     * are free again. Shared with {@link ScopeIdentityIndex#endScope}.
     */
    static void forget(PrivacyCluster cluster, String scopeId) {
        IMap<String, String> approvals = cluster.instance().getMap(PrivacyCluster.APPROVAL_MAP);
        IMap<String, String> claims = cluster.instance().getMap(PrivacyCluster.APPROVAL_ID_MAP);
        IMap<String, String> pendingSets = cluster.instance().getMap(PrivacyCluster.APPROVAL_PENDING_MAP);
        String prefix = ScopeKeys.approvalPrefix(scopeId);
        for (String key : approvals.keySet().stream().filter(k -> k.startsWith(prefix)).toList()) {
            String raw = approvals.get(key);
            approvals.delete(key);
            if (raw != null) {
                ApprovalRequest request = Codec.decode(raw);
                unreserve(pendingSets, request);
                unclaim(claims, request.approvalId(), key);
            }
        }
    }

    private ApprovalRequest decide(String approvalId, Instant now, Function<ApprovalRequest, ApprovalRequest> next) {
        IMap<String, String> approvals = approvals();
        String key = locate(approvalId).orElseThrow(() -> new ApprovalRefusedException(Code.UNKNOWN_APPROVAL));
        ClusterLocks.acquire(approvals, key, lockTimeout);
        try {
            String raw = approvals.get(key);
            if (raw == null) {
                throw new ApprovalRefusedException(Code.UNKNOWN_APPROVAL);
            }
            ApprovalRequest request = Codec.decode(raw);
            if (request.status() != Status.PENDING) {
                throw new ApprovalRefusedException(Code.NOT_PENDING);
            }
            if (!now.isBefore(request.expiresAt())) {
                swap(approvals, key, raw, request.withStatus(Status.EXPIRED), now);
                leavePending(request);
                throw new ApprovalRefusedException(Code.EXPIRED);
            }
            ApprovalRequest decided = next.apply(request);
            if (!swap(approvals, key, raw, decided, now)) {
                // Someone changed the entry between the read and the swap: they decided first.
                throw new ApprovalRefusedException(Code.NOT_PENDING);
            }
            leavePending(request);
            return decided;
        } finally {
            ClusterLocks.release(approvals, key);
        }
    }

    private List<ApprovalRequest> matches(Status status, Kind kind, String principal, String scopeId,
                                          String tool, String fingerprint, Instant now) {
        IMap<String, String> approvals = approvals();
        List<ApprovalRequest> found = new ArrayList<>();
        for (String key : keysOf(scopeId)) {
            String raw = approvals.get(key);
            if (raw != null) {
                ApprovalRequest request = Codec.decode(raw);
                if (isMatch(request, status, kind, principal, scopeId, tool, fingerprint, now)) {
                    found.add(request);
                }
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

    /**
     * Replaces {@code key}'s value only if it is still {@code expected}, then restores the entry's expiry, because
     * a replace resets it to none. Returns whether this caller made the change.
     */
    private boolean swap(IMap<String, String> approvals, String key, String expected, ApprovalRequest next,
                         Instant now) {
        if (!approvals.replace(key, expected, Codec.encode(next))) {
            return false;
        }
        Duration remaining = Duration.between(now, next.expiresAt());
        if (remaining.isNegative() || remaining.isZero()) {
            remaining = retention;
        }
        approvals.setTtl(key, Math.max(1L, remaining.toMillis()), TimeUnit.MILLISECONDS);
        return true;
    }

    private List<String> keysOf(String scopeId) {
        String prefix = ScopeKeys.approvalPrefix(scopeId);
        return approvals().keySet().stream().filter(key -> key.startsWith(prefix)).toList();
    }

    private List<String> locateAll(String approvalId) {
        return approvals().keySet().stream()
                .filter(key -> ScopeKeys.approvalId(key).equals(approvalId))
                .toList();
    }

    /** The one key for an id; an id that matches several keys is refused, never guessed. */
    private Optional<String> locate(String approvalId) {
        List<String> keys = locateAll(approvalId);
        if (keys.size() > 1) {
            throw new ApprovalRefusedException(Code.UNKNOWN_APPROVAL);
        }
        return keys.stream().findFirst();
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

        static String encode(ApprovalRequest r) {
            return String.join(String.valueOf(SEP),
                    field(r.approvalId()), field(r.kind().name()), field(r.requesterPrincipalId()),
                    field(r.requesterClientId()), field(r.scopeId()), field(r.tool()),
                    field(r.bindingFingerprint()), field(r.namespace()), field(r.syntheticValue()),
                    field(r.purpose()), field(r.caseId()), field(r.createdAt().toString()),
                    field(r.expiresAt().toString()), field(r.status().name()),
                    field(r.approverPrincipalId()),
                    field(r.decidedAt() == null ? null : r.decidedAt().toString()));
        }

        static ApprovalRequest decode(String raw) {
            String[] f = raw.split(String.valueOf(SEP), -1);
            if (f.length != 16) {
                throw new IllegalStateException("unreadable approval entry");
            }
            return new ApprovalRequest(value(f[0]), Kind.valueOf(value(f[1])), value(f[2]), value(f[3]),
                    value(f[4]), value(f[5]), value(f[6]), value(f[7]), value(f[8]), value(f[9]),
                    value(f[10]), Instant.parse(value(f[11])), Instant.parse(value(f[12])),
                    Status.valueOf(value(f[13])), value(f[14]),
                    f[15].equals("~") ? null : Instant.parse(value(f[15])));
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

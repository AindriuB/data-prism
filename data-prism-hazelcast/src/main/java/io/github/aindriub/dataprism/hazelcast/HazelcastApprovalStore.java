package io.github.aindriub.dataprism.hazelcast;

import com.hazelcast.map.IMap;
import io.github.aindriub.dataprism.oversight.ApprovalRefusedException;
import io.github.aindriub.dataprism.oversight.ApprovalRefusedException.Code;
import io.github.aindriub.dataprism.oversight.ApprovalRequest;
import io.github.aindriub.dataprism.oversight.ApprovalRequest.Kind;
import io.github.aindriub.dataprism.oversight.ApprovalRequest.Status;
import io.github.aindriub.dataprism.oversight.ApprovalStore;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Approvals shared across the cluster, so one granted on one instance is
 * consumable on any instance, exactly once.
 *
 * <p>Fails closed: nothing here catches a cluster exception. Entries are stored
 * as strings, like every other key and value in the cluster, so no serialiser
 * has to agree across members. A record holds principal ids, purpose, case id,
 * fingerprint, namespace and the synthetic value, and no subject id, because
 * {@link ApprovalRequest} has no field for one. Each entry lives until the
 * request's {@code expiresAt}, then the cluster drops it.
 *
 * <p>State changes take the entry's lock and re-read under it, which is what
 * makes {@link #consumeApproved} succeed once across members.
 */
public final class HazelcastApprovalStore implements ApprovalStore {

    /** How long an entry we mark expired stays visible, so a late approver sees EXPIRED. */
    private static final Duration EXPIRED_RETENTION = Duration.ofMinutes(1);

    private final PrivacyCluster cluster;

    public HazelcastApprovalStore(PrivacyCluster cluster) {
        this.cluster = Objects.requireNonNull(cluster, "cluster");
    }

    /**
     * @throws IllegalArgumentException if the request is not {@code PENDING} or its id is already used
     */
    @Override
    public ApprovalRequest create(ApprovalRequest pending) {
        Objects.requireNonNull(pending, "pending");
        if (pending.status() != Status.PENDING) {
            throw new IllegalArgumentException("a new approval must be PENDING");
        }
        IMap<String, String> approvals = approvals();
        // The bare id holds no separator, so it is never an entry key; locking it
        // makes the duplicate check and the put atomic across scopes.
        String idLock = pending.approvalId();
        approvals.lock(idLock);
        try {
            if (!locateAll(pending.approvalId()).isEmpty()) {
                throw new IllegalArgumentException("duplicate approval id");
            }
            String key = ScopeKeys.approval(pending.scopeId(), pending.approvalId());
            long ttl = Math.max(1L, Duration.between(pending.createdAt(), pending.expiresAt()).toMillis());
            if (approvals.putIfAbsent(key, Codec.encode(pending), ttl, TimeUnit.MILLISECONDS) != null) {
                throw new IllegalArgumentException("duplicate approval id");
            }
        } finally {
            unlock(approvals, idLock);
        }
        return pending;
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
            approvals.lock(key);
            try {
                String raw = approvals.get(key);
                if (raw == null) {
                    continue;
                }
                ApprovalRequest request = Codec.decode(raw);
                if (isMatch(request, Status.APPROVED, kind, principal, scopeId, tool, fingerprint, now)) {
                    ApprovalRequest consumed = request.withStatus(Status.CONSUMED);
                    write(approvals, key, consumed, now);
                    return Optional.of(consumed);
                }
            } finally {
                unlock(approvals, key);
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
        IMap<String, String> approvals = approvals();
        for (String key : keysOf(scopeId)) {
            approvals.delete(key);
        }
    }

    private ApprovalRequest decide(String approvalId, Instant now, Function<ApprovalRequest, ApprovalRequest> next) {
        IMap<String, String> approvals = approvals();
        String key = locate(approvalId).orElseThrow(() -> new ApprovalRefusedException(Code.UNKNOWN_APPROVAL));
        approvals.lock(key);
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
                write(approvals, key, request.withStatus(Status.EXPIRED), now);
                throw new ApprovalRefusedException(Code.EXPIRED);
            }
            ApprovalRequest decided = next.apply(request);
            write(approvals, key, decided, now);
            return decided;
        } finally {
            unlock(approvals, key);
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

    /** Rewrites an entry keeping its original expiry, since a plain set would reset the TTL. */
    private static void write(IMap<String, String> approvals, String key, ApprovalRequest request, Instant now) {
        Duration remaining = Duration.between(now, request.expiresAt());
        if (remaining.isNegative() || remaining.isZero()) {
            remaining = EXPIRED_RETENTION;
        }
        approvals.set(key, Codec.encode(request), Math.max(1L, remaining.toMillis()), TimeUnit.MILLISECONDS);
    }

    private static void unlock(IMap<String, String> approvals, String key) {
        try {
            approvals.unlock(key);
        } catch (RuntimeException ignored) {
            // The lock dies with the member; throwing here would mask the real failure.
        }
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

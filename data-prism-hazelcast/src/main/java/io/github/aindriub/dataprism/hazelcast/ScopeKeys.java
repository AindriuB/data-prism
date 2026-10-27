package io.github.aindriub.dataprism.hazelcast;

import com.hazelcast.map.IMap;
import com.hazelcast.query.Predicates;
import io.github.aindriub.dataprism.annotations.PrivacyNamespace;
import io.github.aindriub.dataprism.hazelcast.seal.KeyedNames;
import io.github.aindriub.dataprism.oversight.ApprovalRequest;

import java.util.List;
import java.util.Objects;

/**
 * Builds the map keys and lock keys, scope first, as keyed pseudonymous names
 * (docs/plan/design/cluster-security.md 2.3.2). No key contains a scope id, subject id, principal, approval id, case
 * id or pseudonym: each is an HMAC under a subkey of the name key, and a scope's entries share the prefix
 * {@code scopeToken + "."}, so ending a scope is still a prefix match.
 *
 * <p>The canonical input of an entry is the key the 0.7 code built, with the same parts in the same order, joined
 * by NUL (it cannot appear in a scope id, a namespace name or an identifier, so no crafted value can make one entry
 * look like another). Every map name is the full sealed-map name, generation suffix included.
 *
 * <p>A lock key is the key of the entry it guards, so there is no separate lock label: the lock a caller takes with
 * {@link ClusterLocks#acquire} is on the string these methods return, in the same map.
 *
 * <p>Keys are plain strings on purpose. A record key would need a serialiser registered on every member, and a
 * mismatched serialiser across a rolling upgrade is a way to lose a scope's identities mid-investigation.
 */
final class ScopeKeys {

    private static final char SEPARATOR = 0;
    /** Hazelcast's name for the key of an entry in a query. */
    private static final String KEY_ATTRIBUTE = "__key";

    private final KeyedNames names;

    ScopeKeys(KeyedNames names) {
        this.names = Objects.requireNonNull(names, "names");
    }

    String identity(String scopeId, String subjectId, PrivacyNamespace namespace) {
        return names.scoped(PrivacyCluster.IDENTITY_MAP, scopeId, identityCanonical(scopeId, subjectId, namespace));
    }

    static String identityCanonical(String scopeId, String subjectId, PrivacyNamespace namespace) {
        return scopeId + SEPARATOR + namespace.name() + SEPARATOR + subjectId;
    }

    String reidentification(String scopeId, PrivacyNamespace namespace, String synthetic) {
        return names.scoped(PrivacyCluster.REIDENTIFICATION_MAP, scopeId,
                reidentificationCanonical(scopeId, namespace, synthetic));
    }

    static String reidentificationCanonical(String scopeId, PrivacyNamespace namespace, String synthetic) {
        return scopeId + SEPARATOR + namespace.name() + SEPARATOR + synthetic;
    }

    String budget(String scopeId, String subjectId) {
        return names.scoped(PrivacyCluster.BUDGET_MAP, scopeId, budgetCanonical(scopeId, subjectId));
    }

    static String budgetCanonical(String scopeId, String subjectId) {
        return scopeId + SEPARATOR + subjectId;
    }

    /**
     * The scope total of the read budget, in the budget map, and the lock key that makes a reservation, a release,
     * a counted read and the ending of the scope one unit. It shares the scope prefix of the subject counters, so
     * ending the scope removes it by prefix. Its name is an HMAC under a label of its own ({@code map + "#total"}),
     * not under the budget map's, so no subject id, whatever bytes it holds, can produce this key.
     */
    String budgetTotal(String scopeId) {
        return names.scoped(PrivacyCluster.BUDGET_MAP + "#total", scopeId, scopeId);
    }

    /** Scope first, so ending the scope removes its approvals by prefix. A null scope keys as empty. */
    String approval(String scopeId, String approvalId) {
        return names.scoped(PrivacyCluster.APPROVAL_MAP, scopeId == null ? "" : scopeId,
                approvalCanonical(scopeId, approvalId));
    }

    static String approvalCanonical(String scopeId, String approvalId) {
        return (scopeId == null ? "" : scopeId) + SEPARATOR + approvalId;
    }

    String approvalPrefix(String scopeId) {
        return scopePrefix(scopeId == null ? "" : scopeId);
    }

    /** The claim on an approval id: no scope token, so one id has one claim across every scope. */
    String claim(String approvalId) {
        return names.mapKey(PrivacyCluster.APPROVAL_ID_MAP, approvalId);
    }

    /**
     * The pending-approval entry of a (kind, requester): the keyed name of the canonical
     * {@code KIND NUL (=requester or ~)}. A map of its own, so it can equal no approval key, and a null requester
     * ({@code ~}) differs from any named one ({@code =} first). It is also the lock key of that entry.
     */
    String pending(ApprovalRequest.Kind kind, String requesterPrincipalId) {
        return names.mapKey(PrivacyCluster.APPROVAL_PENDING_MAP, pendingCanonical(kind, requesterPrincipalId));
    }

    static String pendingCanonical(ApprovalRequest.Kind kind, String requesterPrincipalId) {
        return kind.name() + SEPARATOR + (requesterPrincipalId == null ? "~" : "=" + requesterPrincipalId);
    }

    String callerRate(String principalId) {
        return names.mapKey(PrivacyCluster.CALLER_RATE_MAP, principalId);
    }

    /**
     * Pause keys. The global and tool keys are not scoped and contain a colon or no dot, and every scope key is
     * unpadded URL-safe base64 with one dot, so ending a scope by prefix can never remove them and a scope key can
     * never be read as a tool key.
     */
    static String pausedAll() {
        return "all";
    }

    static String pausedTool(String tool) {
        return "tool:" + tool;
    }

    String pausedScope(String scopeId) {
        return names.scoped(PrivacyCluster.OVERSIGHT_MAP, scopeId, scopeId + SEPARATOR + "paused");
    }

    String scopePrefix(String scopeId) {
        return names.scopeToken(scopeId) + ".";
    }

    /**
     * The keys of {@code map} that start with {@code prefix}, chosen on the members that own them by the Hazelcast
     * predicate {@code Predicates.like("__key", escaped prefix + "%")}, so only matching keys come back to the
     * caller, not every key of the map. {@code _} is a base64url character and a LIKE wildcard, so the prefix is
     * escaped ({@code %} and {@code _} get a backslash) and the match is exactly {@code startsWith}. A prefix with
     * a backslash is refused: a scope prefix is base64url and a dot, and the escape of a backslash is not relied on.
     *
     * @throws IllegalArgumentException if the prefix contains a backslash
     */
    static <V> List<String> keysWithPrefix(IMap<String, V> map, String prefix) {
        StringBuilder pattern = new StringBuilder(prefix.length() + 8);
        for (char c : prefix.toCharArray()) {
            if (c == '\\') {
                throw new IllegalArgumentException("not a scope prefix");
            }
            if (c == '%' || c == '_') {
                pattern.append('\\');
            }
            pattern.append(c);
        }
        pattern.append('%');
        return List.copyOf(map.keySet(Predicates.<String, V>like(KEY_ATTRIBUTE,
                pattern.toString())));
    }
}

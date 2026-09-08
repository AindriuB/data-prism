package io.github.aindriub.dataprism.hazelcast;

import com.hazelcast.map.IMap;
import io.github.aindriub.dataprism.annotations.PrivacyNamespace;

import java.util.Objects;
import java.util.Optional;

/**
 * Maps a pseudonym back to the subject it stands for, and ends scopes.
 *
 * <p>The most sensitive object in the system, and the reason it is off unless a
 * deployment asks for it. A pseudonym is one-way by construction; this index is
 * the only thing that undoes it, so whoever can read it can undo every
 * pseudonymisation in every live scope at once.
 *
 * <p>Deliberately not reachable from the tool surface. Nothing in {@code mcp}
 * depends on this module, and the operator-facing surface that will use it is a
 * separate application on a separate port under a separate authorisation scope.
 * The one design mistake that would undo the whole platform is exposing this as
 * a tool, so it is worth restating wherever the class appears. See
 * docs/design-review.md §B1.
 *
 * <p>It holds subject identifiers, not names: turning a subject id into a person
 * remains the source systems' job, behind their own access control.
 */
public final class ScopeIdentityIndex {

    private final PrivacyCluster cluster;

    public ScopeIdentityIndex(PrivacyCluster cluster) {
        this.cluster = Objects.requireNonNull(cluster, "cluster");
    }

    /**
     * @return the subject a pseudonym stands for, or empty if the scope has ended,
     *         the entry expired, or the index was never enabled
     */
    public Optional<String> subjectFor(String scopeId, PrivacyNamespace namespace, String synthetic) {
        if (!cluster.reidentificationEnabled()) {
            return Optional.empty();
        }
        IMap<String, String> index = cluster.instance().getMap(PrivacyCluster.REIDENTIFICATION_MAP);
        return Optional.ofNullable(index.get(
                ScopeKeys.reidentification(scopeId, namespace, synthetic)));
    }

    /**
     * Ends a scope: every identity, reverse entry and read count for it goes.
     *
     * <p>Keys lead with the scope so this is a prefix match. It is also the reason
     * an investigation ending is a real event rather than a matter of waiting for
     * a TTL: the mapping stops existing when someone says it should.
     */
    public void endScope(String scopeId) {
        String prefix = ScopeKeys.scopePrefix(scopeId);
        for (String map : new String[]{PrivacyCluster.IDENTITY_MAP,
                PrivacyCluster.REIDENTIFICATION_MAP, PrivacyCluster.BUDGET_MAP}) {
            IMap<String, ?> entries = cluster.instance().getMap(map);
            entries.keySet().stream()
                    .filter(key -> key.startsWith(prefix))
                    .forEach(entries::delete);
        }
    }
}

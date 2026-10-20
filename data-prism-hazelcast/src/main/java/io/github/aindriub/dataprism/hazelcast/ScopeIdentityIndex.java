package io.github.aindriub.dataprism.hazelcast;

import com.hazelcast.map.IMap;
import io.github.aindriub.dataprism.annotations.PrivacyNamespace;
import io.github.aindriub.dataprism.core.metrics.Metric;
import io.github.aindriub.dataprism.core.metrics.PrivacyMetrics;

import java.time.Duration;
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
    private final PrivacyMetrics metrics;
    private final HazelcastApprovalStore approvals;

    /** With the default lock timeout of two seconds. */
    public ScopeIdentityIndex(PrivacyCluster cluster) {
        this(cluster, PrivacyMetrics.none());
    }

    /** With the default lock timeout of two seconds. */
    public ScopeIdentityIndex(PrivacyCluster cluster, PrivacyMetrics metrics) {
        this(cluster, metrics, ClusterLocks.DEFAULT_TIMEOUT);
    }

    /**
     * @param lockTimeout how long ending a scope waits for each approval lock it needs before it refuses,
     *                    {@code PT100MS} to {@code PT30S}; use the timeout the approval store was given
     * @throws IllegalArgumentException if the timeout is outside that range
     */
    public ScopeIdentityIndex(PrivacyCluster cluster, PrivacyMetrics metrics, Duration lockTimeout) {
        this.cluster = Objects.requireNonNull(cluster, "cluster");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.approvals = HazelcastApprovalStore.forScopeEnd(cluster, lockTimeout, metrics);
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
        Optional<String> subject = Optional.ofNullable(index.get(
                ScopeKeys.reidentification(scopeId, namespace, synthetic)));
        subject.ifPresent(ignored -> metrics.increment(Metric.REIDENTIFICATION, namespace.name()));
        return subject;
    }

    /**
     * Ends a scope: every identity, reverse entry and read count for it goes.
     *
     * <p>Keys lead with the scope so this is a prefix match. It is also the reason
     * an investigation ending is a real event rather than a matter of waiting for
     * a TTL: the mapping stops existing when someone says it should.
     *
     * <p>The identity, reverse, budget and oversight maps are deleted without a lock bound, as before: a key
     * another member holds makes that delete wait. The approvals of the scope are bounded: each of their locks waits
     * at most the lock timeout.
     *
     * @throws io.github.aindriub.dataprism.core.refusal.ClusterLockTimeoutException if the lock of an approval of the
     *         scope timed out; that approval, its id claim and its pending slot are left in place
     */
    public void endScope(String scopeId) {
        String prefix = ScopeKeys.scopePrefix(scopeId);
        for (String map : new String[]{PrivacyCluster.IDENTITY_MAP,
                PrivacyCluster.REIDENTIFICATION_MAP, PrivacyCluster.BUDGET_MAP,
                PrivacyCluster.OVERSIGHT_MAP}) {
            IMap<String, ?> entries = cluster.instance().getMap(map);
            entries.keySet().stream()
                    .filter(key -> key.startsWith(prefix))
                    .forEach(entries::delete);
        }
        // Approvals go with their id claims and pending counts, so those ids and slots are free again.
        approvals.forgetScope(scopeId);
    }
}

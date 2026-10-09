package io.github.aindriub.dataprism.hazelcast;

import com.hazelcast.map.IMap;
import io.github.aindriub.dataprism.core.limits.ScopeBudget;
import io.github.aindriub.dataprism.core.refusal.ClusterLockTimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Objects;

/**
 * A read budget shared across the cluster.
 *
 * <p>This is what makes the number mean what it says. Held per instance, a budget
 * of a hundred lets a caller spread across eight instances read a subject eight
 * hundred times, and the setting quietly means something other than it appears to.
 *
 * <p>Failing closed here, unlike the identity cache. The two are different kinds
 * of thing: an unreachable identity cache costs a recomputed HMAC and changes no
 * answer, whereas an unreachable budget means nobody is counting. Falling back to
 * an uncounted read would turn a cluster outage into an open door on the one
 * control that limits how much a caller can extract from one subject, and it
 * would do it silently.
 */
public final class HazelcastScopeBudget implements ScopeBudget {

    private static final Logger LOG = LoggerFactory.getLogger(HazelcastScopeBudget.class);

    private final PrivacyCluster cluster;
    private final Duration lockTimeout;

    /** With the default lock timeout of two seconds. */
    public HazelcastScopeBudget(PrivacyCluster cluster) {
        this(cluster, ClusterLocks.DEFAULT_TIMEOUT);
    }

    /**
     * @param lockTimeout how long a read waits for the subject's lock before it is refused, {@code PT100MS}
     *                    to {@code PT30S}
     * @throws IllegalArgumentException if the timeout is outside that range
     */
    public HazelcastScopeBudget(PrivacyCluster cluster, Duration lockTimeout) {
        this.cluster = Objects.requireNonNull(cluster, "cluster");
        this.lockTimeout = ClusterLocks.checked(lockTimeout);
    }

    /**
     * @throws ClusterLockTimeoutException if the subject's lock is held and not released within the lock
     *                                     timeout. That is not an exhausted budget and is not reported as one.
     */
    @Override
    public boolean tryRead(String scopeId, String subjectId, int budget) {
        String key = ScopeKeys.budget(scopeId, subjectId);
        IMap<String, Integer> counts;
        // Obtaining the map and taking the lock are themselves cluster calls and
        // throw when the member is gone. They belong inside the guard: leaving
        // them outside meant the fail-closed path could not run, because the
        // exception escaped before reaching it.
        try {
            counts = counts();
            ClusterLocks.acquire(counts, key, lockTimeout);
        } catch (ClusterLockTimeoutException held) {
            LOG.error("read budget lock not obtained within {}; refusing the read", lockTimeout);
            throw held;
        } catch (RuntimeException clusterFailure) {
            return refuse(clusterFailure);
        }

        try {
            int current = counts.getOrDefault(key, 0);
            if (current >= budget) {
                return false;
            }
            // A refused read is not counted, so a caller that keeps asking cannot
            // push the count somewhere a later budget increase never recovers from.
            counts.set(key, current + 1);
            return true;
        } catch (RuntimeException clusterFailure) {
            return refuse(clusterFailure);
        } finally {
            ClusterLocks.release(counts, key);
        }
    }

    private static boolean refuse(RuntimeException clusterFailure) {
        LOG.error("read budget unavailable; refusing the read rather than leaving it "
                + "uncounted: {}", clusterFailure.getClass().getSimpleName());
        return false;
    }

    @Override
    public int reads(String scopeId, String subjectId) {
        return counts().getOrDefault(ScopeKeys.budget(scopeId, subjectId), 0);
    }

    @Override
    public void forget(String scopeId) {
        String prefix = ScopeKeys.scopePrefix(scopeId);
        IMap<String, Integer> counts = counts();
        counts.keySet().stream()
                .filter(key -> key.startsWith(prefix))
                .forEach(counts::delete);
    }

    private IMap<String, Integer> counts() {
        return cluster.instance().getMap(PrivacyCluster.BUDGET_MAP);
    }
}

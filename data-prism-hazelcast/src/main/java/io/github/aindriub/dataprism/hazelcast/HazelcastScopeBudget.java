package io.github.aindriub.dataprism.hazelcast;

import com.hazelcast.map.EntryProcessor;
import com.hazelcast.map.IMap;
import io.github.aindriub.dataprism.core.limits.ScopeBudget;
import io.github.aindriub.dataprism.core.refusal.ClusterLockTimeoutException;
import io.github.aindriub.dataprism.core.refusal.ClusterOperationRefusedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.Map;
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

    private static volatile Logger LOG = LoggerFactory.getLogger(HazelcastScopeBudget.class);

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
     * @throws ClusterOperationRefusedException if the cluster rejected the lock or its lease. That is a refusal of
     *                                          the read with its own code, not an exhausted budget, and it is
     *                                          already logged where it was made.
     */
    @Override
    public boolean tryRead(String scopeId, String subjectId, int budget) {
        String key = cluster.scopeKeys().budget(scopeId, subjectId);
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
        } catch (ClusterOperationRefusedException rejected) {
            throw rejected;
        } catch (RuntimeException clusterFailure) {
            return refuse(clusterFailure);
        }

        try {
            // The lease bounds lock stalls; correctness belongs to the atomic map transition.
            return counts.executeOnKey(key, new ReadWithinBudget(budget));
        } catch (RuntimeException clusterFailure) {
            return refuse(clusterFailure);
        } finally {
            ClusterLocks.release(counts, key);
        }
    }

    private record ReadWithinBudget(int budget) implements EntryProcessor<String, Integer, Boolean> {
        @Override
        public Boolean process(Map.Entry<String, Integer> entry) {
            int current = entry.getValue() == null ? 0 : entry.getValue();
            if (current >= budget) return false;
            // Refusals do not count, so a later budget increase can still admit reads.
            entry.setValue(current + 1);
            return true;
        }
    }

    private static boolean refuse(RuntimeException clusterFailure) {
        LOG.error("read budget unavailable; refusing the read rather than leaving it "
                + "uncounted: {}", clusterFailure.getClass().getSimpleName());
        return false;
    }

    @Override
    public int reads(String scopeId, String subjectId) {
        return counts().getOrDefault(cluster.scopeKeys().budget(scopeId, subjectId), 0);
    }

    /**
     * Deletes the scope's counts, each under its key's bounded lock. {@link ScopeIdentityIndex#endScope} does not
     * call this: it deletes the budget keys itself, under the one deadline it shares across all its maps. Because
     * {@code forget} can be called on its own, with no caller's deadline, it takes a lock timeout of its own per
     * key.
     *
     * @throws ClusterLockTimeoutException if the lock of a key timed out; that count is left in place
     */
    @Override
    public void forget(String scopeId) {
        String prefix = cluster.scopeKeys().scopePrefix(scopeId);
        IMap<String, Integer> counts;
        List<String> keys;
        try {
            counts = counts();
            keys = ScopeKeys.keysWithPrefix(counts, prefix);
        } catch (RuntimeException failed) {
            throw ClusterRefusals.mapped(failed);
        }
        // Each delete runs under its key's bounded lock, so a key another member holds cannot make this wait.
        int skipped = ClusterLocks.deleteAll(counts, keys, lockTimeout);
        if (skipped > 0) {
            throw new ClusterLockTimeoutException();
        }
    }

    /** Routes this class's log lines to {@code replacement} until the returned handle is closed; for tests. */
    static AutoCloseable captureTo(Logger replacement) {
        Logger previous = LOG;
        LOG = replacement;
        return () -> LOG = previous;
    }

    private IMap<String, Integer> counts() {
        return cluster.instance().getMap(PrivacyCluster.BUDGET_MAP);
    }
}

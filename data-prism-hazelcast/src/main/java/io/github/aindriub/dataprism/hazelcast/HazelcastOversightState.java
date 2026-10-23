package io.github.aindriub.dataprism.hazelcast;

import com.hazelcast.map.IMap;
import io.github.aindriub.dataprism.oversight.OversightSnapshot;
import io.github.aindriub.dataprism.oversight.OversightState;

import java.time.Duration;
import java.util.HashSet;
import java.util.Objects;
import java.util.Map;
import java.util.Set;

/**
 * Pause flags shared across the cluster, so pausing on one instance pauses all.
 *
 * <p>Fails closed: every method goes to the cluster and lets its exception
 * escape. A caller that cannot learn whether it is paused must treat itself as
 * paused, and cannot do that if this class invents an answer.
 *
 * <p>The global and per-tool flags keep their plain keys. A scope flag is keyed by the scope's keyed name, so its key
 * holds no scope id; its value is the scope id until the pause document of task 228 replaces it, which is how
 * {@link #snapshot()} still lists the paused scopes. Every write waits for the key's lock at most the lock timeout
 * and then fails with {@code CLUSTER_LOCK_TIMEOUT}.
 */
public final class HazelcastOversightState implements OversightState {

    private final PrivacyCluster cluster;
    private final Duration lockTimeout;

    /** With the default lock timeout of two seconds. */
    public HazelcastOversightState(PrivacyCluster cluster) {
        this(cluster, ClusterLocks.DEFAULT_TIMEOUT);
    }

    /**
     * @param lockTimeout how long a write waits for its key's lock before it is refused, {@code PT100MS} to
     *                    {@code PT30S}
     * @throws IllegalArgumentException if the timeout is outside that range
     */
    public HazelcastOversightState(PrivacyCluster cluster, Duration lockTimeout) {
        this.cluster = Objects.requireNonNull(cluster, "cluster");
        this.lockTimeout = ClusterLocks.checked(lockTimeout);
    }

    @Override
    public boolean allPaused() {
        return flags().containsKey(ScopeKeys.pausedAll());
    }

    @Override
    public boolean toolPaused(String tool) {
        return flags().containsKey(ScopeKeys.pausedTool(tool));
    }

    @Override
    public boolean scopePaused(String scopeId) {
        return flags().containsKey(cluster.scopeKeys().pausedScope(scopeId));
    }

    @Override
    public void pauseAll() {
        ClusterLocks.set(flags(), ScopeKeys.pausedAll(), Boolean.TRUE, 0, lockTimeout);
    }

    @Override
    public void resumeAll() {
        ClusterLocks.delete(flags(), ScopeKeys.pausedAll(), lockTimeout);
    }

    @Override
    public void pauseTool(String tool) {
        ClusterLocks.set(flags(), ScopeKeys.pausedTool(tool), Boolean.TRUE, 0, lockTimeout);
    }

    @Override
    public void resumeTool(String tool) {
        ClusterLocks.delete(flags(), ScopeKeys.pausedTool(tool), lockTimeout);
    }

    @Override
    public void pauseScope(String scopeId) {
        ClusterLocks.set(flags(), cluster.scopeKeys().pausedScope(scopeId), scopeId, 0, lockTimeout);
    }

    @Override
    public void resumeScope(String scopeId) {
        ClusterLocks.delete(flags(), cluster.scopeKeys().pausedScope(scopeId), lockTimeout);
    }

    @Override
    public OversightSnapshot snapshot() {
        boolean all = false;
        Set<String> tools = new HashSet<>();
        Set<String> scopes = new HashSet<>();
        for (Map.Entry<String, Object> flag : flags().entrySet()) {
            String key = flag.getKey();
            if (key.equals(ScopeKeys.pausedAll())) {
                all = true;
            } else if (key.startsWith("tool:")) {
                tools.add(key.substring("tool:".length()));
            } else if (flag.getValue() instanceof String scopeId) {
                scopes.add(scopeId);
            }
        }
        return new OversightSnapshot(all, tools, scopes);
    }

    private IMap<String, Object> flags() {
        return cluster.instance().getMap(PrivacyCluster.OVERSIGHT_MAP);
    }
}

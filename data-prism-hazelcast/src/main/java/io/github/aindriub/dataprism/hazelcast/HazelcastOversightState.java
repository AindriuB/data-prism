package io.github.aindriub.dataprism.hazelcast;

import com.hazelcast.map.IMap;
import io.github.aindriub.dataprism.oversight.OversightSnapshot;
import io.github.aindriub.dataprism.oversight.OversightState;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Pause flags shared across the cluster, so pausing on one instance pauses all.
 *
 * <p>Fails closed: every method goes to the cluster and lets its exception
 * escape. A caller that cannot learn whether it is paused must treat itself as
 * paused, and cannot do that if this class invents an answer.
 */
public final class HazelcastOversightState implements OversightState {

    private final PrivacyCluster cluster;

    public HazelcastOversightState(PrivacyCluster cluster) {
        this.cluster = Objects.requireNonNull(cluster, "cluster");
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
        return flags().containsKey(ScopeKeys.pausedScope(scopeId));
    }

    @Override
    public void pauseAll() {
        flags().set(ScopeKeys.pausedAll(), Boolean.TRUE);
    }

    @Override
    public void resumeAll() {
        flags().delete(ScopeKeys.pausedAll());
    }

    @Override
    public void pauseTool(String tool) {
        flags().set(ScopeKeys.pausedTool(tool), Boolean.TRUE);
    }

    @Override
    public void resumeTool(String tool) {
        flags().delete(ScopeKeys.pausedTool(tool));
    }

    @Override
    public void pauseScope(String scopeId) {
        flags().set(ScopeKeys.pausedScope(scopeId), Boolean.TRUE);
    }

    @Override
    public void resumeScope(String scopeId) {
        flags().delete(ScopeKeys.pausedScope(scopeId));
    }

    @Override
    public OversightSnapshot snapshot() {
        boolean all = false;
        Set<String> tools = new HashSet<>();
        Set<String> scopes = new HashSet<>();
        for (String key : flags().keySet()) {
            if (key.equals(ScopeKeys.pausedAll())) {
                all = true;
            } else if (key.startsWith("tool:")) {
                tools.add(key.substring("tool:".length()));
            } else {
                scopes.add(ScopeKeys.scopeOf(key));
            }
        }
        return new OversightSnapshot(all, tools, scopes);
    }

    private IMap<String, Boolean> flags() {
        return cluster.instance().getMap(PrivacyCluster.OVERSIGHT_MAP);
    }
}

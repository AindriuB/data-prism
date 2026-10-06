package io.github.aindriub.dataprism.oversight;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** Per-instance pause flags. A deployment with several instances uses the Hazelcast implementation. */
public final class InMemoryOversightState implements OversightState {

    private final AtomicBoolean all = new AtomicBoolean();
    private final Set<String> tools = ConcurrentHashMap.newKeySet();
    private final Set<String> scopes = ConcurrentHashMap.newKeySet();

    @Override
    public boolean allPaused() {
        return all.get();
    }

    @Override
    public boolean toolPaused(String tool) {
        return tools.contains(tool);
    }

    @Override
    public boolean scopePaused(String scopeId) {
        return scopes.contains(scopeId);
    }

    @Override
    public void pauseAll() {
        all.set(true);
    }

    @Override
    public void resumeAll() {
        all.set(false);
    }

    @Override
    public void pauseTool(String tool) {
        tools.add(tool);
    }

    @Override
    public void resumeTool(String tool) {
        tools.remove(tool);
    }

    @Override
    public void pauseScope(String scopeId) {
        scopes.add(scopeId);
    }

    @Override
    public void resumeScope(String scopeId) {
        scopes.remove(scopeId);
    }

    @Override
    public OversightSnapshot snapshot() {
        return new OversightSnapshot(all.get(), tools, scopes);
    }
}

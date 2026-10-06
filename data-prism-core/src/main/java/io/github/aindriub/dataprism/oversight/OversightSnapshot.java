package io.github.aindriub.dataprism.oversight;

import java.util.Set;

/** A point-in-time copy of the pause flags. */
public record OversightSnapshot(boolean allPaused, Set<String> pausedTools, Set<String> pausedScopes) {

    public OversightSnapshot {
        pausedTools = Set.copyOf(pausedTools);
        pausedScopes = Set.copyOf(pausedScopes);
    }
}

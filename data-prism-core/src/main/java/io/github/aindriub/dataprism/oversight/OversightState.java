package io.github.aindriub.dataprism.oversight;

/**
 * Pause flags: global, per tool and per scope.
 *
 * <p>Any {@link RuntimeException} from an implementation means "unavailable",
 * and callers fail closed.
 */
public interface OversightState {

    boolean allPaused();

    boolean toolPaused(String tool);

    boolean scopePaused(String scopeId);

    void pauseAll();

    void resumeAll();

    void pauseTool(String tool);

    void resumeTool(String tool);

    void pauseScope(String scopeId);

    void resumeScope(String scopeId);

    OversightSnapshot snapshot();
}

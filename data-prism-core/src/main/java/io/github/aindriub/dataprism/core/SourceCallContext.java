package io.github.aindriub.dataprism.core;

import io.github.aindriub.dataprism.core.correlation.ExternalCorrelationId;
import java.util.Optional;

/**
 * Per-call context handed to a source adapter. An explicit field rather than a
 * ThreadLocal because source fan-out runs on parallel virtual threads.
 */
public record SourceCallContext(Optional<ExternalCorrelationId> externalCorrelationId) {

    private static final SourceCallContext NONE = new SourceCallContext(Optional.empty());

    public SourceCallContext {
        externalCorrelationId = externalCorrelationId == null ? Optional.empty() : externalCorrelationId;
    }

    public static SourceCallContext none() {
        return NONE;
    }

    public static SourceCallContext of(ExternalCorrelationId id) {
        return new SourceCallContext(Optional.ofNullable(id));
    }
}

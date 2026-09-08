package io.github.aindriub.dataprism.orchestration;

import java.util.Objects;

/**
 * What a caller asked for.
 *
 * <p>An entity type and a subject, and nothing that selects a source, a scope or
 * a profile. Those come from configuration and the authenticated session, so a
 * caller cannot widen its own access by asking differently.
 */
public record ContextRequest(String entityType, String subjectId) {

    public ContextRequest {
        Objects.requireNonNull(entityType, "entityType");
        Objects.requireNonNull(subjectId, "subjectId");
        if (entityType.isBlank() || subjectId.isBlank()) {
            throw new IllegalArgumentException("entityType and subjectId must not be blank");
        }
    }
}

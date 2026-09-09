package io.github.aindriub.dataprism.orchestration;

import java.util.Objects;
import java.util.Set;

/**
 * What a caller asked for.
 *
 * <p>An entity type and a subject, and nothing that selects a source, a scope or
 * a profile. Those come from configuration and the authenticated session, so a
 * caller cannot widen its own access by asking differently.
 *
 * @param rejectedArguments the *names* of any reserved argument the caller
 *                          attempted to supply — {@code scopeId}, {@code purpose}
 *                          and the like. A value must never be put in this set;
 *                          it is audited and logged verbatim, and the component
 *                          being {@code Set<String>} of names is what makes
 *                          putting a value in it impossible by type.
 */
public record ContextRequest(String entityType, String subjectId, Set<String> rejectedArguments) {

    public ContextRequest {
        Objects.requireNonNull(entityType, "entityType");
        Objects.requireNonNull(subjectId, "subjectId");
        Objects.requireNonNull(rejectedArguments, "rejectedArguments");
        if (entityType.isBlank() || subjectId.isBlank()) {
            throw new IllegalArgumentException("entityType and subjectId must not be blank");
        }
        rejectedArguments = Set.copyOf(rejectedArguments);
    }

    /** No reserved argument was rejected — the ordinary case. */
    public static ContextRequest of(String entityType, String subjectId) {
        return new ContextRequest(entityType, subjectId, Set.of());
    }
}

package io.github.aindriub.dataprism.core;

import java.util.Map;
import java.util.Objects;

/**
 * What to fetch from a source.
 *
 * <p>Deliberately not a URL, a query or anything else a caller could steer.
 * Adapters map these fields onto endpoints configured server-side.
 */
public record DataRequest(String entityType, String subjectId, Map<String, Object> parameters) {

    public DataRequest {
        Objects.requireNonNull(entityType, "entityType");
        Objects.requireNonNull(subjectId, "subjectId");
        parameters = parameters == null ? Map.of() : Map.copyOf(parameters);
    }

    public static DataRequest of(String entityType, String subjectId) {
        return new DataRequest(entityType, subjectId, Map.of());
    }
}

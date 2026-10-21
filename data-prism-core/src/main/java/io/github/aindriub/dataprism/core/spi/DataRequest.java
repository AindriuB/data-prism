package io.github.aindriub.dataprism.core.spi;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * What to fetch from a source.
 *
 * <p>Deliberately not a URL, a query or anything else a caller could steer.
 * Adapters map these fields onto endpoints configured server-side.
 */
public record DataRequest(
        String entityType,
        String subjectId,
        Map<String, Object> parameters,
        SourceCallContext context,
        Optional<KeyNamespace> keyNamespace) {

    public DataRequest(String entityType, String subjectId, Map<String, Object> parameters) {
        this(entityType, subjectId, parameters, SourceCallContext.none());
    }

    public DataRequest(
            String entityType, String subjectId, Map<String, Object> parameters, SourceCallContext context) {
        this(entityType, subjectId, parameters, context, Optional.empty());
    }

    public DataRequest {
        Objects.requireNonNull(entityType, "entityType");
        Objects.requireNonNull(subjectId, "subjectId");
        parameters = parameters == null ? Map.of() : Map.copyOf(parameters);
        context = context == null ? SourceCallContext.none() : context;
        keyNamespace = keyNamespace == null ? Optional.empty() : keyNamespace;
    }

    public static DataRequest of(String entityType, String subjectId) {
        return new DataRequest(entityType, subjectId, Map.of());
    }

    public DataRequest withContext(SourceCallContext newContext) {
        return new DataRequest(entityType, subjectId, parameters, newContext, keyNamespace);
    }

    /** The namespace {@code subjectId} is expressed in; empty when none was chosen. */
    public DataRequest withKeyNamespace(KeyNamespace namespace) {
        return new DataRequest(entityType, subjectId, parameters, context, Optional.of(namespace));
    }
}

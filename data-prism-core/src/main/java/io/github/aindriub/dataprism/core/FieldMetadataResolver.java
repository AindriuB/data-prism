package io.github.aindriub.dataprism.core;

import java.util.List;

/** Reads classification metadata off a type. */
public interface FieldMetadataResolver {

    List<FieldMetadata> resolve(Class<?> type);

    /** The field carrying this type's own correlation id, or null if it has none. */
    default String internalIdentifierField(Class<?> type) {
        return resolve(type).stream()
                .filter(FieldMetadata::internalIdentifier)
                .map(FieldMetadata::fieldName)
                .findFirst()
                .orElse(null);
    }
}

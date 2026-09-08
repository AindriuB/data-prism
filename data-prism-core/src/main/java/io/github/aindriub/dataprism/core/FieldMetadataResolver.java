package io.github.aindriub.dataprism.core;

import io.github.aindriub.dataprism.annotations.LlmExposedModel;
import io.github.aindriub.dataprism.annotations.SensitiveObject;

import java.util.List;

/**
 * Reads classification metadata off a type.
 *
 * <p>The engine asks this rather than reading annotations itself, so that where
 * the metadata comes from is a deployment choice. Annotations keep the
 * classification beside the field it describes, which is the better default;
 * descriptors handle the cases annotations cannot reach — a generated DTO, a
 * type from a dependency, a model that has to be reclassified without a release.
 */
public interface FieldMetadataResolver {

    List<FieldMetadata> resolve(Class<?> type);

    /**
     * Whether this type may be returned through MCP at all. Absence is a refusal,
     * not an oversight.
     */
    default boolean exposed(Class<?> type) {
        return type.getAnnotation(LlmExposedModel.class) != null;
    }

    /**
     * Whether this type may be descended into when it appears nested. A nested
     * object is more fields rather than a value, so descending into one that
     * nobody reviewed would emit unclassified data.
     */
    default boolean descendable(Class<?> type) {
        return exposed(type) || type.getAnnotation(SensitiveObject.class) != null;
    }

    /** The field carrying this type's own correlation id, or null if it has none. */
    default String internalIdentifierField(Class<?> type) {
        return resolve(type).stream()
                .filter(FieldMetadata::internalIdentifier)
                .map(FieldMetadata::fieldName)
                .findFirst()
                .orElse(null);
    }
}

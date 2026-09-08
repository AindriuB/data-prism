package io.github.aindriub.dataprism.core;

import io.github.aindriub.dataprism.annotations.DataClassification;
import io.github.aindriub.dataprism.annotations.PrivacyAction;
import io.github.aindriub.dataprism.annotations.PrivacyNamespace;

import java.util.List;

/**
 * What the annotations say about one field. Derived once per class and cached;
 * this is annotation metadata rather than data, so caching it is safe.
 *
 * @param identifier         whether this field identifies a subject, whether the
 *                           record's own or another's. Identifiers are never
 *                           emitted
 * @param subjectRole        {@code "self"} for {@code @InternalIdentifier}, the
 *                           declared role for {@code @SubjectIdentifier}, null
 *                           otherwise
 * @param subjectField       the field naming this value's subject; empty means
 *                           the record's own
 * @param nonSensitiveReason the author's stated reason, null unless declared
 *                           non-sensitive
 * @param valueType          the field's declared type. Needed because the engine
 *                           walks a JSON tree, which has no idea what a nested
 *                           object was: without the type there is no metadata to
 *                           descend with, and the subtree can only be copied or
 *                           refused
 * @param elementType        for a collection, the declared element type; null
 *                           otherwise
 */
public record FieldMetadata(
        String fieldName,
        boolean identifier,
        String subjectRole,
        List<DataClassification> classifications,
        PrivacyNamespace namespace,
        PrivacyAction suggestedAction,
        String subjectField,
        String nonSensitiveReason,
        Class<?> valueType,
        Class<?> elementType) {

    public static final String SELF = "self";

    public FieldMetadata {
        classifications = List.copyOf(classifications);
    }

    /**
     * A property that appeared in the serialised source with no declaration at
     * all behind it. Undeclared by construction, so the profile's setting for
     * unclassified data decides what happens to it.
     */
    public static FieldMetadata undeclared(String fieldName) {
        return new FieldMetadata(fieldName, false, null, List.of(), PrivacyNamespace.NONE,
                null, "", null, Object.class, null);
    }

    public boolean sensitive() {
        return !classifications.isEmpty();
    }

    /** The record's own correlation identifier, as opposed to another subject's. */
    public boolean internalIdentifier() {
        return identifier && SELF.equals(subjectRole);
    }

    /**
     * Whether someone made a decision about this field. An undeclared field on an
     * exposed model is the fail-closed case: it is not evidence that the field is
     * safe, it is evidence that nobody looked.
     */
    public boolean declared() {
        return sensitive() || nonSensitiveReason != null || identifier;
    }
}

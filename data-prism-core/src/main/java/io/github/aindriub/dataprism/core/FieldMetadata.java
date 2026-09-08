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
 */
public record FieldMetadata(
        String fieldName,
        boolean identifier,
        String subjectRole,
        List<DataClassification> classifications,
        PrivacyNamespace namespace,
        PrivacyAction suggestedAction,
        String subjectField,
        String nonSensitiveReason) {

    public static final String SELF = "self";

    public FieldMetadata {
        classifications = List.copyOf(classifications);
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

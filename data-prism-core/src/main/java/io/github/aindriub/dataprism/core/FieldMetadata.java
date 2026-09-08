package io.github.aindriub.dataprism.core;

import io.github.aindriub.dataprism.annotations.DataClassification;
import io.github.aindriub.dataprism.annotations.PrivacyAction;
import io.github.aindriub.dataprism.annotations.PrivacyNamespace;

import java.util.List;

/**
 * What the annotations say about one field. Derived once per class and cached;
 * this is annotation metadata, not data, so caching it is safe.
 */
public record FieldMetadata(
        String fieldName,
        boolean internalIdentifier,
        List<DataClassification> classifications,
        PrivacyNamespace namespace,
        PrivacyAction suggestedAction,
        String subjectField,
        String nonSensitiveReason) {

    public FieldMetadata {
        classifications = List.copyOf(classifications);
    }

    public boolean sensitive() {
        return !classifications.isEmpty();
    }

    /**
     * Whether someone made a decision about this field. An undeclared field on an
     * exposed model is the fail-closed case: it is not evidence of safety, it is
     * evidence that nobody looked.
     */
    public boolean declared() {
        return sensitive() || nonSensitiveReason != null || internalIdentifier;
    }
}

package io.github.aindriub.dataprism.quickstart.extension;

import io.github.aindriub.dataprism.annotations.DataClassification;
import io.github.aindriub.dataprism.annotations.InternalIdentifier;
import io.github.aindriub.dataprism.annotations.LlmExposedModel;
import io.github.aindriub.dataprism.annotations.NonSensitive;
import io.github.aindriub.dataprism.annotations.PrivacyAction;
import io.github.aindriub.dataprism.annotations.PrivacyNamespace;
import io.github.aindriub.dataprism.annotations.SensitiveData;

/**
 * What {@code data-prism-quickstart-fixtures}' customer record looks like
 * once this reviewed extension has classified every field — the decision the
 * platform never makes for an adapter (see {@code LlmExposedModelProcessor}
 * and pack.md §30: a field nobody classified is never exposed).
 */
// --8<-- [start:model]
@LlmExposedModel
public record CustomerModel(

        @InternalIdentifier
        String customerId,

        @SensitiveData(
                classifications = DataClassification.PII,
                namespace = PrivacyNamespace.PERSON_NAME,
                suggestedAction = PrivacyAction.SYNTHESIZE)
        String customerName,

        @SensitiveData(
                classifications = DataClassification.CONTACT,
                namespace = PrivacyNamespace.EMAIL,
                suggestedAction = PrivacyAction.REDACT)
        String email,

        @NonSensitive(reason = "Enumerated lifecycle state; no free text and no bearing on identity")
        String status) {
}
// --8<-- [end:model]

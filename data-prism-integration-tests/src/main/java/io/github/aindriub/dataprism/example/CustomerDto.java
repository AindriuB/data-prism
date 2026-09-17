package io.github.aindriub.dataprism.example;

import io.github.aindriub.dataprism.annotations.DataClassification;
import io.github.aindriub.dataprism.annotations.InternalIdentifier;
import io.github.aindriub.dataprism.annotations.LlmExposedModel;
import io.github.aindriub.dataprism.annotations.NonSensitive;
import io.github.aindriub.dataprism.annotations.PrivacyAction;
import io.github.aindriub.dataprism.annotations.PrivacyNamespace;
import io.github.aindriub.dataprism.annotations.SensitiveData;

/**
 * A source record, as one system happens to shape it.
 *
 * <p>This type lives in the example because a business domain has no place in
 * the platform. Core knows about entities, namespaces and policies; it has never
 * heard of a customer.
 */
@LlmExposedModel
public record CustomerDto(

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

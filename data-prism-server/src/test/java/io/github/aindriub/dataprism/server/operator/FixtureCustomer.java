package io.github.aindriub.dataprism.server.operator;

import io.github.aindriub.dataprism.annotations.DataClassification;
import io.github.aindriub.dataprism.annotations.InternalIdentifier;
import io.github.aindriub.dataprism.annotations.LlmExposedModel;
import io.github.aindriub.dataprism.annotations.NonSensitive;
import io.github.aindriub.dataprism.annotations.PrivacyAction;
import io.github.aindriub.dataprism.annotations.PrivacyNamespace;
import io.github.aindriub.dataprism.annotations.SensitiveData;

/** A synthetic source record for the operator tests. Invented values only. */
@LlmExposedModel
public record FixtureCustomer(
        @InternalIdentifier String customerId,
        @SensitiveData(classifications = DataClassification.PII, namespace = PrivacyNamespace.PERSON_NAME,
                suggestedAction = PrivacyAction.SYNTHESIZE) String customerName,
        @NonSensitive(reason = "Enumerated lifecycle state; no free text and no bearing on identity")
        String status) {
}

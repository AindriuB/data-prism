package io.github.aindriub.dataprism.example;

import io.github.aindriub.dataprism.annotations.DataClassification;
import io.github.aindriub.dataprism.annotations.InternalIdentifier;
import io.github.aindriub.dataprism.annotations.LlmExposedModel;
import io.github.aindriub.dataprism.annotations.NonSensitive;
import io.github.aindriub.dataprism.annotations.PrivacyAction;
import io.github.aindriub.dataprism.annotations.PrivacyNamespace;
import io.github.aindriub.dataprism.annotations.SensitiveData;

/** The same person again, as the order system holds them. */
@LlmExposedModel
public record OrderDto(

        @InternalIdentifier
        String customerId,

        @NonSensitive(reason = "Opaque order reference, meaningless outside the order system")
        String orderId,

        @SensitiveData(
                classifications = DataClassification.PII,
                namespace = PrivacyNamespace.PERSON_NAME,
                suggestedAction = PrivacyAction.SYNTHESIZE)
        String customerName,

        @NonSensitive(reason = "Free-text note; carried so injection heuristics have something real to look at")
        String note) {
}

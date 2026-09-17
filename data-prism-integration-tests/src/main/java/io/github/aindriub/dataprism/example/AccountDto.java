package io.github.aindriub.dataprism.example;

import io.github.aindriub.dataprism.annotations.DataClassification;
import io.github.aindriub.dataprism.annotations.InternalIdentifier;
import io.github.aindriub.dataprism.annotations.LlmExposedModel;
import io.github.aindriub.dataprism.annotations.NonSensitive;
import io.github.aindriub.dataprism.annotations.PrivacyAction;
import io.github.aindriub.dataprism.annotations.PrivacyNamespace;
import io.github.aindriub.dataprism.annotations.SensitiveData;

import java.math.BigDecimal;

/**
 * The same person as {@link CustomerDto}, as the account system happens to hold
 * them.
 *
 * <p>Note that the field is called {@code holderName} rather than
 * {@code customerName}. Correlation matches on the namespace and not the name,
 * which is what lets two systems that never agreed on a schema still be compared.
 */
@LlmExposedModel
public record AccountDto(

        @InternalIdentifier
        String customerId,

        @NonSensitive(reason = "Opaque account reference, meaningless outside the account system")
        String accountId,

        @SensitiveData(
                classifications = DataClassification.PII,
                namespace = PrivacyNamespace.PERSON_NAME,
                suggestedAction = PrivacyAction.SYNTHESIZE)
        String holderName,

        @SensitiveData(
                classifications = DataClassification.FINANCIAL,
                namespace = PrivacyNamespace.FINANCIAL_VALUE,
                suggestedAction = PrivacyAction.GENERALIZE)
        BigDecimal balance) {
}

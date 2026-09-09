package io.github.aindriub.dataprism.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PurposeValidatorTest {

    @Test
    @DisplayName("a purpose in the configured list is accepted")
    void acceptsConfiguredPurpose() {
        PurposeValidator validator = new PurposeValidator(Set.of("fraud_investigation"));

        assertThatCode(() -> validator.validate("fraud_investigation")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a null purpose is refused with UNKNOWN_PURPOSE")
    void refusesNullPurpose() {
        PurposeValidator validator = new PurposeValidator(Set.of("fraud_investigation"));

        assertThatThrownBy(() -> validator.validate(null))
                .isInstanceOf(SecurityRefusedException.class)
                .satisfies(e -> assertThat(((SecurityRefusedException) e).code())
                        .isEqualTo(PurposeValidator.UNKNOWN_PURPOSE));
    }

    @Test
    @DisplayName("a blank purpose is refused with UNKNOWN_PURPOSE")
    void refusesBlankPurpose() {
        PurposeValidator validator = new PurposeValidator(Set.of("fraud_investigation"));

        assertThatThrownBy(() -> validator.validate("   "))
                .isInstanceOf(SecurityRefusedException.class)
                .satisfies(e -> assertThat(((SecurityRefusedException) e).code())
                        .isEqualTo(PurposeValidator.UNKNOWN_PURPOSE));
    }

    @Test
    @DisplayName("a purpose not in the configured list is refused with UNKNOWN_PURPOSE")
    void refusesUnknownPurpose() {
        PurposeValidator validator = new PurposeValidator(Set.of("fraud_investigation"));

        assertThatThrownBy(() -> validator.validate("marketing"))
                .isInstanceOf(SecurityRefusedException.class)
                .satisfies(e -> assertThat(((SecurityRefusedException) e).code())
                        .isEqualTo(PurposeValidator.UNKNOWN_PURPOSE));
    }

    @Test
    @DisplayName("purpose matching is case-sensitive")
    void isCaseSensitive() {
        PurposeValidator validator = new PurposeValidator(Set.of("fraud_investigation"));

        assertThatThrownBy(() -> validator.validate("Fraud_Investigation"))
                .isInstanceOf(SecurityRefusedException.class);
    }

    @Test
    @DisplayName("adding a purpose to configuration is the only way to make a previously refused purpose pass")
    void onlyConfigurationChangesTheOutcome() {
        PurposeValidator withoutPurpose = new PurposeValidator(Set.of("fraud_investigation"));
        assertThatThrownBy(() -> withoutPurpose.validate("customer_support"))
                .isInstanceOf(SecurityRefusedException.class);

        PurposeValidator withPurpose =
                new PurposeValidator(Set.of("fraud_investigation", "customer_support"));
        assertThatCode(() -> withPurpose.validate("customer_support")).doesNotThrowAnyException();
    }
}

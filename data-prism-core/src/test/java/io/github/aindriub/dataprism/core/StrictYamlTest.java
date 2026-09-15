package io.github.aindriub.dataprism.core;

import io.github.aindriub.dataprism.annotations.DataClassification;
import io.github.aindriub.dataprism.annotations.PrivacyAction;
import io.github.aindriub.dataprism.annotations.PrivacyNamespace;
import io.github.aindriub.dataprism.core.policy.GeneralizationRule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StrictYamlTest {

    @Test
    @DisplayName("case and surrounding whitespace are tolerated")
    void toleratesCaseAndWhitespace() {
        assertThat(StrictYaml.enumValue(PrivacyAction.class, " redact ", "x.y"))
                .isEqualTo(PrivacyAction.REDACT);
    }

    @Test
    @DisplayName("an unknown enum constant names the enum, the value and the offending key")
    void unknownConstantFails() {
        assertThatThrownBy(() -> StrictYaml.enumValue(DataClassification.class, "PIII", "Some.Type.field"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("DataClassification")
                .hasMessageContaining("PIII")
                .hasMessageContaining("Some.Type.field");
    }

    @Test
    @DisplayName("an unknown namespace fails the same way")
    void unknownNamespaceFails() {
        assertThatThrownBy(() -> StrictYaml.enumValue(PrivacyNamespace.class, "NOWHERE", "P.generalization.NOWHERE"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("PrivacyNamespace")
                .hasMessageContaining("NOWHERE");
    }

    @Test
    @DisplayName("an unknown precision fails the same way")
    void unknownPrecisionFails() {
        assertThatThrownBy(() ->
                StrictYaml.enumValue(GeneralizationRule.Precision.class, "DECADE", "P.generalization.X.precision"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Precision")
                .hasMessageContaining("DECADE");
    }

    @Test
    @DisplayName("a null raw value is not silently accepted: it fails like any other unresolvable value")
    void nullRawValueFails() {
        // Both callers guard every call site with `== null` before reaching this
        // helper, so it was never actually invoked with null before this
        // extraction. What is preserved here is that guard's effect: a null
        // still cannot resolve to an enum constant, and still fails loudly
        // naming the offending key, rather than being accepted as a default.
        assertThatThrownBy(() -> StrictYaml.enumValue(PrivacyAction.class, null, "x.y"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("PrivacyAction")
                .hasMessageContaining("x.y");
    }
}

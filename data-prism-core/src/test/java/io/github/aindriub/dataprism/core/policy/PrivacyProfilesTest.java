package io.github.aindriub.dataprism.core.policy;

import io.github.aindriub.dataprism.annotations.DataClassification;
import io.github.aindriub.dataprism.annotations.PrivacyAction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PrivacyProfilesTest {

    private static InputStream yaml(String body) {
        return new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("the profiles shipped with the library load")
    void shippedProfilesLoad() throws Exception {
        try (InputStream in = PrivacyProfiles.class.getResourceAsStream("/privacy-profiles-default.yaml")) {
            Map<String, PrivacyProfile> profiles = PrivacyProfiles.fromYaml(in);

            assertThat(profiles).containsKeys("DEFAULT", "STRICT");
            assertThat(profiles.get("DEFAULT").classifications())
                    .containsEntry(DataClassification.PII,
                            PrivacyProfile.ClassificationRule.of(PrivacyAction.SYNTHESIZE));
            // Every shipped profile refuses rather than exposing an unclassified
            // field. There is no setting that would let one through.
            assertThat(profiles.values()).allSatisfy(p ->
                    assertThat(p.unclassified())
                            .isEqualTo(PrivacyProfile.UnclassifiedBehaviour.FAIL_REQUEST));
        }
    }

    @Test
    @DisplayName("override is read, and defaults to false")
    void readsOverride() {
        var profiles = PrivacyProfiles.fromYaml(yaml("""
                profiles:
                  P:
                    unclassified: REDACT_AND_WARN
                    classifications:
                      PII: { action: SYNTHESIZE, override: true }
                      CONTACT: { action: REDACT }
                """));

        var rules = profiles.get("P").classifications();
        assertThat(rules.get(DataClassification.PII).override()).isTrue();
        assertThat(rules.get(DataClassification.CONTACT).override()).isFalse();
    }

    @Test
    @DisplayName("a misspelled classification fails at load, naming the key")
    void unknownClassificationFails() {
        // The alternative is a rule that silently does not apply, which looks
        // exactly like a rule that does.
        assertThatThrownBy(() -> PrivacyProfiles.fromYaml(yaml("""
                profiles:
                  P:
                    classifications:
                      PIII: { action: REDACT }
                """)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("PIII");
    }

    @Test
    @DisplayName("a misspelled action fails at load")
    void unknownActionFails() {
        assertThatThrownBy(() -> PrivacyProfiles.fromYaml(yaml("""
                profiles:
                  P:
                    classifications:
                      PII: { action: REDAKT }
                """)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("REDAKT");
    }

    @Test
    @DisplayName("a rule with no action fails rather than defaulting")
    void missingActionFails() {
        assertThatThrownBy(() -> PrivacyProfiles.fromYaml(yaml("""
                profiles:
                  P:
                    classifications:
                      PII: { override: true }
                """)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no action");
    }

    @Test
    @DisplayName("a file with no profiles section fails")
    void emptyFileFails() {
        assertThatThrownBy(() -> PrivacyProfiles.fromYaml(yaml("something: else\n")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("profiles");
    }

    @Test
    @DisplayName("generalisation rules are read from the profile")
    void readsGeneralizationRules() {
        var profiles = PrivacyProfiles.fromYaml(yaml("""
                profiles:
                  P:
                    classifications:
                      FINANCIAL: { action: GENERALIZE }
                    generalization:
                      FINANCIAL_VALUE:
                        type: NUMERIC_BAND
                        unit: EUR
                        bounds: [0, 1000, 10000]
                      PERSON_IDENTITY:
                        type: DATE_TRUNCATION
                        precision: YEAR
                """));

        var rules = profiles.get("P").generalizations();
        assertThat(rules.get(io.github.aindriub.dataprism.annotations.PrivacyNamespace.FINANCIAL_VALUE))
                .satisfies(rule -> {
                    assertThat(rule.kind()).isEqualTo(GeneralizationRule.Kind.NUMERIC_BAND);
                    assertThat(rule.unit()).isEqualTo("EUR");
                    assertThat(rule.bounds()).hasSize(3);
                });
        assertThat(rules.get(io.github.aindriub.dataprism.annotations.PrivacyNamespace.PERSON_IDENTITY)
                .precision()).isEqualTo(GeneralizationRule.Precision.YEAR);
    }

    @Test
    @DisplayName("a band rule with unusable bounds fails at load")
    void badBoundsFailAtLoad() {
        assertThatThrownBy(() -> PrivacyProfiles.fromYaml(yaml("""
                profiles:
                  P:
                    generalization:
                      FINANCIAL_VALUE: { type: NUMERIC_BAND, bounds: [1000, 10] }
                """)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must ascend");

        assertThatThrownBy(() -> PrivacyProfiles.fromYaml(yaml("""
                profiles:
                  P:
                    generalization:
                      FINANCIAL_VALUE: { type: NUMERIC_BAND, bounds: [ten, twenty] }
                """)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-numeric bound");
    }

    @Test
    @DisplayName("unclassified defaults to failing the request when not stated")
    void unclassifiedDefaultsToFailClosed() {
        var profiles = PrivacyProfiles.fromYaml(yaml("""
                profiles:
                  P:
                    classifications:
                      PII: { action: REDACT }
                """));

        assertThat(profiles.get("P").unclassified())
                .isEqualTo(PrivacyProfile.UnclassifiedBehaviour.FAIL_REQUEST);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
            "PASS_THROUGH", "GENERALIZE", "SYNTHESIZE", "TOKENIZE", "HASH"})
    @DisplayName("a profile mapping a special category weaker than REDACT is refused, override or not")
    void weakSpecialCategoryRuleIsRefused(String action) {
        for (String extra : new String[] {"", ", override: true"}) {
            assertThatThrownBy(() -> PrivacyProfiles.fromYaml(yaml("""
                    profiles:
                      LAX:
                        classifications:
                          BIOMETRIC: { action: %s%s }
                    """.formatted(action, extra))))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("SPECIAL_CATEGORY_EXPOSED")
                    .hasMessageContaining("LAX")
                    .hasMessageContaining("BIOMETRIC");
        }
    }

    @Test
    @DisplayName("shipped profiles remove the seven new special categories and keep PHI at REDACT")
    void shippedProfilesCoverSpecialCategories() throws Exception {
        try (InputStream in = PrivacyProfiles.class.getResourceAsStream("/privacy-profiles-default.yaml")) {
            var profiles = PrivacyProfiles.fromYaml(in);
            for (String name : new String[] {"DEFAULT", "STRICT"}) {
                var rules = profiles.get(name).classifications();
                for (DataClassification c : DataClassification.SPECIAL_CATEGORIES) {
                    assertThat(rules.get(c).action())
                            .isEqualTo(c == DataClassification.PHI ? PrivacyAction.REDACT : PrivacyAction.REMOVE);
                }
            }
        }
    }

    @Test
    @DisplayName("the special categories are exactly PHI plus the seven Art. 9 additions")
    void specialCategoryMembership() {
        assertThat(DataClassification.SPECIAL_CATEGORIES).containsExactlyInAnyOrder(
                DataClassification.PHI, DataClassification.BIOMETRIC, DataClassification.GENETIC,
                DataClassification.ETHNIC_ORIGIN, DataClassification.POLITICAL_OPINION,
                DataClassification.RELIGIOUS_BELIEF, DataClassification.TRADE_UNION,
                DataClassification.SEX_LIFE_ORIENTATION);
    }

    private static void assertRefused(String yaml, String prefix) {
        assertThatThrownBy(() -> PrivacyProfiles.fromYaml(yaml(yaml)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith(prefix);
    }

    private static final String RULE = "profiles:\n  p:\n    classifications:\n      PII:\n        action: REDACT\n";

    @Test
    @DisplayName("a duplicate top-level key is refused with DUPLICATE_CONFIG_KEY")
    void duplicateTopLevelKey() {
        assertRefused(RULE + "profiles:\n  q:\n    unclassified: FAIL_REQUEST\n",
                "DUPLICATE_CONFIG_KEY: privacy profiles has a duplicate key 'profiles'");
    }

    @Test
    @DisplayName("a duplicate key in a profile is refused with DUPLICATE_CONFIG_KEY, naming the path")
    void duplicateKeyInProfile() {
        assertRefused("profiles:\n  p:\n    unclassified: REDACT_AND_WARN\n    unclassified: DROP_AND_WARN\n",
                "DUPLICATE_CONFIG_KEY: privacy profiles has a duplicate key 'unclassified' in profiles.p");
    }

    @Test
    @DisplayName("a duplicate key in a rule is refused with DUPLICATE_CONFIG_KEY, naming the path")
    void duplicateKeyInRule() {
        assertRefused(RULE + "        action: SYNTHESIZE\n",
                "DUPLICATE_CONFIG_KEY: privacy profiles has a duplicate key 'action' in profiles.p.classifications.PII");
    }

    @Test
    @DisplayName("a duplicate classification in one profile is refused with DUPLICATE_CONFIG_KEY")
    void duplicateClassification() {
        assertRefused(RULE + "      PII:\n        action: SYNTHESIZE\n",
                "DUPLICATE_CONFIG_KEY: privacy profiles has a duplicate key 'PII' in profiles.p.classifications");
    }

    @Test
    @DisplayName("an unknown top-level key is refused with UNKNOWN_CONFIG_KEY")
    void unknownTopLevelKey() {
        assertRefused("extra: 1\n" + RULE, "UNKNOWN_CONFIG_KEY: privacy profiles has an unknown key 'extra'");
    }

    @Test
    @DisplayName("an unknown key in a profile is refused with UNKNOWN_CONFIG_KEY, naming the path")
    void unknownKeyInProfile() {
        assertRefused("profiles:\n  p:\n    unclasified: REDACT_AND_WARN\n",
                "UNKNOWN_CONFIG_KEY: privacy profiles profiles.p has an unknown key 'unclasified'");
    }

    @Test
    @DisplayName("an unknown key in a rule is refused with UNKNOWN_CONFIG_KEY, naming the path")
    void unknownKeyInRule() {
        assertRefused(RULE + "        overide: true\n",
                "UNKNOWN_CONFIG_KEY: privacy profiles profiles.p.classifications.PII has an unknown key 'overide'");
    }

    @Test
    @DisplayName("an unknown key in a generalization rule is refused with UNKNOWN_CONFIG_KEY, naming the path")
    void unknownKeyInGeneralization() {
        assertRefused("profiles:\n  p:\n    generalization:\n      FINANCIAL_VALUE:\n        bounds: [0, 10]\n"
                        + "        bound: [1]\n",
                "UNKNOWN_CONFIG_KEY: privacy profiles profiles.p.generalization.FINANCIAL_VALUE has an unknown key 'bound'");
    }

    @Test
    @DisplayName("a second YAML document is refused with TRAILING_CONFIG_CONTENT")
    void secondDocument() {
        assertRefused(RULE + "---\nprofiles:\n  q:\n    unclassified: FAIL_REQUEST\n", "TRAILING_CONFIG_CONTENT: ");
    }

    @Test
    @DisplayName("override accepts true and false, and refuses yes, on, True and 1 with INVALID_CONFIG_BOOLEAN")
    void overrideBoolean() {
        assertThat(PrivacyProfiles.fromYaml(yaml(RULE + "        override: true\n")).get("p")
                .classifications().get(DataClassification.PII).override()).isTrue();
        for (String spelling : new String[] {"yes", "no", "on", "off", "True", "FALSE", "1"}) {
            assertRefused(RULE + "        override: " + spelling + "\n",
                    "INVALID_CONFIG_BOOLEAN: privacy profiles profiles.p.classifications.PII.override "
                            + "must be exactly true or false");
        }
    }

    @Test
    @DisplayName("a band bound written with a leading zero is refused with LEADING_ZERO_CONFIG_NUMBER")
    void leadingZeroBound() {
        for (String bound : new String[] {"010", "0777", "00", "-01"}) {
            assertRefused("profiles:\n  p:\n    generalization:\n      FINANCIAL_VALUE:\n        bounds: [0, " + bound
                            + "]\n",
                    "LEADING_ZERO_CONFIG_NUMBER: privacy profiles profiles.p.generalization.FINANCIAL_VALUE.bounds[1] "
                            + "must not be written with a leading zero");
        }
        assertThat(PrivacyProfiles.fromYaml(yaml(
                "profiles:\n  p:\n    generalization:\n      FINANCIAL_VALUE:\n        bounds: [0, 0.5, 10]\n"))
                .get("p").generalizations()).hasSize(1);
    }

    @Test
    @DisplayName("a unit written as a number or boolean is refused with NON_STRING_CONFIG_SCALAR; a quoted one is accepted")
    void unitMustBeAString() {
        for (String unit : new String[] {"5", "true", "1.5"}) {
            assertRefused("profiles:\n  p:\n    generalization:\n      FINANCIAL_VALUE:\n        bounds: [0, 10]\n"
                            + "        unit: " + unit + "\n",
                    "NON_STRING_CONFIG_SCALAR: privacy profiles profiles.p.generalization.FINANCIAL_VALUE.unit "
                            + "must be a quoted string");
        }
        assertThat(PrivacyProfiles.fromYaml(yaml(
                "profiles:\n  p:\n    generalization:\n      FINANCIAL_VALUE:\n        bounds: [0, 10]\n"
                        + "        unit: \"010\"\n")).get("p").generalizations()).hasSize(1);
    }

    @Test
    @DisplayName("a refusal names the key and never the value")
    void messageNeverRepeatsTheValue() {
        assertThatThrownBy(() -> PrivacyProfiles.fromYaml(yaml(RULE + "        overide: s3cr3t\n")))
                .hasMessageNotContaining("s3cr3t");
        assertThatThrownBy(() -> PrivacyProfiles.fromYaml(yaml(RULE + "        override: s3cr3t\n")))
                .hasMessageNotContaining("s3cr3t");
    }
}

package io.github.aindriub.dataprism.example.characterisation;

import io.github.aindriub.dataprism.core.policy.PrivacyProfile;
import io.github.aindriub.dataprism.core.policy.PrivacyProfiles;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What {@link PrivacyProfiles#fromYaml} does today (Jackson 3 YAML, YAML 1.2 reading rules; decision D-167-1 accepted
 * the change from Jackson 2 and YAML 1.1). The expected text is what the current code printed.
 */
class PrivacyProfilesYamlCharacterisationTest {

    private static String render(InputStream in) {
        Map<String, PrivacyProfile> profiles = PrivacyProfiles.fromYaml(in);
        StringBuilder out = new StringBuilder();
        new TreeMap<>(profiles).forEach((name, p) -> {
            out.append(name).append(": unclassified=").append(p.unclassified());
            new TreeMap<>(p.classifications()).forEach((c, r) ->
                    out.append(" ").append(c).append("->").append(r.action()).append("/override=").append(r.override()));
            new TreeMap<>(p.generalizations()).forEach((n, g) ->
                    out.append(" ").append(n).append("->").append(g.kind()).append(g.bounds())
                            .append("/unit=").append(g.unit()).append("/precision=").append(g.precision()));
        });
        return out.toString();
    }

    private static String outcome(String yaml) {
        return Observe.outcome(() -> render(Observe.yaml(yaml)));
    }

    private static String profile(String body) {
        return "profiles:\n  p:\n" + body;
    }

    @Test
    @DisplayName("duplicate key: refused, IllegalArgumentException \"DUPLICATE_CONFIG_KEY: ...\" (unclassified given twice)")
    void duplicateKey() {
        assertThat(outcome(profile("    unclassified: redact_and_warn\n    unclassified: drop_and_warn\n")))
                .isEqualTo("refused IllegalArgumentException \"DUPLICATE_CONFIG_KEY: privacy profiles has a duplicate key 'unclassifi\"");
    }

    @Test
    @DisplayName("override: yes, no, on, off, y, n, True and FALSE are refused, IllegalArgumentException \"INVALID_CONFIG_BOOLEAN: ...\"; true and false are accepted")
    void booleanSpellings() {
        assertThat(Observe.table(java.util.stream.Stream.concat(Observe.BOOLEAN_SPELLINGS.stream(),
                        java.util.stream.Stream.of("true", "false")).toList(),
                s -> profile("    classifications:\n      PII:\n        action: REDACT\n        override: " + s + "\n"),
                PrivacyProfilesYamlCharacterisationTest::render)).isEqualTo("yes => refused IllegalArgumentException \"INVALID_CONFIG_BOOLEAN: privacy profiles profiles.p.classifications.PI\"\n"
                + "no => refused IllegalArgumentException \"INVALID_CONFIG_BOOLEAN: privacy profiles profiles.p.classifications.PI\"\n"
                + "on => refused IllegalArgumentException \"INVALID_CONFIG_BOOLEAN: privacy profiles profiles.p.classifications.PI\"\n"
                + "off => refused IllegalArgumentException \"INVALID_CONFIG_BOOLEAN: privacy profiles profiles.p.classifications.PI\"\n"
                + "y => refused IllegalArgumentException \"INVALID_CONFIG_BOOLEAN: privacy profiles profiles.p.classifications.PI\"\n"
                + "n => refused IllegalArgumentException \"INVALID_CONFIG_BOOLEAN: privacy profiles profiles.p.classifications.PI\"\n"
                + "True => refused IllegalArgumentException \"INVALID_CONFIG_BOOLEAN: privacy profiles profiles.p.classifications.PI\"\n"
                + "FALSE => refused IllegalArgumentException \"INVALID_CONFIG_BOOLEAN: privacy profiles profiles.p.classifications.PI\"\n"
                + "true => ok p: unclassified=FAIL_REQUEST PII->REDACT/override=true\n"
                + "false => ok p: unclassified=FAIL_REQUEST PII->REDACT/override=false\n");
    }

    @Test
    @DisplayName("a band bound written with a leading zero, such as 010 or 0777, is refused, IllegalArgumentException \"LEADING_ZERO_CONFIG_NUMBER: ...\"; 0o10 is not a number")
    void octalLookingScalars() {
        assertThat(Observe.table(Observe.OCTAL_SPELLINGS,
                s -> profile("    generalization:\n      FINANCIAL_VALUE:\n        bounds: [0, " + s + "]\n"),
                PrivacyProfilesYamlCharacterisationTest::render)).isEqualTo("010 => refused IllegalArgumentException \"LEADING_ZERO_CONFIG_NUMBER: privacy profiles profiles.p.generalization\"\n"
                + "0o10 => refused IllegalArgumentException \"p.generalization.FINANCIAL_VALUE has a non-numeric bound '0o10'\" caused by NumberFormatException\n"
                + "0777 => refused IllegalArgumentException \"LEADING_ZERO_CONFIG_NUMBER: privacy profiles profiles.p.generalization\"\n");
    }

    @Test
    @DisplayName("a plain 0 and a decimal such as 0.5 are still accepted as band bounds")
    void plainZeroAndDecimalBounds() {
        assertThat(outcome(profile("    generalization:\n      FINANCIAL_VALUE:\n        bounds: [0, 0.5, 10]\n")))
                .isEqualTo("ok p: unclassified=FAIL_REQUEST FINANCIAL_VALUE->NUMERIC_BAND[0, 0.5, 10]/unit=null/precision=null");
    }

    @Test
    @DisplayName("a unit that looks like a leading-zero number is text here and is read as written; a number or boolean unit is refused, \"NON_STRING_CONFIG_SCALAR: ...\"")
    void unitIsAString() {
        assertThat(Observe.table(java.util.List.of("010", "0777", "\"010\"", "5", "true", "1.5"),
                s -> profile("    generalization:\n      FINANCIAL_VALUE:\n        bounds: [0, 10]\n        unit: " + s + "\n"),
                PrivacyProfilesYamlCharacterisationTest::render)).isEqualTo("010 => ok p: unclassified=FAIL_REQUEST FINANCIAL_VALUE->NUMERIC_BAND[0, 10]/unit=010/precision=null\n"
                + "0777 => ok p: unclassified=FAIL_REQUEST FINANCIAL_VALUE->NUMERIC_BAND[0, 10]/unit=0777/precision=null\n"
                + "\"010\" => ok p: unclassified=FAIL_REQUEST FINANCIAL_VALUE->NUMERIC_BAND[0, 10]/unit=010/precision=null\n"
                + "5 => refused IllegalArgumentException \"NON_STRING_CONFIG_SCALAR: privacy profiles profiles.p.generalization.F\"\n"
                + "true => refused IllegalArgumentException \"NON_STRING_CONFIG_SCALAR: privacy profiles profiles.p.generalization.F\"\n"
                + "1.5 => refused IllegalArgumentException \"NON_STRING_CONFIG_SCALAR: privacy profiles profiles.p.generalization.F\"\n");
    }

    @Test
    @DisplayName("unknown top-level key: refused, IllegalArgumentException \"UNKNOWN_CONFIG_KEY: ...\"")
    void unknownTopLevelKey() {
        assertThat(outcome("extra: 1\nprofiles:\n  p:\n    unclassified: FAIL_REQUEST\n")).isEqualTo("refused IllegalArgumentException \"UNKNOWN_CONFIG_KEY: privacy profiles has an unknown key 'extra'\"");
    }

    @Test
    @DisplayName("unknown nested key in a profile: refused, IllegalArgumentException \"UNKNOWN_CONFIG_KEY: ...\"")
    void unknownNestedKey() {
        assertThat(outcome(profile("    surprise: 1\n    classifications:\n      PII:\n        action: REDACT\n")))
                .isEqualTo("refused IllegalArgumentException \"UNKNOWN_CONFIG_KEY: privacy profiles profiles.p has an unknown key 'su\"");
    }

    @Test
    @DisplayName("unknown nested key in a rule: refused, IllegalArgumentException \"UNKNOWN_CONFIG_KEY: ...\"")
    void unknownNestedKeyInRule() {
        assertThat(outcome(profile("    classifications:\n      PII:\n        action: REDACT\n"
                + "        surprise: 2\n")))
                .isEqualTo("refused IllegalArgumentException \"UNKNOWN_CONFIG_KEY: privacy profiles profiles.p.classifications.PII ha\"");
    }

    @Test
    @DisplayName("enum spelling of unclassified: lower, mixed, leading-padded and trailing-padded all accepted (trimmed, upper-cased)")
    void enumSpelling() {
        assertThat(Observe.table(List.of("redact_and_warn", "Redact_And_Warn", "\"  REDACT_AND_WARN\"",
                        "\"REDACT_AND_WARN  \""),
                s -> profile("    unclassified: " + s + "\n"),
                PrivacyProfilesYamlCharacterisationTest::render)).isEqualTo("redact_and_warn => ok p: unclassified=REDACT_AND_WARN\n"
                + "Redact_And_Warn => ok p: unclassified=REDACT_AND_WARN\n"
                + "\"  REDACT_AND_WARN\" => ok p: unclassified=REDACT_AND_WARN\n"
                + "\"REDACT_AND_WARN  \" => ok p: unclassified=REDACT_AND_WARN\n");
    }

    @Test
    @DisplayName("enum spelling of a rule's action and classification key: lower, mixed and padded all accepted")
    void ruleEnumSpelling() {
        assertThat(Observe.table(List.of("redact", "Redact", "\"  REDACT \""),
                s -> profile("    classifications:\n      pii:\n        action: " + s + "\n"),
                PrivacyProfilesYamlCharacterisationTest::render)).isEqualTo("redact => ok p: unclassified=FAIL_REQUEST PII->REDACT/override=false\n"
                + "Redact => ok p: unclassified=FAIL_REQUEST PII->REDACT/override=false\n"
                + "\"  REDACT \" => ok p: unclassified=FAIL_REQUEST PII->REDACT/override=false\n");
    }
}

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
 * What {@link PrivacyProfiles#fromYaml} does today (Jackson 2 YAML, SnakeYAML, YAML 1.1 reading
 * rules). The expected text is what the current code printed.
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
    @DisplayName("duplicate key: last wins, silently (unclassified redact_and_warn then drop_and_warn gives DROP_AND_WARN)")
    void duplicateKey() {
        assertThat(outcome(profile("    unclassified: redact_and_warn\n    unclassified: drop_and_warn\n")))
                .isEqualTo("ok p: unclassified=DROP_AND_WARN");
    }

    @Test
    @DisplayName("as the override flag, yes, on and True read as true; no, off and FALSE read as false; y and n stay the text y and n (YAML 1.1 booleans, but only the long forms); override is true only for the ones that read as true")
    void booleanSpellings() {
        assertThat(Observe.table(Observe.BOOLEAN_SPELLINGS,
                s -> profile("    classifications:\n      PII:\n        action: REDACT\n        override: " + s + "\n"),
                PrivacyProfilesYamlCharacterisationTest::render)).isEqualTo("yes => ok p: unclassified=FAIL_REQUEST PII->REDACT/override=true\n"
                + "no => ok p: unclassified=FAIL_REQUEST PII->REDACT/override=false\n"
                + "on => ok p: unclassified=FAIL_REQUEST PII->REDACT/override=true\n"
                + "off => ok p: unclassified=FAIL_REQUEST PII->REDACT/override=false\n"
                + "y => ok p: unclassified=FAIL_REQUEST PII->REDACT/override=false\n"
                + "n => ok p: unclassified=FAIL_REQUEST PII->REDACT/override=false\n"
                + "True => ok p: unclassified=FAIL_REQUEST PII->REDACT/override=true\n"
                + "FALSE => ok p: unclassified=FAIL_REQUEST PII->REDACT/override=false\n");
    }

    @Test
    @DisplayName("as a band bound and the unit, 010 reads as decimal 8 and 0777 as decimal 511 (YAML 1.1 octal); 0o10 stays the text 0o10; a bound 0o10 is refused as non-numeric")
    void octalLookingScalars() {
        assertThat(Observe.table(Observe.OCTAL_SPELLINGS,
                s -> profile("    generalization:\n      FINANCIAL_VALUE:\n        bounds: [0, " + s + "]\n"
                        + "        unit: " + s + "\n"),
                PrivacyProfilesYamlCharacterisationTest::render)).isEqualTo("010 => ok p: unclassified=FAIL_REQUEST FINANCIAL_VALUE->NUMERIC_BAND[0, 8]/unit=8/precision=null\n"
                + "0o10 => refused IllegalArgumentException \"p.generalization.FINANCIAL_VALUE has a non-numeric bound '0o10'\" caused by NumberFormatException\n"
                + "0777 => ok p: unclassified=FAIL_REQUEST FINANCIAL_VALUE->NUMERIC_BAND[0, 511]/unit=511/precision=null\n");
    }

    @Test
    @DisplayName("unknown top-level key: ignored silently")
    void unknownTopLevelKey() {
        assertThat(outcome("extra: 1\nprofiles:\n  p:\n    unclassified: FAIL_REQUEST\n")).isEqualTo("ok p: unclassified=FAIL_REQUEST");
    }

    @Test
    @DisplayName("unknown nested key (in a profile and in a rule): ignored silently")
    void unknownNestedKey() {
        assertThat(outcome(profile("    surprise: 1\n    classifications:\n      PII:\n        action: REDACT\n"
                + "        surprise: 2\n"))).isEqualTo("ok p: unclassified=FAIL_REQUEST PII->REDACT/override=false");
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

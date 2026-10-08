package io.github.aindriub.dataprism.example.characterisation;

import io.github.aindriub.dataprism.core.descriptor.ModelDescriptor;
import io.github.aindriub.dataprism.core.descriptor.ModelDescriptors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What {@link ModelDescriptors#fromYaml} does today (Jackson 3 YAML, YAML 1.2 reading rules; decision D-167-1 accepted
 * the change from Jackson 2 and YAML 1.1). The expected text is what the current code printed.
 */
class ModelDescriptorsYamlCharacterisationTest {

    private static String render(InputStream in) {
        Map<String, ModelDescriptor> models = ModelDescriptors.fromYaml(in);
        StringBuilder out = new StringBuilder();
        new TreeMap<>(models).forEach((name, m) -> {
            out.append(name).append(": exposed=").append(m.exposed()).append(" descendable=").append(m.descendable())
                    .append(" undeclaredFields=").append(m.undeclaredFields());
            new TreeMap<>(m.fields()).forEach((f, d) -> out.append(" ").append(f).append("={classifications=")
                    .append(d.classifications()).append(" namespace=").append(d.namespace())
                    .append(" action=").append(d.action()).append(" subject=").append(d.subjectField())
                    .append(" nonSensitive=").append(d.nonSensitiveReason())
                    .append(" identifier=").append(d.identifierRole()).append("}"));
        });
        return out.toString();
    }

    private static String outcome(String yaml) {
        return Observe.outcome(() -> render(Observe.yaml(yaml)));
    }

    private static String model(String body) {
        return "models:\n  M:\n" + body;
    }

    @Test
    @DisplayName("duplicate key: last wins, silently (exposed true then false gives false)")
    void duplicateKey() {
        assertThat(outcome(model("    exposed: true\n    exposed: false\n"))).isEqualTo("ok M: exposed=false descendable=null undeclaredFields=null");
    }

    @Test
    @DisplayName("yes, no, on and off are read as text, not booleans (YAML 1.2); only true and false, in any case, are booleans")
    void booleanSpellings() {
        assertThat(Observe.table(Observe.BOOLEAN_SPELLINGS, s -> model("    exposed: " + s + "\n"),
                ModelDescriptorsYamlCharacterisationTest::render)).isEqualTo("yes => ok M: exposed=false descendable=null undeclaredFields=null\n"
                + "no => ok M: exposed=false descendable=null undeclaredFields=null\n"
                + "on => ok M: exposed=false descendable=null undeclaredFields=null\n"
                + "off => ok M: exposed=false descendable=null undeclaredFields=null\n"
                + "y => ok M: exposed=false descendable=null undeclaredFields=null\n"
                + "n => ok M: exposed=false descendable=null undeclaredFields=null\n"
                + "True => ok M: exposed=true descendable=null undeclaredFields=null\n"
                + "FALSE => ok M: exposed=false descendable=null undeclaredFields=null\n");
    }

    @Test
    @DisplayName("a leading-zero number such as 010 or 0777 is read as written, not as octal (YAML 1.2)")
    void octalLookingScalars() {
        assertThat(Observe.table(Observe.OCTAL_SPELLINGS,
                s -> model("    fields:\n      f:\n        nonSensitive: " + s + "\n        subject: " + s
                        + "\n        identifier: " + s + "\n"),
                ModelDescriptorsYamlCharacterisationTest::render)).isEqualTo("010 => ok M: exposed=null descendable=null undeclaredFields=null f={classifications=[] namespace=null action=null subject=010 nonSensitive=010 identifier=010}\n"
                + "0o10 => ok M: exposed=null descendable=null undeclaredFields=null f={classifications=[] namespace=null action=null subject=0o10 nonSensitive=0o10 identifier=0o10}\n"
                + "0777 => ok M: exposed=null descendable=null undeclaredFields=null f={classifications=[] namespace=null action=null subject=0777 nonSensitive=0777 identifier=0777}\n");
    }

    @Test
    @DisplayName("yes, no, on and off arrive as the text yes, no, on and off (YAML 1.2)")
    void booleanSpellingsAsText() {
        assertThat(Observe.table(Observe.BOOLEAN_SPELLINGS,
                s -> model("    fields:\n      f:\n        subject: " + s + "\n"),
                ModelDescriptorsYamlCharacterisationTest::render)).isEqualTo("yes => ok M: exposed=null descendable=null undeclaredFields=null f={classifications=[] namespace=null action=null subject=yes nonSensitive=null identifier=null}\n"
                + "no => ok M: exposed=null descendable=null undeclaredFields=null f={classifications=[] namespace=null action=null subject=no nonSensitive=null identifier=null}\n"
                + "on => ok M: exposed=null descendable=null undeclaredFields=null f={classifications=[] namespace=null action=null subject=on nonSensitive=null identifier=null}\n"
                + "off => ok M: exposed=null descendable=null undeclaredFields=null f={classifications=[] namespace=null action=null subject=off nonSensitive=null identifier=null}\n"
                + "y => ok M: exposed=null descendable=null undeclaredFields=null f={classifications=[] namespace=null action=null subject=y nonSensitive=null identifier=null}\n"
                + "n => ok M: exposed=null descendable=null undeclaredFields=null f={classifications=[] namespace=null action=null subject=n nonSensitive=null identifier=null}\n"
                + "True => ok M: exposed=null descendable=null undeclaredFields=null f={classifications=[] namespace=null action=null subject=True nonSensitive=null identifier=null}\n"
                + "FALSE => ok M: exposed=null descendable=null undeclaredFields=null f={classifications=[] namespace=null action=null subject=FALSE nonSensitive=null identifier=null}\n");
    }

    @Test
    @DisplayName("unknown top-level key: ignored silently")
    void unknownTopLevelKey() {
        assertThat(outcome("extra: 1\nmodels:\n  M:\n    exposed: true\n")).isEqualTo("ok M: exposed=true descendable=null undeclaredFields=null");
    }

    @Test
    @DisplayName("unknown nested key (in a model and in a field): ignored silently")
    void unknownNestedKey() {
        assertThat(outcome(model("    surprise: 1\n    fields:\n      f:\n        surprise: 2\n        subject: s\n")))
                .isEqualTo("ok M: exposed=null descendable=null undeclaredFields=null f={classifications=[] namespace=null action=null subject=s nonSensitive=null identifier=null}");
    }

    @Test
    @DisplayName("enum spelling of undeclaredFields: lower, mixed and padded all accepted (trimmed, upper-cased)")
    void enumSpelling() {
        assertThat(Observe.table(List.of("profile_default", "Profile_Default", "\"  PROFILE_DEFAULT\"",
                        "\"PROFILE_DEFAULT  \""),
                s -> model("    undeclaredFields: " + s + "\n"),
                ModelDescriptorsYamlCharacterisationTest::render)).isEqualTo("profile_default => ok M: exposed=null descendable=null undeclaredFields=PROFILE_DEFAULT\n"
                + "Profile_Default => ok M: exposed=null descendable=null undeclaredFields=PROFILE_DEFAULT\n"
                + "\"  PROFILE_DEFAULT\" => ok M: exposed=null descendable=null undeclaredFields=PROFILE_DEFAULT\n"
                + "\"PROFILE_DEFAULT  \" => ok M: exposed=null descendable=null undeclaredFields=PROFILE_DEFAULT\n");
    }

    @Test
    @DisplayName("enum spelling of a field's classifications, namespace and action: lower, mixed and padded all accepted")
    void fieldEnumSpelling() {
        assertThat(Observe.table(List.of("pii", "Pii", "\"  PII \""),
                s -> model("    fields:\n      f:\n        classifications: [" + s + "]\n"
                        + "        namespace: " + s.replace("pii", "email").replace("Pii", "Email").replace("PII", "EMAIL")
                        + "\n        action: " + s.replace("pii", "redact").replace("Pii", "Redact").replace("PII", "REDACT")
                        + "\n"),
                ModelDescriptorsYamlCharacterisationTest::render)).isEqualTo("pii => ok M: exposed=null descendable=null undeclaredFields=null f={classifications=[PII] namespace=EMAIL action=REDACT subject=null nonSensitive=null identifier=null}\n"
                + "Pii => ok M: exposed=null descendable=null undeclaredFields=null f={classifications=[PII] namespace=EMAIL action=REDACT subject=null nonSensitive=null identifier=null}\n"
                + "\"  PII \" => ok M: exposed=null descendable=null undeclaredFields=null f={classifications=[PII] namespace=EMAIL action=REDACT subject=null nonSensitive=null identifier=null}\n");
    }
}

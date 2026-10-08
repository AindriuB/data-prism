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
    @DisplayName("duplicate key: refused, IllegalArgumentException \"DUPLICATE_CONFIG_KEY: ...\" (exposed given twice)")
    void duplicateKey() {
        assertThat(outcome(model("    exposed: true\n    exposed: false\n"))).isEqualTo("refused IllegalArgumentException \"DUPLICATE_CONFIG_KEY: model descriptors has a duplicate key 'exposed' \"");
    }

    @Test
    @DisplayName("exposed: yes, no, on, off, y, n, True and FALSE are refused, IllegalArgumentException \"INVALID_CONFIG_BOOLEAN: ...\"; true and false are accepted")
    void booleanSpellings() {
        assertThat(Observe.table(java.util.stream.Stream.concat(Observe.BOOLEAN_SPELLINGS.stream(),
                        java.util.stream.Stream.of("true", "false")).toList(),
                s -> model("    exposed: " + s + "\n"),
                ModelDescriptorsYamlCharacterisationTest::render)).isEqualTo("yes => refused IllegalArgumentException \"INVALID_CONFIG_BOOLEAN: model descriptors models.M.exposed must be exa\"\n"
                + "no => refused IllegalArgumentException \"INVALID_CONFIG_BOOLEAN: model descriptors models.M.exposed must be exa\"\n"
                + "on => refused IllegalArgumentException \"INVALID_CONFIG_BOOLEAN: model descriptors models.M.exposed must be exa\"\n"
                + "off => refused IllegalArgumentException \"INVALID_CONFIG_BOOLEAN: model descriptors models.M.exposed must be exa\"\n"
                + "y => refused IllegalArgumentException \"INVALID_CONFIG_BOOLEAN: model descriptors models.M.exposed must be exa\"\n"
                + "n => refused IllegalArgumentException \"INVALID_CONFIG_BOOLEAN: model descriptors models.M.exposed must be exa\"\n"
                + "True => refused IllegalArgumentException \"INVALID_CONFIG_BOOLEAN: model descriptors models.M.exposed must be exa\"\n"
                + "FALSE => refused IllegalArgumentException \"INVALID_CONFIG_BOOLEAN: model descriptors models.M.exposed must be exa\"\n"
                + "true => ok M: exposed=true descendable=null undeclaredFields=null\n"
                + "false => ok M: exposed=false descendable=null undeclaredFields=null\n");
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
    @DisplayName("a number, boolean or decimal where a string is expected (subject) is refused, IllegalArgumentException \"NON_STRING_CONFIG_SCALAR: ...\"; the quoted form is accepted")
    void nonStringScalarsInAStringField() {
        assertThat(Observe.table(java.util.List.of("1", "true", "1.5", "\"1\"", "\"true\""),
                s -> model("    fields:\n      f:\n        subject: " + s + "\n"),
                ModelDescriptorsYamlCharacterisationTest::render)).isEqualTo("1 => refused IllegalArgumentException \"NON_STRING_CONFIG_SCALAR: model descriptors models.M.fields.f.subject \"\n"
                + "true => refused IllegalArgumentException \"NON_STRING_CONFIG_SCALAR: model descriptors models.M.fields.f.subject \"\n"
                + "1.5 => refused IllegalArgumentException \"NON_STRING_CONFIG_SCALAR: model descriptors models.M.fields.f.subject \"\n"
                + "\"1\" => ok M: exposed=null descendable=null undeclaredFields=null f={classifications=[] namespace=null action=null subject=1 nonSensitive=null identifier=null}\n"
                + "\"true\" => ok M: exposed=null descendable=null undeclaredFields=null f={classifications=[] namespace=null action=null subject=true nonSensitive=null identifier=null}\n");
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
    @DisplayName("unknown top-level key: refused, IllegalArgumentException \"UNKNOWN_CONFIG_KEY: ...\"")
    void unknownTopLevelKey() {
        assertThat(outcome("extra: 1\nmodels:\n  M:\n    exposed: true\n")).isEqualTo("refused IllegalArgumentException \"UNKNOWN_CONFIG_KEY: model descriptors has an unknown key 'extra'\"");
    }

    @Test
    @DisplayName("unknown nested key in a model: refused, IllegalArgumentException \"UNKNOWN_CONFIG_KEY: ...\"")
    void unknownNestedKey() {
        assertThat(outcome(model("    surprise: 1\n    fields:\n      f:\n        subject: s\n")))
                .isEqualTo("refused IllegalArgumentException \"UNKNOWN_CONFIG_KEY: model descriptors models.M has an unknown key 'sur\"");
    }

    @Test
    @DisplayName("unknown nested key in a field: refused, IllegalArgumentException \"UNKNOWN_CONFIG_KEY: ...\"")
    void unknownNestedKeyInField() {
        assertThat(outcome(model("    fields:\n      f:\n        surprise: 2\n        subject: s\n")))
                .isEqualTo("refused IllegalArgumentException \"UNKNOWN_CONFIG_KEY: model descriptors models.M.fields.f has an unknown\"");
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

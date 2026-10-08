package io.github.aindriub.dataprism.core.descriptor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link ModelDescriptors#fromYaml} refuses, at load time and with a stable code, a duplicate key, an unknown key,
 * trailing content, and a scalar of the wrong YAML type. A message names the key and its path and never a value.
 */
class ModelDescriptorsStrictKeysTest {

    private static Map<String, ModelDescriptor> load(String yaml) {
        return ModelDescriptors.fromYaml(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));
    }

    private static void assertRefused(String yaml, String prefix) {
        assertThatThrownBy(() -> load(yaml))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith(prefix);
    }

    private static final String MODEL = "models:\n  M:\n    exposed: true\n";

    @Test
    @DisplayName("a duplicate top-level key is refused with DUPLICATE_CONFIG_KEY")
    void duplicateTopLevelKey() {
        assertRefused(MODEL + "models:\n  N:\n    exposed: true\n",
                "DUPLICATE_CONFIG_KEY: model descriptors has a duplicate key 'models'");
    }

    @Test
    @DisplayName("a duplicate key in a model is refused with DUPLICATE_CONFIG_KEY, naming the path")
    void duplicateKeyInModel() {
        assertRefused("models:\n  M:\n    exposed: true\n    exposed: false\n",
                "DUPLICATE_CONFIG_KEY: model descriptors has a duplicate key 'exposed' in models.M");
    }

    @Test
    @DisplayName("a duplicate key in a field is refused with DUPLICATE_CONFIG_KEY, naming the path")
    void duplicateKeyInField() {
        assertRefused("models:\n  M:\n    fields:\n      f:\n        action: REDACT\n        action: REMOVE\n",
                "DUPLICATE_CONFIG_KEY: model descriptors has a duplicate key 'action' in models.M.fields.f");
    }

    @Test
    @DisplayName("a duplicate model name is refused with DUPLICATE_CONFIG_KEY")
    void duplicateModelName() {
        assertRefused("models:\n  M:\n    exposed: true\n  M:\n    exposed: false\n",
                "DUPLICATE_CONFIG_KEY: model descriptors has a duplicate key 'M' in models");
    }

    @Test
    @DisplayName("an unknown top-level key is refused with UNKNOWN_CONFIG_KEY")
    void unknownTopLevelKey() {
        assertRefused("extra: 1\n" + MODEL,
                "UNKNOWN_CONFIG_KEY: model descriptors has an unknown key 'extra'");
    }

    @Test
    @DisplayName("an unknown key in a model is refused with UNKNOWN_CONFIG_KEY, naming the path")
    void unknownKeyInModel() {
        assertRefused("models:\n  M:\n    surprise: 1\n",
                "UNKNOWN_CONFIG_KEY: model descriptors models.M has an unknown key 'surprise'");
    }

    @Test
    @DisplayName("an unknown key in a field is refused with UNKNOWN_CONFIG_KEY, naming the path")
    void unknownKeyInField() {
        assertRefused("models:\n  M:\n    fields:\n      f:\n        surprise: 2\n        subject: s\n",
                "UNKNOWN_CONFIG_KEY: model descriptors models.M.fields.f has an unknown key 'surprise'");
    }

    @Test
    @DisplayName("a misspelled undeclaredFields is refused rather than ignored")
    void misspelledUndeclaredFields() {
        assertRefused("models:\n  M:\n    undeclaredField: PROFILE_DEFAULT\n",
                "UNKNOWN_CONFIG_KEY: model descriptors models.M has an unknown key 'undeclaredField'");
    }

    @Test
    @DisplayName("an unknown key longer than 64 characters is named cut to 64")
    void longKeyIsTruncated() {
        String key = "k".repeat(100);
        assertThatThrownBy(() -> load("models:\n  M:\n    " + key + ": 1\n"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'" + "k".repeat(64) + "'")
                .hasMessageNotContaining("k".repeat(65));
    }

    @Test
    @DisplayName("a refusal names the key and never its value")
    void messageNeverRepeatsTheValue() {
        assertThatThrownBy(() -> load("models:\n  M:\n    surprise: s3cr3t-value\n"))
                .hasMessageNotContaining("s3cr3t-value");
        assertThatThrownBy(() -> load("models:\n  M:\n    exposed: s3cr3t-value\n"))
                .hasMessageNotContaining("s3cr3t-value");
        assertThatThrownBy(() -> load("models:\n  M:\n    fields:\n      f:\n        subject: 12345678\n"))
                .hasMessageNotContaining("12345678");
    }

    @Test
    @DisplayName("the same field name in two different models still loads")
    void sameFieldNameInTwoModelsLoads() {
        Map<String, ModelDescriptor> models = load("models:\n"
                + "  A:\n    fields:\n      name:\n        subject: s\n"
                + "  B:\n    fields:\n      name:\n        subject: s\n");
        assertThat(models).containsOnlyKeys("A", "B");
        assertThat(models.get("A").fields()).containsKey("name");
        assertThat(models.get("B").fields()).containsKey("name");
    }

    @Test
    @DisplayName("a second YAML document is refused with TRAILING_CONFIG_CONTENT")
    void secondDocument() {
        assertRefused(MODEL + "---\nmodels:\n  N:\n    exposed: true\n", "TRAILING_CONFIG_CONTENT: ");
    }

    @Test
    @DisplayName("a bare trailing document marker is refused; a document-end marker is accepted")
    void trailingDocumentMarkers() {
        assertRefused(MODEL + "---\n", "TRAILING_CONFIG_CONTENT: ");
        assertThat(load(MODEL + "...\n")).containsKey("M");
    }

    @Test
    @DisplayName("exposed accepts true and false")
    void booleanTrueAndFalseAccepted() {
        assertThat(load("models:\n  M:\n    exposed: false\n    descendable: true\n").get("M").exposed())
                .isFalse();
    }

    @Test
    @DisplayName("exposed: yes, on, True and 1 are refused with INVALID_CONFIG_BOOLEAN")
    void booleanLookAlikesRefused() {
        for (String spelling : new String[] {"yes", "no", "on", "off", "True", "FALSE", "1", "0"}) {
            assertRefused("models:\n  M:\n    exposed: " + spelling + "\n",
                    "INVALID_CONFIG_BOOLEAN: model descriptors models.M.exposed must be exactly true or false");
        }
        assertRefused("models:\n  M:\n    descendable: yes\n",
                "INVALID_CONFIG_BOOLEAN: model descriptors models.M.descendable ");
    }

    @Test
    @DisplayName("a string field written as a number, boolean or empty value is refused with NON_STRING_CONFIG_SCALAR")
    void nonStringScalarRefused() {
        for (String key : new String[] {"subject", "nonSensitive", "identifier"}) {
            for (String value : new String[] {"1", "true", "1.5", "", "null"}) {
                assertRefused("models:\n  M:\n    fields:\n      f:\n        " + key + ": " + value + "\n",
                        "NON_STRING_CONFIG_SCALAR: model descriptors models.M.fields.f." + key
                                + " must be a quoted string");
            }
        }
    }

    @Test
    @DisplayName("an enum field written as a boolean or number is refused with NON_STRING_CONFIG_SCALAR")
    void nonStringEnumRefused() {
        assertRefused("models:\n  M:\n    undeclaredFields: true\n", "NON_STRING_CONFIG_SCALAR: ");
        assertRefused("models:\n  M:\n    fields:\n      f:\n        action: 1\n", "NON_STRING_CONFIG_SCALAR: ");
        assertRefused("models:\n  M:\n    fields:\n      f:\n        action:\n", "NON_STRING_CONFIG_SCALAR: ");
    }

    @Test
    @DisplayName("a quoted number is a string and is accepted")
    void quotedNumberAccepted() {
        ModelDescriptor.FieldDescriptor f = load(
                "models:\n  M:\n    fields:\n      f:\n        subject: \"010\"\n        identifier: \"1\"\n")
                .get("M").fields().get("f");
        assertThat(f.subjectField()).isEqualTo("010");
        assertThat(f.identifierRole()).isEqualTo("1");
    }

    @Test
    @DisplayName("a file that is not a mapping is still 'could not be read', not a strictness code")
    void rootNotAMappingStaysUnreadable() {
        assertThatThrownBy(() -> load("- 1\n"))
                .isInstanceOf(UncheckedIOException.class)
                .hasMessage("model descriptors could not be read");
    }

    @Test
    @DisplayName("fields that are not a mapping are refused with INVALID_CONFIG_SHAPE")
    void wrongShapedFields() {
        for (String shape : new String[] {"[f]", "x", ""}) {
            assertRefused("models:\n  M:\n    fields: " + shape + "\n",
                    "INVALID_CONFIG_SHAPE: model descriptors models.M.fields must be a mapping");
        }
    }

    @Test
    @DisplayName("an empty classifications value is refused rather than loading a field with no classifications")
    void emptyClassificationsRefused() {
        assertRefused("models:\n  M:\n    fields:\n      f:\n        classifications:\n",
                "NON_STRING_CONFIG_SCALAR: model descriptors models.M.fields.f.classifications must be a quoted string");
    }

    @Test
    @DisplayName("null-like nonSensitive text is refused rather than becoming the reason: null as a non-string, ~, Null and NULL as NULL_LIKE_CONFIG_SCALAR")
    void nullLikeNonSensitive() {
        for (String value : new String[] {"null"}) {
            assertRefused("models:\n  M:\n    fields:\n      f:\n        nonSensitive: " + value + "\n",
                    "NON_STRING_CONFIG_SCALAR: model descriptors models.M.fields.f.nonSensitive ");
        }
        for (String value : new String[] {"~", "Null", "NULL"}) {
            assertRefused("models:\n  M:\n    fields:\n      f:\n        nonSensitive: " + value + "\n",
                    "NULL_LIKE_CONFIG_SCALAR: model descriptors models.M.fields.f.nonSensitive ");
        }
    }

    @Test
    @DisplayName("an alias, anchor or tag is refused with UNSUPPORTED_CONFIG_YAML")
    void unsupportedYaml() {
        assertRefused("models:\n  M:\n    fields: &f\n      a:\n        subject: s\n  N:\n    fields: *f\n",
                "UNSUPPORTED_CONFIG_YAML: ");
        assertRefused("models:\n  M:\n    exposed: !!int 010\n", "UNSUPPORTED_CONFIG_YAML: ");
    }
}

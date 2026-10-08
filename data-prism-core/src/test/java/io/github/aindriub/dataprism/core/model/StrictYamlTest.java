package io.github.aindriub.dataprism.core.model;

import io.github.aindriub.dataprism.annotations.DataClassification;
import io.github.aindriub.dataprism.annotations.PrivacyAction;
import io.github.aindriub.dataprism.annotations.PrivacyNamespace;
import io.github.aindriub.dataprism.core.policy.GeneralizationRule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

    private static Map<String, Object> read(String yaml) {
        return StrictYaml.readMapping(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)), "test doc");
    }

    @Test
    @DisplayName("a document reads as nested maps, lists and typed scalars in file order")
    void readsATypedTree() {
        Map<String, Object> root = read("a: 1\nb: [x, true, 2.5]\nc:\n  d: ~\n  e:\n");

        assertThat(root).containsExactly(
                Map.entry("a", 1), Map.entry("b", List.of("x", true, 2.5)),
                Map.entry("c", root.get("c")));
        @SuppressWarnings("unchecked")
        Map<String, Object> c = (Map<String, Object>) root.get("c");
        assertThat(c).containsEntry("d", "~").containsEntry("e", null);
    }

    @Test
    @DisplayName("an empty document reads as null")
    void emptyDocumentIsNull() {
        assertThat(read("")).isNull();
        assertThat(read("# nothing\n")).isNull();
    }

    @Test
    @DisplayName("a duplicate key is DUPLICATE_CONFIG_KEY at the top level, in a nested map, in a list item and in a flow map")
    void duplicateKeyAtAnyDepth() {
        assertThatThrownBy(() -> read("a: 1\na: 2\n")).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("DUPLICATE_CONFIG_KEY: test doc has a duplicate key 'a'");
        assertThatThrownBy(() -> read("x:\n  y:\n    k: 1\n    k: 2\n")).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("DUPLICATE_CONFIG_KEY: test doc has a duplicate key 'k' in x.y");
        assertThatThrownBy(() -> read("x:\n  - k: 1\n    k: 2\n")).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("DUPLICATE_CONFIG_KEY: test doc has a duplicate key 'k' in x[0]");
        assertThatThrownBy(() -> read("x: {k: 1, k: 2}\n")).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("DUPLICATE_CONFIG_KEY: test doc has a duplicate key 'k' in x");
    }

    @Test
    @DisplayName("the same key in two different maps is not a duplicate")
    void sameKeyInDifferentMapsIsFine() {
        assertThat(read("a:\n  k: 1\nb:\n  k: 2\n")).containsKeys("a", "b");
    }

    @Test
    @DisplayName("a second document, or a bare trailing --- , is TRAILING_CONFIG_CONTENT; a ... end marker is not")
    void trailingContent() {
        assertThatThrownBy(() -> read("a: 1\n---\nb: 2\n")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("TRAILING_CONFIG_CONTENT: test doc ");
        assertThatThrownBy(() -> read("a: 1\n---\n")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("TRAILING_CONFIG_CONTENT: test doc ");
        assertThatThrownBy(() -> read("a: 1\n---\n: : :\n")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("TRAILING_CONFIG_CONTENT: test doc ");
        assertThat(read("a: 1\n...\n")).containsKey("a");
    }

    @Test
    @DisplayName("text that is not YAML, or whose root is not a mapping, is 'could not be read'")
    void unreadableDocument() {
        assertThatThrownBy(() -> read("- 1\n")).isInstanceOf(UncheckedIOException.class)
                .hasMessage("test doc could not be read");
        assertThatThrownBy(() -> read("a: [1\n")).isInstanceOf(UncheckedIOException.class)
                .hasMessage("test doc could not be read");
    }

    @Test
    @DisplayName("an unknown key is UNKNOWN_CONFIG_KEY, with the key cut to 64 characters and control characters replaced")
    void unknownKeyIsNamedSafely() {
        assertThatThrownBy(() -> StrictYaml.requireOnlyKeys(Set.of("ok", "x"), Set.of("ok"), "thing"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("UNKNOWN_CONFIG_KEY: thing has an unknown key 'x'");
        assertThatThrownBy(() -> StrictYaml.requireOnlyKeys(Set.of("a".repeat(80)), Set.of(), "thing"))
                .hasMessage("UNKNOWN_CONFIG_KEY: thing has an unknown key '" + "a".repeat(64) + "'");
        assertThatThrownBy(() -> StrictYaml.requireOnlyKeys(Set.of("a\nb"), Set.of(), "thing"))
                .hasMessage("UNKNOWN_CONFIG_KEY: thing has an unknown key 'a?b'");
        StrictYaml.requireOnlyKeys(Set.of("ok"), Set.of("ok", "also"), "thing");
    }

    @Test
    @DisplayName("a string field refuses a boolean, a number and an empty value without echoing it")
    void textRefusesNonStrings() {
        for (Object raw : new Object[] {true, 12345, 1.5, null}) {
            assertThatThrownBy(() -> StrictYaml.text(raw, "doc a.b"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("NON_STRING_CONFIG_SCALAR: doc a.b must be a quoted string");
        }
        assertThat(StrictYaml.text("010", "doc a.b")).isEqualTo("010");
    }

    @Test
    @DisplayName("a boolean field accepts only true and false")
    void booleanOnlyTrueOrFalse() {
        assertThat(StrictYaml.optionalBoolean(Map.of("k", true), "k", "doc")).isTrue();
        assertThat(StrictYaml.optionalBoolean(Map.of("k", "false"), "k", "doc")).isFalse();
        assertThat(StrictYaml.optionalBoolean(Map.of(), "k", "doc")).isNull();
        for (Object raw : new Object[] {"yes", "no", "on", "off", "True", "FALSE", 1, 0, ""}) {
            assertThatThrownBy(() -> StrictYaml.optionalBoolean(Map.of("k", raw), "k", "doc"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("INVALID_CONFIG_BOOLEAN: doc.k must be exactly true or false");
        }
        assertThatThrownBy(() -> StrictYaml.optionalBoolean(java.util.Collections.singletonMap("k", null), "k", "doc"))
                .hasMessageStartingWith("INVALID_CONFIG_BOOLEAN: ");
    }

    @Test
    @DisplayName("a number written with a leading zero is LEADING_ZERO_CONFIG_NUMBER; 0, 0.5 and -0.5 are fine")
    void leadingZeroNumbers() {
        for (String raw : new String[] {"010", "0777", "00", "-01", "+07", "00.5"}) {
            assertThatThrownBy(() -> StrictYaml.decimal(raw, "doc bounds[1]"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("LEADING_ZERO_CONFIG_NUMBER: doc bounds[1] must not be written with a leading zero");
            assertThatThrownBy(() -> StrictYaml.integer(raw, "doc version"))
                    .hasMessageStartingWith("LEADING_ZERO_CONFIG_NUMBER: ");
        }
        assertThat(StrictYaml.decimal(0, "doc")).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(StrictYaml.decimal(0.5, "doc")).isEqualByComparingTo("0.5");
        assertThat(StrictYaml.decimal("-0.5", "doc")).isEqualByComparingTo("-0.5");
        assertThat(StrictYaml.integer(7, "doc")).isEqualTo(7);
        assertThat(StrictYaml.integer("7", "doc")).isEqualTo(7);
    }

    @Test
    @DisplayName("an enum written as a boolean or number is NON_STRING_CONFIG_SCALAR; an explicit empty value is too")
    void enumRefusesNonStrings() {
        assertThatThrownBy(() -> StrictYaml.enumValue(PrivacyAction.class, true, "x.y"))
                .hasMessage("NON_STRING_CONFIG_SCALAR: x.y must be a quoted string");
        assertThatThrownBy(() -> StrictYaml.optionalEnum(PrivacyAction.class,
                java.util.Collections.singletonMap("action", null), "action", "x.y.action"))
                .hasMessage("NON_STRING_CONFIG_SCALAR: x.y.action must be a quoted string");
        assertThat(StrictYaml.optionalEnum(PrivacyAction.class, Map.of(), "action", "x.y.action")).isNull();
    }
}

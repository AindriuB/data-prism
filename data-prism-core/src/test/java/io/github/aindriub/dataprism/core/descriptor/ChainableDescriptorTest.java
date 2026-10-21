package io.github.aindriub.dataprism.core.descriptor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The {@code chainable} and {@code keyNamespace} descriptor keys (task 240). */
class ChainableDescriptorTest {

    private static Map<String, ModelDescriptor> load(String yaml) {
        return ModelDescriptors.fromYaml(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    @DisplayName("both keys are read onto the field descriptor")
    void readsBothKeys() {
        var f = load("models:\n  M:\n    fields:\n      f:\n        identifier: self\n"
                + "        chainable: true\n        keyNamespace: parcel-ref\n").get("M").field("f");

        assertThat(f.chainable()).isTrue();
        assertThat(f.keyNamespace()).isEqualTo("parcel-ref");
    }

    @Test
    @DisplayName("absent keys read as not stated")
    void absentIsNull() {
        var f = load("models:\n  M:\n    fields:\n      f:\n        identifier: self\n").get("M").field("f");

        assertThat(f.chainable()).isNull();
        assertThat(f.keyNamespace()).isNull();
    }

    @Test
    @DisplayName("a non-boolean chainable value is refused at load")
    void refusesNonBoolean() {
        assertThatThrownBy(() -> load("models:\n  M:\n    fields:\n      f:\n        chainable: maybe\n"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("an unknown sibling key is still refused")
    void stillStrict() {
        assertThatThrownBy(() -> load("models:\n  M:\n    fields:\n      f:\n        chainabel: true\n"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

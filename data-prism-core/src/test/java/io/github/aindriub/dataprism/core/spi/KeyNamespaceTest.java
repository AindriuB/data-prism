package io.github.aindriub.dataprism.core.spi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class KeyNamespaceTest {

    @Test
    @DisplayName("32 characters conform, 33 do not")
    void length() {
        assertThat(new KeyNamespace("a".repeat(32)).value()).hasSize(32);
        assertThatThrownBy(() -> new KeyNamespace("a".repeat(33))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("grammar is exact: no upper case, leading digit or hyphen, underscore, blank or padding")
    void grammar() {
        for (String bad : new String[] {"", "A", "Ab", "1a", "-a", "a_b", " a", "a ", "a b", "a.b"}) {
            assertThatThrownBy(() -> new KeyNamespace(bad)).as(bad).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(new KeyNamespace("a").value()).isEqualTo("a");
        assertThat(new KeyNamespace("ref-2").value()).isEqualTo("ref-2");
    }

    @Test
    @DisplayName("a null value is rejected, equality is exact, toString is the value")
    void equalsAndToString() {
        assertThatThrownBy(() -> new KeyNamespace(null)).isInstanceOf(NullPointerException.class);
        assertThat(new KeyNamespace("ref")).isEqualTo(new KeyNamespace("ref")).isNotEqualTo(new KeyNamespace("ref2"));
        assertThat(new KeyNamespace("ref")).hasToString("ref");
    }

    @Test
    @DisplayName("the refusal does not echo the value")
    void messageHasNoValue() {
        assertThatThrownBy(() -> new KeyNamespace("SENTINEL"))
                .hasMessageNotContaining("SENTINEL");
    }
}

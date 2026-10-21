package io.github.aindriub.dataprism.core.spi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SourceDescriptionTest {

    private static SourceDescription withTypes(Set<String> types) {
        return new SourceDescription(types, null, null, List.of(), null, null);
    }

    private static SourceDescription withKeys(List<KeyNamespace> keys) {
        return new SourceDescription(Set.of(), null, null, keys, null, null);
    }

    @Test
    @DisplayName("undeclared declares nothing")
    void undeclared() {
        SourceDescription d = SourceDescription.undeclared();
        assertThat(d.entityTypes()).isEmpty();
        assertThat(d.inputKeys()).isEmpty();
        assertThat(d.aliasDescription()).isNull();
        assertThat(d.realDescription()).isNull();
        assertThat(d.cost()).isNull();
        assertThat(d.freshness()).isNull();
    }

    @Test
    @DisplayName("an adapter that does not override description() is undeclared")
    void defaultMethod() {
        DataSourceAdapter<String> adapter = new DataSourceAdapter<>() {
            public String sourceName() { return "x"; }
            public Class<String> responseType() { return String.class; }
            public String fetch(DataRequest request) { return ""; }
        };
        assertThat(adapter.description()).isEqualTo(SourceDescription.undeclared());
    }

    @Test
    @DisplayName("the constructor copies its collections")
    void copies() {
        Set<String> types = new HashSet<>(Set.of("A"));
        List<KeyNamespace> keys = new ArrayList<>(List.of(new KeyNamespace("k")));
        SourceDescription d = new SourceDescription(types, null, null, keys, null, null);
        types.add("B");
        keys.add(new KeyNamespace("j"));
        assertThat(d.entityTypes()).containsExactly("A");
        assertThat(d.inputKeys()).containsExactly(new KeyNamespace("k"));
        assertThatThrownBy(() -> d.entityTypes().add("C")).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> d.inputKeys().add(new KeyNamespace("z"))).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("entity type grammar: 64 characters pass, 65, a leading digit, a space and an empty type do not")
    void entityTypeGrammar() {
        assertThat(withTypes(Set.of("A" + "b".repeat(63))).entityTypes()).hasSize(1);
        assertThat(withTypes(Set.of("A_b-1")).entityTypes()).hasSize(1);
        for (String bad : new String[] {"A" + "b".repeat(64), "1A", "A b", "", "_a"}) {
            assertThatThrownBy(() -> withTypes(Set.of(bad))).as(bad).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    @DisplayName("8 input keys pass, 9 do not, and a duplicate does not")
    void inputKeys() {
        List<KeyNamespace> eight = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            eight.add(new KeyNamespace("k" + i));
        }
        assertThat(withKeys(eight).inputKeys()).hasSize(8);
        eight.add(new KeyNamespace("k8"));
        assertThatThrownBy(() -> withKeys(eight)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> withKeys(List.of(new KeyNamespace("k"), new KeyNamespace("k"))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("refusals carry no description text or declared value")
    void messagesHaveNoText() {
        assertThatThrownBy(() -> new SourceDescription(Set.of("SENTINEL value"), "SENTINEL text", null, List.of(), null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("SENTINEL");
    }
}

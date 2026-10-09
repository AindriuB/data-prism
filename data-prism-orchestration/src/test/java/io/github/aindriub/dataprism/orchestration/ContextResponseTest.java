package io.github.aindriub.dataprism.orchestration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.JsonNodeFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ContextResponseTest {

    private static ContextResponse responseWith(Map<String, String> sources) {
        return new ContextResponse("CUSTOMER", "subject", sources, List.of(),
                JsonNodeFactory.instance.objectNode());
    }

    @Test
    @DisplayName("sources iterate in ascending key order whatever order they were supplied in, and answered() follows")
    void sourcesAreSortedByKey() {
        List<String> ascending = List.of("a1", "b2", "c3", "d4", "e5", "f6", "g7", "h8", "i9");
        Map<String, String> reversed = new LinkedHashMap<>();
        for (int i = ascending.size() - 1; i >= 0; i--) {
            reversed.put(ascending.get(i), "ANSWERED");
        }

        ContextResponse response = responseWith(reversed);

        assertThat(new ArrayList<>(response.sources().keySet())).containsExactlyElementsOf(ascending);
        assertThat(response.answered()).containsExactlyElementsOf(ascending);
    }

    @Test
    @DisplayName("sources is unmodifiable and refuses a null key or value")
    void sourcesAreUnmodifiableAndNullHostile() {
        ContextResponse response = responseWith(Map.of("a", "ANSWERED"));
        assertThatThrownBy(() -> response.sources().put("b", "ANSWERED"))
                .isInstanceOf(UnsupportedOperationException.class);

        Map<String, String> nullKey = new LinkedHashMap<>();
        nullKey.put(null, "ANSWERED");
        assertThatThrownBy(() -> responseWith(nullKey)).isInstanceOf(NullPointerException.class);
        Map<String, String> nullValue = new LinkedHashMap<>();
        nullValue.put("a", null);
        assertThatThrownBy(() -> responseWith(nullValue)).isInstanceOf(NullPointerException.class);
    }
}

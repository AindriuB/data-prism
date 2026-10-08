package io.github.aindriub.dataprism.core.engine;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.exc.InvalidDefinitionException;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Jackson 3 changed three defaults that touch a source tree; each is pinned back to the Jackson 2 value. */
class SourceTreeJacksonParityTest {

    public record Money(BigDecimal amount) {
    }

    public record Pair(String zeta, String alpha) {
    }

    public static final class Empty {
    }

    @Test
    void trailingZerosOfADecimalAreStripped() {
        JsonNode tree = SourceTree.of(new Money(new BigDecimal("1.50")));
        assertThat(tree.get("amount").decimalValue()).isEqualByComparingTo("1.5");
        assertThat(tree.get("amount").decimalValue().toPlainString()).isEqualTo("1.5");
    }

    @Test
    void propertiesKeepDeclaredOrder() {
        assertThat(SourceTree.of(new Pair("z", "a")).propertyNames()).containsExactly("zeta", "alpha");
    }

    @Test
    void anEmptyBeanIsRefusedNotReadAsAnEmptyObject() {
        assertThatThrownBy(() -> SourceTree.of(new Empty())).isInstanceOf(InvalidDefinitionException.class);
    }
}

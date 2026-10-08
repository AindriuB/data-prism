package io.github.aindriub.dataprism.mcp;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Jackson 3 changed defaults that alter what a model sees; the output mapper pins the Jackson 2 values. */
class DataPrismObjectMapperDefaultsTest {

    public enum Kind {
        FIRST {
            @Override
            public String toString() {
                return "first-display";
            }
        }
    }

    public record Shape(String zeta, String alpha, Kind kind) {
    }

    private final ObjectMapper mapper = DataPrismObjectMapper.create();

    @Test
    void anEnumIsWrittenByNameNotByToString() {
        assertThat(mapper.writeValueAsString(Kind.FIRST)).isEqualTo("\"FIRST\"");
        assertThat(mapper.writeValueAsString(Map.of(Kind.FIRST, 1))).isEqualTo("{\"FIRST\":1}");
    }

    @Test
    void propertiesKeepDeclaredOrder() {
        assertThat(mapper.writeValueAsString(new Shape("z", "a", Kind.FIRST)))
                .isEqualTo("{\"zeta\":\"z\",\"alpha\":\"a\",\"kind\":\"FIRST\"}");
    }
}

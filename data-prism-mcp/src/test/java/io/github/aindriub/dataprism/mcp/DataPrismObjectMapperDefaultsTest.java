package io.github.aindriub.dataprism.mcp;

import io.github.aindriub.dataprism.core.engine.SourceTree;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.exc.InvalidDefinitionException;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    public static final class Empty {
    }

    /**
     * The source reader and this mapper cannot share one pin list (the reader is in core and keeps no public
     * configuration), so this asserts they agree on every pinned feature that is observable in a tree: if one
     * drifts back to a Jackson 3 default, the two stop describing the same value. The date setting is the one
     * deliberate difference: the reader keeps Jackson 2's epoch millis for a legacy Date, the output mapper
     * writes ISO text, and each matches what Jackson 2 did on its side.
     */
    @Test
    void sourceReaderAndOutputMapperAgreeOnWhatTheyPin() {
        Map<Kind, String> notes = new LinkedHashMap<>();
        notes.put(Kind.FIRST, "n");
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("zeta", "z");
        source.put("alpha", Map.of("kind", Kind.FIRST));
        source.put("notes", notes);

        JsonNode written = mapper.readTree(mapper.writeValueAsString(source));
        assertThat(SourceTree.of(source)).isEqualTo(written);
        assertThat(SourceTree.of(source).toString()).isEqualTo(written.toString());

        assertThatThrownBy(() -> mapper.writeValueAsString(new Empty()))
                .isInstanceOf(InvalidDefinitionException.class);
        assertThatThrownBy(() -> SourceTree.of(new Empty()))
                .isInstanceOf(InvalidDefinitionException.class);
    }
}

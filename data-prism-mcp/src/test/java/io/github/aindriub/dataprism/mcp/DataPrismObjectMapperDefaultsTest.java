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
     * configuration), so this asserts they agree on the pinned features that show up in a tree of a record:
     * property order, enum names (as a value and as a map key) and refusal of an empty bean. A legacy
     * java.util.Date is a deliberate difference (D-173-1): the reader writes epoch millis for Jackson 2 parity,
     * the output mapper writes ISO text. Features that differ by design, such as the output mapper not
     * stripping BigDecimal zeros, are not covered here.
     */
    @Test
    void sourceReaderAndOutputMapperAgreeOnWhatTheyPin() {
        Shape source = new Shape("z", "a", Kind.FIRST);
        JsonNode written = mapper.readTree(mapper.writeValueAsString(source));
        assertThat(SourceTree.of(source)).isEqualTo(written);
        assertThat(SourceTree.of(source).toString()).isEqualTo(written.toString());

        Map<Kind, String> notes = new LinkedHashMap<>();
        notes.put(Kind.FIRST, "n");
        assertThat(SourceTree.of(notes).toString()).isEqualTo(mapper.writeValueAsString(notes));

        assertThatThrownBy(() -> mapper.writeValueAsString(new Empty()))
                .isInstanceOf(InvalidDefinitionException.class);
        assertThatThrownBy(() -> SourceTree.of(new Empty()))
                .isInstanceOf(InvalidDefinitionException.class);
    }
}

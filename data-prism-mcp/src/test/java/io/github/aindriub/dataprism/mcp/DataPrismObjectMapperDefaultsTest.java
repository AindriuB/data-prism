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

    public static final class PlainShape {
        public String zeta = "z";
        public String alpha = "a";
    }

    /** A record keeps its component order whatever the sort setting, so only a plain class exercises the pin. */
    @Test
    void aPlainClassKeepsDeclaredOrderNotAlphabetical() {
        assertThat(mapper.writeValueAsString(new PlainShape())).isEqualTo("{\"zeta\":\"z\",\"alpha\":\"a\"}");
    }

    @Test
    void anEmptyBeanIsRefusedNotWrittenAsAnEmptyObject() {
        assertThatThrownBy(() -> mapper.writeValueAsString(new Empty()))
                .isInstanceOf(InvalidDefinitionException.class);
    }

    /**
     * The source reader (core) and this mapper cannot share one pin list, and since source models are records
     * the reader's sort pin is not observable. What this asserts is narrower: for a record, and for an enum
     * used as a map value, the reader's tree equals what this mapper writes. It does not cover sorting,
     * BigDecimal zero stripping or dates, which differ by design or are tested on each mapper alone.
     */
    @Test
    void sourceReaderAndOutputMapperAgreeOnEnumNamesAndRecordOrder() {
        Shape source = new Shape("z", "a", Kind.FIRST);
        JsonNode written = mapper.readTree(mapper.writeValueAsString(source));
        assertThat(SourceTree.of(source).toString()).isEqualTo(written.toString());

        record Notes(Map<Kind, String> notes) { }
        Map<Kind, String> notes = new LinkedHashMap<>();
        notes.put(Kind.FIRST, "n");
        assertThat(SourceTree.of(new Notes(notes)).get("notes").toString())
                .isEqualTo(mapper.writeValueAsString(notes));
    }
}

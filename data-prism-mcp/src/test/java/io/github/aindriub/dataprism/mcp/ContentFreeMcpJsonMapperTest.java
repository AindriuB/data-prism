package io.github.aindriub.dataprism.mcp;

import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ContentFreeMcpJsonMapperTest {

    private static final String SENTINEL = "SENTINEL-SUBJECT-7f3a91c2";
    private static final TypeRef<HashMap<String, Object>> HASH_MAP = new TypeRef<>() { };

    private final ContentFreeMcpJsonMapper mapper =
            new ContentFreeMcpJsonMapper(new JacksonMcpJsonMapper(DataPrismObjectMapper.create()));

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static void assertContentFree(Throwable e, String message) {
        assertThat(e).isInstanceOf(IOException.class).hasMessage(message).hasNoCause();
        assertThat(e.getSuppressed()).isEmpty();
        assertThat(e.getMessage()).doesNotContain(SENTINEL);
    }

    @Test
    @DisplayName("a failed read carries a fixed message and no cause, on every readValue overload")
    void failedReadsCarryNoCause() {
        String bad = "{\"a\":\"" + SENTINEL;
        assertThatThrownBy(() -> mapper.readValue(bad, Object.class))
                .satisfies(e -> assertContentFree(e, ContentFreeMcpJsonMapper.READ_FAILED));
        assertThatThrownBy(() -> mapper.readValue(bytes(bad), Object.class))
                .satisfies(e -> assertContentFree(e, ContentFreeMcpJsonMapper.READ_FAILED));
        assertThatThrownBy(() -> mapper.readValue(bad, HASH_MAP))
                .satisfies(e -> assertContentFree(e, ContentFreeMcpJsonMapper.READ_FAILED));
        assertThatThrownBy(() -> mapper.readValue(bytes(bad), HASH_MAP))
                .satisfies(e -> assertContentFree(e, ContentFreeMcpJsonMapper.READ_FAILED));
    }

    @Test
    @DisplayName("both HashMap overloads, byte[] included, refuse a map with no routing key")
    void byteOverloadsRefuseANonJsonRpcMap() {
        String json = "{\"" + SENTINEL + "\":1}";
        assertThatThrownBy(() -> mapper.readValue(json, HASH_MAP))
                .satisfies(e -> assertContentFree(e, ContentFreeMcpJsonMapper.NOT_JSON_RPC));
        assertThatThrownBy(() -> mapper.readValue(bytes(json), HASH_MAP))
                .satisfies(e -> assertContentFree(e, ContentFreeMcpJsonMapper.NOT_JSON_RPC));
    }

    @Test
    @DisplayName("a HashMap with a routing key is returned as read")
    void aRoutedMapPasses() throws Exception {
        assertThat(mapper.readValue("{\"method\":\"ping\"}", HASH_MAP)).containsEntry("method", "ping");
        assertThat(mapper.readValue(bytes("{\"result\":{}}"), HASH_MAP)).containsKey("result");
    }

    @Test
    @DisplayName("reads of a non-HashMap TypeRef pass through untouched")
    void nonMapTypeRefPassesThrough() throws Exception {
        TypeRef<List<String>> list = new TypeRef<>() { };
        TypeRef<Map<String, Object>> plainMap = new TypeRef<>() { };

        assertThat(mapper.readValue("[\"a\"]", list)).containsExactly("a");
        assertThat(mapper.readValue(bytes("[\"a\"]"), list)).containsExactly("a");
        assertThat(mapper.readValue("{\"x\":1}", plainMap)).containsEntry("x", 1);
        assertThat(mapper.readValue(bytes("{\"x\":1}"), plainMap)).containsEntry("x", 1);
    }

    @Test
    @DisplayName("a failed conversion carries a fixed message and no cause")
    void failedConversionCarriesNoCause() {
        Map<String, Object> value = Map.of("id", SENTINEL);
        assertThatThrownBy(() -> mapper.convertValue(value, Integer.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(ContentFreeMcpJsonMapper.CONVERT_FAILED).hasNoCause();
        assertThatThrownBy(() -> mapper.convertValue(value, new TypeRef<List<Integer>>() { }))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(ContentFreeMcpJsonMapper.CONVERT_FAILED).hasNoCause();
    }
}

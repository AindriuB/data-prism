package io.github.aindriub.dataprism.mcp;

import io.modelcontextprotocol.json.schema.JsonSchemaValidator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The input schema {@code follow} serves: generated from the configured length, the file at the default. */
class FollowToolSchemaTest {

    private static final JsonMapper MAPPER = DataPrismObjectMapper.create();

    @SuppressWarnings("unchecked")
    private static Map<String, Object> checkedIn() throws Exception {
        try (InputStream in = FollowToolSchemaTest.class.getResourceAsStream(
                "/io/github/aindriub/dataprism/mcp/schema/follow.input.json")) {
            assertThat(in).as("the checked-in schema").isNotNull();
            return MAPPER.readValue(in, LinkedHashMap.class);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> link(Map<String, Object> schema) {
        return (Map<String, Object>) ((Map<String, Object>) schema.get("properties")).get("link");
    }

    @Test
    @DisplayName("at the default length the served schema is the checked-in file")
    void defaultIsTheFile() throws Exception {
        assertThat(FollowTool.inputSchema(MAPPER, 1024)).isEqualTo(checkedIn());
    }

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("the file: one required string link, 1 to 1024, the link alphabet, nothing else allowed")
    void theFileSaysWhatTheDesignSays() throws Exception {
        Map<String, Object> schema = checkedIn();

        assertThat(schema.get("type")).isEqualTo("object");
        assertThat(((Map<String, Object>) schema.get("properties")).keySet()).containsExactly("link");
        assertThat(schema.get("required")).isEqualTo(List.of("link"));
        assertThat(schema.get("additionalProperties")).isEqualTo(false);
        Map<String, Object> link = link(schema);
        assertThat(link.get("type")).isEqualTo("string");
        assertThat(link.get("minLength")).isEqualTo(1);
        assertThat(link.get("maxLength")).isEqualTo(1024);
        assertThat(link.get("pattern")).isEqualTo("^[A-Za-z0-9_-]+$");
    }

    @Test
    @DisplayName("a configured length changes maxLength and nothing else")
    void configuredLengthIsTheOnlyDifference() throws Exception {
        Map<String, Object> expected = checkedIn();
        link(expected).put("maxLength", 2048);

        assertThat(FollowTool.inputSchema(MAPPER, 2048)).isEqualTo(expected);
    }

    @Test
    @DisplayName("the served schema enforces its bounds, the pattern and the closed object")
    void theSchemaEnforcesItself() {
        JsonSchemaValidator validator = ToolInput.validatorFor(MAPPER);
        ToolInput input = new ToolInput(validator, FollowTool.inputSchema(MAPPER, 64));

        assertThat(input.valid(Map.of("link", "a".repeat(64)))).isTrue();
        assertThat(input.valid(Map.of("link", "Ab_-9"))).isTrue();
        assertThat(input.valid(Map.of("link", "a".repeat(65)))).isFalse();
        assertThat(input.valid(Map.of("link", ""))).isFalse();
        assertThat(input.valid(Map.of("link", "a b"))).isFalse();
        assertThat(input.valid(Map.of("link", "a.b"))).isFalse();
        assertThat(input.valid(Map.of("link", 7))).isFalse();
        assertThat(input.valid(Map.of())).isFalse();
        assertThat(input.valid(Map.of("link", "abc", "other", "x"))).isFalse();
        // A reserved name is stripped first, so it does not break the closed object.
        assertThat(input.valid(Map.of("link", "abc", "principalId", "x"))).isTrue();
    }
}

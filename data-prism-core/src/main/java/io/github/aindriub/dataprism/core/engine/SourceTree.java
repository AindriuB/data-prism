package io.github.aindriub.dataprism.core.engine;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.node.StringNode;

/**
 * Reads source objects into trees, and builds the nodes that replace them.
 *
 * <p>The one mapper on the trusted side, and it never serialises anything. Three
 * places need to turn a source record into a tree — the scrubbing engine, the
 * prohibited-value extractor and the correlation service — and each having its
 * own mapper meant three objects that an architecture rule had to name
 * individually, which is a rule that gets weaker every time someone adds a
 * fourth.
 *
 * <p>The rule that matters is that exactly one mapper <em>writes</em> what a model
 * sees, so the scrubbing module cannot be bypassed. Keeping the reading side to a
 * single object here lets that rule stay short: two names on the allowlist, one
 * for reading and one for writing, and anything else constructing a mapper is a
 * finding.
 */
public final class SourceTree {

    private static final ObjectMapper READER = JsonMapper.builder()
            // Jackson 3 sorts properties alphabetically and tolerates an empty bean by default.
            // Jackson 2 did neither, and a source tree must keep the source's own field order
            // and refuse a shape it cannot read rather than read it as an empty object.
            .disable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .enable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
            .build();

    private SourceTree() {
    }

    /** A source object as a tree. Never used to produce output. */
    public static JsonNode of(Object source) {
        return READER.valueToTree(source);
    }

    public static ObjectNode newObject() {
        return READER.createObjectNode();
    }

    public static ArrayNode newArray() {
        return READER.createArrayNode();
    }

    public static StringNode text(String value) {
        return READER.getNodeFactory().stringNode(value);
    }
}

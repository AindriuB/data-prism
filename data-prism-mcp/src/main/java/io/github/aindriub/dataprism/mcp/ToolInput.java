package io.github.aindriub.dataprism.mcp;

import io.modelcontextprotocol.json.schema.JsonSchemaValidator;
import io.modelcontextprotocol.json.schema.jackson3.DefaultJsonSchemaValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The one path every tool checks its input on, against the schema the tool serves. The SDK's own input
 * validation is switched off, because it answers before a handler runs and so before the call is
 * authenticated, audited or counted; the handler calls this instead, after authorisation.
 *
 * <p>The answer is valid or invalid, and nothing else: the validator's message can carry the value that
 * broke the schema, so it is never returned, logged or audited. The validator cannot tell bad input from its
 * own fault, so a throw, a {@code null} answer and a tool with no schema are all invalid. Fail closed.
 *
 * <p>Reserved and approval argument names are removed from the copy that is checked, so a schema with
 * {@code additionalProperties: false} does not refuse them; they are audited by name by the caller, from
 * the original map.
 */
final class ToolInput {

    /** The refusal code for input that breaks the schema, or that cannot be checked. Not retryable; names nothing. */
    static final String TOOL_INPUT_INVALID = "TOOL_INPUT_INVALID";

    private static final Logger LOG = LoggerFactory.getLogger(ToolInput.class);

    private final JsonSchemaValidator validator;
    private final Map<String, Object> schema;

    /**
     * @param schema the tool's served {@code inputSchema}; {@code null} or empty makes every input invalid
     */
    ToolInput(JsonSchemaValidator validator, Map<String, Object> schema) {
        this.validator = java.util.Objects.requireNonNull(validator, "validator");
        this.schema = schema;
    }

    /** The one validator, built on the shared mapper, that all tools use. */
    static JsonSchemaValidator validatorFor(JsonMapper mapper) {
        return new DefaultJsonSchemaValidator(java.util.Objects.requireNonNull(mapper, "mapper"));
    }

    /** Whether {@code arguments} satisfy the schema. {@code null} arguments are an empty object. */
    boolean valid(Map<String, Object> arguments) {
        if (schema == null || schema.isEmpty()) {
            LOG.error("a tool has no input schema; its input is refused");
            return false;
        }
        Map<String, Object> checked = new LinkedHashMap<>();
        if (arguments != null) {
            checked.putAll(arguments);
            checked.keySet().removeAll(ToolCalls.rejectedArguments(arguments));
        }
        JsonSchemaValidator.ValidationResponse response;
        try {
            response = validator.validate(schema, checked);
        } catch (RuntimeException failure) {
            // Class name only: the message and the stack can quote the value being checked.
            LOG.error("the input validator threw {}; the input is refused", failure.getClass().getName());
            return false;
        }
        if (response == null) {
            LOG.error("the input validator gave no answer; the input is refused");
            return false;
        }
        return response.valid();
    }
}

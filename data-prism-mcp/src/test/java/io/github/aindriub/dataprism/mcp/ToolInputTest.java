package io.github.aindriub.dataprism.mcp;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.modelcontextprotocol.json.schema.JsonSchemaValidator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** {@link ToolInput}: valid or invalid, and invalid whenever the answer cannot be trusted. */
class ToolInputTest {

    private static final Map<String, Object> SCHEMA = Map.of("type", "object",
            "properties", Map.of("name", Map.of("type", "string")), "required", List.of("name"));
    private static final Map<String, Object> CLOSED = Map.of("type", "object",
            "properties", Map.of("name", Map.of("type", "string")), "additionalProperties", false);

    private static final JsonSchemaValidator REAL = ToolInput.validatorFor(DataPrismObjectMapper.create());

    private ListAppender<ILoggingEvent> logs;
    private Logger root;

    @BeforeEach
    void captureLogs() {
        root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        logs = new ListAppender<>();
        logs.start();
        root.addAppender(logs);
    }

    @AfterEach
    void releaseLogs() {
        root.detachAppender(logs);
    }

    private static JsonSchemaValidator answering(JsonSchemaValidator.ValidationResponse response) {
        return (schema, input) -> response;
    }

    @Test
    @DisplayName("a valid answer is valid, an invalid answer is invalid")
    void answersAreRelayed() {
        assertThat(new ToolInput(answering(JsonSchemaValidator.ValidationResponse.asValid(null)), SCHEMA)
                .valid(Map.of("name", "a"))).isTrue();
        assertThat(new ToolInput(answering(JsonSchemaValidator.ValidationResponse.asInvalid("no")), SCHEMA)
                .valid(Map.of("name", "a"))).isFalse();
    }

    @Test
    @DisplayName("a validator that throws is invalid, and the log names the class only")
    void throwingValidatorRefuses() {
        JsonSchemaValidator throwing = (schema, input) -> {
            throw new IllegalStateException("SENTINEL-from-the-validator");
        };

        assertThat(new ToolInput(throwing, SCHEMA).valid(Map.of("name", "a"))).isFalse();

        assertThat(logs.list).anySatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.ERROR);
            assertThat(event.getFormattedMessage()).contains(IllegalStateException.class.getName());
        });
        for (ILoggingEvent event : logs.list) {
            assertThat(event.getFormattedMessage()).doesNotContain("SENTINEL");
            assertThat(event.getThrowableProxy()).as("no stack trace, no exception message").isNull();
        }
    }

    @Test
    @DisplayName("a validator that answers null is invalid")
    void nullAnswerRefuses() {
        assertThat(new ToolInput(answering(null), SCHEMA).valid(Map.of("name", "a"))).isFalse();
    }

    @Test
    @DisplayName("a tool with no schema, or an empty one, refuses everything, whatever the validator says")
    void emptySchemaRefuses() {
        JsonSchemaValidator yes = answering(JsonSchemaValidator.ValidationResponse.asValid(null));

        assertThat(new ToolInput(yes, Map.of()).valid(Map.of("name", "a"))).isFalse();
        assertThat(new ToolInput(yes, null).valid(Map.of("name", "a"))).isFalse();
        assertThat(new ToolInput(yes, Map.of()).valid(null)).isFalse();
    }

    @Test
    @DisplayName("the validator's message is never logged")
    void messageIsNotLogged() {
        new ToolInput(answering(JsonSchemaValidator.ValidationResponse.asInvalid("SENTINEL-message")), SCHEMA)
                .valid(Map.of("name", 5));

        for (ILoggingEvent event : logs.list) {
            assertThat(event.getFormattedMessage()).doesNotContain("SENTINEL");
        }
    }

    @Test
    @DisplayName("null arguments are an empty object")
    void nullArgumentsAreEmpty() {
        List<Object> seen = new ArrayList<>();
        JsonSchemaValidator recording = (schema, input) -> {
            seen.add(input);
            return JsonSchemaValidator.ValidationResponse.asValid(null);
        };

        assertThat(new ToolInput(recording, SCHEMA).valid(null)).isTrue();
        assertThat(seen).containsExactly(Map.of());
        assertThat(new ToolInput(REAL, SCHEMA).valid(null)).as("required name is missing").isFalse();
        assertThat(new ToolInput(REAL, CLOSED).valid(null)).isTrue();
    }

    @Test
    @DisplayName("a reserved or approval name does not fail additionalProperties false, and stays in the caller's map")
    void reservedNamesAreStrippedForTheCheckOnly() {
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("name", "a");
        arguments.put("principalId", "p");
        arguments.put("approvalId", "x");
        arguments.put("approverId", "y");
        arguments.put("traceparent", "t");

        assertThat(new ToolInput(REAL, CLOSED).valid(arguments)).isTrue();
        assertThat(arguments.keySet()).containsExactly("name", "principalId", "approvalId", "approverId",
                "traceparent");
        assertThat(ToolCalls.rejectedArguments(arguments)).containsExactlyInAnyOrder("principalId", "approvalId",
                "approverId", "traceparent");
        assertThat(new ToolInput(REAL, CLOSED).valid(Map.of("name", "a", "other", "z"))).isFalse();
    }

    @Test
    @DisplayName("the real validator rejects a wrong type and accepts a null-valued optional only if the schema does")
    void realValidator() {
        assertThat(new ToolInput(REAL, SCHEMA).valid(Map.of("name", 5))).isFalse();
        Map<String, Object> nullValue = new HashMap<>();
        nullValue.put("name", null);
        assertThat(new ToolInput(REAL, SCHEMA).valid(nullValue)).isFalse();
    }
}

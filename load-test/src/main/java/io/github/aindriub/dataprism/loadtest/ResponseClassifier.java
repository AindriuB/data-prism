package io.github.aindriub.dataprism.loadtest;

import io.modelcontextprotocol.spec.McpSchema;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns a {@code get_entity_context} result into a {@link CallOutcome}.
 *
 * <p>The error text is never recorded. A refusal's code is read from the two shapes the tool produces
 * ({@code refused: CODE at path} and a bare denial code); anything else is the fixed code
 * {@value #UNCLASSIFIED}, so no free text from a response can reach a result file.
 */
final class ResponseClassifier {

    static final String UNCLASSIFIED = "UNCLASSIFIED_ERROR";

    private static final Pattern REFUSED = Pattern.compile("^refused: ([A-Z][A-Z0-9_]*) at .*", Pattern.DOTALL);
    private static final Pattern DENIAL = Pattern.compile("^([A-Z][A-Z0-9_]+)( approvalId=\\S+)?$");
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private ResponseClassifier() { }

    static CallOutcome classify(McpSchema.CallToolResult result, long latencyMicros) {
        if (Boolean.TRUE.equals(result.isError())) {
            return CallOutcome.refused(latencyMicros, refusalCode(firstText(result)));
        }
        Map<String, String> statuses = sourceStatuses(result);
        if (statuses == null) {
            return CallOutcome.transportFailure("UnparseableBody", FailureCause.UNPARSEABLE_BODY);
        }
        return CallOutcome.completed(latencyMicros, statuses);
    }

    private static String refusalCode(String text) {
        if (text == null) {
            return UNCLASSIFIED;
        }
        Matcher refused = REFUSED.matcher(text);
        if (refused.matches()) {
            return refused.group(1);
        }
        Matcher denial = DENIAL.matcher(text);
        return denial.matches() ? denial.group(1) : UNCLASSIFIED;
    }

    /** The statuses, or {@code null} when the response holds no {@code sources} object to read them from. */
    private static Map<String, String> sourceStatuses(McpSchema.CallToolResult result) {
        JsonNode sources = null;
        if (result.structuredContent() != null) {
            sources = MAPPER.valueToTree(result.structuredContent()).path("sources");
        } else {
            String text = firstText(result);
            if (text != null) {
                try {
                    sources = MAPPER.readTree(text).path("sources");
                } catch (RuntimeException notJson) {
                    sources = null;
                }
            }
        }
        if (sources == null || !sources.isObject()) {
            return null;
        }
        Map<String, String> statuses = new LinkedHashMap<>();
        sources.properties().forEach(entry -> statuses.put(entry.getKey(), entry.getValue().path("status").asString("")));
        return statuses;
    }

    private static String firstText(McpSchema.CallToolResult result) {
        return result.content() == null ? null : result.content().stream()
                .filter(McpSchema.TextContent.class::isInstance)
                .map(c -> ((McpSchema.TextContent) c).text())
                .findFirst().orElse(null);
    }
}

package io.github.aindriub.dataprism.loadtest;

import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Canned responses: what the server's get_entity_context returns, per GetEntityContextTool. */
class ResponseClassifierTest {

    private static McpSchema.CallToolResult ok(Map<String, Object> structured) {
        return McpSchema.CallToolResult.builder().structuredContent(structured).addTextContent("{}").build();
    }

    private static McpSchema.CallToolResult error(String text) {
        return McpSchema.CallToolResult.builder().isError(true).addTextContent(text).build();
    }

    @Test
    void readsEachSourcesStatusFromTheStructuredResponse() {
        Map<String, Object> sources = Map.of(
                "a", Map.of("status", "ANSWERED", "entity", Map.of("id", "x")),
                "b", Map.of("status", "NO_DATA"),
                "c", Map.of("status", "TIMED_OUT"),
                "d", Map.of("status", "FAILED"),
                "e", Map.of("status", "CIRCUIT_OPEN"),
                "f", Map.of("status", "SKIPPED_OVER_LIMIT"),
                "g", Map.of("status", "REFUSED"));

        CallOutcome outcome = ResponseClassifier.classify(ok(Map.of("entityType", "PARCEL", "sources", sources)), 5000);

        assertThat(outcome.completed()).isTrue();
        assertThat(outcome.refusalCode()).isNull();
        assertThat(outcome.sourceStatuses()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "a", "ANSWERED", "b", "NO_DATA", "c", "TIMED_OUT", "d", "FAILED", "e", "CIRCUIT_OPEN",
                "f", "SKIPPED_OVER_LIMIT", "g", "REFUSED"));
        assertThat(outcome.latencyMicros()).isEqualTo(5000);
    }

    @Test
    void fallsBackToTheTextContentWhenThereIsNoStructuredContent() {
        McpSchema.CallToolResult result = McpSchema.CallToolResult.builder()
                .addTextContent("{\"sources\":{\"a\":{\"status\":\"REFUSED\"}}}").build();

        assertThat(ResponseClassifier.classify(result, 1).sourceStatuses()).containsEntry("a", "REFUSED");
    }

    @Test
    void readsTheCodeFromARefusedCall() {
        assertThat(ResponseClassifier.classify(error("refused: TOO_MANY_RECORDS at $"), 1).refusalCode())
                .isEqualTo("TOO_MANY_RECORDS");
        assertThat(ResponseClassifier.classify(error("refused: RESPONSE_TOO_LARGE at $"), 1).refusalCode())
                .isEqualTo("RESPONSE_TOO_LARGE");
        assertThat(ResponseClassifier.classify(error("refused: NO_SOURCE_DATA at PARCEL"), 1).refusalCode())
                .isEqualTo("NO_SOURCE_DATA");
    }

    @Test
    void readsABareDenialCode() {
        CallOutcome outcome = ResponseClassifier.classify(error("SCOPE_READ_BUDGET"), 1);

        assertThat(outcome.completed()).isTrue();
        assertThat(outcome.refusalCode()).isEqualTo("SCOPE_READ_BUDGET");
        assertThat(outcome.sourceStatuses()).isEmpty();
    }

    @Test
    void anApprovalDenialKeepsTheCodeAndDropsTheId() {
        assertThat(ResponseClassifier.classify(error("APPROVAL_REQUIRED approvalId=abc-123"), 1).refusalCode())
                .isEqualTo("APPROVAL_REQUIRED");
    }

    @Test
    void anUnrecognisedErrorTextIsOneFixedCodeAndNeverTheTextItself() {
        assertThat(ResponseClassifier.classify(error("the request could not be completed"), 1).refusalCode())
                .isEqualTo("UNCLASSIFIED_ERROR");
        assertThat(ResponseClassifier.classify(error("something with a value 1234 in it"), 1).refusalCode())
                .isEqualTo("UNCLASSIFIED_ERROR");
    }

    @Test
    void aResponseWithNoSourcesAndNoErrorIsAMalformedCompletion() {
        CallOutcome outcome = ResponseClassifier.classify(ok(Map.of("entityType", "PARCEL")), 1);

        assertThat(outcome.completed()).isTrue();
        assertThat(outcome.sourceStatuses()).isEmpty();
        assertThat(outcome.refusalCode()).isNull();
    }
}

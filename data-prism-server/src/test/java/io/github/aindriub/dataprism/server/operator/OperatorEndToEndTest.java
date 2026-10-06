package io.github.aindriub.dataprism.server.operator;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.http.HttpResponse;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 105, end to end on two embedded ports with a fixture source: an operator pauses and resumes
 * a tool, and a second operator approves a gated tool call that the first caller then repeats.
 */
class OperatorEndToEndTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void aToolPauseIsHonouredAndLifted() throws Exception {
        try (OperatorHarness app = OperatorHarness.start(tempDir, false);
             McpSyncClient client = app.mcpClient(app.mcpToken("analyst-e2e", "CASE-E2E"))) {
            String operator = app.operatorToken("operator-e2e");

            McpSchema.CallToolResult before = app.getEntityContext(client, "123");
            assertThat(before.isError()).isNotEqualTo(Boolean.TRUE);

            assertThat(app.operator("POST", "/operator/pause", operator,
                    "{\"target\":\"TOOL\",\"name\":\"get_entity_context\"}").statusCode()).isEqualTo(200);
            McpSchema.CallToolResult paused = app.getEntityContext(client, "123");
            assertThat(paused.isError()).isTrue();
            assertThat(OperatorHarness.text(paused)).isEqualTo("TOOL_PAUSED");

            assertThat(app.operator("POST", "/operator/resume", operator,
                    "{\"target\":\"TOOL\",\"name\":\"get_entity_context\"}").statusCode()).isEqualTo(200);
            McpSchema.CallToolResult resumed = app.getEntityContext(client, "123");
            assertThat(resumed.isError()).isNotEqualTo(Boolean.TRUE);
            assertThat(JSON.readTree(OperatorHarness.text(resumed)).path("entity").path("status").asText())
                    .isEqualTo("ACTIVE");
        }
    }

    @Test
    void aSecondOperatorsApprovalAdmitsExactlyOneIdenticalRetry() throws Exception {
        try (OperatorHarness app = OperatorHarness.start(tempDir, false,
                "--dataprism.oversight.approval-required-tools[0]=get_entity_context");
             McpSyncClient client = app.mcpClient(app.mcpToken("analyst-e2e", "CASE-E2E"))) {
            String second = app.operatorToken("operator-two");

            McpSchema.CallToolResult first = app.getEntityContext(client, "123");
            assertThat(first.isError()).isTrue();
            String refusal = OperatorHarness.text(first);
            assertThat(refusal).startsWith("APPROVAL_REQUIRED approvalId=");
            String approvalId = refusal.substring(refusal.indexOf('=') + 1).trim();

            HttpResponse<String> approved = app.operator("POST", "/operator/approvals/" + approvalId + "/approve",
                    second, null);
            assertThat(approved.statusCode()).isEqualTo(200);

            McpSchema.CallToolResult retry = app.getEntityContext(client, "123");
            assertThat(retry.isError()).isNotEqualTo(Boolean.TRUE);
            assertThat(JSON.readTree(OperatorHarness.text(retry)).path("entity").path("status").asText())
                    .isEqualTo("ACTIVE");

            McpSchema.CallToolResult third = app.getEntityContext(client, "123");
            assertThat(third.isError()).isTrue();
            assertThat(OperatorHarness.text(third)).startsWith("APPROVAL_REQUIRED approvalId=")
                    .doesNotContain(approvalId);

            assertThat(app.auditFor("operator:approve")).singleElement().satisfies(e -> {
                assertThat(e.principalId()).isEqualTo("operator-two");
                assertThat(e.approvalId()).isEqualTo(approvalId);
                assertThat(e.approverId()).isEqualTo("operator-two");
            });
        }
    }
}

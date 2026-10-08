package io.github.aindriub.dataprism.server.operator;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import io.modelcontextprotocol.client.McpSyncClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.http.HttpResponse;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 127: a pseudonym taken from a real MCP tool result is re-identified through the operator
 * port, with nothing hand-fed into the index. Fixture subject only.
 */
class ReidentificationEndToEndTest {

    private static final ObjectMapper JSON = JsonMapper.builder().build();
    private static final String SUBJECT = "subject-4711";
    private static final String CASE = "CASE-E2E";

    @TempDir
    Path tempDir;

    @Test
    void aPseudonymFromAToolResultIsReidentifiedAfterASecondOperatorApproves() throws Exception {
        try (OperatorHarness app = OperatorHarness.start(tempDir, true);
             McpSyncClient client = app.mcpClient(app.mcpToken("analyst-e2e", CASE))) {
            JsonNode entity = JSON.readTree(OperatorHarness.text(app.getEntityContext(client, SUBJECT)))
                    .path("entity");
            String synthetic = entity.path("customerName").asString();
            assertThat(synthetic).isNotBlank().isNotEqualTo("Fixture Person");

            String requester = app.operatorToken("requester-e2e", "requester");
            String approver = app.operatorToken("approver-e2e", "approver");
            HttpResponse<String> requested = app.operator("POST", "/operator/reidentifications", requester,
                    "{\"scopeId\":\"case:" + CASE + "\",\"namespace\":\"PERSON_NAME\",\"syntheticValue\":\""
                            + synthetic + "\",\"purpose\":\"fraud-review\",\"caseId\":\"" + CASE + "\"}");
            assertThat(requested.statusCode()).isEqualTo(202);
            String approvalId = JSON.readTree(requested.body()).path("approvalId").asString();
            assertThat(approvalId).isNotBlank();

            assertThat(app.operator("POST", "/operator/reidentifications/" + approvalId + "/approve", approver,
                    null).statusCode()).isEqualTo(200);

            HttpResponse<String> collected = app.operator("GET", "/operator/reidentifications/" + approvalId,
                    requester, null);
            assertThat(collected.statusCode()).isEqualTo(200);
            JsonNode body = JSON.readTree(collected.body());
            assertThat(body.path("status").asString()).isEqualTo("RESOLVED");
            assertThat(body.path("subjectId").asString()).isEqualTo(SUBJECT);
        }
    }
}

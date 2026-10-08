package io.github.aindriub.dataprism.server.operator;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import io.modelcontextprotocol.client.McpSyncClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 127, attempt 2: the configured-JSON engine is built from the wired source too. The EMAIL
 * pseudonym is produced only by the configured-JSON source (the Java fixture carries a name and
 * nothing else), so it resolves only if that engine's source feeds the reverse index. Synthetic
 * fixture data only.
 */
class ConfiguredJsonReidentificationEndToEndTest {

    private static final ObjectMapper JSON = JsonMapper.builder().build();
    private static final String SUBJECT = "subject-4712";
    private static final String CASE = "CASE-CFG";
    private static final String RECORD = "{\"id\":\"" + SUBJECT + "\",\"contactEmail\":\"fixture.person@example.invalid\"}";

    @TempDir
    Path tempDir;

    @Test
    void aPseudonymFromAConfiguredJsonToolResultIsReidentified() throws Exception {
        String template = new String(getClass().getResourceAsStream("/configured-json/reidentification-source.yaml")
                .readAllBytes(), StandardCharsets.UTF_8);
        try (OperatorHarness app = OperatorHarness.startWithJsonSource(tempDir, template, RECORD);
             McpSyncClient client = app.mcpClient(app.mcpToken("analyst-cfg", CASE))) {
            String text = OperatorHarness.text(app.getEntityContext(client, SUBJECT));
            String synthetic = firstEmail(JSON.readTree(text));
            assertThat(synthetic).as(text).isNotBlank().doesNotContain("fixture.person@example.invalid");

            String requester = app.operatorToken("requester-cfg", "requester");
            String approver = app.operatorToken("approver-cfg", "approver");
            HttpResponse<String> requested = app.operator("POST", "/operator/reidentifications", requester,
                    "{\"scopeId\":\"case:" + CASE + "\",\"namespace\":\"EMAIL\",\"syntheticValue\":\""
                            + synthetic + "\",\"purpose\":\"fraud-review\",\"caseId\":\"" + CASE + "\"}");
            assertThat(requested.statusCode()).isEqualTo(202);
            String approvalId = JSON.readTree(requested.body()).path("approvalId").asString();
            assertThat(app.operator("POST", "/operator/reidentifications/" + approvalId + "/approve", approver,
                    null).statusCode()).isEqualTo(200);

            JsonNode body = JSON.readTree(app.operator("GET", "/operator/reidentifications/" + approvalId,
                    requester, null).body());
            assertThat(body.path("status").asString()).isEqualTo("RESOLVED");
            assertThat(body.path("subjectId").asString()).isEqualTo(SUBJECT);
        }
    }

    private static String firstEmail(JsonNode node) {
        if (node.has("contactEmail")) return node.get("contactEmail").asString();
        for (JsonNode child : node) {
            String found = firstEmail(child);
            if (found != null) return found;
        }
        return null;
    }
}

package io.github.aindriub.dataprism.server.operator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.modelcontextprotocol.client.McpSyncClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 127, attempt 2: the configured-JSON engine is built from the wired source too. The EMAIL
 * pseudonym is produced only by the configured-JSON source (the Java fixture carries a name and
 * nothing else), so it resolves only if that engine's source feeds the reverse index. Synthetic
 * fixture data only.
 */
class ConfiguredJsonReidentificationEndToEndTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SUBJECT = "subject-4712";
    private static final String CASE = "CASE-CFG";
    private static final String RECORD = "{\"id\":\"" + SUBJECT + "\",\"contactEmail\":\"fixture.person@example.invalid\"}";

    @TempDir
    Path tempDir;

    @Test
    void aPseudonymFromAConfiguredJsonToolResultIsReidentified() throws Exception {
        HttpServer source = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        source.createContext("/records/", exchange -> {
            byte[] body = RECORD.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        source.start();
        try {
            String template = new String(getClass().getResourceAsStream("/configured-json/reidentification-source.yaml")
                    .readAllBytes(), StandardCharsets.UTF_8);
            Path catalogue = tempDir.resolve("catalogue.yaml");
            Files.writeString(catalogue, template.replace("@PORT@", String.valueOf(source.getAddress().getPort())));
            try (OperatorHarness app = OperatorHarness.start(tempDir, true,
                    "--dataprism.json-sources.config-location=file:" + catalogue.toAbsolutePath(),
                    "--dataprism.transport.fixture-development=true");
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
                String approvalId = JSON.readTree(requested.body()).path("approvalId").asText();
                assertThat(app.operator("POST", "/operator/reidentifications/" + approvalId + "/approve", approver,
                        null).statusCode()).isEqualTo(200);

                JsonNode body = JSON.readTree(app.operator("GET", "/operator/reidentifications/" + approvalId,
                        requester, null).body());
                assertThat(body.path("status").asText()).isEqualTo("RESOLVED");
                assertThat(body.path("subjectId").asText()).isEqualTo(SUBJECT);
            }
        } finally {
            source.stop(0);
        }
    }

    private static String firstEmail(JsonNode node) {
        if (node.has("contactEmail")) return node.get("contactEmail").asText();
        for (JsonNode child : node) {
            String found = firstEmail(child);
            if (found != null) return found;
        }
        return null;
    }
}

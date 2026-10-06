package io.github.aindriub.dataprism.server.operator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.aindriub.dataprism.audit.AuditEvent;
import io.modelcontextprotocol.client.McpSyncClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 105: the operator port, its separation from the MCP port, its authorisation, and the
 * oversight endpoints, against the real application on two real ports.
 */
class OperatorSurfaceTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    static Path tempDir;
    static OperatorHarness app;

    @BeforeAll
    static void start() throws Exception {
        app = OperatorHarness.start(tempDir, false,
                "--dataprism.oversight.approval-required-tools[0]=get_entity_context");
    }

    @AfterAll
    static void stop() {
        if (app != null) app.close();
    }

    private static JsonNode body(HttpResponse<String> response) throws Exception {
        return JSON.readTree(response.body());
    }

    // ---- separation ----------------------------------------------------------------------------

    @Test
    void operatorPathsAreServedOnTheOperatorPortOnly() throws Exception {
        String operator = app.operatorToken("operator-sep");

        assertThat(app.operator("GET", "/operator/state", operator, null).statusCode()).isEqualTo(200);
        assertThat(app.mcpPort("GET", "/operator/state", operator, null).statusCode()).isEqualTo(404);
        assertThat(app.mcpPort("GET", "/operator/state", null, null).statusCode()).isEqualTo(404);
        assertThat(app.mcpPort("POST", "/operator/pause", operator, "{\"target\":\"ALL\"}").statusCode())
                .isEqualTo(404);
        assertThat(app.mcpPort("GET", "/operator/approvals", operator, null).statusCode()).isEqualTo(404);
    }

    @Test
    void anEncodedSpellingOfAnOperatorPathIsNotServedOnTheMcpPort() throws Exception {
        String operator = app.operatorToken("operator-enc");

        assertThat(app.mcpPort("GET", "/%6fperator/state", operator, null).statusCode()).isNotEqualTo(200);
        assertThat(app.mcpPort("GET", "/Operator/state", operator, null).statusCode()).isEqualTo(404);
        assertThat(app.mcpPort("GET", "/operator;x=1/state", operator, null).statusCode()).isEqualTo(404);
    }

    @Test
    void theMcpEndpointAndHealthAreNotServedOnTheOperatorPort() throws Exception {
        String operator = app.operatorToken("operator-mcp");
        String mcp = app.mcpToken("analyst-sep", "CASE-SEP");

        assertThat(app.operator("POST", "/mcp", operator, "{}").statusCode()).isEqualTo(404);
        assertThat(app.operator("POST", "/mcp", mcp, "{}").statusCode()).isEqualTo(404);
        assertThat(app.operator("POST", "/mcp", null, "{}").statusCode()).isEqualTo(404);
        assertThat(app.operator("GET", "/health", null, null).statusCode()).isEqualTo(404);
        assertThat(app.operator("GET", "/", operator, null).statusCode()).isEqualTo(404);
        assertThat(app.mcpPort("GET", "/health", null, null).statusCode()).isEqualTo(200);
    }

    @Test
    void theOperatorSurfaceIsNotAnMcpTool() throws Exception {
        try (McpSyncClient client = app.mcpClient(app.mcpToken("analyst-tools", "CASE-TOOLS"))) {
            assertThat(client.listTools().tools()).extracting(t -> t.name())
                    .containsExactlyInAnyOrder("get_entity_context", "compare_entity_sources");
        }
    }

    // ---- authorisation -------------------------------------------------------------------------

    @Test
    void theOperatorPortRefusesAnyTokenWithoutTheOperatorAudienceAndScope() throws Exception {
        String valid = app.operatorToken("operator-auth");
        String mcpOnly = app.mcpToken("analyst-auth", "CASE-AUTH");
        String wrongAudienceRightScope = app.token("operator-auth", "operator-console", OperatorHarness.MCP_AUDIENCE,
                OperatorHarness.OPERATOR_SCOPE, List.of(), "CASE-OPS");
        String rightAudienceNoScope = app.token("operator-auth", "operator-console",
                OperatorHarness.OPERATOR_AUDIENCE, null, List.of(), "CASE-OPS");
        String rightAudienceOtherScope = app.token("operator-auth", "operator-console",
                OperatorHarness.OPERATOR_AUDIENCE, "something.else", List.of(), "CASE-OPS");

        assertThat(app.operator("GET", "/operator/state", null, null).statusCode()).isEqualTo(401);
        assertThat(app.operator("GET", "/operator/state", "not-a-jwt", null).statusCode()).isEqualTo(401);
        assertThat(app.operator("GET", "/operator/state", mcpOnly, null).statusCode()).isEqualTo(401);
        assertThat(app.operator("GET", "/operator/state", wrongAudienceRightScope, null).statusCode())
                .isEqualTo(401);
        assertThat(app.operator("GET", "/operator/state", rightAudienceNoScope, null).statusCode())
                .isEqualTo(403);
        assertThat(app.operator("GET", "/operator/state", rightAudienceOtherScope, null).statusCode())
                .isEqualTo(403);
        assertThat(app.operator("GET", "/operator/state", valid, null).statusCode()).isEqualTo(200);
    }

    @Test
    void anOperatorTokenIsNotAcceptedOnTheMcpEndpoint() throws Exception {
        String operator = app.operatorToken("operator-reverse");

        assertThat(app.mcpPort("POST", "/mcp", operator, "{}").statusCode()).isEqualTo(401);
    }

    // ---- pause / resume / state ----------------------------------------------------------------

    @Test
    void pauseWritesOneAuditEventNamingTheOperatorAndTheTarget() throws Exception {
        String operator = app.operatorToken("operator-pause");
        app.audit.clear();

        HttpResponse<String> response = app.operator("POST", "/operator/pause", operator,
                "{\"target\":\"TOOL\",\"name\":\"compare_entity_sources\"}");
        try {
            assertThat(response.statusCode()).isEqualTo(200);
            List<AuditEvent> events = app.auditFor("operator:pause");
            assertThat(events).hasSize(1);
            AuditEvent event = events.get(0);
            assertThat(event.principalId()).isEqualTo("operator-pause");
            assertThat(event.clientId()).isEqualTo("operator-console");
            assertThat(event.entityType()).isEqualTo("compare_entity_sources");
            assertThat(event.policyDecision()).isEqualTo("ALLOW:PAUSED");

            JsonNode state = body(app.operator("GET", "/operator/state", operator, null));
            assertThat(state.path("pausedTools").toString()).contains("compare_entity_sources");
        } finally {
            app.operator("POST", "/operator/resume", operator,
                    "{\"target\":\"TOOL\",\"name\":\"compare_entity_sources\"}");
        }
        assertThat(app.auditFor("operator:resume")).hasSize(1);
        assertThat(app.auditFor("operator:resume").get(0).entityType()).isEqualTo("compare_entity_sources");
    }

    @Test
    void aScopePauseNamesTheScopeInTheAuditEvent() throws Exception {
        String operator = app.operatorToken("operator-scope");
        app.audit.clear();

        assertThat(app.operator("POST", "/operator/pause", operator,
                "{\"target\":\"SCOPE\",\"name\":\"case:CASE-PAUSED\"}").statusCode()).isEqualTo(200);
        try {
            assertThat(app.auditFor("operator:pause")).singleElement()
                    .satisfies(e -> assertThat(e.scopeId()).isEqualTo("case:CASE-PAUSED"));
            assertThat(body(app.operator("GET", "/operator/state", operator, null)).path("pausedScopes")
                    .toString()).contains("case:CASE-PAUSED");
        } finally {
            app.operator("POST", "/operator/resume", operator,
                    "{\"target\":\"SCOPE\",\"name\":\"case:CASE-PAUSED\"}");
        }
    }

    @Test
    void anInvalidTargetIsRefusedWithAStableCodeAndNoEcho() throws Exception {
        String operator = app.operatorToken("operator-invalid");
        for (String bad : new String[] {"{}", "{\"target\":\"EVERYTHING\"}", "{\"target\":\"TOOL\"}",
                "{\"target\":\"ALL\",\"name\":\"x\"}", "{\"target\":\"SCOPE\",\"name\":\"  \"}"}) {
            HttpResponse<String> response = app.operator("POST", "/operator/pause", operator, bad);
            assertThat(response.statusCode()).as(bad).isEqualTo(400);
            assertThat(response.body()).as(bad).isEqualTo("{\"code\":\"INVALID_REQUEST\"}");
        }
        HttpResponse<String> malformed = app.operator("POST", "/operator/pause", operator, "{not json");
        assertThat(malformed.statusCode()).isEqualTo(400);
        assertThat(malformed.body()).isEqualTo("{\"code\":\"INVALID_REQUEST\"}");
        assertThat(app.operator("GET", "/operator/pause", operator, null).body())
                .isEqualTo("{\"code\":\"METHOD_NOT_ALLOWED\"}");
        assertThat(app.operator("GET", "/operator/nothing-here", operator, null).body())
                .isEqualTo("{\"code\":\"NOT_FOUND\"}");
    }

    @Test
    void killSwitchPausesEveryCallAndResumeRestoresIt() throws Exception {
        String operator = app.operatorToken("operator-all");
        try (McpSyncClient client = app.mcpClient(app.mcpToken("analyst-all", "CASE-ALL"))) {
            assertThat(app.operator("POST", "/operator/pause", operator, "{\"target\":\"ALL\"}").statusCode())
                    .isEqualTo(200);
            try {
                assertThat(OperatorHarness.text(app.getEntityContext(client, "1"))).isEqualTo("DATAPRISM_PAUSED");
            } finally {
                assertThat(app.operator("POST", "/operator/resume", operator, "{\"target\":\"ALL\"}")
                        .statusCode()).isEqualTo(200);
            }
            assertThat(OperatorHarness.text(app.getEntityContext(client, "1"))).startsWith("APPROVAL_REQUIRED");
        }
    }

    // ---- tool-call approvals -------------------------------------------------------------------

    @Test
    void approvingYourOwnToolCallIsRefusedWithSelfApproval() throws Exception {
        try (McpSyncClient client = app.mcpClient(app.mcpToken("analyst-self", "CASE-SELF"))) {
            String refusal = OperatorHarness.text(app.getEntityContext(client, "777"));
            assertThat(refusal).startsWith("APPROVAL_REQUIRED approvalId=");
            String approvalId = refusal.substring(refusal.indexOf('=') + 1).trim();
            // The same principal that made the MCP call, now holding an operator token.
            String sameOperator = app.operatorToken("analyst-self");
            app.audit.clear();

            HttpResponse<String> response = app.operator("POST", "/operator/approvals/" + approvalId + "/approve",
                    sameOperator, null);

            assertThat(response.statusCode()).isEqualTo(409);
            assertThat(response.body()).isEqualTo("{\"code\":\"SELF_APPROVAL\"}");
            assertThat(app.auditFor("operator:approve")).singleElement().satisfies(e -> {
                assertThat(e.principalId()).isEqualTo("analyst-self");
                assertThat(e.policyDecision()).isEqualTo("DENY:SELF_APPROVAL");
                assertThat(e.approvalId()).isEqualTo(approvalId);
            });

            JsonNode listed = body(app.operator("GET", "/operator/approvals", sameOperator, null));
            assertThat(listed.path("approvals").toString()).contains(approvalId).contains("analyst-self");
            assertThat(listed.toString()).doesNotContain("777");

            assertThat(app.operator("POST", "/operator/approvals/" + approvalId + "/reject",
                    app.operatorToken("operator-second"), null).statusCode()).isEqualTo(200);
            assertThat(body(app.operator("GET", "/operator/approvals", sameOperator, null)).toString())
                    .doesNotContain(approvalId);
            assertThat(app.auditFor("operator:reject")).singleElement().satisfies(e -> {
                assertThat(e.principalId()).isEqualTo("operator-second");
                assertThat(e.approvalId()).isEqualTo(approvalId);
            });
        }
    }

    @Test
    void anUnknownOrAlreadyDecidedApprovalIsRefusedWithAStableCode() throws Exception {
        String operator = app.operatorToken("operator-unknown");

        HttpResponse<String> unknown = app.operator("POST", "/operator/approvals/does-not-exist/approve",
                operator, null);
        assertThat(unknown.statusCode()).isEqualTo(404);
        assertThat(unknown.body()).isEqualTo("{\"code\":\"APPROVAL_NOT_FOUND\"}");
        assertThat(app.operator("POST", "/operator/approvals/does-not-exist/reject", operator, null)
                .body()).isEqualTo("{\"code\":\"APPROVAL_NOT_FOUND\"}");

        try (McpSyncClient client = app.mcpClient(app.mcpToken("analyst-decided", "CASE-DECIDED"))) {
            String refusal = OperatorHarness.text(app.getEntityContext(client, "888"));
            String approvalId = refusal.substring(refusal.indexOf('=') + 1).trim();
            assertThat(app.operator("POST", "/operator/approvals/" + approvalId + "/approve", operator, null)
                    .statusCode()).isEqualTo(200);
            HttpResponse<String> again = app.operator("POST", "/operator/approvals/" + approvalId + "/approve",
                    operator, null);
            assertThat(again.statusCode()).isEqualTo(409);
            assertThat(again.body()).isEqualTo("{\"code\":\"APPROVAL_NOT_PENDING\"}");
        }
    }

    @Test
    void everyOperatorActionIsAuditedWithTheOperatorsIdentity() throws Exception {
        String operator = app.operatorToken("operator-audited");
        app.audit.clear();

        app.operator("GET", "/operator/state", operator, null);
        app.operator("GET", "/operator/approvals", operator, null);

        assertThat(Stream.of("operator:state", "operator:approvals")).allSatisfy(tool ->
                assertThat(app.auditFor(tool)).singleElement().satisfies(e -> {
                    assertThat(e.principalId()).isEqualTo("operator-audited");
                    assertThat(e.clientId()).isEqualTo("operator-console");
                    assertThat(e.policyDecision()).isEqualTo("ALLOW:READ");
                }));
    }

    @Test
    void reidentificationEndpointsReportTheServiceAsDisabledWhenItIsNotBuilt() throws Exception {
        String operator = app.operatorToken("operator-nore", "requester");

        HttpResponse<String> response = app.operator("GET", "/operator/reidentifications/anything", operator,
                null);

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.body()).isEqualTo("{\"code\":\"REIDENTIFICATION_DISABLED\"}");
    }
}

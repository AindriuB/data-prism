package io.github.aindriub.dataprism.server.operator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.aindriub.dataprism.audit.AuditEvent;
import io.modelcontextprotocol.client.McpSyncClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.http.HttpRequest;
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
            String refusal = OperatorHarness.text(app.getEntityContext(client, "subject-zzqx"));
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
            assertThat(listed.toString()).doesNotContain("zzqx");

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

    // ---- error bodies on the operator port: code only, no path echo ---------------------------------

    private static void assertCodeOnly(HttpResponse<String> response, String what) throws Exception {
        assertThat(response.statusCode()).as(what).isBetween(400, 499);
        JsonNode json = JSON.readTree(response.body());
        assertThat(json.fieldNames()).as(what + " " + response.body()).toIterable().containsExactly("code");
        assertThat(response.body()).as(what).doesNotContain("path").doesNotContain("timestamp")
                .doesNotContain("/operator").doesNotContain("state");
    }

    @Test
    void requestsTheFirewallRejectsGetACodeOnlyBodyOnTheOperatorPort() throws Exception {
        String operator = app.operatorToken("operator-firewall");
        for (String path : new String[] {"/operator//state", "/operator;x=1/state", "/operator/state;x=1",
                "/operator/./state", "/operator/%2e%2e/state", "/operator/%2Fstate", "/operator/state%00"}) {
            for (String token : new String[] {operator, null}) {
                HttpResponse<String> probe = app.operator("GET", path, token, null);
                assertCodeOnly(probe, path + (token == null ? " (anon)" : ""));
            }
        }
    }

    @Test
    void aTrailingDotGetsACodeOnlyBodyOnTheOperatorPort() throws Exception {
        String operator = app.operatorToken("operator-dot");
        for (String path : new String[] {"/operator/state.", "/operator./state", "/operator/state/."}) {
            assertCodeOnly(app.operator("GET", path, operator, null), path);
        }
    }

    @Test
    void forwardedHeadersChangeNeitherWhichSurfaceAPortServesNorTheErrorShape() throws Exception {
        String operator = app.operatorToken("operator-fwd");
        String[][] spoofs = {
                {"X-Forwarded-Port", String.valueOf(app.mcpPort)},
                {"X-Forwarded-Port", String.valueOf(app.operatorPort)},
                {"X-Forwarded-Host", "127.0.0.1:" + app.operatorPort},
                {"X-Forwarded-Host", "127.0.0.1:" + app.mcpPort},
                {"Forwarded", "host=127.0.0.1:" + app.operatorPort}};
        for (String[] spoof : spoofs) {
            String what = spoof[0] + "=" + spoof[1];
            // The operator port keeps serving the operator surface, and keeps refusing the MCP one.
            assertThat(app.raw(true, "GET", "/operator/state", operator, HttpRequest.BodyPublishers.noBody(),
                    spoof).statusCode()).as(what).isEqualTo(200);
            assertCodeOnly(app.raw(true, "GET", "/operator/nothing-here", operator,
                    HttpRequest.BodyPublishers.noBody(), spoof), what);
            assertThat(app.raw(true, "POST", "/mcp", operator, HttpRequest.BodyPublishers.ofString("{}"),
                    spoof).statusCode()).as(what).isEqualTo(404);
            // The MCP port never serves the operator surface, whatever the headers claim.
            assertThat(app.raw(false, "GET", "/operator/state", operator, HttpRequest.BodyPublishers.noBody(),
                    spoof).statusCode()).as(what).isEqualTo(404);
            assertThat(app.raw(false, "GET", "/health", null, HttpRequest.BodyPublishers.noBody(), spoof)
                    .statusCode()).as(what).isEqualTo(200);
        }
    }

    // ---- body limit ----------------------------------------------------------------------------

    private static String oversizedPause() {
        return "{\"target\":\"TOOL\",\"name\":\"" + "a".repeat(20 * 1024) + "\"}";
    }

    @Test
    void aBodyOverTheLimitIsRefusedWhetherOrNotItDeclaresALength() throws Exception {
        String operator = app.operatorToken("operator-big");
        String body = oversizedPause();

        HttpResponse<String> declared = app.raw(true, "POST", "/operator/pause", operator,
                HttpRequest.BodyPublishers.ofString(body));
        HttpResponse<String> chunked = app.raw(true, "POST", "/operator/pause", operator,
                HttpRequest.BodyPublishers.fromPublisher(HttpRequest.BodyPublishers.ofString(body)));

        for (HttpResponse<String> response : List.of(declared, chunked)) {
            assertThat(response.statusCode()).isEqualTo(413);
            assertThat(response.body()).isEqualTo("{\"code\":\"PAYLOAD_TOO_LARGE\"}");
        }
        assertThat(body(app.operator("GET", "/operator/state", operator, null)).path("pausedTools").toString())
                .doesNotContain("aaaa");
    }

    @Test
    void aChunkedBodyWithinTheLimitIsStillServed() throws Exception {
        String operator = app.operatorToken("operator-small");
        String pause = "{\"target\":\"TOOL\",\"name\":\"chunked-tool\"}";

        HttpResponse<String> response = app.raw(true, "POST", "/operator/pause", operator,
                HttpRequest.BodyPublishers.fromPublisher(HttpRequest.BodyPublishers.ofString(pause)));
        try {
            assertThat(response.statusCode()).isEqualTo(200);
        } finally {
            app.operator("POST", "/operator/resume", operator, "{\"target\":\"TOOL\",\"name\":\"chunked-tool\"}");
        }
    }

    // ---- applied but not audited ---------------------------------------------------------------

    @Test
    void aPauseOrRejectThatTookEffectButCouldNotBeAuditedSaysSo() throws Exception {
        String operator = app.operatorToken("operator-appliedaudit");
        try (McpSyncClient client = app.mcpClient(app.mcpToken("analyst-applied", "CASE-APPLIED"))) {
            String refusal = OperatorHarness.text(app.getEntityContext(client, "999"));
            String approvalId = refusal.substring(refusal.indexOf('=') + 1).trim();
            app.failAudit = true;
            try {
                HttpResponse<String> pause = app.operator("POST", "/operator/pause", operator,
                        "{\"target\":\"TOOL\",\"name\":\"applied-tool\"}");
                HttpResponse<String> reject = app.operator("POST", "/operator/approvals/" + approvalId + "/reject",
                        operator, null);
                for (HttpResponse<String> response : List.of(pause, reject)) {
                    assertThat(response.statusCode()).isEqualTo(503);
                    assertThat(response.body()).isEqualTo("{\"code\":\"APPLIED_AUDIT_UNAVAILABLE\"}");
                }
            } finally {
                app.failAudit = false;
            }
            assertThat(body(app.operator("GET", "/operator/state", operator, null)).path("pausedTools")
                    .toString()).contains("applied-tool");
            assertThat(app.operator("POST", "/operator/approvals/" + approvalId + "/approve", operator, null)
                    .body()).isEqualTo("{\"code\":\"APPROVAL_NOT_PENDING\"}");
            app.operator("POST", "/operator/resume", operator, "{\"target\":\"TOOL\",\"name\":\"applied-tool\"}");
        }
    }

    // ---- attempt 3: the MCP port is untouched; operator-port errors are code-only -----------------

    /** A hand-written request line, because the JDK client refuses to send these targets. */
    private static String rawExchange(int port, String requestTarget) throws Exception {
        try (java.net.Socket socket = new java.net.Socket("127.0.0.1", port)) {
            socket.setSoTimeout(5000);
            socket.getOutputStream().write(("GET " + requestTarget + " HTTP/1.1\r\nHost: 127.0.0.1\r\n"
                    + "Connection: close\r\n\r\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            return new String(socket.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    @Test
    void tomcatsOwnErrorPageOnTheMcpPortShowsNoMessageTraceOrServerVersion() throws Exception {
        for (String target : new String[] {"/a{b}", "/mcp%2Fx"}) {
            String response = rawExchange(app.mcpPort, target);
            assertThat(response).as(target).startsWith("HTTP/1.1 400");
            assertThat(response).as(target).doesNotContain("Apache Tomcat").doesNotContain("Exception")
                    .doesNotContain("org.apache").doesNotContain("Message").doesNotContain("Description")
                    .doesNotContain("Invalid").doesNotContain("\tat ").doesNotContain("code");
        }
    }

    @Test
    void theOperatorPortStillAnswersTheSameInputsWithACodeOnly() throws Exception {
        for (String target : new String[] {"/a{b}", "/operator%2Fx"}) {
            String response = rawExchange(app.operatorPort, target);
            assertThat(response).as(target).contains("{\"code\":\"").doesNotContain("Apache Tomcat")
                    .doesNotContain("Exception").doesNotContain("Message");
        }
    }

    @Test
    void theOperatorErrorAdviceIsLimitedToTheOperatorControllers() {
        var advice = org.springframework.web.method.ControllerAdviceBean.findAnnotatedBeans(app.context).stream()
                .filter(bean -> bean.getBeanType() == OperatorErrorAdvice.class).findFirst().orElseThrow();

        assertThat(advice.isApplicableToBeanType(OversightOperatorController.class)).isTrue();
        assertThat(advice.isApplicableToBeanType(ReidentificationOperatorController.class)).isTrue();
        assertThat(advice.isApplicableToBeanType(
                org.springframework.boot.autoconfigure.web.servlet.error.BasicErrorController.class)).isFalse();
        assertThat(advice.isApplicableToBeanType(Object.class)).isFalse();
    }

    @Test
    void unknownPathsAndWrongMethodsOnTheOperatorPortStillGetTheirCodes() throws Exception {
        String operator = app.operatorToken("operator-codes");
        HttpResponse<String> unknown = app.operator("GET", "/operator/nothing-here", operator, null);
        HttpResponse<String> wrongMethod = app.operator("GET", "/operator/pause", operator, null);

        assertThat(unknown.statusCode()).isEqualTo(404);
        assertThat(unknown.body()).isEqualTo("{\"code\":\"NOT_FOUND\"}");
        assertThat(wrongMethod.statusCode()).isEqualTo(405);
        assertThat(wrongMethod.body()).isEqualTo("{\"code\":\"METHOD_NOT_ALLOWED\"}");
    }

    private static HttpResponse<String> htmlGet(int port, String path, String token) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:" + port + path))
                .header("Accept", "text/html");
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return java.net.http.HttpClient.newHttpClient().send(request.GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void anUnauthenticatedOperatorRequestGetsAnUnauthenticatedCode() throws Exception {
        HttpResponse<String> none = app.operator("GET", "/operator/state", null, null);
        HttpResponse<String> wrongAudience = app.operator("GET", "/operator/state",
                app.mcpToken("analyst-401", "CASE-1"), null);

        for (HttpResponse<String> response : List.of(none, wrongAudience)) {
            assertThat(response.statusCode()).isEqualTo(401);
            assertThat(response.body()).isEqualTo("{\"code\":\"UNAUTHENTICATED\"}");
            assertThat(response.headers().firstValue("WWW-Authenticate")).isPresent();
        }
    }

    @Test
    void aTokenWithoutTheOperatorScopeGetsAForbiddenCode() throws Exception {
        String noScope = app.token("operator-noscope", "operator-console", OperatorHarness.OPERATOR_AUDIENCE, null,
                List.of(), "CASE-OPS");

        HttpResponse<String> response = app.operator("GET", "/operator/state", noScope, null);

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.body()).isEqualTo("{\"code\":\"FORBIDDEN\"}");
    }

    @Test
    void anHtmlAcceptHeaderOnTheOperatorPortStillGetsACodeOnlyBody() throws Exception {
        String operator = app.operatorToken("operator-html");

        HttpResponse<String> unknown = htmlGet(app.operatorPort, "/operator/nothing-here", operator);
        HttpResponse<String> unauthenticated = htmlGet(app.operatorPort, "/operator/state", null);
        HttpResponse<String> firewalled = htmlGet(app.operatorPort, "/operator//state", operator);

        assertThat(unknown.statusCode()).isEqualTo(404);
        assertThat(unknown.body()).isEqualTo("{\"code\":\"NOT_FOUND\"}");
        assertThat(unauthenticated.statusCode()).isEqualTo(401);
        assertThat(unauthenticated.body()).isEqualTo("{\"code\":\"UNAUTHENTICATED\"}");
        assertThat(firewalled.statusCode()).isEqualTo(400);
        assertThat(firewalled.body()).isEqualTo("{\"code\":\"INVALID_REQUEST\"}");
    }

    @Test
    void theMcpPortKeepsItsEmptyBodied401And403() throws Exception {
        HttpResponse<String> anonymous = app.mcpPort("POST", "/mcp", null, "{}");
        HttpResponse<String> denied = app.mcpPort("GET", "/nothing-here", app.mcpToken("analyst-403", "CASE-1"),
                null);

        assertThat(anonymous.statusCode()).isEqualTo(401);
        assertThat(anonymous.body()).isEmpty();
        assertThat(denied.statusCode()).isEqualTo(403);
        assertThat(denied.body()).isEmpty();
    }

    @Test
    void aDoubleSlashPathOnTheOperatorPortGetsACodeOnlyBody() throws Exception {
        String operator = app.operatorToken("operator-slash");
        for (String path : new String[] {"/operator//state", "//operator/state", "/operator/approvals//x"}) {
            HttpResponse<String> response = app.operator("GET", path, operator, null);
            assertCodeOnly(response, path);
        }
    }

    @Test
    void aSemicolonParameterPathOnTheOperatorPortGetsACodeOnlyBody() throws Exception {
        String operator = app.operatorToken("operator-semi");
        for (String path : new String[] {"/operator;x=1/state", "/operator/state;jsessionid=1", "/operator/state;"}) {
            HttpResponse<String> response = app.operator("GET", path, operator, null);
            assertCodeOnly(response, path);
        }
    }
}

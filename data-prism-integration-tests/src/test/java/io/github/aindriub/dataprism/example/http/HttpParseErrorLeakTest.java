package io.github.aindriub.dataprism.example.http;

import io.github.aindriub.dataprism.audit.AuditRecorder;
import io.github.aindriub.dataprism.core.metrics.PrivacyMetrics;
import io.github.aindriub.dataprism.core.model.InvestigationContext;
import io.github.aindriub.dataprism.core.model.PrivacyContext;
import io.github.aindriub.dataprism.core.model.PrivacyScopeType;
import io.github.aindriub.dataprism.core.model.PseudonymisationVersion;
import io.github.aindriub.dataprism.mcp.DataPrismMcpServer;
import io.github.aindriub.dataprism.mcp.GetEntityContextTool;
import io.github.aindriub.dataprism.mcp.ToolOptions;
import io.github.aindriub.dataprism.orchestration.ContextOrchestrator;
import io.github.aindriub.dataprism.orchestration.ContextRequest;
import io.github.aindriub.dataprism.orchestration.ContextResponse;
import io.github.aindriub.dataprism.security.AuthenticatedCaller;
import io.github.aindriub.dataprism.security.AuthorizationService;
import io.github.aindriub.dataprism.security.CaseAuthorizer;
import io.github.aindriub.dataprism.security.ClientAllowlist;
import io.github.aindriub.dataprism.security.PurposeValidator;
import io.github.aindriub.dataprism.security.ScopeResolver;
import io.github.aindriub.dataprism.security.SecurityPolicy;
import io.modelcontextprotocol.common.McpTransportContext;
import org.apache.catalina.Context;
import org.apache.catalina.startup.Tomcat;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A malformed inbound message on streamable HTTP must put no client content in a log line at WARN or
 * above or in a response body, and must leave the session usable. The SDK's DEBUG line carrying the
 * raw body is a named residual (task 304), so slf4j-simple's default level here is the INFO the
 * launcher ships.
 */
class HttpParseErrorLeakTest {

    private static final String SENTINEL = "SENTINEL-SUBJECT-7f3a91c2";
    private static final Clock FIXED = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    private static final String INITIALIZE = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":"
            + "{\"protocolVersion\":\"2025-03-26\",\"capabilities\":{},\"clientInfo\":{\"name\":\"leak\","
            + "\"version\":\"1\"}}}";

    @TempDir
    Path tempDir;

    private Tomcat tomcat;

    @AfterEach
    void stop() throws Exception {
        if (tomcat != null) {
            tomcat.stop();
            tomcat.destroy();
        }
    }

    @Test
    @DisplayName("A: an unterminated object holding the sentinel")
    void unterminatedObject() throws Exception {
        refused("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"ping\",\"params\":{\"x\":\"" + SENTINEL, true);
    }

    @Test
    @DisplayName("B: a bare alphanumeric token")
    void bareToken() throws Exception {
        refused("SENTINELSUBJECT7f3a91c2", true);
    }

    @Test
    @DisplayName("C: a bare token with punctuation")
    void bareTokenWithPunctuation() throws Exception {
        refused(SENTINEL, true);
    }

    @Test
    @DisplayName("D: valid JSON that is not a JSON-RPC message")
    void validJsonNotJsonRpc() throws Exception {
        refused("{\"jsonrpc\":\"2.0\",\"subjectId\":\"" + SENTINEL + "\"}", true);
    }

    @Test
    @DisplayName("E: tools/call whose arguments is a string holding the sentinel")
    void toolCallArgumentsString() throws Exception {
        refused("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"get_entity_context\","
                + "\"arguments\":\"" + SENTINEL + "\"}}", false);
    }

    @Test
    @DisplayName("E: initialize whose capabilities is a string holding the sentinel")
    void initializeCapabilitiesString() throws Exception {
        refused("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"initialize\",\"params\":{\"protocolVersion\":"
                + "\"2025-03-26\",\"capabilities\":\"" + SENTINEL + "\",\"clientInfo\":{\"name\":\"leak\","
                + "\"version\":\"1\"}}}", false);
    }

    /**
     * Posts the body to an initialised session, then asserts the refusal, that no response or captured
     * log line holds the sentinel, and that the session still answers {@code tools/list}.
     *
     * @param transportRefusal {@code true} for cases the transport rejects before the server sees them
     *                         (400 and the SDK's {@code Failed to deserialize message} line)
     */
    private void refused(String body, boolean transportRefusal) throws Exception {
        PrintStream originalErr = System.err;
        PrintStream originalOut = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        PrintStream redirect = new PrintStream(captured, true, StandardCharsets.UTF_8);
        System.setErr(redirect);
        System.setOut(redirect);
        HttpResponse<String> refusal;
        HttpResponse<String> list;
        try {
            int port = start();
            HttpClient http = HttpClient.newBuilder().sslContext(explicitSslContext()).build();
            URI uri = URI.create("http://localhost:" + port + "/mcp");
            HttpResponse<String> init = post(http, uri, null, INITIALIZE);
            assertThat(init.statusCode()).isEqualTo(200);
            String session = init.headers().firstValue("Mcp-Session-Id").orElseThrow();
            post(http, uri, session, "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");

            refusal = post(http, uri, session, body);
            list = post(http, uri, session, "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/list\"}");
        } finally {
            System.setErr(originalErr);
            System.setOut(originalOut);
        }
        String log = captured.toString(StandardCharsets.UTF_8);

        if (transportRefusal) {
            assertThat(refusal.statusCode()).isEqualTo(400);
            assertThat(log).contains("Failed to deserialize message");
        }
        assertThat(refusal.body()).contains("\"code\":-32");
        assertThat(list.statusCode()).isEqualTo(200);
        assertThat(list.body()).contains("get_entity_context");
        for (String text : new String[] {refusal.body(), list.body(), log}) {
            assertThat(text).doesNotContain(SENTINEL).doesNotContain("SENTINELSUBJECT");
        }
    }

    private static HttpResponse<String> post(HttpClient http, URI uri, String session, String body)
            throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (session != null) {
            request.header("Mcp-Session-Id", session).header("MCP-Protocol-Version", "2025-03-26");
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private int start() throws Exception {
        // See McpHttpEndToEndTest for why this is forced before Tomcat's webapp classloader exists.
        reactor.core.scheduler.Schedulers.boundedElastic().schedule(() -> { });
        ContextOrchestrator orchestrator = new ContextOrchestrator() {
            @Override
            public ContextResponse buildContext(ContextRequest request, PrivacyContext privacy,
                                                InvestigationContext caller) {
                throw new AssertionError("a malformed message never reaches the orchestrator");
            }
        };
        SecurityPolicy security = new SecurityPolicy(Set.of("demonstration"),
                Map.of("investigator", Set.of("GET_ENTITY_CONTEXT")));
        AuthorizationService authz = new AuthorizationService(security, "DEFAULT", PrivacyScopeType.INVESTIGATION,
                ClientAllowlist.unrestricted());
        ScopeResolver scopes = new ScopeResolver(
                PseudonymisationVersion.HMAC_SHA256_V1.withKey("key-1").withVocabulary("vocab-1"),
                Duration.ofHours(8), new PurposeValidator(Set.of("demonstration")), CaseAuthorizer.tokenTrusted());
        AuditRecorder audit = new AuditRecorder(event -> { }, FIXED, "test-mcp");

        DataPrismMcpServer.HttpTransport transport = DataPrismMcpServer.streamableHttp(orchestrator, authz, scopes,
                request -> McpTransportContext.create(Map.of(GetEntityContextTool.TRANSPORT_CONTEXT_CALLER_KEY,
                        new AuthenticatedCaller("principal-1", "client-1", Set.of("investigator"), "demonstration",
                                "case-1", null))),
                "/mcp", PrivacyMetrics.none(), audit, FIXED, ToolOptions.defaults().noAdmission().build());

        tomcat = new Tomcat();
        tomcat.setBaseDir(tempDir.toString());
        tomcat.setPort(0);
        tomcat.getConnector();
        Context context = tomcat.addContext("", tempDir.toString());
        Tomcat.addServlet(context, "mcp", transport.transportProvider()).setAsyncSupported(true);
        context.addServletMappingDecoded("/mcp", "mcp");
        tomcat.start();
        return tomcat.getConnector().getLocalPort();
    }

    /**
     * An explicit SSLContext: {@code HttpClient.newHttpClient()} would initialise the JDK default one, and
     * McpHttpEndToEndTest's trust settings must reach it first. See AuditSinkFailureAbortsResponseTest.
     */
    private static javax.net.ssl.SSLContext explicitSslContext() throws Exception {
        javax.net.ssl.TrustManagerFactory trust = javax.net.ssl.TrustManagerFactory
                .getInstance(javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm());
        trust.init((java.security.KeyStore) null);
        javax.net.ssl.SSLContext ssl = javax.net.ssl.SSLContext.getInstance("TLS");
        ssl.init(null, trust.getTrustManagers(), null);
        return ssl;
    }
}

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
    @DisplayName("F: a method that is an object")
    void methodWrongType() throws Exception {
        refused("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":{\"a\":\"" + SENTINEL + "\"}}", true);
    }

    @Test
    @DisplayName("F: an error whose code is a string")
    void errorCodeWrongType() throws Exception {
        refused("{\"jsonrpc\":\"2.0\",\"id\":2,\"error\":{\"code\":\"" + SENTINEL + "\",\"message\":\"m\"}}", true);
    }

    @Test
    @DisplayName("F: an error that is a string")
    void errorWrongType() throws Exception {
        refused("{\"jsonrpc\":\"2.0\",\"id\":2,\"error\":\"" + SENTINEL + "\"}", true);
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

    private static final String CASE_E = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\","
            + "\"params\":{\"name\":\"get_entity_context\",\"arguments\":\"" + SENTINEL + "\"}}";
    private static final String TOOLS_LIST = "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/list\"}";

    @Test
    @DisplayName("E: a type error closes the connection once, declared, and the session answers on a new one")
    void typeErrorClosesCleanlyAndSessionSurvives() throws Exception {
        int port = start();
        String session;
        try (java.net.Socket socket = new java.net.Socket("127.0.0.1", port)) {
            socket.setSoTimeout(30_000);
            java.io.OutputStream out = socket.getOutputStream();
            java.io.InputStream in = new java.io.BufferedInputStream(socket.getInputStream());

            String[] init = rawPost(out, in, null, INITIALIZE);
            assertThat(init[0]).isEqualTo("200");
            session = init[1];
            assertThat(rawPost(out, in, session, "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}")[0])
                    .isEqualTo("202");
            String[] bad = rawPost(out, in, session, CASE_E);
            assertThat(bad[0]).isEqualTo("400");
            assertThat(bad[2]).contains("\"jsonrpc\":\"2.0\"").contains("\"id\":2").contains("\"code\":-32602");
            // Exactly one Connection header, close, and no keep-alive token anywhere in the headers.
            assertThat(bad[3].toLowerCase(java.util.Locale.ROOT).lines()
                    .filter(h -> h.startsWith("connection:")).toList()).containsExactly("connection: close");
            assertThat(bad[3].toLowerCase(java.util.Locale.ROOT)).doesNotContain("keep-alive");
        }
        try (java.net.Socket socket = new java.net.Socket("127.0.0.1", port)) {
            socket.setSoTimeout(30_000);
            String[] list = rawPost(socket.getOutputStream(), new java.io.BufferedInputStream(socket.getInputStream()),
                    session, TOOLS_LIST);
            assertThat(list[0]).isEqualTo("200");
            assertThat(list[2]).contains("get_entity_context");
        }
    }

    @Test
    @DisplayName("E: a pooled HTTP/1.1 client reuses nothing dead after a type error")
    void pooledClientSucceedsAfterTypeError() throws Exception {
        int port = start();
        HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .sslContext(explicitSslContext()).build();
        URI uri = URI.create("http://127.0.0.1:" + port + "/mcp");
        HttpResponse<String> init = post(http, uri, null, INITIALIZE);
        assertThat(init.statusCode()).isEqualTo(200);
        String session = init.headers().firstValue("Mcp-Session-Id").orElseThrow();
        post(http, uri, session, "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");

        HttpResponse<String> bad = post(http, uri, session, CASE_E);
        assertThat(bad.statusCode()).isEqualTo(400);
        assertThat(bad.headers().allValues("Connection")).doesNotContain("keep-alive");
        HttpResponse<String> list = post(http, uri, session, TOOLS_LIST);
        assertThat(list.statusCode()).isEqualTo(200);
        assertThat(list.body()).contains("get_entity_context");
    }

    /** One HTTP/1.1 request on an open socket; returns {status, Mcp-Session-Id or null, body, raw headers}. */
    private static String[] rawPost(java.io.OutputStream out, java.io.InputStream in, String session, String body)
            throws Exception {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        StringBuilder head = new StringBuilder("POST /mcp HTTP/1.1\r\nHost: 127.0.0.1\r\n"
                + "Content-Type: application/json\r\nAccept: application/json, text/event-stream\r\n"
                + "Content-Length: " + bytes.length + "\r\n");
        if (session != null) {
            head.append("Mcp-Session-Id: ").append(session).append("\r\nMCP-Protocol-Version: 2025-03-26\r\n");
        }
        out.write(head.append("\r\n").toString().getBytes(StandardCharsets.US_ASCII));
        out.write(bytes);
        out.flush();

        String status = line(in);
        if (status == null) {
            throw new java.io.EOFException("the server closed the connection before answering");
        }
        String sessionId = null;
        StringBuilder headers = new StringBuilder();
        int length = -1;
        boolean chunked = false;
        for (String header = line(in); header != null && !header.isEmpty(); header = line(in)) {
            headers.append(header).append('\n');
            String lower = header.toLowerCase(java.util.Locale.ROOT);
            if (lower.startsWith("mcp-session-id:")) {
                sessionId = header.substring(header.indexOf(':') + 1).trim();
            } else if (lower.startsWith("content-length:")) {
                length = Integer.parseInt(header.substring(header.indexOf(':') + 1).trim());
            } else if (lower.startsWith("transfer-encoding:") && lower.contains("chunked")) {
                chunked = true;
            }
        }
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        if (chunked) {
            for (int size = Integer.parseInt(line(in).trim(), 16); size > 0;
                    size = Integer.parseInt(line(in).trim(), 16)) {
                payload.write(in.readNBytes(size));
                line(in);
            }
            line(in);
        } else if (length > 0) {
            payload.write(in.readNBytes(length));
        }
        return new String[] {status.split(" ")[1], sessionId, payload.toString(StandardCharsets.UTF_8), headers.toString()};
    }

    private static String line(java.io.InputStream in) throws java.io.IOException {
        StringBuilder text = new StringBuilder();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') {
                return text.toString().replace("\r", "");
            }
            text.append((char) c);
        }
        return text.length() == 0 ? null : text.toString();
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

        assertThat(refusal.statusCode()).isEqualTo(400);
        assertThat(refusal.body()).contains("\"jsonrpc\":\"2.0\"").containsPattern("\"code\":-32\\d+");
        if (transportRefusal) {
            assertThat(log).contains("Failed to deserialize message");
            assertThat(refusal.body()).containsPattern("\"id\":null");
        } else {
            // Refused by the inbound guard before the SDK is reached.
            assertThat(refusal.body()).contains("\"id\":2").contains("\"code\":-32602");
            assertThat(log).doesNotContain("asyncComplete()").doesNotContain("\tat ");
        }
        for (String forbidden : new String[] {"stackTrace", "\"cause\"", "io.modelcontextprotocol.", "org.apache.",
                "java.", "jdk."}) {
            assertThat(refusal.body()).doesNotContain(forbidden);
        }
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
        Tomcat.addServlet(context, "mcp", transport.servlet()).setAsyncSupported(true);
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

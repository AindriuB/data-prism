package io.github.aindriub.dataprism.example.http;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import io.github.aindriub.dataprism.audit.AuditEvent;
import io.github.aindriub.dataprism.audit.AuditSink;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Drives the real HTTP endpoint: a JVM-bound Tomcat, a bearer JWT minted
 * locally against an in-test JWKS, and the MCP SDK's own client transport —
 * not a synthetic {@code McpSyncServerExchange}. Task 06 built the streamable
 * HTTP transport and its {@code contextExtractor} hook; this is the first test
 * that starts a server and opens a socket against it, which is what proves the
 * hook actually delivers an authenticated caller into
 * {@code exchange.transportContext()} on a real request.
 *
 * <p>Every assertion about who the pipeline thinks is calling reads it from the
 * orchestrator's own audit trail — the {@code instanceId="example-1"} events
 * {@code DataPrismAssembly} wires internally — rather than from the response,
 * because {@link io.github.aindriub.dataprism.audit.AuditEvent} carries the
 * resolved {@code PrivacyContext}'s scope, purpose and case, and
 * {@code InvestigationContext}'s principal, verbatim. An {@code AuditSink}
 * substituted for the whole test class is the test double the refusal cases
 * read: no {@code example-1} event for a request means the orchestrator was
 * never reached, which is a stronger claim than "the response was an error".
 */
class McpHttpEndToEndTest {

    private static final String ISSUER = "data-prism-example";
    private static final String AUDIENCE = "data-prism-mcp";
    private static final String CONFIGURED_PURPOSE = "investigation";
    private static final String PRINCIPAL = "jwt-principal-8b2f";
    private static final String CLIENT_ID = "jwt-client-4d1a";
    private static final String CASE_ID = "CASE-JWT-771";
    private static final String OTHER_CASE_ID = "CASE-JWT-772";

    private static HttpServer jwksServer;
    private static RSAKey signingKey;
    private static List<AuditEvent> auditEvents;
    private static ConfigurableApplicationContext context;
    private static int port;

    @BeforeAll
    static void startJwksAndApplication() throws Exception {
        signingKey = new RSAKeyGenerator(2048).keyID("test-signing-key-1").algorithm(JWSAlgorithm.RS256).generate();
        byte[] jwksBody = new JWKSet(signingKey.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);

        jwksServer = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        jwksServer.createContext("/jwks", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, jwksBody.length);
            try (var out = exchange.getResponseBody()) {
                out.write(jwksBody);
            }
        });
        jwksServer.start();
        int jwksPort = jwksServer.getAddress().getPort();

        // Reactor's shared Schedulers.boundedElastic() is a JVM-wide singleton,
        // created lazily on first use. Forcing that creation here, before
        // Tomcat's webapp classloader exists, means its worker and evictor
        // threads inherit this class's (neutral) context classloader instead of
        // the webapp's — otherwise Tomcat's own leak detector reports them as
        // an unstopped thread when the context closes, because the MCP SDK's
        // server-side reactive pipeline is what first touches this scheduler,
        // from a request thread running under the webapp's classloader.
        reactor.core.scheduler.Schedulers.boundedElastic().schedule(() -> { });

        auditEvents = new CopyOnWriteArrayList<>();
        AuditSink recordingSink = auditEvents::add;

        context = new SpringApplicationBuilder(ResourceServerApplication.class)
                .web(WebApplicationType.SERVLET)
                .initializers(applicationContext -> applicationContext.getBeanFactory()
                        .registerSingleton("recordingAuditSink", recordingSink))
                // Command-line args, not .properties(...): SpringApplicationBuilder's
                // properties() lands as *default* properties, lower priority than
                // application.yaml, so it can never override the placeholder
                // jwk-set-uri already set there.
                .run(
                        "--server.port=0",
                        "--spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:" + jwksPort
                                + "/jwks");
        port = ((ServletWebServerApplicationContext) context).getWebServer().getPort();
    }

    @AfterAll
    static void stopJwksAndApplication() {
        context.close();
        jwksServer.stop(0);

        // The acceptance bar task 06 was already held to, checked again here:
        // closing the context must actually have released the ephemeral port
        // rather than merely stopping accepting new MCP sessions.
        assertThatThrownBy(() -> new java.net.Socket("localhost", port))
                .isInstanceOf(java.io.IOException.class);
    }

    @AfterEach
    void clearAudit() {
        auditEvents.clear();
    }

    private static String mintToken(Set<String> roles, String purpose, String caseId) throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(PRINCIPAL)
                .claim("azp", CLIENT_ID)
                .claim("roles", List.copyOf(roles))
                .claim("purpose", purpose)
                .claim("case_id", caseId)
                .issuer(ISSUER)
                .audience(AUDIENCE)
                .expirationTime(Date.from(Instant.now().plus(10, ChronoUnit.MINUTES)))
                .build();
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(signingKey.getKeyID()).build(), claims);
        jwt.sign(new RSASSASigner(signingKey));
        return jwt.serialize();
    }

    private static McpSyncClient clientWithToken(String token) {
        HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport
                .builder("http://localhost:" + port)
                .endpoint("/mcp")
                .requestBuilder(HttpRequest.newBuilder().header("Authorization", "Bearer " + token))
                .build();
        McpSyncClient client = McpClient.sync(transport)
                .clientInfo(new McpSchema.Implementation("mcp-http-end-to-end-test", "1.0.0"))
                .build();
        client.initialize();
        return client;
    }

    private static McpSchema.CallToolResult callGetEntityContext(McpSyncClient client, String subjectId) {
        return client.callTool(new McpSchema.CallToolRequest(
                "get_entity_context", Map.of("entityType", "CUSTOMER", "subjectId", subjectId)));
    }

    private static String text(McpSchema.CallToolResult result) {
        return ((McpSchema.TextContent) result.content().get(0)).text();
    }

    private static AuditEvent orchestratorEvent() {
        return auditEvents.stream()
                .filter(e -> "example-1".equals(e.instanceId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("orchestrator produced no audit event"));
    }

    @Test
    @DisplayName("the health probe answers without a token")
    void healthProbeIsUnauthenticated() throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/health")).GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("UP");
    }

    @Test
    @DisplayName("a request with no token is refused with 401 and never reaches the orchestrator")
    void mcpEndpointRejectsAnUnauthenticatedRequest() throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/mcp"))
                        .header("Content-Type", "application/json")
                        .header("Accept", "application/json, text/event-stream")
                        .POST(HttpRequest.BodyPublishers.ofString("{}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(auditEvents).noneMatch(e -> "example-1".equals(e.instanceId()));
    }

    @Test
    @DisplayName("a valid token with a permitted purpose reaches the orchestrator with the token's own values")
    void validTokenCarriesItsOwnValuesThroughToThePrivacyContext() throws Exception {
        String token = mintToken(Set.of("investigator"), CONFIGURED_PURPOSE, CASE_ID);
        McpSyncClient client = clientWithToken(token);
        try {
            McpSchema.CallToolResult result = callGetEntityContext(client, "123");

            assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);

            AuditEvent event = orchestratorEvent();
            // Every one of these is a value that exists nowhere but this
            // token: none of them is stdio's development constant, and none
            // is a fixed literal anywhere in McpAssemblyConfig.
            assertThat(event.principalId()).isEqualTo(PRINCIPAL);
            assertThat(event.clientId()).isEqualTo(CLIENT_ID);
            assertThat(event.purpose()).isEqualTo(CONFIGURED_PURPOSE);
            assertThat(event.caseId()).isEqualTo(CASE_ID);
            assertThat(event.scopeId()).isEqualTo("case:" + CASE_ID);
        } finally {
            client.closeGracefully();
        }
    }

    @Test
    @DisplayName("a purpose absent from the configured list is refused and never reaches the orchestrator")
    void unknownPurposeIsRefusedBeforeTheOrchestrator() throws Exception {
        String token = mintToken(Set.of("investigator"), "not-a-configured-purpose", CASE_ID);
        McpSyncClient client = clientWithToken(token);
        try {
            McpSchema.CallToolResult result = callGetEntityContext(client, "123");

            assertThat(result.isError()).isEqualTo(Boolean.TRUE);
            assertThat(text(result)).contains("UNKNOWN_PURPOSE");
            assertThat(auditEvents).noneMatch(e -> "example-1".equals(e.instanceId()));
        } finally {
            client.closeGracefully();
        }
    }

    @Test
    @DisplayName("two tokens with different case_id claims give the same subject different pseudonyms")
    void differentCaseIdsIsolateThePseudonym() throws Exception {
        String firstToken = mintToken(Set.of("investigator"), CONFIGURED_PURPOSE, CASE_ID);
        String secondToken = mintToken(Set.of("investigator"), CONFIGURED_PURPOSE, OTHER_CASE_ID);

        McpSyncClient first = clientWithToken(firstToken);
        McpSyncClient second = clientWithToken(secondToken);
        try {
            String firstBody = text(callGetEntityContext(first, "123"));
            String secondBody = text(callGetEntityContext(second, "123"));

            assertThat(firstBody).isNotEqualTo(secondBody);
        } finally {
            first.closeGracefully();
            second.closeGracefully();
        }
    }

    @Test
    @DisplayName("a caller without EXPOSE_SOURCE_NAMES sees aliased source names")
    void callerWithoutExposeCapabilitySeesAliasedSourceNames() throws Exception {
        String token = mintToken(Set.of("investigator"), CONFIGURED_PURPOSE, CASE_ID);
        McpSyncClient client = clientWithToken(token);
        try {
            String body = text(callGetEntityContext(client, "123"));

            assertThat(body).doesNotContain("customer-api").doesNotContain("account-api")
                    .doesNotContain("order-api");
        } finally {
            client.closeGracefully();
        }
    }

    @Test
    @DisplayName("a caller with EXPOSE_SOURCE_NAMES sees real source names")
    void callerWithExposeCapabilitySeesRealSourceNames() throws Exception {
        String token = mintToken(Set.of("privileged-investigator"), CONFIGURED_PURPOSE, CASE_ID);
        McpSyncClient client = clientWithToken(token);
        try {
            String body = text(callGetEntityContext(client, "123"));

            assertThat(body).contains("customer-api");
        } finally {
            client.closeGracefully();
        }
    }
}

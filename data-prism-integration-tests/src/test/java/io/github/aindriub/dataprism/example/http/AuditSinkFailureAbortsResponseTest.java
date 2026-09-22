package io.github.aindriub.dataprism.example.http;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.github.aindriub.dataprism.audit.AuditRecorder;
import io.github.aindriub.dataprism.audit.AuditSink;
import io.github.aindriub.dataprism.core.PrivacyMetrics;
import io.github.aindriub.dataprism.core.PrivacyScopeType;
import io.github.aindriub.dataprism.example.DataPrismAssembly;
import io.github.aindriub.dataprism.mcp.DataPrismObjectMapper;
import io.github.aindriub.dataprism.mcp.GetEntityContextTool;
import io.github.aindriub.dataprism.security.AuthenticatedCaller;
import io.github.aindriub.dataprism.security.AuthorizationService;
import io.github.aindriub.dataprism.security.PurposeValidator;
import io.github.aindriub.dataprism.security.ScopeResolver;
import io.github.aindriub.dataprism.security.SecurityPolicy;
import io.github.aindriub.dataprism.spring.boot.HmacKeyReferenceResolver;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpAsyncServerExchange;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import java.net.http.HttpRequest;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins the fail-closed guarantee task 63's goal names: {@code
 * GetEntityContextTool} wraps no call to {@code AuditRecorder#record} in a
 * {@code catch} that swallows the failure, so an {@code AuditSink} that throws
 * aborts the response rather than letting it through unaudited. Boots the real
 * application — {@link ResourceServerApplication}, the same embedded shape
 * {@code McpHttpEndToEndTest} drives — with a servlet-mode, JWT-authenticated
 * transport and an {@code AuditSink} bean that always throws, then drives the
 * real MCP client transport against it. Nothing here is a synthetic {@code
 * McpSyncServerExchange}: the exception has to survive the real HTTP
 * round-trip to be observed at all.
 *
 * <p>Unlike {@code McpHttpEndToEndTest}, the JWT is verified against an
 * in-process public key — a {@link JwtDecoder} bean substituted in over
 * {@code SecurityConfig}'s own {@code @ConditionalOnMissingBean} one — rather
 * than a real JWKS HTTP fetch: this test's only concern is what happens once a
 * caller is authenticated, not proving the transport's JWKS-fetching path
 * again. That also sidesteps a real hazard the JWKS-server shape would add: the
 * JDK's default {@code SSLContext} is a JVM-wide singleton, initialised once
 * from whichever test sets {@code javax.net.ssl.trustStore} first — a second
 * test class in the same forked JVM minting its own self-signed JWKS
 * certificate would corrupt whichever test won that race for the rest of the
 * fork's lifetime.
 *
 * <p>The path exercised is {@code deny()}, not the {@code ALLOW} path: a
 * caller authenticated with a purpose absent from the configured policy is
 * denied before the orchestrator is reached, and {@code
 * GetEntityContextTool#deny} calls {@code audit.record(...)} with nothing
 * wrapping it — precisely the call site task 63's context names as the
 * behaviour to pin rather than soften.
 */
class AuditSinkFailureAbortsResponseTest {

    private static final String ISSUER = "data-prism-example";
    private static final String AUDIENCE = "data-prism-mcp";
    private static final String PRINCIPAL = "jwt-principal-audit-failure";
    private static final String CLIENT_ID = "jwt-client-audit-failure";
    private static final String CASE_ID = "CASE-AUDIT-FAILURE-1";
    private static final String UNCONFIGURED_PURPOSE = "not-a-configured-purpose";

    private static RSAKey signingKey;
    private static ConfigurableApplicationContext context;
    private static int port;

    @BeforeAll
    static void startApplication() throws Exception {
        signingKey = new RSAKeyGenerator(2048).keyID("test-signing-key-1").algorithm(JWSAlgorithm.RS256).generate();

        // See McpHttpEndToEndTest for why this is forced before Tomcat's webapp
        // classloader exists.
        reactor.core.scheduler.Schedulers.boundedElastic().schedule(() -> { });

        AuditSink throwingSink = event -> {
            throw new IllegalStateException("simulated sink failure, for this test only");
        };
        HmacKeyReferenceResolver testKeys = (keyId, reference) ->
                "task-63-test-only-key-material-longer-than-thirty-two-bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        JwtDecoder testDecoder = NimbusJwtDecoder.withPublicKey((RSAPublicKey) signingKey.toRSAPublicKey()).build();

        context = new SpringApplicationBuilder(ResourceServerApplication.class)
                .web(WebApplicationType.SERVLET)
                .initializers(applicationContext -> {
                    applicationContext.getBeanFactory().registerSingleton("throwingAuditSink", throwingSink);
                    applicationContext.getBeanFactory().registerSingleton("testHmacKeyReferenceResolver", testKeys);
                    applicationContext.getBeanFactory().registerSingleton("testJwtDecoder", testDecoder);
                })
                .run("--server.port=0");
        port = ((ServletWebServerApplicationContext) context).getWebServer().getPort();
    }

    @AfterAll
    static void stopApplication() {
        context.close();
    }

    private static String mintToken(String purpose) throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(PRINCIPAL)
                .claim("azp", CLIENT_ID)
                .claim("roles", List.of("investigator"))
                .claim("purpose", purpose)
                .claim("case_id", CASE_ID)
                .issuer(ISSUER)
                .audience(AUDIENCE)
                .expirationTime(Date.from(Instant.now().plus(10, ChronoUnit.MINUTES)))
                .build();
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(signingKey.getKeyID()).build(), claims);
        jwt.sign(new RSASSASigner(signingKey));
        return jwt.serialize();
    }

    private static McpSyncClient clientWithToken(String token) throws Exception {
        // An explicit SSLContext, never java.net.http.HttpClient's own implicit
        // SSLContext.getDefault(): that JDK default is a JVM-wide singleton,
        // initialised once (from the platform's own cacerts, here) and then
        // fixed for the rest of this test fork's lifetime. This connection is
        // plain HTTP and never needs TLS at all, but building the default
        // HttpClient still resolves it eagerly — and a later test in the same
        // fork that mints its own self-signed HTTPS server (McpHttpEndToEndTest)
        // would find the cached default no longer trusts its certificate.
        javax.net.ssl.TrustManagerFactory trustManagers =
                javax.net.ssl.TrustManagerFactory.getInstance(javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm());
        trustManagers.init((java.security.KeyStore) null);
        javax.net.ssl.SSLContext explicitDefault = javax.net.ssl.SSLContext.getInstance("TLS");
        explicitDefault.init(null, trustManagers.getTrustManagers(), null);

        HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport
                .builder("http://localhost:" + port)
                .endpoint("/mcp")
                .clientBuilder(java.net.http.HttpClient.newBuilder().sslContext(explicitDefault))
                .requestBuilder(HttpRequest.newBuilder().header("Authorization", "Bearer " + token))
                .build();
        McpSyncClient client = McpClient.sync(transport)
                .clientInfo(new McpSchema.Implementation("audit-sink-failure-test", "1.0.0"))
                .build();
        client.initialize();
        return client;
    }

    @Test
    @DisplayName("a denial that must be audited fails the call rather than returning a result, when the sink throws")
    void auditSinkFailureAbortsTheResponse() throws Exception {
        String token = mintToken(UNCONFIGURED_PURPOSE);
        McpSyncClient client = clientWithToken(token);
        try {
            assertThatThrownBy(() -> client.callTool(new McpSchema.CallToolRequest(
                    "get_entity_context", Map.of("entityType", "CUSTOMER", "subjectId", "123"))))
                    .as("the sink's failure must surface as a call failure, never a successful result "
                            + "carrying an unaudited decision")
                    .isInstanceOf(McpError.class)
                    .hasMessageContaining("simulated sink failure, for this test only");
        } finally {
            client.closeGracefully();
        }
    }

    /**
     * The companion proof that {@link #auditSinkFailureAbortsTheResponse()} is
     * not vacuous: the exact same denial — same tool, same missing purpose,
     * same {@code deny()} call site — against a sink that records instead of
     * throwing comes back as an ordinary, non-throwing error result. Without
     * this, a change that broke the tool for every call — not only an audit
     * failure — would still turn the test above green for the wrong reason.
     *
     * <p>Deliberately not a second full application boot: {@code
     * GetEntityContextTool}'s own call handler, driven directly with a real
     * {@link McpSyncServerExchange} (the same shape {@code EndToEndTest} and
     * {@code PiiLogScanTest} already use), is enough to prove this half of the
     * pair — the part under test here is the tool's own {@code catch}-free call
     * to {@code audit.record(...)}, not the HTTP transport a second time.
     */
    @Test
    @DisplayName("the same denial, against a sink that does not throw, is an ordinary error result rather than a thrown exception")
    void sameDenialWithoutAFailingSinkIsAnOrdinaryErrorResult() {
        Clock clock = Clock.fixed(Instant.parse("2026-09-22T12:00:00Z"), java.time.ZoneOffset.UTC);
        String purpose = "demonstration";
        String role = "investigator";

        List<Object> recorded = new ArrayList<>();
        AuditSink recordingSink = recorded::add;
        DataPrismAssembly assembly = DataPrismAssembly.standard();
        AuditRecorder toolAudit = new AuditRecorder(recordingSink, clock, "audit-sink-failure-test-control");
        SecurityPolicy policy = new SecurityPolicy(Set.of(purpose),
                Map.of(role, Set.of(io.github.aindriub.dataprism.core.Capability.GET_ENTITY_CONTEXT)));
        AuthorizationService authorizationService =
                new AuthorizationService(policy, "DEFAULT", PrivacyScopeType.INVESTIGATION);
        ScopeResolver scopeResolver = new ScopeResolver(assembly.pseudonymisationVersion(), Duration.ofHours(8),
                new PurposeValidator(Set.of(purpose)));
        GetEntityContextTool tool = new GetEntityContextTool(assembly.orchestrator(), authorizationService,
                scopeResolver, DataPrismObjectMapper.create(), PrivacyMetrics.none(), toolAudit, clock);

        AuthenticatedCaller caller = new AuthenticatedCaller(
                PRINCIPAL, CLIENT_ID, Set.of(role), UNCONFIGURED_PURPOSE, CASE_ID, null);
        McpSyncServerExchange exchange = new McpSyncServerExchange(new McpAsyncServerExchange(
                "audit-sink-failure-test-control-session", null, null, null,
                McpTransportContext.create(Map.of(GetEntityContextTool.TRANSPORT_CONTEXT_CALLER_KEY, caller))));

        McpSchema.CallToolResult result = tool.specification().callHandler().apply(exchange,
                new McpSchema.CallToolRequest(GetEntityContextTool.NAME,
                        Map.of("entityType", "CUSTOMER", "subjectId", "123")));

        assertThat(result.isError()).isEqualTo(Boolean.TRUE);
        assertThat(recorded).isNotEmpty();
    }
}

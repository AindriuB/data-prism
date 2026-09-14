package io.github.aindriub.dataprism.server;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import io.github.aindriub.dataprism.audit.AuditSink;
import io.github.aindriub.dataprism.core.DataRequest;
import io.github.aindriub.dataprism.core.DataSourceAdapter;
import io.github.aindriub.dataprism.core.PassThroughIdentityResolver;
import io.github.aindriub.dataprism.core.PrivacyMetrics;
import io.github.aindriub.dataprism.mcp.GetEntityContextTool;
import io.github.aindriub.dataprism.security.AuthenticatedCaller;
import io.github.aindriub.dataprism.spring.boot.DataPrismConfigurationException;
import io.github.aindriub.dataprism.spring.boot.DataPrismProperties;
import io.github.aindriub.dataprism.spring.boot.HmacKeyReferenceResolver;
import io.modelcontextprotocol.common.McpTransportContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Non-vacuous proof of the security code owned by the standalone distribution. */
class ServerSecurityBoundaryTest {
    private static final String ISSUER = "https://issuer.example";
    private static final String AUDIENCE = "data-prism-mcp";
    private static final String CUSTOM_MCP_PATH = "/private-mcp";
    private static final String STORE_PASSWORD = "task-17-test-only";
    private static final Instant VALID_NOT_BEFORE = Instant.parse("2020-01-01T00:00:00Z");
    private static final Instant VALID_EXPIRES_AT = Instant.parse("2100-01-01T00:00:00Z");
    private static final Instant EXPIRED_NOT_BEFORE = Instant.parse("2000-01-01T00:00:00Z");
    private static final Instant EXPIRED_AT = Instant.parse("2001-01-01T00:00:00Z");
    private static final Instant FUTURE_NOT_BEFORE = Instant.parse("2200-01-01T00:00:00Z");
    private static final Instant FUTURE_EXPIRES_AT = Instant.parse("2201-01-01T00:00:00Z");

    @TempDir
    static Path tempDir;

    private static RSAKey signingKey;
    private static RSAKey wrongKey;
    private static HttpsServer identityServer;
    private static String identityBase;
    private static ConfigurableApplicationContext application;
    private static int applicationPort;
    private static String previousTrustStore;
    private static String previousTrustStorePassword;
    private static String previousTrustStoreType;
    private static SSLContext previousDefaultSslContext;
    private static SSLSocketFactory previousDefaultSocketFactory;
    private static final AtomicInteger exactMetadataRequests = new AtomicInteger();
    private static final AtomicInteger exactJwksRequests = new AtomicInteger();

    @BeforeAll
    static void startIdentityAndApplication() throws Exception {
        signingKey = new RSAKeyGenerator(2048).keyID("task-17-signing-key")
                .algorithm(JWSAlgorithm.RS256).generate();
        wrongKey = new RSAKeyGenerator(2048).keyID(signingKey.getKeyID())
                .algorithm(JWSAlgorithm.RS256).generate();

        Path keyStore = tempDir.resolve("identity-server.p12");
        Path trustStore = tempDir.resolve("identity-trust.p12");
        Path certificate = tempDir.resolve("identity-server.cer");
        keytool("-genkeypair", "-alias", "identity", "-keyalg", "RSA", "-keysize", "2048",
                "-validity", "2", "-keystore", keyStore.toString(), "-storetype", "PKCS12",
                "-storepass", STORE_PASSWORD, "-keypass", STORE_PASSWORD,
                "-dname", "CN=127.0.0.1", "-ext", "san=ip:127.0.0.1");
        keytool("-exportcert", "-alias", "identity", "-keystore", keyStore.toString(),
                "-storetype", "PKCS12", "-storepass", STORE_PASSWORD, "-file", certificate.toString());
        keytool("-importcert", "-alias", "identity", "-file", certificate.toString(),
                "-keystore", trustStore.toString(), "-storetype", "PKCS12",
                "-storepass", STORE_PASSWORD, "-noprompt");

        previousTrustStore = System.getProperty("javax.net.ssl.trustStore");
        previousTrustStorePassword = System.getProperty("javax.net.ssl.trustStorePassword");
        previousTrustStoreType = System.getProperty("javax.net.ssl.trustStoreType");
        System.setProperty("javax.net.ssl.trustStore", trustStore.toString());
        System.setProperty("javax.net.ssl.trustStorePassword", STORE_PASSWORD);
        System.setProperty("javax.net.ssl.trustStoreType", "PKCS12");

        SSLContext tls = SSLContext.getInstance("TLS");
        KeyStore serverKeys = KeyStore.getInstance("PKCS12");
        try (var input = Files.newInputStream(keyStore)) {
            serverKeys.load(input, STORE_PASSWORD.toCharArray());
        }
        KeyManagerFactory keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyManagers.init(serverKeys, STORE_PASSWORD.toCharArray());
        tls.init(keyManagers.getKeyManagers(), null, null);

        KeyStore trustedKeys = KeyStore.getInstance("PKCS12");
        try (var input = Files.newInputStream(trustStore)) {
            trustedKeys.load(input, STORE_PASSWORD.toCharArray());
        }
        TrustManagerFactory trustManagers = TrustManagerFactory.getInstance(
                TrustManagerFactory.getDefaultAlgorithm());
        trustManagers.init(trustedKeys);
        SSLContext clientTls = SSLContext.getInstance("TLS");
        clientTls.init(null, trustManagers.getTrustManagers(), null);
        previousDefaultSslContext = SSLContext.getDefault();
        previousDefaultSocketFactory = HttpsURLConnection.getDefaultSSLSocketFactory();
        SSLContext.setDefault(clientTls);
        HttpsURLConnection.setDefaultSSLSocketFactory(clientTls.getSocketFactory());

        identityServer = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        identityServer.setHttpsConfigurator(new HttpsConfigurator(tls));
        identityServer.start();
        identityBase = "https://127.0.0.1:" + identityServer.getAddress().getPort();
        identityServer.createContext("/jwks", exchange -> respond(exchange,
                new JWKSet(signingKey.toPublicJWK()).toString()));
        identityServer.createContext("/discovery-exact", exchange -> {
            exactMetadataRequests.incrementAndGet();
            respond(exchange, "{\"issuer\":\"" + ISSUER + "\",\"jwks_uri\":\""
                    + identityBase + "/keys-exact\"}");
        });
        identityServer.createContext("/keys-exact", exchange -> {
            exactJwksRequests.incrementAndGet();
            respond(exchange, new JWKSet(signingKey.toPublicJWK()).toString());
        });
        identityServer.createContext("/wrong-issuer-metadata", exchange -> respond(exchange,
                "{\"issuer\":\"https://other-issuer.example\",\"jwks_uri\":\""
                        + identityBase + "/keys-exact\"}"));

        application = startApplication();
        applicationPort = ((ServletWebServerApplicationContext) application).getWebServer().getPort();
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @AfterAll
    static void stopServers() {
        if (application != null) application.close();
        if (identityServer != null) identityServer.stop(0);
        restoreSystemProperty("javax.net.ssl.trustStore", previousTrustStore);
        restoreSystemProperty("javax.net.ssl.trustStorePassword", previousTrustStorePassword);
        restoreSystemProperty("javax.net.ssl.trustStoreType", previousTrustStoreType);
        if (previousDefaultSslContext != null) SSLContext.setDefault(previousDefaultSslContext);
        if (previousDefaultSocketFactory != null) {
            HttpsURLConnection.setDefaultSSLSocketFactory(previousDefaultSocketFactory);
        }
    }

    @Test
    void validSignedTokenPassesAuthenticationAtTheCustomMcpPath() throws Exception {
        String initialize = """
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{
                  "protocolVersion":"2025-11-25","capabilities":{},
                  "clientInfo":{"name":"task-17-security-test","version":"1.0.0"}}}
                """;
        HttpResponse<String> response = post(CUSTOM_MCP_PATH, validToken(), initialize);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("protocolVersion", "data-prism");
    }

    @Test
    void tokenWithWrongSignatureIsRefused() throws Exception {
        assertThat(post(CUSTOM_MCP_PATH, token(wrongKey, ISSUER, AUDIENCE,
                VALID_NOT_BEFORE, VALID_EXPIRES_AT)).statusCode()).isEqualTo(401);
    }

    @Test
    void tokenWithWrongIssuerIsRefused() throws Exception {
        assertThat(post(CUSTOM_MCP_PATH, token(signingKey, "https://wrong.example", AUDIENCE,
                VALID_NOT_BEFORE, VALID_EXPIRES_AT)).statusCode()).isEqualTo(401);
    }

    @Test
    void tokenWithWrongAudienceIsRefused() throws Exception {
        assertThat(post(CUSTOM_MCP_PATH, token(signingKey, ISSUER, "other-audience",
                VALID_NOT_BEFORE, VALID_EXPIRES_AT)).statusCode()).isEqualTo(401);
    }

    @Test
    void expiredTokenIsRefused() throws Exception {
        assertThat(post(CUSTOM_MCP_PATH, token(signingKey, ISSUER, AUDIENCE,
                EXPIRED_NOT_BEFORE, EXPIRED_AT)).statusCode()).isEqualTo(401);
    }

    @Test
    void notYetValidTokenIsRefused() throws Exception {
        assertThat(post(CUSTOM_MCP_PATH, token(signingKey, ISSUER, AUDIENCE,
                FUTURE_NOT_BEFORE, FUTURE_EXPIRES_AT)).statusCode()).isEqualTo(401);
    }

    @Test
    void onlyGetHealthIsPublicAndUnrelatedRoutesAreDenied() throws Exception {
        HttpResponse<String> getHealth = send(HttpRequest.newBuilder(applicationUri("/health")).GET().build());
        HttpResponse<String> postHealth = send(HttpRequest.newBuilder(applicationUri("/health"))
                .POST(HttpRequest.BodyPublishers.noBody()).build());
        HttpResponse<String> unrelated = send(HttpRequest.newBuilder(applicationUri("/actuator/health")).GET().build());

        assertThat(getHealth.statusCode()).isEqualTo(200);
        assertThat(postHealth.statusCode()).isEqualTo(401);
        assertThat(unrelated.statusCode()).isEqualTo(401);
    }

    @Test
    void customMcpPathRequiresAuthenticationAndDefaultPathIsDenied() throws Exception {
        assertThat(post(CUSTOM_MCP_PATH, null).statusCode()).isEqualTo(401);
        assertThat(post("/mcp", validToken()).statusCode()).isEqualTo(403);
    }

    @Test
    void exactDiscoveryAndJwksUrisAreUsedAndMetadataIssuerMustMatch() throws Exception {
        exactMetadataRequests.set(0);
        exactJwksRequests.set(0);
        DataPrismProperties properties = securityProperties();
        properties.getSecurity().getJwt().setJwkSetUri(null);
        properties.getSecurity().getJwt().setIssuerDiscoveryUri(identityBase + "/discovery-exact");

        JwtDecoder decoder = new ServerSecurityConfiguration().jwtDecoder(properties);
        decoder.decode(validToken());

        assertThat(exactMetadataRequests).hasValue(1);
        assertThat(exactJwksRequests).hasValue(1);

        properties.getSecurity().getJwt().setIssuerDiscoveryUri(identityBase + "/wrong-issuer-metadata");
        assertThatThrownBy(() -> new ServerSecurityConfiguration().jwtDecoder(properties))
                .isInstanceOf(DataPrismConfigurationException.class)
                .hasMessageStartingWith("JWT_DISCOVERY_FAILED:");
    }

    @Test
    void decoderAppliesSignatureIssuerAudienceAndTimeValidators() throws Exception {
        JwtDecoder decoder = new ServerSecurityConfiguration().jwtDecoder(securityProperties());

        assertThat(decoder.decode(validToken()).getSubject()).isEqualTo("subject-17");
        assertThatThrownBy(() -> decoder.decode(token(wrongKey, ISSUER, AUDIENCE,
                VALID_NOT_BEFORE, VALID_EXPIRES_AT)))
                .isInstanceOf(JwtException.class);
        assertThatThrownBy(() -> decoder.decode(token(signingKey, "https://wrong.example", AUDIENCE,
                VALID_NOT_BEFORE, VALID_EXPIRES_AT)))
                .isInstanceOf(JwtException.class);
        assertThatThrownBy(() -> decoder.decode(token(signingKey, ISSUER, "wrong",
                VALID_NOT_BEFORE, VALID_EXPIRES_AT)))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void configuredClaimsBecomeTheAuthenticatedCaller() {
        DataPrismProperties properties = securityProperties();
        Instant expiresAt = Instant.parse("2035-01-01T00:00:00Z");
        Jwt jwt = Jwt.withTokenValue("already-verified")
                .header("alg", "RS256")
                .subject("ignored-default-subject")
                .claim("operator_id", "operator-17")
                .claim("operator_roles", List.of("investigator", "auditor"))
                .claim("investigation_ref", "CASE-17")
                .claim("azp", "client-17")
                .claim("purpose", "investigation")
                .issuedAt(Instant.parse("2030-01-01T00:00:00Z"))
                .expiresAt(expiresAt)
                .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));

        McpTransportContext transport = new JwtCallerContextExtractor(properties).extract(null);
        AuthenticatedCaller caller = (AuthenticatedCaller) transport.get(
                GetEntityContextTool.TRANSPORT_CONTEXT_CALLER_KEY);

        assertThat(caller.principalId()).isEqualTo("operator-17");
        assertThat(caller.clientId()).isEqualTo("client-17");
        assertThat(caller.roles()).containsExactlyInAnyOrder("investigator", "auditor");
        assertThat(caller.purpose()).isEqualTo("investigation");
        assertThat(caller.caseId()).isEqualTo("CASE-17");
        assertThat(caller.expiresAt()).isEqualTo(expiresAt);
    }

    private static ConfigurableApplicationContext startApplication() {
        return new SpringApplicationBuilder(DataPrismServerApplication.class)
                .web(WebApplicationType.SERVLET)
                .logStartupInfo(false)
                .initializers(context -> {
                    var beans = context.getBeanFactory();
                    beans.registerSingleton("testIdentityResolver", new PassThroughIdentityResolver());
                    beans.registerSingleton("testCustomerAdapter", adapter());
                    beans.registerSingleton("testKeys", (HmacKeyReferenceResolver) (keyId, reference) ->
                            "task-17-security-test-key-material-longer-than-thirty-two-bytes"
                                    .getBytes(StandardCharsets.UTF_8));
                    beans.registerSingleton("testAudit", (AuditSink) event -> { });
                    beans.registerSingleton("testMetrics", PrivacyMetrics.none());
                })
                .run(validApplicationArguments());
    }

    private static DataSourceAdapter<String> adapter() {
        return new DataSourceAdapter<>() {
            @Override public String sourceName() { return "customer"; }
            @Override public Class<String> responseType() { return String.class; }
            @Override public String fetch(DataRequest request) { return null; }
        };
    }

    private static DataPrismProperties securityProperties() {
        DataPrismProperties properties = new DataPrismProperties();
        properties.getSecurity().getJwt().setIssuer(ISSUER);
        properties.getSecurity().getJwt().setAudience(AUDIENCE);
        properties.getSecurity().getJwt().setJwkSetUri(identityBase + "/jwks");
        properties.getSecurity().getCallerClaims().setPrincipal("operator_id");
        properties.getSecurity().getCallerClaims().setRoles("operator_roles");
        properties.getSecurity().getCallerClaims().setInvestigation("investigation_ref");
        return properties;
    }

    private static String validToken() throws Exception {
        return token(signingKey, ISSUER, AUDIENCE, VALID_NOT_BEFORE, VALID_EXPIRES_AT);
    }

    private static String token(RSAKey key, String issuer, String audience,
            Instant notBefore, Instant expiresAt) throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject("subject-17")
                .claim("operator_id", "operator-17")
                .claim("operator_roles", List.of("investigator"))
                .claim("investigation_ref", "CASE-17")
                .claim("azp", "client-17")
                .claim("purpose", "investigation")
                .issuer(issuer).audience(audience)
                .notBeforeTime(Date.from(notBefore)).expirationTime(Date.from(expiresAt)).build();
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), claims);
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }

    private static HttpResponse<String> post(String path, String token) throws Exception {
        return post(path, token, "{}");
    }

    private static HttpResponse<String> post(String path, String token, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(applicationUri(path))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream");
        if (token != null) request.header("Authorization", "Bearer " + token);
        return send(request.POST(HttpRequest.BodyPublishers.ofString(body)).build());
    }

    private static HttpResponse<String> send(HttpRequest request) throws Exception {
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static URI applicationUri(String path) {
        return URI.create("http://127.0.0.1:" + applicationPort + path);
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, String body) throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private static void keytool(String... arguments) throws Exception {
        java.util.List<String> command = new java.util.ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "keytool").toString());
        command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (process.waitFor() != 0) throw new IllegalStateException("keytool failed: " + output);
    }

    private static void restoreSystemProperty(String name, String value) {
        if (value == null) System.clearProperty(name);
        else System.setProperty(name, value);
    }

    private static String[] validApplicationArguments() {
        return new String[] {
                "--server.port=0", "--spring.main.banner-mode=off",
                "--dataprism.transport.http.path=" + CUSTOM_MCP_PATH,
                "--dataprism.security.jwt.issuer=" + ISSUER,
                "--dataprism.security.jwt.audience=" + AUDIENCE,
                "--dataprism.security.jwt.jwk-set-uri=" + identityBase + "/jwks",
                "--dataprism.security.caller-claims.principal=operator_id",
                "--dataprism.security.caller-claims.roles=operator_roles",
                "--dataprism.security.caller-claims.investigation=investigation_ref",
                "--dataprism.security-policy.purposes[0]=investigation",
                "--dataprism.security-policy.roles.investigator[0]=GET_ENTITY_CONTEXT",
                "--dataprism.privacy.profile=DEFAULT", "--dataprism.privacy.scope-lifetime=8h",
                "--dataprism.privacy.hmac-key.key-id=v1",
                "--dataprism.privacy.hmac-key.provider-reference=test-key",
                "--dataprism.audit.sink=approved-sink", "--dataprism.audit.writer-id=security-test",
                "--dataprism.metrics.sink=micrometer",
                "--dataprism.sources.customer.base-url=https://customer.example",
                "--dataprism.sources.customer.timeout=2s"
        };
    }
}

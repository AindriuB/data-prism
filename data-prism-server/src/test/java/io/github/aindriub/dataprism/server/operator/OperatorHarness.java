package io.github.aindriub.dataprism.server.operator;

import com.hazelcast.core.Hazelcast;
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
import io.github.aindriub.dataprism.audit.AuditEvent;
import io.github.aindriub.dataprism.audit.AuditSink;
import io.github.aindriub.dataprism.core.DataRequest;
import io.github.aindriub.dataprism.core.DataSourceAdapter;
import io.github.aindriub.dataprism.core.PassThroughIdentityResolver;
import io.github.aindriub.dataprism.core.PrivacyMetrics;
import io.github.aindriub.dataprism.server.DataPrismServerApplication;
import io.github.aindriub.dataprism.spring.boot.HmacKeyReferenceResolver;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Starts the real standalone application on two real ports, with an HTTPS JWKS endpoint of its own
 * and a fixture source. Nothing is mocked: tokens are signed, decoded and audience-checked by the
 * production decoder.
 */
final class OperatorHarness implements AutoCloseable {

    static final String ISSUER = "https://issuer.example";
    static final String MCP_AUDIENCE = "data-prism-mcp";
    static final String OPERATOR_AUDIENCE = "data-prism-operator";
    static final String OPERATOR_SCOPE = "dataprism.operate";
    private static final String STORE_PASSWORD = "task-105-test-only";

    final List<AuditEvent> audit = new CopyOnWriteArrayList<>();
    /** While true the audit sink refuses every event, to exercise the audit-unavailable paths. */
    volatile boolean failAudit;
    final int mcpPort;
    final int operatorPort;
    final ConfigurableApplicationContext context;

    private final HttpsServer identityServer;
    private final RSAKey signingKey;
    private final String previousTrustStore;
    private final String previousTrustStorePassword;
    private final String previousTrustStoreType;
    private final boolean embedded;
    private final SSLContext previousDefaultSslContext;
    private final SSLSocketFactory previousDefaultSocketFactory;

    static OperatorHarness start(Path tempDir, boolean embedded, String... extraArguments) throws Exception {
        return new OperatorHarness(tempDir, embedded, null, null, extraArguments);
    }

    /**
     * Embedded, with a configured-JSON source (task 127) served over this harness's own HTTPS
     * endpoint, which the application already trusts. {@code catalogueTemplate} may contain
     * {@code @HOST@}, replaced by {@code https://127.0.0.1:<port>}; every {@code /records/*} request
     * answers {@code recordJson}. The location is passed as a system property, as the packaged
     * server is run, because {@code DataPrismProperties} refuses unknown {@code dataprism.*} keys
     * from every other source.
     */
    static OperatorHarness startWithJsonSource(Path tempDir, String catalogueTemplate, String recordJson,
                                               String... extraArguments) throws Exception {
        return new OperatorHarness(tempDir, true, catalogueTemplate, recordJson, extraArguments);
    }

    private static final String CATALOGUE_LOCATION_PROPERTY = "dataprism.json-sources.config-location";
    private final boolean catalogueLocationSet;

    private OperatorHarness(Path tempDir, boolean embedded, String catalogueTemplate, String recordJson,
                            String... extraArguments) throws Exception {
        this.embedded = embedded;
        this.catalogueLocationSet = catalogueTemplate != null;
        signingKey = new RSAKeyGenerator(2048).keyID("task-105-key").algorithm(JWSAlgorithm.RS256).generate();
        Path keyStore = tempDir.resolve("identity-server.p12");
        Path trustStore = tempDir.resolve("identity-trust.p12");
        Path certificate = tempDir.resolve("identity-server.cer");
        keytool("-genkeypair", "-alias", "identity", "-keyalg", "RSA", "-keysize", "2048", "-validity", "2",
                "-keystore", keyStore.toString(), "-storetype", "PKCS12", "-storepass", STORE_PASSWORD,
                "-keypass", STORE_PASSWORD, "-dname", "CN=127.0.0.1", "-ext", "san=ip:127.0.0.1");
        keytool("-exportcert", "-alias", "identity", "-keystore", keyStore.toString(), "-storetype", "PKCS12",
                "-storepass", STORE_PASSWORD, "-file", certificate.toString());
        keytool("-importcert", "-alias", "identity", "-file", certificate.toString(), "-keystore",
                trustStore.toString(), "-storetype", "PKCS12", "-storepass", STORE_PASSWORD, "-noprompt");
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
        KeyStore trusted = KeyStore.getInstance("PKCS12");
        try (var input = Files.newInputStream(trustStore)) {
            trusted.load(input, STORE_PASSWORD.toCharArray());
        }
        TrustManagerFactory trustManagers = TrustManagerFactory.getInstance(
                TrustManagerFactory.getDefaultAlgorithm());
        trustManagers.init(trusted);
        SSLContext clientTls = SSLContext.getInstance("TLS");
        clientTls.init(null, trustManagers.getTrustManagers(), null);
        // The JVM default context is built once, so each harness installs its own explicitly.
        previousDefaultSslContext = SSLContext.getDefault();
        previousDefaultSocketFactory = HttpsURLConnection.getDefaultSSLSocketFactory();
        SSLContext.setDefault(clientTls);
        HttpsURLConnection.setDefaultSSLSocketFactory(clientTls.getSocketFactory());
        identityServer = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        identityServer.setHttpsConfigurator(new HttpsConfigurator(tls));
        byte[] jwks = new JWKSet(signingKey.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
        identityServer.createContext("/jwks", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, jwks.length);
            try (var out = exchange.getResponseBody()) {
                out.write(jwks);
            }
        });
        if (recordJson != null) {
            byte[] record = recordJson.getBytes(StandardCharsets.UTF_8);
            identityServer.createContext("/records/", exchange -> {
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, record.length);
                try (var out = exchange.getResponseBody()) {
                    out.write(record);
                }
            });
        }
        identityServer.start();
        if (catalogueTemplate != null) {
            Path catalogue = tempDir.resolve("json-sources.yaml");
            Files.writeString(catalogue, catalogueTemplate.replace("@HOST@",
                    "https://127.0.0.1:" + identityServer.getAddress().getPort()));
            System.setProperty(CATALOGUE_LOCATION_PROPERTY, "file:" + catalogue.toAbsolutePath());
        }
        String jwksUri = "https://127.0.0.1:" + identityServer.getAddress().getPort() + "/jwks";

        operatorPort = freePort();
        List<String> arguments = new ArrayList<>(List.of(
                "--server.port=0", "--spring.main.banner-mode=off",
                "--dataprism.security.jwt.issuer=" + ISSUER,
                "--dataprism.security.jwt.audience=" + MCP_AUDIENCE,
                "--dataprism.security.jwt.jwk-set-uri=" + jwksUri,
                "--dataprism.security.caller-claims.principal=sub",
                "--dataprism.security.caller-claims.roles=roles",
                "--dataprism.security.caller-claims.investigation=case_id",
                "--dataprism.security-policy.purposes[0]=investigation",
                "--dataprism.security-policy.roles.investigator[0]=GET_ENTITY_CONTEXT",
                "--dataprism.privacy.profile=DEFAULT", "--dataprism.privacy.scope-lifetime=8h",
                "--dataprism.privacy.hmac-key.key-id=v1",
                "--dataprism.privacy.hmac-key.provider-reference=test-key",
                "--dataprism.audit.sink=approved-sink", "--dataprism.audit.writer-id=operator-test",
                "--dataprism.metrics.sink=micrometer",
                "--dataprism.hazelcast.topology=" + (embedded ? "embedded" : "single-node"),
                "--dataprism.sources.customer.base-url=https://customer.example",
                "--dataprism.sources.customer.timeout=2s",
                "--dataprism.operator.enabled=true",
                "--dataprism.operator.port=" + operatorPort,
                "--dataprism.operator.required-audience=" + OPERATOR_AUDIENCE,
                "--dataprism.operator.required-scope=" + OPERATOR_SCOPE));
        if (embedded) {
            arguments.addAll(List.of(
                    "--dataprism.hazelcast.reidentification-enabled=true",
                    "--dataprism.hazelcast.reidentification-controls-reference=REVIEWED_CONTROLS",
                    "--dataprism.reidentification.enabled=true",
                    "--dataprism.reidentification.purposes[0]=fraud-review",
                    "--dataprism.reidentification.roles.requester[0]=REQUEST",
                    "--dataprism.reidentification.roles.approver[0]=APPROVE"));
        }
        arguments.addAll(List.of(extraArguments));
        context = new SpringApplicationBuilder(DataPrismServerApplication.class)
                .web(WebApplicationType.SERVLET)
                .logStartupInfo(false)
                .initializers(applicationContext -> {
                    var beans = applicationContext.getBeanFactory();
                    beans.registerSingleton("testIdentityResolver", new PassThroughIdentityResolver());
                    beans.registerSingleton("testCustomerAdapter", adapter());
                    beans.registerSingleton("testKeys", (HmacKeyReferenceResolver) (keyId, reference) ->
                            "task-105-test-key-material-longer-than-thirty-two-bytes"
                                    .getBytes(StandardCharsets.UTF_8));
                    beans.registerSingleton("testAudit", (AuditSink) event -> {
                        if (failAudit) throw new IllegalStateException("audit down");
                        audit.add(event);
                    });
                    beans.registerSingleton("testMetrics", PrivacyMetrics.none());
                })
                .run(arguments.toArray(String[]::new));
        mcpPort = ((ServletWebServerApplicationContext) context).getWebServer().getPort();
    }

    private static DataSourceAdapter<FixtureCustomer> adapter() {
        return new DataSourceAdapter<>() {
            @Override public String sourceName() { return "customer"; }
            @Override public Class<FixtureCustomer> responseType() { return FixtureCustomer.class; }
            @Override public FixtureCustomer fetch(DataRequest request) {
                return new FixtureCustomer(request.subjectId(), "Fixture Person", "ACTIVE");
            }
        };
    }

    // ---- tokens --------------------------------------------------------------------------------

    /** A token for the MCP endpoint: MCP audience, no operator scope. */
    String mcpToken(String principal, String caseId) throws Exception {
        return token(principal, "mcp-client", MCP_AUDIENCE, null, List.of("investigator"), caseId);
    }

    /** A token for the operator port. */
    String operatorToken(String principal, String... roles) throws Exception {
        return token(principal, "operator-console", OPERATOR_AUDIENCE, OPERATOR_SCOPE, List.of(roles), "CASE-OPS");
    }

    String token(String principal, String client, String audience, String scope, List<String> roles,
                 String caseId) throws Exception {
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                .subject(principal).issuer(ISSUER).audience(audience)
                .claim("azp", client).claim("roles", roles).claim("purpose", "investigation")
                .claim("case_id", caseId)
                .notBeforeTime(Date.from(Instant.parse("2020-01-01T00:00:00Z")))
                .expirationTime(Date.from(Instant.parse("2100-01-01T00:00:00Z")));
        if (scope != null) {
            claims.claim("scope", scope);
        }
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(signingKey.getKeyID())
                .build(), claims.build());
        jwt.sign(new RSASSASigner(signingKey));
        return jwt.serialize();
    }

    // ---- requests ------------------------------------------------------------------------------

    HttpResponse<String> operator(String method, String path, String token, String body) throws Exception {
        return send(operatorPort, method, path, token, body);
    }

    HttpResponse<String> mcpPort(String method, String path, String token, String body) throws Exception {
        return send(mcpPort, method, path, token, body);
    }

    private static HttpResponse<String> send(int port, String method, String path, String token, String body)
            throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Content-Type", "application/json").header("Accept", "application/json");
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body));
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** A request with extra headers and a caller-chosen body publisher (e.g. unknown length, so chunked). */
    HttpResponse<String> raw(boolean onOperatorPort, String method, String path, String token,
                             HttpRequest.BodyPublisher body, String... headers) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + (onOperatorPort ? operatorPort : mcpPort) + path))
                .header("Content-Type", "application/json").header("Accept", "application/json");
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        for (int i = 0; i < headers.length; i += 2) {
            request.header(headers[i], headers[i + 1]);
        }
        request.method(method, body);
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    McpSyncClient mcpClient(String token) {
        HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport
                .builder("http://127.0.0.1:" + mcpPort).endpoint("/mcp")
                .requestBuilder(HttpRequest.newBuilder().header("Authorization", "Bearer " + token)).build();
        McpSyncClient client = McpClient.sync(transport)
                .clientInfo(new McpSchema.Implementation("operator-test", "1.0.0")).build();
        client.initialize();
        return client;
    }

    McpSchema.CallToolResult getEntityContext(McpSyncClient client, String subjectId) {
        return client.callTool(new McpSchema.CallToolRequest("get_entity_context",
                Map.of("entityType", "CUSTOMER", "subjectId", subjectId)));
    }

    static String text(McpSchema.CallToolResult result) {
        return ((McpSchema.TextContent) result.content().get(0)).text();
    }

    List<AuditEvent> auditFor(String tool) {
        return audit.stream().filter(e -> e.tool().equals(tool)).toList();
    }

    // ---- lifecycle -----------------------------------------------------------------------------

    @Override
    public void close() {
        context.close();
        identityServer.stop(0);
        if (catalogueLocationSet) {
            System.clearProperty(CATALOGUE_LOCATION_PROPERTY);
        }
        if (embedded) {
            Hazelcast.shutdownAll();
        }
        SSLContext.setDefault(previousDefaultSslContext);
        HttpsURLConnection.setDefaultSSLSocketFactory(previousDefaultSocketFactory);
        restore("javax.net.ssl.trustStore", previousTrustStore);
        restore("javax.net.ssl.trustStorePassword", previousTrustStorePassword);
        restore("javax.net.ssl.trustStoreType", previousTrustStoreType);
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static void restore(String name, String value) {
        if (value == null) System.clearProperty(name);
        else System.setProperty(name, value);
    }

    private static void keytool(String... arguments) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "keytool").toString());
        command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (process.waitFor() != 0) throw new IllegalStateException("keytool failed: " + output);
    }
}

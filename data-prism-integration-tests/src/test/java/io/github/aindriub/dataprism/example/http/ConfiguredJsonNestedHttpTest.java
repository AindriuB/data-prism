package io.github.aindriub.dataprism.example.http;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import io.github.aindriub.dataprism.mcp.DataPrismObjectMapper;
import io.github.aindriub.dataprism.spring.boot.HmacKeyReferenceResolver;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves task 60's nested-catalogue refusals against the real MCP HTTP/SSE
 * endpoint, not against {@code JsonTreeScrubbingEngine} directly the way
 * {@code ConfiguredJsonNestedCatalogueScrubbingTest} (data-prism-connectors-rest)
 * does.
 *
 * <p>The MCP client is the SDK's own {@code McpSyncClient} on {@code
 * HttpClientStreamableHttpTransport} -- the same one {@link McpHttpEndToEndTest}
 * drives -- which is what actually negotiates the {@code Accept:
 * application/json, text/event-stream} header and parses the server's SSE
 * frame back into the JSON-RPC response whose {@code id} matches the request.
 * That negotiation is exactly the step two tasks in the previous wave skipped
 * by assuming a bare JSON body instead, and were only caught because a test
 * like this one actually opened a socket against the real transport. Rolling
 * the same negotiation by hand here would only reintroduce that exact risk in
 * a second place.
 *
 * <p>Like {@code AuditSinkFailureAbortsResponseTest} and unlike {@code
 * McpHttpEndToEndTest}, the caller's JWT is verified against an in-process
 * public key -- a {@code JwtDecoder} bean substituted in over {@code
 * SecurityConfig}'s own {@code @ConditionalOnMissingBean} one -- rather than a
 * real JWKS HTTPS fetch. This class's own concern is the nested-catalogue
 * refusal codes, not the JWKS-fetching path {@code McpHttpEndToEndTest} already
 * covers, and skipping a second self-signed HTTPS server here is what avoids
 * corrupting the JDK's JVM-wide default {@code SSLContext} singleton for
 * whichever of that class's tests shares this Surefire fork. The MCP client's
 * own {@code HttpClient} still gets an explicit, non-default {@code
 * SSLContext} for the same reason, even though its connection is plain HTTP
 * and never needs TLS at all: {@code HttpClient.Builder#build()} resolves
 * {@code SSLContext.getDefault()} eagerly regardless.
 *
 * <p>The nested-catalogue source itself is wired the same way an operator
 * would wire one in production: a {@code json-sources:} YAML document read
 * through {@code dataprism.json-sources.config-location} (a system property,
 * not a {@code "--"} command-line argument -- see that field's own comment
 * for why), naming an upstream reached over real TLS with a {@code tls:}
 * block, exactly the shape {@code ConfiguredJsonSourcesInitializer} documents
 * for a production deployment. {@code dataprism.transport.fixture-development}
 * is never set here: {@code DataPrismProperties} itself refuses that flag
 * outright once {@code dataprism.transport.mode=http}, which this server
 * always is, so a plaintext loopback upstream was never an option for this
 * class the way it is for {@code ConfiguredJsonSourcesAutoConfigurationTest}'s
 * bare {@code ApplicationContextRunner}. The upstream fixture's key pair is
 * generated per test run, the same way {@code MutualTlsRestClientsHttpsTest}
 * (data-prism-connectors-rest) generates its own, and for the same reason:
 * {@code MutualTlsRestClients.build} builds its {@code SSLContext} from those
 * exact stores, never from the JVM-wide default, so this sidesteps the
 * {@code SSLContext.getDefault()} singleton entirely rather than merely
 * managing it carefully. No test-only bean registration bypasses the
 * production wiring path.
 *
 * <p>No assertion here pins a literal pseudonym: task 71 is planned to widen
 * the pseudonym discriminator, which would change every rendered pseudonym
 * this class could otherwise hard-code. Every assertion about a scrubbed
 * value instead checks its absence (the raw value is never present) or its
 * shape (present, non-blank, not equal to the raw value) -- never its exact
 * rendered text.
 */
class ConfiguredJsonNestedHttpTest {

    private static final String ISSUER = "data-prism-example";
    private static final String AUDIENCE = "data-prism-mcp";
    private static final String CONFIGURED_PURPOSE = "investigation";
    private static final String PRINCIPAL = "jwt-principal-task-61";
    private static final String CLIENT_ID = "jwt-client-task-61";
    private static final String CASE_ID = "CASE-TASK-61";

    /**
     * Must match the module POM's surefire {@code environmentVariables}
     * configuration for {@code TASK61_TLS_PASSWORD}; protects only a
     * throwaway key pair generated into a {@code @TempDir} for this run.
     */
    private static final String STORE_PASSWORD = "dp-test-only-not-a-real-secret";
    private static final String TLS_PASSWORD_ENV = "TASK61_TLS_PASSWORD";

    @TempDir
    static Path tempDir;

    private static HttpsServer upstreamServer;
    private static RSAKey signingKey;
    private static ConfigurableApplicationContext context;
    private static int port;

    /** Keyed by subject id, so each test drives its own upstream response without interference. */
    private static final Map<String, String> UPSTREAM_BODIES = new ConcurrentHashMap<>();

    @BeforeAll
    static void startFixturesAndApplication() throws Exception {
        assertThat(System.getenv(TLS_PASSWORD_ENV))
                .as("test relies on the module POM's surefire env config matching STORE_PASSWORD")
                .isEqualTo(STORE_PASSWORD);

        // See McpHttpEndToEndTest for why this is forced before Tomcat's
        // webapp classloader exists.
        reactor.core.scheduler.Schedulers.boundedElastic().schedule(() -> { });

        Path clientKeyStore = tempDir.resolve("upstream-client-keystore.p12");
        Path clientTrustStore = tempDir.resolve("upstream-client-truststore.p12");
        startUpstreamFixture(clientKeyStore, clientTrustStore);

        signingKey = new RSAKeyGenerator(2048).keyID("test-signing-key-task-61").algorithm(JWSAlgorithm.RS256)
                .generate();
        Path config = writeSourceConfig(clientKeyStore, clientTrustStore);

        HmacKeyReferenceResolver testKeys = (keyId, reference) ->
                "task-61-test-only-key-material-longer-than-thirty-two-bytes".getBytes(StandardCharsets.UTF_8);
        JwtDecoder testDecoder = NimbusJwtDecoder.withPublicKey((RSAPublicKey) signingKey.toRSAPublicKey()).build();

        // A system property, not a "--" command-line argument:
        // DataPrismProperties binds strictly (ignoreUnknownFields = false) and
        // has no field of its own for this key -- ConfiguredJsonSourcesAutoConfiguration's
        // own property lives outside that model entirely. Spring Boot's
        // unbound-elements check exempts the systemProperties source (that is
        // how the packaged distribution's own -D-based wiring, exercised by
        // data-prism-server's ConfiguredJsonSourcesPackagingIT, gets away with
        // exactly this key) but does not exempt commandLineArgs, which is
        // what "--dataprism.json-sources.config-location=..." here would have
        // landed as, and DataPrismProperties would have refused startup over
        // an element it never claims to bind.
        System.setProperty("dataprism.json-sources.config-location", "file:" + config);
        try {
            context = new SpringApplicationBuilder(ResourceServerApplication.class)
                    .web(WebApplicationType.SERVLET)
                    .initializers(applicationContext -> {
                        applicationContext.getBeanFactory()
                                .registerSingleton("testHmacKeyReferenceResolver", testKeys);
                        applicationContext.getBeanFactory().registerSingleton("testJwtDecoder", testDecoder);
                    })
                    .run("--server.port=0");
        } finally {
            System.clearProperty("dataprism.json-sources.config-location");
        }
        port = ((ServletWebServerApplicationContext) context).getWebServer().getPort();
    }

    @AfterAll
    static void stopFixturesAndApplication() {
        context.close();
        upstreamServer.stop(0);
    }

    /**
     * A real TLS upstream, key pair generated fresh for this run -- see
     * {@code MutualTlsRestClientsHttpsTest} (data-prism-connectors-rest) for
     * the same pattern. The server never demands a client certificate
     * ({@code needClientAuth} defaults to {@code false}), so only its own
     * key pair is generated; {@code clientKeyStore}/{@code clientTrustStore}
     * are populated with a throwaway client key pair and the server's own
     * certificate respectively, since {@link
     * io.github.aindriub.dataprism.connectors.rest.TlsSettings}'s compact
     * constructor requires both regardless of whether the server end ever
     * validates the client's certificate.
     */
    private static void startUpstreamFixture(Path clientKeyStore, Path clientTrustStore) throws Exception {
        Path serverKeyStore = tempDir.resolve("upstream-server-keystore.p12");
        Path serverCert = tempDir.resolve("upstream-server.cer");
        Path clientCert = tempDir.resolve("upstream-client.cer");

        keytool("-genkeypair", "-alias", "upstream-server", "-keyalg", "RSA", "-keysize", "2048",
                "-validity", "2", "-keystore", serverKeyStore.toString(), "-storetype", "PKCS12",
                "-storepass", STORE_PASSWORD, "-keypass", STORE_PASSWORD,
                "-dname", "CN=127.0.0.1", "-ext", "san=ip:127.0.0.1");
        keytool("-exportcert", "-alias", "upstream-server", "-keystore", serverKeyStore.toString(),
                "-storetype", "PKCS12", "-storepass", STORE_PASSWORD, "-file", serverCert.toString());

        keytool("-genkeypair", "-alias", "upstream-client", "-keyalg", "RSA", "-keysize", "2048",
                "-validity", "2", "-keystore", clientKeyStore.toString(), "-storetype", "PKCS12",
                "-storepass", STORE_PASSWORD, "-keypass", STORE_PASSWORD,
                "-dname", "CN=configured-json-nested-http-test-client");
        keytool("-exportcert", "-alias", "upstream-client", "-keystore", clientKeyStore.toString(),
                "-storetype", "PKCS12", "-storepass", STORE_PASSWORD, "-file", clientCert.toString());

        keytool("-importcert", "-alias", "upstream-server", "-file", serverCert.toString(),
                "-keystore", clientTrustStore.toString(), "-storetype", "PKCS12",
                "-storepass", STORE_PASSWORD, "-noprompt");

        SSLContext serverContext = SSLContext.getInstance("TLS");
        KeyManagerFactory keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        KeyStore serverKeys = KeyStore.getInstance("PKCS12");
        try (var input = Files.newInputStream(serverKeyStore)) {
            serverKeys.load(input, STORE_PASSWORD.toCharArray());
        }
        keyManagers.init(serverKeys, STORE_PASSWORD.toCharArray());
        serverContext.init(keyManagers.getKeyManagers(), null, null);

        upstreamServer = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstreamServer.setHttpsConfigurator(new HttpsConfigurator(serverContext));
        upstreamServer.createContext("/nested", exchange -> {
            String requestPath = exchange.getRequestURI().getPath();
            String subject = requestPath.substring(requestPath.lastIndexOf('/') + 1);
            String responseBody = UPSTREAM_BODIES.get(subject);
            byte[] body = (responseBody == null ? "{}" : responseBody).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        upstreamServer.start();
    }

    /**
     * A configuration-driven JSON source, wired exactly the way an operator's
     * {@code json-sources:} YAML would be: one root catalogue with a scalar
     * PII field and one {@code nested:} field, and one nested catalogue
     * ("address") declaring one non-sensitive leaf ("line1") and one PII leaf
     * ("ssn"). The base URL is the upstream fixture's own ephemeral port,
     * reached over the {@code tls:} block naming the client key pair and
     * trust store {@link #startUpstreamFixture} just generated.
     */
    private static Path writeSourceConfig(Path clientKeyStore, Path clientTrustStore) throws Exception {
        String yaml = """
                json-sources:
                  customer-nested-api:
                    base-url: https://127.0.0.1:%d
                    path: /nested/{subject}
                    timeout: PT2S
                    model-version: customer-nested-v1
                    subject-json-path: id
                    fields:
                      id:
                        identifier: true
                      name:
                        classifications: [PII]
                        namespace: PERSON_NAME
                        action: SYNTHESIZE
                      address:
                        nested: address
                    nested-catalogues:
                      address:
                        line1:
                          nonSensitive: "street address line, reviewed as inert structure"
                        ssn:
                          classifications: [PII]
                          namespace: PERSON_IDENTITY
                          action: SYNTHESIZE
                tls:
                  key-store: %s
                  key-store-password-env: %s
                  trust-store: %s
                  trust-store-password-env: %s
                  store-type: PKCS12
                """.formatted(upstreamServer.getAddress().getPort(), clientKeyStore, TLS_PASSWORD_ENV,
                clientTrustStore, TLS_PASSWORD_ENV);
        Path config = tempDir.resolve("json-sources.yaml");
        Files.writeString(config, yaml);
        return config;
    }

    private static void keytool(String... arguments) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "keytool").toString());
        command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        boolean finished = process.waitFor(30, TimeUnit.SECONDS);
        if (!finished || process.exitValue() != 0) {
            throw new IllegalStateException("keytool failed: " + output);
        }
    }

    private static String mintToken() throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(PRINCIPAL)
                .claim("azp", CLIENT_ID)
                .claim("roles", List.of("investigator"))
                .claim("purpose", CONFIGURED_PURPOSE)
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

    private static McpSyncClient client() throws Exception {
        String token = mintToken();

        // An explicit SSLContext, never java.net.http.HttpClient's own implicit
        // SSLContext.getDefault(): see this class's own Javadoc for why. This
        // connection is plain HTTP and never needs TLS at all, but building the
        // default HttpClient still resolves the JVM-wide singleton eagerly.
        TrustManagerFactory trustManagers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trustManagers.init((KeyStore) null);
        SSLContext explicitDefault = SSLContext.getInstance("TLS");
        explicitDefault.init(null, trustManagers.getTrustManagers(), null);

        HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport
                .builder("http://localhost:" + port)
                .endpoint("/mcp")
                .clientBuilder(HttpClient.newBuilder().sslContext(explicitDefault))
                .requestBuilder(HttpRequest.newBuilder().header("Authorization", "Bearer " + token))
                .build();
        McpSyncClient client = McpClient.sync(transport)
                .clientInfo(new McpSchema.Implementation("configured-json-nested-http-test", "1.0.0"))
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

    @Test
    @DisplayName("a nested object comes back scrubbed under its own catalogue, over the real HTTP/SSE transport")
    void nestedObjectComesBackScrubbed() throws Exception {
        String subject = "NESTED-OK-1";
        UPSTREAM_BODIES.put(subject, """
                {"id":"%s","name":"Raw Full Name","address":{"line1":"123 Main St","ssn":"111-22-3333"}}
                """.formatted(subject));

        McpSyncClient client = client();
        try {
            McpSchema.CallToolResult result = callGetEntityContext(client, subject);

            assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
            String body = text(result);
            assertThat(body).doesNotContain("Raw Full Name").doesNotContain("111-22-3333");

            var response = DataPrismObjectMapper.create().readTree(body);
            var entity = response.path("entity");
            assertThat(entity.path("address").path("line1").asText()).isEqualTo("123 Main St");
            assertThat(entity.path("address").path("ssn").asText())
                    .isNotBlank().isNotEqualTo("111-22-3333");
            assertThat(entity.path("name").asText()).isNotBlank().isNotEqualTo("Raw Full Name");
        } finally {
            client.closeGracefully();
        }
    }

    @Test
    @DisplayName("deeper-than-declared: a nested catalogue's scalar leaf arriving as a structure refuses "
            + "as NESTED_LEAF_NOT_SCALAR, with no value from the fixture in the response")
    void deeperThanDeclaredLeafRefuses() throws Exception {
        String subject = "NESTED-DEEPER-1";
        UPSTREAM_BODIES.put(subject, """
                {"id":"%s","name":"Raw Full Name",\
                "address":{"line1":"123 Main St","ssn":{"unexpected":"structure-not-for-any-real-data"}}}
                """.formatted(subject));

        McpSyncClient client = client();
        try {
            McpSchema.CallToolResult result = callGetEntityContext(client, subject);

            assertThat(result.isError()).isEqualTo(Boolean.TRUE);
            String body = text(result);
            assertThat(body).contains("NESTED_LEAF_NOT_SCALAR")
                    .doesNotContain("Raw Full Name")
                    .doesNotContain("123 Main St")
                    .doesNotContain("unexpected")
                    .doesNotContain("structure-not-for-any-real-data");
        } finally {
            client.closeGracefully();
        }
    }

    @Test
    @DisplayName("type mismatch at depth: a scalar arriving where the catalogue declares `nested:` "
            + "refuses as NESTED_FIELD_NOT_STRUCTURED rather than passing the scalar through")
    void scalarWhereNestedIsDeclaredRefuses() throws Exception {
        String subject = "NESTED-MISMATCH-1";
        UPSTREAM_BODIES.put(subject, """
                {"id":"%s","name":"Raw Full Name","address":"raw-scalar-address-not-for-any-real-data"}
                """.formatted(subject));

        McpSyncClient client = client();
        try {
            McpSchema.CallToolResult result = callGetEntityContext(client, subject);

            assertThat(result.isError()).isEqualTo(Boolean.TRUE);
            String body = text(result);
            assertThat(body).contains("NESTED_FIELD_NOT_STRUCTURED")
                    .doesNotContain("Raw Full Name")
                    .doesNotContain("raw-scalar-address-not-for-any-real-data");
        } finally {
            client.closeGracefully();
        }
    }

    @Test
    @DisplayName("a property inside the nested object that its catalogue does not declare "
            + "refuses as UNKNOWN_FIELD over the same transport")
    void undeclaredNestedPropertyRefuses() throws Exception {
        String subject = "NESTED-UNKNOWN-1";
        UPSTREAM_BODIES.put(subject, """
                {"id":"%s","name":"Raw Full Name",\
                "address":{"line1":"123 Main St","ssn":"111-22-3333","extraField":"leaked-if-allowed"}}
                """.formatted(subject));

        McpSyncClient client = client();
        try {
            McpSchema.CallToolResult result = callGetEntityContext(client, subject);

            assertThat(result.isError()).isEqualTo(Boolean.TRUE);
            String body = text(result);
            assertThat(body).contains("UNKNOWN_FIELD")
                    .doesNotContain("Raw Full Name")
                    .doesNotContain("111-22-3333")
                    .doesNotContain("leaked-if-allowed");
        } finally {
            client.closeGracefully();
        }
    }
}

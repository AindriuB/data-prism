package io.github.aindriub.dataprism.quickstart.extension;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The "no Docker daemon needed" half of task 18's acceptance list: starts the
 * fixture API, the token issuer and the packaged standalone server — with
 * this module's own extension jar loaded through {@code -Dloader.path}, the
 * exact mechanism {@code ServerPackagingIT} in {@code data-prism-server}
 * proves — as three real OS subprocesses, then drives {@code /mcp} with the
 * MCP SDK's own client transport, the same way {@code McpHttpEndToEndTest}
 * (data-prism-integration-tests) drives the embedded server. Task 21 gives the
 * standalone server no fixture-development bypass, so every token here is
 * minted by a real, running issuer process signing with a key nobody but that
 * process ever holds — never a token this test constructs itself.
 *
 * <p>The pseudonymisation assertions below are deliberately specific: not
 * "a response arrived" but that the two raw fixture values this quickstart
 * ships ({@code Fixture Person One}, {@code fixture.person.one@example.invalid})
 * are absent from the response, and that a stable, differently-shaped
 * synthetic name and a literal {@code [REDACTED]} are present in their place.
 * A privacy engine doing nothing — an adapter returning the fixture's own
 * JSON untouched — fails every one of those, not just the first.
 */
class QuickstartSmokeIT {

    private static final String ISSUER_ID = "https://issuer.quickstart.invalid";
    private static final String AUDIENCE = "data-prism-quickstart-mcp";
    private static final String STORE_PASSWORD = "quickstart-smoke-test-only";
    private static final String HMAC_ENVIRONMENT_VARIABLE = "DATAPRISM_QUICKSTART_SMOKE_HMAC_KEY";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    static Path tempDir;

    private static SSLContext trustingSslContext;
    private static HttpClient trustingHttpClient;
    private static Path keyStore;

    private static Process fixturesProcess;
    private static Process issuerProcess;
    private static Process serverProcess;
    private static int fixturesPort;
    private static int issuerPort;
    private static int serverPort;

    @BeforeAll
    static void startEverything() throws Exception {
        generateCertificateMaterial();
        trustingSslContext = buildTrustingSslContext();
        trustingHttpClient = HttpClient.newBuilder().sslContext(trustingSslContext).build();

        fixturesPort = freePort();
        fixturesProcess = startProcess(fixturesJar(), List.of(
                "--server.port=" + fixturesPort,
                "--server.ssl.key-store=file:" + keyStore,
                "--server.ssl.key-store-password=" + STORE_PASSWORD,
                "--server.ssl.key-store-type=PKCS12",
                "--server.ssl.key-alias=quickstart"), Map.of());
        awaitHealthy("https://127.0.0.1:" + fixturesPort + "/health");

        issuerPort = freePort();
        issuerProcess = startProcess(issuerJar(), List.of(
                "--server.port=" + issuerPort,
                "--server.ssl.key-store=file:" + keyStore,
                "--server.ssl.key-store-password=" + STORE_PASSWORD,
                "--server.ssl.key-store-type=PKCS12",
                "--server.ssl.key-alias=quickstart",
                "--quickstart.issuer.issuer-id=" + ISSUER_ID,
                "--quickstart.issuer.audience=" + AUDIENCE), Map.of());
        awaitHealthy("https://127.0.0.1:" + issuerPort + "/health");

        serverPort = freePort();
        List<String> serverArguments = List.of(
                "--server.port=" + serverPort,
                "--dataprism.security.jwt.issuer=" + ISSUER_ID,
                "--dataprism.security.jwt.audience=" + AUDIENCE,
                "--dataprism.security.jwt.jwk-set-uri=https://127.0.0.1:" + issuerPort + "/jwks",
                "--dataprism.security.caller-claims.principal=sub",
                "--dataprism.security.caller-claims.roles=roles",
                "--dataprism.security.caller-claims.investigation=case_id",
                "--dataprism.security-policy.purposes[0]=investigation",
                "--dataprism.security-policy.roles.investigator[0]=GET_ENTITY_CONTEXT",
                "--dataprism.privacy.profile=DEFAULT",
                "--dataprism.privacy.scope-lifetime=8h",
                "--dataprism.privacy.hmac-key.key-id=quickstart-smoke-v1",
                "--dataprism.privacy.hmac-key.environment-variable=" + HMAC_ENVIRONMENT_VARIABLE,
                "--dataprism.audit.sink=slf4j",
                "--dataprism.audit.writer-id=quickstart-smoke",
                "--dataprism.metrics.sink=micrometer",
                "--dataprism.hazelcast.topology=single-node",
                "--dataprism.sources.customer.base-url=https://127.0.0.1:" + fixturesPort,
                "--dataprism.sources.customer.timeout=5s");
        // Trust for the server's own outbound calls (JWKS discovery, the
        // fixture API) is per-JVM, so it is passed to this subprocess as a
        // JVM system property, exactly like production Java trust
        // configuration and like docker/server's JAVA_TOOL_OPTIONS.
        List<String> serverJvmArguments = List.of(
                "-Djavax.net.ssl.trustStore=" + keyStore,
                "-Djavax.net.ssl.trustStorePassword=" + STORE_PASSWORD,
                "-Djavax.net.ssl.trustStoreType=PKCS12",
                "-Dloader.path=" + extensionJar());
        serverProcess = startServerProcess(serverJvmArguments, serverArguments,
                Map.of(HMAC_ENVIRONMENT_VARIABLE, "quickstart-smoke-test-hmac-key-material-32-bytes-plus"));
        awaitHealthy("http://127.0.0.1:" + serverPort + "/health");
    }

    @AfterAll
    static void stopEverything() {
        destroy(serverProcess);
        destroy(issuerProcess);
        destroy(fixturesProcess);
    }

    @Test
    @DisplayName("a valid token from the running issuer gets a pseudonymised response, "
            + "with neither raw fixture value present")
    void validTokenReceivesAPseudonymisedResponse() throws Exception {
        String token = mintToken("investigator", "investigation", "CASE-QUICKSTART-SMOKE-1");
        McpSyncClient client = clientWithToken(token);
        try {
            McpSchema.CallToolResult result = callGetEntityContext(client, "1001");
            assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);

            String body = text(result);
            // The mutation this proves: an adapter or scrubber doing nothing
            // would return exactly these two raw fixture values verbatim.
            assertThat(body)
                    .doesNotContain("Fixture Person One")
                    .doesNotContain("fixture.person.one@example.invalid")
                    .doesNotContain("\"1001\"");

            JsonNode response = MAPPER.readTree(body);
            assertThat(response.path("subject").asText()).startsWith("SUBJ-").isNotEqualTo("1001");
            assertThat(response.path("entity").path("customerName").asText())
                    .matches("^[A-Za-z]+ [A-Za-z]+ \\([0-9A-Z]{8}\\)$");
            assertThat(response.path("entity").path("email").asText()).isEqualTo("[REDACTED]");
            assertThat(response.path("entity").path("status").asText()).isEqualTo("ACTIVE");
        } finally {
            client.closeGracefully();
        }
    }

    @Test
    @DisplayName("a request with no token is refused with 401")
    void absentTokenIsRefused() throws Exception {
        HttpResponse<String> response = trustingHttpClient.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + serverPort + "/mcp"))
                        .header("Content-Type", "application/json")
                        .header("Accept", "application/json, text/event-stream")
                        .POST(HttpRequest.BodyPublishers.ofString("{}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(401);
    }

    @Test
    @DisplayName("a syntactically invalid token is refused with 401, not treated as anonymous")
    void invalidTokenIsRefused() throws Exception {
        HttpResponse<String> response = trustingHttpClient.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + serverPort + "/mcp"))
                        .header("Content-Type", "application/json")
                        .header("Accept", "application/json, text/event-stream")
                        .header("Authorization", "Bearer not-a-real-token")
                        .POST(HttpRequest.BodyPublishers.ofString("{}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(401);
    }

    private static String mintToken(String role, String purpose, String caseId) throws Exception {
        String requestBody = MAPPER.writeValueAsString(Map.of(
                "subject", "quickstart-smoke-principal",
                "clientId", "quickstart-smoke-client",
                "roles", List.of(role),
                "purpose", purpose,
                "caseId", caseId,
                "ttlMinutes", 10));
        HttpResponse<String> response = trustingHttpClient.send(
                HttpRequest.newBuilder(URI.create("https://127.0.0.1:" + issuerPort + "/token"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("issuer refused to mint a token: " + response.body());
        }
        return MAPPER.readTree(response.body()).path("access_token").asText();
    }

    private static McpSyncClient clientWithToken(String token) {
        HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport
                .builder("http://127.0.0.1:" + serverPort)
                .endpoint("/mcp")
                .requestBuilder(HttpRequest.newBuilder().header("Authorization", "Bearer " + token))
                .build();
        McpSyncClient client = McpClient.sync(transport)
                .clientInfo(new McpSchema.Implementation("quickstart-smoke-it", "1.0.0"))
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

    private static Path fixturesJar() {
        return jarFor("data-prism-quickstart-fixtures");
    }

    private static Path issuerJar() {
        return jarFor("data-prism-quickstart-issuer");
    }

    private static Path extensionJar() {
        return Path.of("target", "data-prism-quickstart-extension-0.2.0.jar").toAbsolutePath();
    }

    private static Path jarFor(String moduleName) {
        return Path.of("..", moduleName, "target", moduleName + "-0.2.0.jar").toAbsolutePath();
    }

    private static Process startProcess(Path jar, List<String> programArguments, Map<String, String> env)
            throws IOException {
        assertThat(java.nio.file.Files.isRegularFile(jar))
                .as("expected a packaged jar at %s; the reactor must build it before this module's tests run", jar)
                .isTrue();
        List<String> command = new java.util.ArrayList<>();
        command.add(javaCommand());
        command.add("-jar");
        command.add(jar.toString());
        command.addAll(programArguments);
        return launch(command, env);
    }

    private static Process startServerProcess(List<String> jvmArguments, List<String> programArguments,
            Map<String, String> env) throws IOException {
        Path jar = Path.of("..", "data-prism-server", "target", "data-prism-server-0.2.0.jar")
                .toAbsolutePath();
        assertThat(java.nio.file.Files.isRegularFile(jar))
                .as("expected the packaged standalone server at %s", jar)
                .isTrue();
        List<String> command = new java.util.ArrayList<>();
        command.add(javaCommand());
        command.addAll(jvmArguments);
        command.add("-jar");
        command.add(jar.toString());
        command.addAll(programArguments);
        return launch(command, env);
    }

    private static Process launch(List<String> command, Map<String, String> env) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        builder.environment().putAll(env);
        Process process = builder.start();
        // Drains the child's combined stdout/stderr continuously in the
        // background: Spring Boot's own startup logging easily exceeds the
        // pipe buffer, and a child blocked writing to a full pipe never
        // reaches the health check this test polls next.
        Thread drain = new Thread(() -> {
            try {
                process.getInputStream().readAllBytes();
            } catch (IOException ignored) {
                // process ended; nothing left to drain
            }
        }, "quickstart-smoke-log-drain");
        drain.setDaemon(true);
        drain.start();
        return process;
    }

    private static void destroy(Process process) {
        if (process != null) {
            process.destroyForcibly();
        }
    }

    private static void awaitHealthy(String healthUri) throws Exception {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(60));
        IOException lastFailure = null;
        while (Instant.now().isBefore(deadline)) {
            try {
                HttpResponse<String> response = trustingHttpClient.send(
                        HttpRequest.newBuilder(URI.create(healthUri)).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200 && response.body().contains("UP")) {
                    return;
                }
            } catch (IOException e) {
                lastFailure = e;
            }
            Thread.sleep(500);
        }
        throw new IllegalStateException(healthUri + " never became healthy", lastFailure);
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static String javaCommand() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }

    /**
     * Generates one self-signed PKCS12 keystore, with a Subject Alternative
     * Name covering both loopback forms this test's subprocesses bind to, and
     * a matching truststore holding its public certificate. The same
     * mechanism {@code McpHttpEndToEndTest} (data-prism-integration-tests) uses for its
     * in-test JWKS server, promoted here to cover three real subprocesses
     * instead of one in-process one. Written only under {@code @TempDir},
     * which JUnit deletes when this class finishes; nothing generated here is
     * ever committed.
     */
    private static void generateCertificateMaterial() throws Exception {
        keyStore = tempDir.resolve("quickstart-smoke.p12");
        Path certificate = tempDir.resolve("quickstart-smoke.cer");
        keytool("-genkeypair", "-alias", "quickstart", "-keyalg", "RSA", "-keysize", "2048",
                "-validity", "2", "-keystore", keyStore.toString(), "-storetype", "PKCS12",
                "-storepass", STORE_PASSWORD, "-keypass", STORE_PASSWORD,
                "-dname", "CN=data-prism-quickstart-smoke",
                "-ext", "san=ip:127.0.0.1,dns:localhost");
        keytool("-exportcert", "-alias", "quickstart", "-keystore", keyStore.toString(),
                "-storetype", "PKCS12", "-storepass", STORE_PASSWORD, "-file", certificate.toString());
        Path trustStore = tempDir.resolve("quickstart-smoke-trust.p12");
        keytool("-importcert", "-alias", "quickstart", "-file", certificate.toString(),
                "-keystore", trustStore.toString(), "-storetype", "PKCS12",
                "-storepass", STORE_PASSWORD, "-noprompt");
    }

    private static SSLContext buildTrustingSslContext() throws Exception {
        Path trustStore = tempDir.resolve("quickstart-smoke-trust.p12");
        KeyStore trust = KeyStore.getInstance("PKCS12");
        try (var input = java.nio.file.Files.newInputStream(trustStore)) {
            trust.load(input, STORE_PASSWORD.toCharArray());
        }
        TrustManagerFactory trustManagers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trustManagers.init(trust);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, trustManagers.getTrustManagers(), null);
        return context;
    }

    private static void keytool(String... arguments) throws Exception {
        List<String> command = new java.util.ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "keytool").toString());
        command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        process.getInputStream().transferTo(output);
        if (process.waitFor() != 0) {
            throw new IllegalStateException("keytool failed: " + output.toString(StandardCharsets.UTF_8));
        }
    }
}

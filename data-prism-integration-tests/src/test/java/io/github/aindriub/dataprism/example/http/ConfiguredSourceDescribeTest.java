package io.github.aindriub.dataprism.example.http;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import io.github.aindriub.dataprism.connectors.rest.ConfiguredJsonPayload;
import io.github.aindriub.dataprism.core.model.FieldProtection;
import io.github.aindriub.dataprism.core.model.FieldProtection.Protection;
import io.github.aindriub.dataprism.core.model.InvestigationContext;
import io.github.aindriub.dataprism.core.model.ModelDescription;
import io.github.aindriub.dataprism.core.model.PrivacyContext;
import io.github.aindriub.dataprism.core.model.PrivacyScopeType;
import io.github.aindriub.dataprism.core.model.PseudonymisationVersion;
import io.github.aindriub.dataprism.core.spi.ScrubbingEngine;
import io.github.aindriub.dataprism.orchestration.ContextOrchestrator;
import io.github.aindriub.dataprism.orchestration.ContextRequest;
import io.github.aindriub.dataprism.orchestration.ContextResponse;
import io.github.aindriub.dataprism.orchestration.SourceResult;
import io.github.aindriub.dataprism.spring.boot.HmacKeyReferenceResolver;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A configured source is described through the real auto-configured context (task 299). Every configured
 * source shares one model class, so it can only be described by name, and only by the scrubber that really
 * scrubs it: the {@code ContextOrchestrator} bean's own, which is not the {@code dataPrismScrubber} bean on
 * a configured deployment.
 *
 * <p>The wiring is the operator's: a {@code json-sources:} YAML read through
 * {@code dataprism.json-sources.config-location}, naming a real TLS upstream. See
 * {@link ConfiguredJsonNestedHttpTest}, whose fixture this one repeats, for why each step is done as it is.
 */
class ConfiguredSourceDescribeTest {

    private static final String STORE_PASSWORD = "dp-test-only-not-a-real-secret";
    private static final String TLS_PASSWORD_ENV = "TASK61_TLS_PASSWORD";
    private static final String SOURCE = "customer-nested-api";
    private static final String SUBJECT = "DESCRIBE-1";
    private static final String BODY = """
            {"id":"DESCRIBE-1","name":"Raw Full Name","address":{"line1":"123 Main St","ssn":"111-22-3333"}}""";

    @TempDir
    static Path tempDir;

    private static HttpsServer upstreamServer;
    private static ConfigurableApplicationContext context;

    @BeforeAll
    static void startFixturesAndApplication() throws Exception {
        assertThat(System.getenv(TLS_PASSWORD_ENV))
                .as("test relies on the module POM's surefire env config matching STORE_PASSWORD")
                .isEqualTo(STORE_PASSWORD);
        reactor.core.scheduler.Schedulers.boundedElastic().schedule(() -> { });

        Path clientKeyStore = tempDir.resolve("upstream-client-keystore.p12");
        Path clientTrustStore = tempDir.resolve("upstream-client-truststore.p12");
        startUpstreamFixture(clientKeyStore, clientTrustStore);

        RSAKey signingKey = new RSAKeyGenerator(2048).keyID("test-signing-key-task-299")
                .algorithm(JWSAlgorithm.RS256).generate();
        Path config = writeSourceConfig(clientKeyStore, clientTrustStore);

        HmacKeyReferenceResolver testKeys = (keyId, reference) ->
                "task-299-test-only-key-material-longer-than-thirty-two-bytes".getBytes(StandardCharsets.UTF_8);
        JwtDecoder testDecoder = NimbusJwtDecoder.withPublicKey((RSAPublicKey) signingKey.toRSAPublicKey()).build();

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
    }

    @AfterAll
    static void stopFixturesAndApplication() {
        context.close();
        upstreamServer.stop(0);
    }

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
                "-dname", "CN=configured-source-describe-test-client");
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
            byte[] body = BODY.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        upstreamServer.start();
    }

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

    private static PrivacyContext privacy() {
        return new PrivacyContext("SCOPE-299", PrivacyScopeType.INVESTIGATION, "DEFAULT", "investigation",
                Instant.parse("2035-01-01T00:00:00Z"),
                PseudonymisationVersion.HMAC_SHA256_V1.withKey("v1").withVocabulary("und"));
    }

    private static void leaves(JsonNode node, String path, List<String> into) {
        if (node.isObject()) {
            node.properties().forEach(e -> leaves(e.getValue(), path.isEmpty() ? e.getKey() : path + "." + e.getKey(),
                    into));
        } else if (node.isArray()) {
            node.forEach(element -> leaves(element, path + "[]", into));
        } else {
            into.add(path);
        }
    }

    private static JsonNode at(JsonNode root, String path) {
        JsonNode node = root;
        for (String segment : path.split("\\.")) {
            node = node.path(segment);
        }
        return node;
    }

    @Test
    @DisplayName("a configured source describes non-empty from the ContextOrchestrator bean")
    void configuredSourceDescribesFromTheOrchestratorBean() {
        ContextOrchestrator orchestrator = context.getBean(ContextOrchestrator.class);

        assertThat(orchestrator.describe(SOURCE, privacy(), false)).isNotEmpty();
        assertThat(orchestrator.describe("no-such-source", privacy(), false)).isEmpty();
    }

    @Test
    @DisplayName("the dataPrismScrubber bean cannot describe a configured source: only the orchestrator's can")
    void dataPrismScrubberCannotDescribeAConfiguredSource() {
        ScrubbingEngine dataPrismScrubber = context.getBean("dataPrismScrubber", ScrubbingEngine.class);

        assertThat(dataPrismScrubber.describe(SOURCE, ConfiguredJsonPayload.class, privacy(), false)).isEmpty();
    }

    @Test
    @DisplayName("every path of a real scrubbed response is described with the protection it was scrubbed under")
    void everyScrubbedPathIsDescribedWithItsProtection() throws Exception {
        ContextOrchestrator orchestrator = context.getBean(ContextOrchestrator.class);
        PrivacyContext privacy = privacy();

        ContextResponse response = orchestrator.buildContext(ContextRequest.of("CUSTOMER", SUBJECT), privacy,
                new InvestigationContext("jwt-principal-task-299", "jwt-client-task-299", "CASE-TASK-299",
                        Set.of()));
        ModelDescription description = orchestrator.describe(SOURCE, privacy, false).orElseThrow();

        SourceResult answered = response.sources().values().stream()
                .filter(r -> r.entity() != null).findFirst().orElseThrow();
        JsonNode raw = JsonMapper.builder().build().readTree(BODY);
        Map<String, FieldProtection> byPath = new HashMap<>();
        description.fields().forEach(f -> byPath.put(f.path(), f));
        Map<String, Protection> expected = Map.of(
                "name", Protection.SYNTHETIC,
                "address.line1", Protection.VERBATIM,
                "address.ssn", Protection.SYNTHETIC);

        List<String> emitted = new ArrayList<>();
        leaves(answered.entity(), "", emitted);
        assertThat(emitted).as("the scrubbed leaves").contains("name", "address.line1", "address.ssn");
        for (String leaf : emitted) {
            assertThat(byPath).as("emitted path %s is described", leaf).containsKey(leaf);
            if (expected.containsKey(leaf)) {
                assertThat(byPath.get(leaf).protection()).as("protection of %s", leaf).isEqualTo(expected.get(leaf));
            }
            // What was emitted agrees with what is described: verbatim only where the value is the raw one.
            boolean verbatim = at(answered.entity(), leaf).asString().equals(at(raw, leaf).asString());
            assertThat(byPath.get(leaf).protection() == Protection.VERBATIM)
                    .as("%s is verbatim in the response iff it is described VERBATIM", leaf).isEqualTo(verbatim);
        }
    }
}

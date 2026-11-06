package io.github.aindriub.dataprism.example.http;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import io.github.aindriub.dataprism.core.model.FieldProtection;
import io.github.aindriub.dataprism.core.model.FieldProtection.Protection;
import io.github.aindriub.dataprism.core.model.InvestigationContext;
import io.github.aindriub.dataprism.core.model.ModelDescription;
import io.github.aindriub.dataprism.core.model.PrivacyContext;
import io.github.aindriub.dataprism.core.model.PrivacyScopeType;
import io.github.aindriub.dataprism.core.model.PseudonymisationVersion;
import io.github.aindriub.dataprism.core.spi.DataSourceAdapter;
import io.github.aindriub.dataprism.core.spi.ScrubbingEngine;
import io.github.aindriub.dataprism.orchestration.ContextOrchestrator;
import io.github.aindriub.dataprism.orchestration.ContextRequest;
import io.github.aindriub.dataprism.orchestration.ContextResponse;
import io.github.aindriub.dataprism.orchestration.SourceResult;
import io.github.aindriub.dataprism.spring.boot.HmacKeyReferenceResolver;
import io.github.aindriub.dataprism.core.model.Capability;
import io.github.aindriub.dataprism.orchestration.OrchestrationOptions;
import io.github.aindriub.dataprism.orchestration.catalog.SourceCatalog;
import io.github.aindriub.dataprism.spring.boot.DataPrismConfigurationException;
import org.springframework.test.util.ReflectionTestUtils;
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
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * The catalog settings and the base-url hosts reach a configured source through the real auto-configured context
 * (task 254). A deployment with {@code dataprism.json-sources.config-location} gets its orchestrator from the
 * connector module, which must use the catalog the starter built, with {@code dataprism.catalog.*} applied and the
 * base-url host of every configured source known to the description checks.
 */
class ConfiguredSourceCatalogWiringTest {

    private static final String STORE_PASSWORD = "dp-test-only-not-a-real-secret";
    private static final String TLS_PASSWORD_ENV = "TASK61_TLS_PASSWORD";
    private static final String SOURCE = "customer-nested-api";
    private static final String SUBJECT = "CATALOG-1";
    private static final String BODY = """
            {"id":"CATALOG-1","name":"Raw Full Name","address":{"line1":"123 Main St","ssn":"111-22-3333"}}""";

    @TempDir
    static Path tempDir;

    private static HttpsServer upstreamServer;
    private static ConfigurableApplicationContext context;

    private static HmacKeyReferenceResolver testKeys;
    private static JwtDecoder testDecoder;
    private static Path clientKeyStore;
    private static Path clientTrustStore;

    @BeforeAll
    static void startFixturesAndApplication() throws Exception {
        assertThat(System.getenv(TLS_PASSWORD_ENV))
                .as("test relies on the module POM's surefire env config matching STORE_PASSWORD")
                .isEqualTo(STORE_PASSWORD);
        reactor.core.scheduler.Schedulers.boundedElastic().schedule(() -> { });

        clientKeyStore = tempDir.resolve("upstream-client-keystore.p12");
        clientTrustStore = tempDir.resolve("upstream-client-truststore.p12");
        startUpstreamFixture(clientKeyStore, clientTrustStore);

        RSAKey signingKey = new RSAKeyGenerator(2048).keyID("test-signing-key-task-254")
                .algorithm(JWSAlgorithm.RS256).generate();
        Path config = writeSourceConfig(clientKeyStore, clientTrustStore, "https://127.0.0.1:" + upstreamServer.getAddress().getPort(), "");

        testKeys = (keyId, reference) ->
                "task-254-test-only-key-material-longer-than-thirty-two-bytes".getBytes(StandardCharsets.UTF_8);
        testDecoder = NimbusJwtDecoder.withPublicKey((RSAPublicKey) signingKey.toRSAPublicKey()).build();

        context = start(config, "--dataprism.catalog.real-names-scopes[0]=SCOPE-254");
    }

    /** The application over {@code config}, with the extra command-line arguments. */
    private static ConfigurableApplicationContext start(Path config, String... arguments) {
        List<String> all = new ArrayList<>(List.of("--server.port=0"));
        all.addAll(List.of(arguments));
        System.setProperty("dataprism.json-sources.config-location", "file:" + config);
        try {
            return new SpringApplicationBuilder(ResourceServerApplication.class)
                    .web(WebApplicationType.SERVLET)
                    .initializers(applicationContext -> {
                        applicationContext.getBeanFactory()
                                .registerSingleton("testHmacKeyReferenceResolver", testKeys);
                        applicationContext.getBeanFactory().registerSingleton("testJwtDecoder", testDecoder);
                    })
                    .run(all.toArray(String[]::new));
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

    private static Path writeSourceConfig(Path clientKeyStore, Path clientTrustStore, String baseUrl,
            String description) throws Exception {
        String yaml = """
                json-sources:
                  customer-nested-api:
                    base-url: %s
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
                """.formatted(baseUrl, clientKeyStore, TLS_PASSWORD_ENV,
                clientTrustStore, TLS_PASSWORD_ENV);
        yaml = yaml.replace("    path: /nested/{subject}\n", description + "    path: /nested/{subject}\n");
        Path config = tempDir.resolve("json-sources-" + System.nanoTime() + ".yaml");
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
        return new PrivacyContext("SCOPE-254", PrivacyScopeType.INVESTIGATION, "DEFAULT", "investigation",
                Instant.parse("2035-01-01T00:00:00Z"),
                PseudonymisationVersion.HMAC_SHA256_V1.withKey("example-v1").withVocabulary("generic-v1#9136656a"));
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

    private static InvestigationContext caller(boolean exposeNames) {
        return new InvestigationContext("jwt-principal-task-254", "jwt-client-task-254", "CASE-TASK-254",
                exposeNames ? Set.of(Capability.EXPOSE_SOURCE_NAMES) : Set.of());
    }

    private static PrivacyContext privacy(String scope) {
        return new PrivacyContext(scope, PrivacyScopeType.INVESTIGATION, "DEFAULT", "investigation",
                Instant.parse("2035-01-01T00:00:00Z"),
                PseudonymisationVersion.HMAC_SHA256_V1.withKey("example-v1").withVocabulary("generic-v1#9136656a"));
    }

    @Test
    @DisplayName("a real-names-scopes entry that matches the scope gives a configured source its real name")
    void matchingScopeGivesTheRealName() {
        ContextOrchestrator orchestrator = context.getBean(ContextOrchestrator.class);

        ContextResponse response = orchestrator.buildContext(ContextRequest.of("CUSTOMER", SUBJECT),
                privacy("SCOPE-254"), caller(true));

        assertThat(response.sources().keySet()).contains(SOURCE);
    }

    @Test
    @DisplayName("a scope that no entry matches still sees an alias, and so does a caller without the capability")
    void otherScopesAndCallersSeeAnAlias() {
        ContextOrchestrator orchestrator = context.getBean(ContextOrchestrator.class);

        assertThat(orchestrator.buildContext(ContextRequest.of("CUSTOMER", SUBJECT), privacy("SCOPE-OTHER"),
                caller(true)).sources().keySet()).doesNotContain(SOURCE);
        assertThat(orchestrator.buildContext(ContextRequest.of("CUSTOMER", SUBJECT), privacy("SCOPE-254"),
                caller(false)).sources().keySet()).doesNotContain(SOURCE);
    }

    @Test
    @DisplayName("the configured orchestrator serves the catalog the starter built, one catalog for the context")
    void theConfiguredOrchestratorUsesTheStartersCatalog() {
        SourceCatalog catalog = context.getBean("dataPrismSourceCatalog", SourceCatalog.class);

        assertThat(catalog.entries()).extracting(SourceCatalog.Entry::name).contains(SOURCE);
        assertThat(context.getBeansOfType(SourceCatalog.class)).hasSize(1);
        assertThat(ReflectionTestUtils.getField(context.getBean(ContextOrchestrator.class), "catalog"))
                .isSameAs(catalog);
    }

    @Test
    @DisplayName("the catalog settings reach the configured orchestrator, which no longer hardcodes the defaults")
    void theCatalogSettingsReachTheConfiguredOrchestrator() {
        OrchestrationOptions options = (OrchestrationOptions) ReflectionTestUtils
                .getField(context.getBean(ContextOrchestrator.class), "options");

        assertThat(options.realNames().permits("SCOPE-254")).isTrue();
        assertThat(options.realNames().permits("SCOPE-OTHER")).isFalse();
    }

    @Test
    @DisplayName("an alias description naming the source's base-url host refuses startup with "
            + "SOURCE_DESCRIPTION_LEAKS_NAME, without the text")
    void anAliasDescriptionNamingTheHostRefusesStartup() throws Exception {
        Path config = writeSourceConfig(clientKeyStore, clientTrustStore, "https://upstream-gateway:8443", """
                    description:
                      entity-types: [CUSTOMER]
                      alias-description: "Customer records held by the upstream-gateway service"
                """);

        Throwable failure = catchThrowable(() -> start(config));

        assertThat(failure).isNotNull();
        DataPrismConfigurationException refusal = null;
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof DataPrismConfigurationException e) {
                refusal = e;
            }
        }
        assertThat(refusal).as("a DataPrismConfigurationException in the cause chain").isNotNull();
        assertThat(refusal.code()).isEqualTo("SOURCE_DESCRIPTION_LEAKS_NAME");
        assertThat(refusal.getMessage()).doesNotContain("upstream-gateway").doesNotContain("Customer records");
    }
}

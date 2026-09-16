package io.github.aindriub.dataprism.server;

import io.github.aindriub.dataprism.audit.AuditSink;
import io.github.aindriub.dataprism.core.DataRequest;
import io.github.aindriub.dataprism.core.DataSourceAdapter;
import io.github.aindriub.dataprism.core.PrivacyMetrics;
import io.github.aindriub.dataprism.core.IdentityResolver;
import io.github.aindriub.dataprism.core.PassThroughIdentityResolver;
import io.github.aindriub.dataprism.spring.boot.DataPrismConfigurationException;
import io.github.aindriub.dataprism.spring.boot.HmacKeyReferenceResolver;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

class ServerStartupTest {
    @Test
    void minimalReviewedExtensionStartsAndExposesOnlySafeUnauthenticatedHealth() throws Exception {
        try (ConfigurableApplicationContext context = start(true, validConfiguration())) {
            int port = ((ServletWebServerApplicationContext) context).getWebServer().getPort();
            HttpClient client = HttpClient.newHttpClient();

            HttpResponse<String> health = client.send(request(port, "/health").GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            HttpResponse<String> mcp = client.send(request(port, "/mcp")
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString("{}" )).build(),
                    HttpResponse.BodyHandlers.ofString());
            HttpResponse<String> unrelated = client.send(request(port, "/actuator/health").GET().build(),
                    HttpResponse.BodyHandlers.ofString());

            assertThat(health.statusCode()).isEqualTo(200);
            assertThat(health.body()).isEqualTo("{\"status\":\"UP\"}");
            assertThat(mcp.statusCode()).isEqualTo(401);
            assertThat(unrelated.statusCode()).isIn(401, 403, 404);
        }
    }

    @Test
    void missingReviewedSourceAdapterRefusesStartup() {
        Throwable failure = catchThrowable(() -> {
            try (var ignored = start(false, validConfiguration())) { }
        });

        assertConfigurationFailure(failure, "UNRESOLVED_SOURCE_ADAPTER");
    }

    @Test
    void missingJwtIssuerRefusesStartup() {
        Throwable failure = catchThrowable(() -> {
            try (var ignored = start(true, validConfigurationWithoutIssuer())) { }
        });

        assertConfigurationFailure(failure, "MISSING_JWT_ISSUER");
    }

    @Test
    void fixtureDevelopmentModeRefusesStartup() {
        String[] fixtureConfiguration = java.util.stream.Stream.concat(
                java.util.Arrays.stream(validConfiguration()),
                java.util.stream.Stream.of("--dataprism.transport.fixture-development=true"))
                .toArray(String[]::new);
        Throwable failure = catchThrowable(() -> {
            try (var ignored = start(true, fixtureConfiguration)) { }
        });

        assertConfigurationFailure(failure, "FIXTURE_DEVELOPMENT_STDIO_ONLY");
    }

    @Test
    void stdioTransportModeRefusesStartup() {
        String[] stdioConfiguration = java.util.stream.Stream.concat(
                java.util.Arrays.stream(validConfiguration()),
                java.util.stream.Stream.of("--dataprism.transport.mode=stdio",
                        "--dataprism.transport.fixture-development=true"))
                .toArray(String[]::new);
        Throwable failure = catchThrowable(() -> {
            try (var ignored = start(true, stdioConfiguration)) { }
        });

        assertConfigurationFailure(failure, "STANDALONE_HTTP_ONLY");
    }

    /**
     * data-prism-server never wires a non-servlet MCP transport, so a
     * {@code spring.main.web-application-type=none} deployment at the default
     * {@code dataprism.transport.mode=HTTP} would previously start with no MCP
     * transport registered at all (task 39). The starter's {@code
     * dataPrismMcpTransportPreflight} refuses before any DataPrism singleton is
     * built, which is why this fires with {@code MCP_TRANSPORT_UNAVAILABLE}
     * rather than {@code ServerIntegrationsConfiguration}'s
     * {@code STANDALONE_HTTP_ONLY}: that bean is never reached on this path.
     */
    @Test
    void webApplicationTypeNoneRefusesStartupWithNoMcpTransport() {
        Throwable failure = catchThrowable(() -> {
            try (var ignored = startNone(validConfiguration())) { }
        });

        assertConfigurationFailure(failure, "MCP_TRANSPORT_UNAVAILABLE");
    }

    private static ConfigurableApplicationContext startNone(String[] configuration) {
        SpringApplicationBuilder application = new SpringApplicationBuilder(DataPrismServerApplication.class)
                .web(WebApplicationType.NONE)
                .logStartupInfo(false)
                .initializers(context -> {
                    var beans = context.getBeanFactory();
                    beans.registerSingleton("testKeys", (HmacKeyReferenceResolver) (keyId, reference) ->
                            "task-17-test-only-key-material-longer-than-thirty-two-bytes"
                                    .getBytes(StandardCharsets.UTF_8));
                    beans.registerSingleton("testAudit", (AuditSink) event -> { });
                    beans.registerSingleton("testMetrics", PrivacyMetrics.none());
                    beans.registerSingleton("testIdentityResolver", (IdentityResolver) new PassThroughIdentityResolver());
                    beans.registerSingleton("testCustomerAdapter", testAdapter());
                });
        return application.run(configuration);
    }

    @Test
    void providerReferenceWithoutAReviewedResolverRefusesStartup() {
        Throwable failure = catchThrowable(() -> {
            try (var ignored = start(true, false, true, validConfiguration())) { }
        });

        assertConfigurationFailure(failure, "MISSING_KEY_PROVIDER");
    }

    @Test
    void approvedAuditSinkWithoutAReviewedBindingRefusesStartup() {
        Throwable failure = catchThrowable(() -> {
            try (var ignored = start(true, true, false, validConfiguration())) { }
        });

        assertConfigurationFailure(failure, "MISSING_AUDIT_SINK");
    }

    @Test
    void missingReviewedIdentityResolverRefusesStartup() {
        Throwable failure = catchThrowable(() -> {
            try (var ignored = start(true, true, true, false, validConfiguration())) { }
        });

        assertConfigurationFailure(failure, "MISSING_IDENTITY_RESOLVER");
    }

    private static ConfigurableApplicationContext start(boolean withAdapter, String[] configuration) {
        return start(withAdapter, true, true, true, configuration);
    }

    private static ConfigurableApplicationContext start(boolean withAdapter, boolean withKeys,
            boolean withAudit, String[] configuration) {
        return start(withAdapter, withKeys, withAudit, true, configuration);
    }

    private static ConfigurableApplicationContext start(boolean withAdapter, boolean withKeys,
            boolean withAudit, boolean withIdentity, String[] configuration) {
        SpringApplicationBuilder application = new SpringApplicationBuilder(DataPrismServerApplication.class)
                .web(WebApplicationType.SERVLET)
                .logStartupInfo(false)
                .initializers(context -> {
                    var beans = context.getBeanFactory();
                    if (withKeys) beans.registerSingleton("testKeys",
                            (HmacKeyReferenceResolver) (keyId, reference) ->
                                    "task-17-test-only-key-material-longer-than-thirty-two-bytes"
                                            .getBytes(StandardCharsets.UTF_8));
                    if (withAudit) beans.registerSingleton("testAudit", (AuditSink) event -> { });
                    beans.registerSingleton("testMetrics", PrivacyMetrics.none());
                    if (withIdentity) beans.registerSingleton("testIdentityResolver",
                            (IdentityResolver) new PassThroughIdentityResolver());
                    if (withAdapter) beans.registerSingleton("testCustomerAdapter", testAdapter());
                });
        return application.run(configuration);
    }

    private static DataSourceAdapter<String> testAdapter() {
        return new DataSourceAdapter<>() {
            @Override public String sourceName() { return "customer"; }
            @Override public Class<String> responseType() { return String.class; }
            @Override public String fetch(DataRequest request) { return null; }
        };
    }

    private static HttpRequest.Builder request(int port, String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
    }

    private static void assertConfigurationFailure(Throwable failure, String code) {
        assertThat(failure).isNotNull();
        Throwable cursor = failure;
        while (cursor.getCause() != null) cursor = cursor.getCause();
        assertThat(cursor).isInstanceOf(DataPrismConfigurationException.class)
                .hasMessageStartingWith(code + ":");
    }

    private static String[] validConfigurationWithoutIssuer() {
        return java.util.Arrays.stream(validConfiguration())
                .filter(value -> !value.startsWith("--dataprism.security.jwt.issuer="))
                .toArray(String[]::new);
    }

    private static String[] validConfiguration() {
        return new String[] {
                "--server.port=0", "--spring.main.banner-mode=off",
                "--dataprism.security.jwt.issuer=https://issuer.example",
                "--dataprism.security.jwt.audience=mcp",
                "--dataprism.security.jwt.jwk-set-uri=https://issuer.example/jwks",
                "--dataprism.security.caller-claims.principal=sub",
                "--dataprism.security.caller-claims.roles=roles",
                "--dataprism.security.caller-claims.investigation=case_id",
                "--dataprism.security-policy.purposes[0]=investigation",
                "--dataprism.security-policy.roles.investigator[0]=GET_ENTITY_CONTEXT",
                "--dataprism.privacy.profile=DEFAULT",
                "--dataprism.privacy.scope-lifetime=8h",
                "--dataprism.privacy.hmac-key.key-id=v1",
                "--dataprism.privacy.hmac-key.provider-reference=test-key",
                "--dataprism.audit.sink=approved-sink",
                "--dataprism.audit.writer-id=test-server",
                "--dataprism.metrics.sink=micrometer",
                "--dataprism.hazelcast.topology=single-node",
                "--dataprism.sources.customer.base-url=https://customer.example",
                "--dataprism.sources.customer.timeout=2s"
        };
    }
}

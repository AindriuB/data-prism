package io.github.aindriub.dataprism.example.http;

import io.github.aindriub.dataprism.audit.AuditSink;
import io.github.aindriub.dataprism.core.DataRequest;
import io.github.aindriub.dataprism.core.DataSourceAdapter;
import io.github.aindriub.dataprism.core.IdentityResolver;
import io.github.aindriub.dataprism.core.PassThroughIdentityResolver;
import io.github.aindriub.dataprism.core.PrivacyMetrics;
import io.github.aindriub.dataprism.spring.boot.DataPrismConfigurationException;
import io.github.aindriub.dataprism.spring.boot.HmacKeyReferenceResolver;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

class StarterStartupFailureTest {

    /**
     * Runs as a genuine servlet web application ({@link #startAsServlet}), not {@code
     * WebApplicationType.NONE}: at that type, {@code dataPrismMcpTransportPreflight}
     * refuses with {@code MCP_TRANSPORT_UNAVAILABLE} before the source-adapter check
     * below is ever reached. Same reasoning applies to
     * {@link #httpFixtureDevelopmentPreventsTheApplicationStarting()}.
     */
    @Test
    void missingAdapterPreventsTheApplicationStarting() {
        Throwable failure = catchThrowable(() -> startAsServlet(MissingAdapterApplication.class, servletConfiguration()));

        assertConfigurationFailure(failure, "UNRESOLVED_SOURCE_ADAPTER");
    }

    @Test
    void missingIdentityResolverPreventsTheApplicationStarting() {
        Throwable failure = catchThrowable(() -> start(MissingIdentityApplication.class));

        assertConfigurationFailure(failure, "MISSING_IDENTITY_RESOLVER");
    }

    @Test
    void httpFixtureDevelopmentPreventsTheApplicationStarting() {
        Throwable failure =
                catchThrowable(() -> startAsServlet(MissingAdapterApplication.class, httpFixtureServletConfiguration()));

        assertConfigurationFailure(failure, "FIXTURE_DEVELOPMENT_STDIO_ONLY");
    }

    /**
     * Pins {@code MCP_TRANSPORT_UNAVAILABLE} directly, rather than only as a side effect of
     * the other tests in this class.
     */
    @Test
    void nonWebApplicationAtDefaultHttpModeRefusesWithNoTransportAvailable() {
        Throwable failure = catchThrowable(() -> start(MissingAdapterApplication.class));

        assertConfigurationFailure(failure, "MCP_TRANSPORT_UNAVAILABLE");
    }

    private static void start(Class<?> application) {
        start(application, validConfiguration());
    }

    private static void start(Class<?> application, String[] configuration) {
        try (var ignored = new SpringApplicationBuilder(application)
                .web(WebApplicationType.NONE)
                .logStartupInfo(false)
                .run(configuration)) {
            throw new AssertionError("application unexpectedly started");
        }
    }

    private static void startAsServlet(Class<?> application, String[] configuration) {
        try (var ignored = new SpringApplicationBuilder(application)
                .web(WebApplicationType.SERVLET)
                .logStartupInfo(false)
                .run(configuration)) {
            throw new AssertionError("application unexpectedly started");
        }
    }

    private static void assertConfigurationFailure(Throwable failure, String code) {
        assertThat(failure).isNotNull();
        Throwable root = failure;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        assertThat(root).isInstanceOf(DataPrismConfigurationException.class)
                .hasMessageStartingWith(code + ":");
    }

    private static String[] httpFixtureConfiguration() {
        String[] valid = validConfiguration();
        String[] configuration = java.util.Arrays.copyOf(valid, valid.length + 2);
        configuration[valid.length] = "--dataprism.transport.mode=http";
        configuration[valid.length + 1] = "--dataprism.transport.fixture-development=true";
        return configuration;
    }

    /** {@code validConfiguration()} plus {@code --server.port=0} (an ephemeral port). */
    private static String[] servletConfiguration() {
        return withServerPort(validConfiguration());
    }

    /** As {@link #servletConfiguration()}, but for {@link #httpFixtureConfiguration()}. */
    private static String[] httpFixtureServletConfiguration() {
        return withServerPort(httpFixtureConfiguration());
    }

    private static String[] withServerPort(String[] configuration) {
        String[] result = java.util.Arrays.copyOf(configuration, configuration.length + 1);
        result[configuration.length] = "--server.port=0";
        return result;
    }

    private static String[] validConfiguration() {
        return new String[] {
                "--spring.main.banner-mode=off",
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
                "--dataprism.privacy.hmac-key.environment-variable=",
                "--dataprism.privacy.hmac-key.provider-reference=test-key",
                "--dataprism.audit.sink=approved-sink",
                "--dataprism.audit.writer-id=test",
                "--dataprism.metrics.sink=micrometer",
                "--dataprism.hazelcast.topology=single-node",
                "--dataprism.sources.customer.base-url=https://customer.example",
                "--dataprism.sources.customer.timeout=2s"
        };
    }

    static class RequiredIntegrations {
        @Bean HmacKeyReferenceResolver keys() {
            return (keyId, reference) -> "task-16-startup-test-key-material-longer-than-32-bytes".getBytes();
        }
        @Bean AuditSink audit() { return event -> { }; }
        @Bean PrivacyMetrics metrics() { return PrivacyMetrics.none(); }
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    static class MissingAdapterApplication extends RequiredIntegrations {
        @Bean IdentityResolver identities() { return new PassThroughIdentityResolver(); }
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    static class MissingIdentityApplication extends RequiredIntegrations {
        @Bean DataSourceAdapter<String> customer() {
            return new DataSourceAdapter<>() {
                @Override public String sourceName() { return "customer"; }
                @Override public Class<String> responseType() { return String.class; }
                @Override public String fetch(DataRequest request) { return null; }
            };
        }
    }
}

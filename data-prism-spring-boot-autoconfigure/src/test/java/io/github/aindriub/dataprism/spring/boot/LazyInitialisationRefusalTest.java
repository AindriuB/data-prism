package io.github.aindriub.dataprism.spring.boot;

import org.junit.jupiter.api.Test;
import org.springframework.boot.LazyInitializationBeanFactoryPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Startup refusals that live in beans nothing else depends on must still fire when every bean is lazy. The
 * context runner ignores {@code spring.main.lazy-initialization}, so the post-processor SpringApplication
 * registers for that property is added directly.
 */
class LazyInitialisationRefusalTest {

    private final WebApplicationContextRunner lazy = new WebApplicationContextRunner()
            .withInitializer(context -> context.addBeanFactoryPostProcessor(
                    new LazyInitializationBeanFactoryPostProcessor()))
            .withConfiguration(AutoConfigurations.of(DataPrismAutoConfiguration.class))
            .withPropertyValues(valid());

    @Test
    void the_stdio_transport_is_refused_under_lazy_initialisation() {
        lazy.withUserConfiguration(DataPrismAutoConfigurationTest.ReviewedHttpIntegrations.class)
                .withPropertyValues("dataprism.transport.mode=stdio", "dataprism.transport.fixture-development=true")
                .run(result -> assertThat(rootMessage(result.getStartupFailure()))
                        .startsWith("STDIO_TRANSPORT_UNSUPPORTED:"));
    }

    @Test
    void a_missing_caller_context_extractor_is_refused_under_lazy_initialisation() {
        lazy.withUserConfiguration(DataPrismAutoConfigurationTest.ReviewedIntegrations.class)
                .run(result -> assertThat(rootMessage(result.getStartupFailure()))
                        .startsWith("MISSING_CALLER_CONTEXT_EXTRACTOR:"));
    }

    @Test
    void an_invalid_property_is_refused_under_lazy_initialisation() {
        lazy.withUserConfiguration(DataPrismAutoConfigurationTest.ReviewedHttpIntegrations.class)
                .withPropertyValues("dataprism.sources.customer.base-url=http://api.example")
                .run(result -> assertThat(rootMessage(result.getStartupFailure()))
                        .startsWith("INVALID_SOURCE_URL:"));
    }

    /** Only dataPrismPropertiesValidated raises this; the property binding alone accepts the value. */
    @Test
    void an_unknown_privacy_profile_is_refused_under_lazy_initialisation() {
        lazy.withUserConfiguration(DataPrismAutoConfigurationTest.ReviewedHttpIntegrations.class)
                .withPropertyValues("dataprism.privacy.profile=NO_SUCH_PROFILE")
                .run(result -> assertThat(rootMessage(result.getStartupFailure()))
                        .startsWith("UNKNOWN_PRIVACY_PROFILE:"));
    }

    @Test
    void a_valid_configuration_still_starts_under_lazy_initialisation() {
        lazy.withUserConfiguration(DataPrismAutoConfigurationTest.ReviewedHttpIntegrations.class)
                .run(result -> assertThat(result).hasNotFailed());
    }

    private static String rootMessage(Throwable failure) {
        assertThat(failure).isNotNull();
        Throwable current = failure;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage();
    }

    private static String[] valid() {
        return new String[] {
                "dataprism.security.jwt.issuer=https://issuer.example", "dataprism.security.jwt.audience=mcp",
                "dataprism.security.jwt.jwk-set-uri=https://issuer.example/jwks",
                "dataprism.security.caller-claims.principal=sub", "dataprism.security.caller-claims.roles=roles",
                "dataprism.security.caller-claims.investigation=case_id",
                // A real deployment supplies a CaseAuthorizer bean and lists its allowed clients; this one trusts the token.
                "dataprism.security-policy.case-authorisation=token-trusted",
                "dataprism.security-policy.client-allowlist=unrestricted",
                "dataprism.security-policy.purposes[0]=investigation",
                "dataprism.security-policy.roles.investigator[0]=GET_ENTITY_CONTEXT",
                "dataprism.privacy.profile=DEFAULT", "dataprism.privacy.scope-lifetime=8h",
                "dataprism.privacy.hmac-key.key-id=v1",
                "dataprism.privacy.hmac-key.environment-variable=DATAPRISM_HMAC_KEY_REF",
                "dataprism.audit.sink=approved-sink", "dataprism.audit.writer-id=test",
                "dataprism.metrics.sink=micrometer", "dataprism.hazelcast.topology=single-node",
                "dataprism.sources.customer.base-url=https://customer.example",
                "dataprism.sources.customer.timeout=2s"};
    }
}

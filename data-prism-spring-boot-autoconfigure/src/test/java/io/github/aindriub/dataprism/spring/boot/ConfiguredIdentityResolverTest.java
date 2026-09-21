package io.github.aindriub.dataprism.spring.boot;

import io.github.aindriub.dataprism.audit.AuditSink;
import io.github.aindriub.dataprism.core.DataRequest;
import io.github.aindriub.dataprism.core.DataSourceAdapter;
import io.github.aindriub.dataprism.core.IdentityResolver;
import io.github.aindriub.dataprism.core.PassThroughIdentityResolver;
import io.github.aindriub.dataprism.core.PrivacyMetrics;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpTransportContextExtractor;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.PriorityOrdered;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves {@code dataprism.identity.resolver} does what task 53 describes: it is
 * the only way a flat-JSON-REST operator with no Java can finish the
 * configuration-driven path without writing an {@link IdentityResolver}, it is
 * strictly opt-in, an unrecognised value is refused rather than silently treated
 * as pass-through, and an application-supplied {@link IdentityResolver} bean is
 * never displaced by it.
 */
class ConfiguredIdentityResolverTest {

    private final WebApplicationContextRunner context = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(DataPrismAutoConfiguration.class))
            .withPropertyValues(valid());

    @Test
    void pass_through_value_registers_the_core_pass_through_resolver_when_none_is_supplied() {
        context.withUserConfiguration(IntegrationsWithoutIdentity.class)
                .withPropertyValues("dataprism.identity.resolver=pass-through")
                .run(result -> {
                    assertThat(result).hasNotFailed();
                    assertThat(result).hasSingleBean(IdentityResolver.class);
                    assertThat(result.getBean(IdentityResolver.class)).isInstanceOf(PassThroughIdentityResolver.class);
                });
    }

    /**
     * Non-vacuity for this acceptance item was checked by hand rather than
     * shipped as an automated mutation test: with the new bean-registration
     * condition in {@code DataPrismAutoConfiguration#dataPrismIdentityResolverPreflight}
     * relaxed so it registers the pass-through bean unconditionally instead of
     * only when {@code dataprism.identity.resolver=pass-through} is set, this
     * exact test — with the property left unset — starts successfully and
     * registers a {@link PassThroughIdentityResolver} instead of failing with
     * {@code MISSING_IDENTITY_RESOLVER}, and the bean-definition assertion below
     * finds a definition where it must find none.
     */
    @Test
    void property_absent_still_refuses_startup_and_defines_no_identity_resolver_bean() {
        IdentityResolverBeanNamesCapture.captured = null;
        context.withUserConfiguration(IntegrationsWithoutIdentity.class).run(result -> {
            assertThat(result).hasFailed();
            assertThat(rootMessage(result.getStartupFailure())).contains("MISSING_IDENTITY_RESOLVER");
        });
        // Captured by a PriorityOrdered BeanFactoryPostProcessor that runs before the
        // preflight above throws, since the failed context itself cannot be inspected
        // once refresh() has aborted.
        assertThat(IdentityResolverBeanNamesCapture.captured)
                .as("no IdentityResolver bean definition should exist when the property is absent")
                .isEmpty();
    }

    @Test
    void an_unrecognised_value_refuses_startup_and_never_falls_back_to_pass_through() {
        context.withUserConfiguration(IntegrationsWithoutIdentity.class)
                .withPropertyValues("dataprism.identity.resolver=whatever")
                .run(result -> {
                    assertThat(result).hasFailed();
                    String message = rootMessage(result.getStartupFailure());
                    assertThat(message).contains("UNSUPPORTED_IDENTITY_RESOLVER");
                    assertThat(message).contains("dataprism.identity.resolver").contains("pass-through");
                });
    }

    @Test
    void an_application_supplied_resolver_wins_over_pass_through_with_no_duplicate_bean() {
        context.withUserConfiguration(IntegrationsWithCustomIdentity.class)
                .withPropertyValues("dataprism.identity.resolver=pass-through")
                .run(result -> {
                    assertThat(result).hasNotFailed();
                    assertThat(result).hasSingleBean(IdentityResolver.class);
                    assertThat(result.getBean(IdentityResolver.class))
                            .isInstanceOf(IntegrationsWithCustomIdentity.CustomIdentityResolver.class);
                });
    }

    private static String rootMessage(Throwable failure) {
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
                "dataprism.security-policy.purposes[0]=investigation",
                "dataprism.security-policy.roles.investigator[0]=GET_ENTITY_CONTEXT",
                "dataprism.privacy.profile=DEFAULT", "dataprism.privacy.scope-lifetime=8h",
                "dataprism.privacy.hmac-key.key-id=v1",
                "dataprism.privacy.hmac-key.environment-variable=DATAPRISM_HMAC_KEY_REF",
                "dataprism.audit.sink=approved-sink", "dataprism.audit.writer-id=test",
                "dataprism.metrics.sink=micrometer",
                "dataprism.hazelcast.topology=single-node",
                "dataprism.sources.customer.base-url=https://customer.example",
                "dataprism.sources.customer.timeout=2s"};
    }

    @Configuration(proxyBeanMethods = false)
    static class IntegrationsWithoutIdentity {
        @Bean
        DataSourceAdapter<String> customerAdapter() {
            return new DataSourceAdapter<>() {
                public String sourceName() { return "customer"; }
                public Class<String> responseType() { return String.class; }
                public String fetch(DataRequest request) { return null; }
            };
        }
        @Bean HmacKeyReferenceResolver keys() { return (id, reference) -> (reference + ":" + id + ":resolved-key-material").getBytes(); }
        @Bean AuditSink audit() { return event -> { }; }
        @Bean PrivacyMetrics metrics() { return PrivacyMetrics.none(); }
        @Bean McpTransportContextExtractor<HttpServletRequest> callerExtractor() { return request -> McpTransportContext.EMPTY; }
        @Bean static BeanFactoryPostProcessor identityResolverBeanNamesCapture() { return new IdentityResolverBeanNamesCapture(); }
    }

    /**
     * Captures {@link IdentityResolver} bean names before any plain (unordered)
     * {@link BeanFactoryPostProcessor} — including {@code DataPrismAutoConfiguration
     * #dataPrismIdentityResolverPreflight}, which throws on this exact case — runs,
     * since a context whose refresh aborted mid-way cannot be inspected afterwards.
     */
    static final class IdentityResolverBeanNamesCapture implements BeanFactoryPostProcessor, PriorityOrdered {
        static volatile String[] captured;

        @Override
        public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
            captured = beanFactory.getBeanNamesForType(IdentityResolver.class, true, false);
        }

        @Override
        public int getOrder() {
            return Ordered.HIGHEST_PRECEDENCE;
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class IntegrationsWithCustomIdentity extends IntegrationsWithoutIdentity {
        @Bean IdentityResolver identities() { return new CustomIdentityResolver(); }

        static final class CustomIdentityResolver implements IdentityResolver {
            @Override
            public CanonicalId resolve(SourceRef ref) { return new CanonicalId(ref.key()); }

            @Override
            public List<SourceRef> expand(CanonicalId id, List<String> sourceNames) {
                return sourceNames.stream().map(name -> new SourceRef(name, id.value())).toList();
            }
        }


    }
}

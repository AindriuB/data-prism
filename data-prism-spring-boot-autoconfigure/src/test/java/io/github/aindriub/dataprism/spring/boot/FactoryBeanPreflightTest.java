package io.github.aindriub.dataprism.spring.boot;

import io.github.aindriub.dataprism.annotations.PrivacyAction;
import io.github.aindriub.dataprism.annotations.PrivacyNamespace;
import io.github.aindriub.dataprism.audit.AuditSink;
import io.github.aindriub.dataprism.core.limits.RequestLimits;
import io.github.aindriub.dataprism.core.metrics.PrivacyMetrics;
import io.github.aindriub.dataprism.core.policy.EffectivePrivacyPolicy;
import io.github.aindriub.dataprism.core.policy.PrivacyPolicyResolver;
import io.github.aindriub.dataprism.core.spi.DataRequest;
import io.github.aindriub.dataprism.core.spi.DataSourceAdapter;
import io.github.aindriub.dataprism.core.spi.IdentityResolver;
import io.github.aindriub.dataprism.core.spi.PassThroughIdentityResolver;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpTransportContextExtractor;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A competing bean registered as a raw {@link FactoryBean} (no generic type) is invisible to
 * {@code getBeanNamesForType(type, true, false)}, because its product type is only known once the factory is
 * created. Both privacy guards must refuse it all the same.
 */
@SuppressWarnings({"rawtypes", "unchecked"})
class FactoryBeanPreflightTest {

    private final WebApplicationContextRunner context = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(DataPrismAutoConfiguration.class))
            .withUserConfiguration(ReviewedIntegrations.class)
            .withPropertyValues(valid());

    @Test
    void a_raw_factory_bean_producing_request_limits_is_refused() {
        context.withUserConfiguration(RawLimitsFactory.class).run(result -> {
            assertThat(result).hasFailed();
            assertThat(code(result.getStartupFailure())).isEqualTo("FORBIDDEN_PRIVACY_OVERRIDE");
        });
    }

    @Test
    void a_raw_factory_bean_producing_a_privacy_policy_resolver_is_refused() {
        context.withUserConfiguration(RawResolverFactory.class).run(result -> {
            assertThat(result).hasFailed();
            assertThat(code(result.getStartupFailure())).isEqualTo("FORBIDDEN_PRIVACY_OVERRIDE");
        });
    }

    private final WebApplicationContextRunner bare = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(DataPrismAutoConfiguration.class))
            .withPropertyValues(valid());

    /** A raw factory declared by the first configuration class cannot be read when the one-shot check would run. */
    @Test
    void a_raw_factory_on_the_first_configuration_class_is_refused() {
        bare.withUserConfiguration(RawLimitsFactory.class, ReviewedIntegrations.class).run(result -> {
            assertThat(result).hasFailed();
            assertThat(code(result.getStartupFailure())).isEqualTo("FORBIDDEN_PRIVACY_OVERRIDE");
        });
    }

    @Test
    void a_bean_declared_as_object_that_is_request_limits_is_refused_on_the_first_and_the_second_class() {
        for (Class<?>[] order : new Class<?>[][] {{ObjectLimits.class, ReviewedIntegrations.class},
                {ReviewedIntegrations.class, ObjectLimits.class}}) {
            bare.withUserConfiguration(order).run(result -> {
                assertThat(result).hasFailed();
                assertThat(code(result.getStartupFailure())).isEqualTo("FORBIDDEN_PRIVACY_OVERRIDE");
            });
        }
    }

    @Test
    void a_raw_factory_behind_a_bean_declared_as_object_is_refused_on_the_first_and_the_second_class() {
        for (Class<?>[] order : new Class<?>[][] {{ObjectRawFactory.class, ReviewedIntegrations.class},
                {ReviewedIntegrations.class, ObjectRawFactory.class}}) {
            bare.withUserConfiguration(order).run(result -> {
                assertThat(result).hasFailed();
                assertThat(code(result.getStartupFailure())).isEqualTo("FORBIDDEN_PRIVACY_OVERRIDE");
            });
        }
    }

    @Test
    void an_unrelated_raw_factory_bean_does_not_stop_startup() {
        context.withUserConfiguration(RawUnrelatedFactory.class).run(result -> assertThat(result).hasNotFailed());
    }

    private static String code(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof DataPrismConfigurationException e) {
                return e.code();
            }
        }
        throw new AssertionError("no DataPrismConfigurationException in the cause chain", failure);
    }

    private static String[] valid() {
        return new String[] {
                "dataprism.security.jwt.issuer=https://issuer.example", "dataprism.security.jwt.audience=mcp",
                "dataprism.security.jwt.jwk-set-uri=https://issuer.example/jwks",
                "dataprism.security.caller-claims.principal=sub", "dataprism.security.caller-claims.roles=roles",
                "dataprism.security.caller-claims.investigation=case_id",
                "dataprism.security-policy.case-authorisation=token-trusted",
                "dataprism.security-policy.client-allowlist=unrestricted",
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
    static class ReviewedIntegrations {
        @Bean
        DataSourceAdapter<TestPayload> customerAdapter() {
            return new DataSourceAdapter<>() {
                public String sourceName() { return "customer"; }
                public Class<TestPayload> responseType() { return TestPayload.class; }
                public TestPayload fetch(DataRequest request) { return null; }
            };
        }
        @Bean IdentityResolver identities() { return new PassThroughIdentityResolver(); }
        @Bean HmacKeyReferenceResolver keys() { return (id, reference) -> (reference + ":" + id + ":resolved-key-material").getBytes(); }
        @Bean AuditSink audit() { return event -> { }; }
        @Bean PrivacyMetrics metrics() { return PrivacyMetrics.none(); }
        @Bean McpTransportContextExtractor<HttpServletRequest> callerExtractor() { return request -> McpTransportContext.EMPTY; }
    }

    /** A factory whose product type is not in its declaration, so only creating it reveals the type. */
    static class RawFactory implements FactoryBean {
        private final Object product;
        private final Class<?> type;

        RawFactory(Object product, Class<?> type) {
            this.product = product;
            this.type = type;
        }

        @Override public Object getObject() { return product; }
        @Override public Class<?> getObjectType() { return type; }
    }

    @Configuration(proxyBeanMethods = false)
    static class RawLimitsFactory {
        @Bean
        FactoryBean rawLimits() {
            return new RawFactory(RequestLimits.DEFAULT, RequestLimits.class);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class RawResolverFactory {
        @Bean
        FactoryBean rawResolver() {
            PrivacyPolicyResolver passThrough = (field, ctx) -> new EffectivePrivacyPolicy(PrivacyAction.PASS_THROUGH,
                    PrivacyNamespace.NONE, true, "PASS_THROUGH_UNSAFE", EffectivePrivacyPolicy.Decided.PROFILE_RULE);
            return new RawFactory(passThrough, PrivacyPolicyResolver.class);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class ObjectLimits {
        @Bean
        Object limits() {
            return RequestLimits.DEFAULT;
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class ObjectRawFactory {
        @Bean
        Object hiddenFactory() {
            return new RawFactory(RequestLimits.DEFAULT, RequestLimits.class);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class RawUnrelatedFactory {
        @Bean
        FactoryBean rawUnrelated() {
            return new RawFactory("not a privacy type", String.class);
        }
    }
}

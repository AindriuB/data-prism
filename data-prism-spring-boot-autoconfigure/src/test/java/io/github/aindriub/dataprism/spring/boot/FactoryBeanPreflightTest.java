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
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.core.env.Environment;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.atomic.AtomicInteger;

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

    /** A bean created before any guard is watching, by a processor that runs first, is found by the check at registration. */
    @Test
    void a_bean_declared_as_object_created_before_the_guards_watch_is_still_refused() {
        bare.withUserConfiguration(ObjectLimits.class, EarlyCreator.class, ReviewedIntegrations.class).run(result -> {
            assertThat(result).hasFailed();
            assertThat(code(result.getStartupFailure())).isEqualTo("FORBIDDEN_PRIVACY_OVERRIDE");
        });
    }

    @Test
    void an_unrelated_raw_factory_bean_does_not_stop_startup() {
        context.withUserConfiguration(RawUnrelatedFactory.class).run(result -> assertThat(result).hasNotFailed());
    }

    /** A raw factory is read only once the post-processors are registered, so its injection and callbacks still happen. */
    @Test
    void a_raw_factory_with_an_autowired_field_is_fully_wired() {
        new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                .withUserConfiguration(WiredFactoryConfig.class).run(result -> {
            assertThat(result).hasNotFailed();
            WiredFactory factory = result.getBean("&wiredFactory", WiredFactory.class);
            assertThat(factory.environment).isNotNull();
            assertThat(factory.initialised).isTrue();
        });
    }

    /** A factory whose type never becomes known must not be rescanned for ever: no read after startup. */
    @Test
    void a_factory_whose_type_stays_unknown_is_not_read_again_after_startup() {
        NEVER_TYPED_READS.set(0);
        context.withUserConfiguration(NeverTypedConfig.class).run(result -> {
            assertThat(result).hasNotFailed();
            int afterStartup = NEVER_TYPED_READS.get();
            for (int i = 0; i < 5; i++) {
                result.getBean("scratch");
            }
            assertThat(NEVER_TYPED_READS.get()).isEqualTo(afterStartup);
        });
    }

    @Test
    void a_post_processor_swapping_in_a_primary_decoy_after_the_watcher_ran_is_refused() {
        context.withUserConfiguration(SwappedDecoy.class).run(result -> {
            assertThat(result).hasFailed();
            assertThat(code(result.getStartupFailure())).isEqualTo("FORBIDDEN_PRIVACY_OVERRIDE");
        });
    }

    @Test
    void a_synthetic_primary_definition_added_late_is_refused() {
        context.withUserConfiguration(LateDefinition.class).run(result -> {
            assertThat(result).hasFailed();
            assertThat(code(result.getStartupFailure())).isEqualTo("FORBIDDEN_PRIVACY_OVERRIDE");
        });
    }

    @Test
    void a_singleton_registered_late_before_singletons_finish_is_refused() {
        context.withUserConfiguration(LateSingleton.class).run(result -> {
            assertThat(result).hasFailed();
            assertThat(code(result.getStartupFailure())).isEqualTo("FORBIDDEN_PRIVACY_OVERRIDE");
        });
    }

    @Test
    void a_bean_that_is_both_a_factory_and_the_guarded_type_is_refused() {
        context.withUserConfiguration(BothConfig.class).run(result -> {
            assertThat(result).hasFailed();
            assertThat(code(result.getStartupFailure())).isEqualTo("FORBIDDEN_PRIVACY_OVERRIDE");
        });
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

    /** Creates the {@link ObjectLimits} bean ahead of every other processor. */
    @Configuration(proxyBeanMethods = false)
    static class EarlyCreator {
        @Bean
        static org.springframework.beans.factory.config.BeanFactoryPostProcessor early() {
            return new EarlyProcessor();
        }
    }

    static class EarlyProcessor implements org.springframework.beans.factory.config.BeanFactoryPostProcessor,
            org.springframework.core.PriorityOrdered {
        @Override
        public void postProcessBeanFactory(org.springframework.beans.factory.config.ConfigurableListableBeanFactory f) {
            f.getBean("limits");
        }

        @Override
        public int getOrder() {
            return org.springframework.core.Ordered.HIGHEST_PRECEDENCE;
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class RawUnrelatedFactory {
        @Bean
        FactoryBean rawUnrelated() {
            return new RawFactory("not a privacy type", String.class);
        }
    }

    static class WiredFactory implements FactoryBean {
        @Autowired Environment environment;
        boolean initialised;

        @PostConstruct
        void init() {
            initialised = environment != null;
        }

        @Override public Object getObject() { return "x"; }
        @Override public Class<?> getObjectType() { return initialised ? String.class : null; }
    }

    @Configuration(proxyBeanMethods = false)
    static class WiredFactoryConfig {
        @Bean
        static FactoryBean wiredFactory() {
            return new WiredFactory();
        }

        @Bean
        static org.springframework.beans.factory.config.BeanFactoryPostProcessor guard() {
            return factory -> PrivacyExtensionPoints.refuseCompetingBeans(factory, RequestLimits.class, "own",
                    () -> new IllegalStateException("refused"));
        }
    }

    static final AtomicInteger NEVER_TYPED_READS = new AtomicInteger();

    static class NeverTypedFactory implements FactoryBean {
        @Override public Object getObject() { return "x"; }
        @Override public Class<?> getObjectType() {
            NEVER_TYPED_READS.incrementAndGet();
            return null;
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class NeverTypedConfig {
        @Bean
        FactoryBean neverTyped() {
            return new NeverTypedFactory();
        }

        @Bean
        @org.springframework.context.annotation.Scope("prototype")
        Object scratch() {
            return new Object();
        }
    }

    /** Replaces a bean with a request-limits object after every earlier post-processor, the watcher included, ran. */
    @Configuration(proxyBeanMethods = false)
    static class SwappedDecoy {
        @Bean
        @org.springframework.context.annotation.Primary
        Object decoy() {
            return "decoy";
        }

        @Bean
        static BeanPostProcessor swapper() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) {
                    return "decoy".equals(beanName) ? RequestLimits.DEFAULT : bean;
                }
            };
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class LateDefinition {
        @Bean
        static LateDefinitionRegistrar registrar() {
            return new LateDefinitionRegistrar();
        }
    }

    /** Adds the definition once the watcher is in place, so the up-front check of definitions never saw it. */
    static class LateDefinitionRegistrar implements BeanFactoryAware, BeanPostProcessor {
        private DefaultListableBeanFactory beans;
        private boolean done;

        @Override public void setBeanFactory(BeanFactory beanFactory) { beans = (DefaultListableBeanFactory) beanFactory; }

        @Override
        public Object postProcessAfterInitialization(Object bean, String beanName) {
            if (!done && "customerAdapter".equals(beanName)) {
                done = true;
                RootBeanDefinition late = new RootBeanDefinition(RequestLimits.class, () -> RequestLimits.DEFAULT);
                late.setSynthetic(true);
                late.setPrimary(true);
                beans.registerBeanDefinition("lateLimits", late);
            }
            return bean;
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class LateSingleton {
        @Bean
        static LateSingletonRegistrar registrar() {
            return new LateSingletonRegistrar();
        }
    }

    /** Registers the singleton after the last bean exists, when nothing is left to be injected with it. */
    static class LateSingletonRegistrar implements BeanFactoryAware,
            org.springframework.beans.factory.SmartInitializingSingleton {
        private ConfigurableListableBeanFactory beans;

        @Override public void setBeanFactory(BeanFactory beanFactory) {
            beans = (ConfigurableListableBeanFactory) beanFactory;
        }

        @Override
        public void afterSingletonsInstantiated() {
            beans.registerSingleton("lateLimits", RequestLimits.DEFAULT);
        }
    }

    static class Both implements FactoryBean<String>, PrivacyPolicyResolver {
        @Override public String getObject() { return "x"; }
        @Override public Class<?> getObjectType() { return String.class; }
        @Override
        public EffectivePrivacyPolicy resolve(io.github.aindriub.dataprism.core.model.FieldMetadata field,
                io.github.aindriub.dataprism.core.model.PrivacyContext ctx) {
            return null;
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class BothConfig {
        @Bean
        Object both() {
            return new Both();
        }
    }
}

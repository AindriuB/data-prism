package io.github.aindriub.dataprism.spring.boot.descriptors;

import io.github.aindriub.dataprism.audit.AuditSink;
import io.github.aindriub.dataprism.core.descriptor.DescriptorFieldMetadataResolver;
import io.github.aindriub.dataprism.core.descriptor.ModelDescriptor;
import io.github.aindriub.dataprism.core.descriptor.ModelDescriptors;
import io.github.aindriub.dataprism.core.engine.DefaultFieldMetadataResolver;
import io.github.aindriub.dataprism.core.engine.JsonTreeScrubbingEngine;
import io.github.aindriub.dataprism.core.metrics.PrivacyMetrics;
import io.github.aindriub.dataprism.core.model.FieldMetadata;
import io.github.aindriub.dataprism.core.model.PrivacyContext;
import io.github.aindriub.dataprism.core.model.PrivacyScopeType;
import io.github.aindriub.dataprism.core.model.PseudonymisationVersion;
import io.github.aindriub.dataprism.core.policy.ProfilePrivacyPolicyResolver;
import io.github.aindriub.dataprism.core.refusal.RefusalCodes;
import io.github.aindriub.dataprism.core.spi.DataRequest;
import io.github.aindriub.dataprism.core.spi.DataSourceAdapter;
import io.github.aindriub.dataprism.core.spi.FieldMetadataResolver;
import io.github.aindriub.dataprism.core.spi.IdentityResolver;
import io.github.aindriub.dataprism.core.spi.PassThroughIdentityResolver;
import io.github.aindriub.dataprism.core.spi.ValueTokenSource;
import io.github.aindriub.dataprism.security.CaseAuthorizer;
import io.github.aindriub.dataprism.security.ClientAllowlist;
import io.github.aindriub.dataprism.spring.boot.DataPrismAutoConfiguration;
import io.github.aindriub.dataprism.spring.boot.DataPrismConfigurationException;
import io.github.aindriub.dataprism.spring.boot.HmacKeyReferenceResolver;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpTransportContextExtractor;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.assertj.AssertableWebApplicationContext;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Spring-built scrubber is given the descriptors the auto-configured resolver was built from,
 * and a set {@code dataprism.privacy.descriptor-file} refuses startup unless the resolver and every
 * engine bean use exactly that resolver (task 271).
 */
class ScrubberDescriptorWiringTest {

    private static final String MESSAGE = "dataprism.privacy.descriptor-file is set but the FieldMetadataResolver "
            + "bean is not the one built from it, or an engine does not use it";

    record Plain(String ref, String state) {
    }

    record Other(String code) {
    }

    private final WebApplicationContextRunner base = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(DataPrismAutoConfiguration.class))
            .withUserConfiguration(Integrations.class)
            .withPropertyValues(valid());

    private static String fixture() throws Exception {
        return Path.of(ScrubberDescriptorWiringTest.class.getResource("/scrubber-descriptors/chainable.yaml").toURI())
                .toString();
    }

    private WebApplicationContextRunner withFile(Class<?>... extra) throws Exception {
        return base.withUserConfiguration(extra).withPropertyValues("dataprism.privacy.descriptor-file=" + fixture());
    }

    private static Map<String, ModelDescriptor> loaded() throws Exception {
        try (InputStream in = Files.newInputStream(Path.of(fixture()))) {
            return ModelDescriptors.fromYaml(in);
        }
    }

    private static PrivacyContext privacy() {
        return new PrivacyContext("C-1", PrivacyScopeType.CASE, "DEFAULT", "test",
                Instant.parse("2030-01-01T00:00:00Z"), PseudonymisationVersion.HMAC_SHA256_V1);
    }

    private static boolean describesNamespace(JsonTreeScrubbingEngine engine) {
        return engine.describe(Plain.class, privacy(), true).map(d -> d.fields().stream()
                .anyMatch(f -> "parcel-ref".equals(f.keyNamespace()))).orElse(false);
    }

    private static void refused(AssertableWebApplicationContext result) {
        assertThat(result).hasFailed();
        Throwable t = result.getStartupFailure();
        while (t != null && !(t instanceof DataPrismConfigurationException)) {
            t = t.getCause();
        }
        assertThat(t).isInstanceOf(DataPrismConfigurationException.class);
        DataPrismConfigurationException refusal = (DataPrismConfigurationException) t;
        assertThat(refusal.code()).isEqualTo("MODEL_DESCRIPTOR_RESOLVER_REPLACED");
        assertThat(refusal.getMessage()).isEqualTo("MODEL_DESCRIPTOR_RESOLVER_REPLACED: " + MESSAGE);
    }

    private void refuses(Class<?> extra, String... properties) throws Exception {
        withFile(extra).withPropertyValues(properties).run(ScrubberDescriptorWiringTest::refused);
    }

    // ---- the descriptor map reaches the engine ----

    @Test
    void a_descriptor_declared_chainable_field_is_described_with_its_key_namespace() throws Exception {
        withFile().run(result -> {
            assertThat(result).hasNotFailed();
            assertThat(describesNamespace(result.getBean(JsonTreeScrubbingEngine.class))).isTrue();
        });
    }

    @Test
    void the_same_holds_behind_an_aop_proxy() throws Exception {
        withFile(ProxyingResolvers.class).run(result -> {
            assertThat(result).hasNotFailed();
            assertThat(result.getBean(FieldMetadataResolver.class)).isNotInstanceOf(DescriptorFieldMetadataResolver.class);
            assertThat(describesNamespace(result.getBean(JsonTreeScrubbingEngine.class))).isTrue();
        });
    }

    @Test
    void without_a_file_no_key_namespace_is_described() {
        base.run(result -> {
            assertThat(result).hasNotFailed();
            JsonTreeScrubbingEngine engine = result.getBean(JsonTreeScrubbingEngine.class);
            assertThat(engine.descriptors()).isEmpty();
            assertThat(describesNamespace(engine)).isFalse();
        });
    }

    @Test
    void the_file_is_read_once_one_parsed_model_backs_both_resolver_and_engine() throws Exception {
        withFile().run(result -> {
            DescriptorFieldMetadataResolver resolver = (DescriptorFieldMetadataResolver)
                    result.getBean(FieldMetadataResolver.class);
            JsonTreeScrubbingEngine engine = result.getBean(JsonTreeScrubbingEngine.class);
            assertThat(engine.resolver()).isSameAs(resolver);
            assertThat(engine.descriptors()).isEqualTo(resolver.descriptors()).isNotEmpty();
            engine.descriptors().forEach((name, descriptor) ->
                    assertThat(descriptor).isSameAs(resolver.descriptors().get(name)));
        });
    }

    // ---- no file set ----

    @Test
    void an_application_resolver_starts_without_a_file_and_the_engine_gets_no_descriptors() {
        base.withUserConfiguration(OwnDefaultResolver.class).run(result -> {
            assertThat(result).hasNotFailed();
            assertThat(result.getBean(JsonTreeScrubbingEngine.class).descriptors()).isEmpty();
        });
    }

    @Test
    void a_blank_file_with_an_application_resolver_starts() {
        base.withUserConfiguration(OwnDefaultResolver.class).withPropertyValues("dataprism.privacy.descriptor-file= ")
                .run(result -> {
                    assertThat(result).hasNotFailed();
                    assertThat(result.getBean(JsonTreeScrubbingEngine.class).descriptors()).isEmpty();
                });
    }

    // ---- refusals ----

    @Test
    void refuses_an_application_descriptor_resolver_with_empty_descriptors() throws Exception {
        refuses(EmptyDescriptorResolver.class);
    }

    @Test
    void refuses_an_application_descriptor_resolver_built_from_the_same_file() throws Exception {
        refuses(SameFileResolver.class);
    }

    @Test
    void refuses_an_application_resolver_of_another_class() throws Exception {
        refuses(OwnDefaultResolver.class);
    }

    @Test
    void refuses_a_decorator_over_a_descriptor_resolver() throws Exception {
        refuses(DecoratingResolver.class);
    }

    @Test
    void refuses_a_resolver_produced_by_a_factory_bean() throws Exception {
        refuses(FactoryResolver.class);
    }

    @Test
    void refuses_a_resolver_registered_under_our_bean_name() throws Exception {
        refuses(SameNameResolver.class, "spring.main.allow-bean-definition-overriding=true");
    }

    @Test
    void refuses_a_prototype_scoped_resolver() throws Exception {
        refuses(PrototypeResolver.class);
    }

    @Test
    void refuses_an_application_engine_built_with_the_default_resolver() throws Exception {
        refuses(EngineWithDefaultResolver.class);
    }

    @Test
    void refuses_an_application_engine_built_with_our_resolver_and_no_descriptors() throws Exception {
        refuses(EngineWithEmptyMap.class);
    }

    @Test
    void refuses_an_application_engine_built_with_our_resolver_and_a_different_map() throws Exception {
        refuses(EngineWithSmallerMap.class);
    }

    @Test
    void refuses_an_application_resolver_together_with_an_application_engine() throws Exception {
        withFile(OwnDefaultResolver.class, EngineWithDefaultResolver.class)
                .run(ScrubberDescriptorWiringTest::refused);
    }

    @Test
    void refuses_an_application_resolver_under_lazy_initialisation() throws Exception {
        refuses(OwnDefaultResolver.class, "spring.main.lazy-initialization=true");
    }

    @Test
    void refuses_a_parent_context_engine_built_with_the_default_resolver() throws Exception {
        new WebApplicationContextRunner().withUserConfiguration(EngineWithDefaultResolver.class)
                .run(parent -> withFile().withParent(parent).run(ScrubberDescriptorWiringTest::refused));
    }

    @Test
    void a_child_without_a_file_starts_under_a_parent_engine() {
        new WebApplicationContextRunner().withUserConfiguration(EngineWithDefaultResolver.class)
                .run(parent -> base.withParent(parent).run(result -> assertThat(result).hasNotFailed()));
    }

    // ---- accepted ----

    @Test
    void the_auto_configured_resolver_and_engine_start() throws Exception {
        withFile().run(result -> assertThat(result).hasNotFailed());
    }

    @Test
    void the_auto_configured_pair_starts_under_lazy_initialisation() throws Exception {
        withFile().withPropertyValues("spring.main.lazy-initialization=true")
                .run(result -> assertThat(result).hasNotFailed());
    }

    @Test
    void the_refusal_code_survives_sanitising() {
        assertThat(RefusalCodes.sanitise("MODEL_DESCRIPTOR_RESOLVER_REPLACED"))
                .isEqualTo("MODEL_DESCRIPTOR_RESOLVER_REPLACED");
    }

    // ---- fixtures ----

    @Configuration(proxyBeanMethods = false)
    static class OwnDefaultResolver {
        @Bean
        FieldMetadataResolver ownResolver() {
            return new DefaultFieldMetadataResolver();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class EmptyDescriptorResolver {
        @Bean
        FieldMetadataResolver ownResolver() {
            return new DescriptorFieldMetadataResolver(new DefaultFieldMetadataResolver(), Map.of());
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class SameFileResolver {
        @Bean
        FieldMetadataResolver ownResolver() throws Exception {
            return new DescriptorFieldMetadataResolver(new DefaultFieldMetadataResolver(), loaded());
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class DecoratingResolver {
        @Bean
        FieldMetadataResolver ownResolver() throws Exception {
            FieldMetadataResolver inner = new DescriptorFieldMetadataResolver(new DefaultFieldMetadataResolver(),
                    loaded());
            return new FieldMetadataResolver() {
                @Override
                public List<FieldMetadata> resolve(Class<?> type) {
                    return inner.resolve(type);
                }

                @Override
                public boolean exposed(Class<?> type) {
                    return inner.exposed(type);
                }

                @Override
                public boolean descendable(Class<?> type) {
                    return inner.descendable(type);
                }
            };
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class FactoryResolver {
        @Bean
        FactoryBean<FieldMetadataResolver> ownResolverFactory() {
            return new FactoryBean<>() {
                @Override
                public FieldMetadataResolver getObject() {
                    return new DefaultFieldMetadataResolver();
                }

                @Override
                public Class<?> getObjectType() {
                    return FieldMetadataResolver.class;
                }
            };
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class SameNameResolver {
        @Bean("dataPrismFieldMetadataResolver")
        FieldMetadataResolver ownResolver() {
            return new DefaultFieldMetadataResolver();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class PrototypeResolver {
        @Bean
        @Scope("prototype")
        FieldMetadataResolver ownResolver() {
            return new DefaultFieldMetadataResolver();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class EngineWithDefaultResolver {
        @Bean
        JsonTreeScrubbingEngine ownEngine() {
            return new JsonTreeScrubbingEngine(new DefaultFieldMetadataResolver(),
                    new ProfilePrivacyPolicyResolver(Map.of()), (s, ns, c) -> "syn");
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class EngineWithEmptyMap {
        @Bean
        JsonTreeScrubbingEngine ownEngine(FieldMetadataResolver ours) {
            return new JsonTreeScrubbingEngine(ours, new ProfilePrivacyPolicyResolver(Map.of()),
                    (s, ns, c) -> "syn", ValueTokenSource.unavailable(), Map.of());
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class EngineWithSmallerMap {
        @Bean
        JsonTreeScrubbingEngine ownEngine(FieldMetadataResolver ours) {
            Map<String, ModelDescriptor> smaller =
                    new HashMap<>(((DescriptorFieldMetadataResolver) ours).descriptors());
            smaller.remove(smaller.keySet().iterator().next());
            return new JsonTreeScrubbingEngine(ours, new ProfilePrivacyPolicyResolver(Map.of()),
                    (s, ns, c) -> "syn", ValueTokenSource.unavailable(), smaller);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class ProxyingResolvers {
        @Bean
        static BeanPostProcessor resolverProxier() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) {
                    if (bean instanceof FieldMetadataResolver) {
                        ProxyFactory factory = new ProxyFactory(bean);
                        factory.setProxyTargetClass(false);
                        factory.setInterfaces(FieldMetadataResolver.class);
                        return factory.getProxy();
                    }
                    return bean;
                }
            };
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class Integrations {
        @Bean
        CaseAuthorizer caseAuthorizer() {
            return request -> true;
        }

        @Bean
        ClientAllowlist clientAllowlist() {
            return ClientAllowlist.unrestricted();
        }

        @Bean
        DataSourceAdapter<Plain> customerAdapter() {
            return new DataSourceAdapter<>() {
                public String sourceName() {
                    return "customer";
                }

                public Class<Plain> responseType() {
                    return Plain.class;
                }

                public Plain fetch(DataRequest request) {
                    return null;
                }
            };
        }

        @Bean
        IdentityResolver identities() {
            return new PassThroughIdentityResolver();
        }

        @Bean
        HmacKeyReferenceResolver keys() {
            return (id, reference) -> (reference + ":" + id + ":resolved-key-material").getBytes();
        }

        @Bean
        AuditSink audit() {
            return event -> {
            };
        }

        @Bean
        PrivacyMetrics metrics() {
            return PrivacyMetrics.none();
        }

        @Bean
        McpTransportContextExtractor<HttpServletRequest> callerExtractor() {
            return request -> McpTransportContext.EMPTY;
        }
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
                "dataprism.metrics.sink=micrometer", "dataprism.hazelcast.topology=single-node",
                "dataprism.sources.customer.base-url=https://customer.example",
                "dataprism.sources.customer.timeout=2s" };
    }
}

package io.github.aindriub.dataprism.connectors.rest;

import io.github.aindriub.dataprism.audit.AuditRecorder;
import io.github.aindriub.dataprism.core.DataSourceAdapter;
import io.github.aindriub.dataprism.core.FieldMetadataResolver;
import io.github.aindriub.dataprism.core.IdentityResolver;
import io.github.aindriub.dataprism.core.JsonTreeScrubbingEngine;
import io.github.aindriub.dataprism.core.PrivacyMetrics;
import io.github.aindriub.dataprism.core.RequestLimits;
import io.github.aindriub.dataprism.core.ScopeBudget;
import io.github.aindriub.dataprism.core.SecretKeyProvider;
import io.github.aindriub.dataprism.core.ScrubbingEngine;
import io.github.aindriub.dataprism.core.SyntheticValueSource;
import io.github.aindriub.dataprism.core.ValueTokenSource;
import io.github.aindriub.dataprism.core.policy.PrivacyPolicyResolver;
import io.github.aindriub.dataprism.orchestration.ContextOrchestrator;
import io.github.aindriub.dataprism.orchestration.DefaultContextOrchestrator;
import io.github.aindriub.dataprism.orchestration.NamespaceCorrelationService;
import io.github.aindriub.dataprism.orchestration.ParameterFingerprinter;
import io.github.aindriub.dataprism.orchestration.SourceAliasing;
import io.github.aindriub.dataprism.orchestration.SourceCircuitBreaker;
import io.github.aindriub.dataprism.orchestration.SourceFanOut;
import io.github.aindriub.dataprism.validation.LlmResponseValidator;
import org.springframework.beans.factory.config.BeanDefinitionHolder;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.util.List;
import java.util.Map;

/**
 * Wires configuration-driven JSON REST sources into a running server, the same
 * way {@code DataPrismAutoConfiguration} wires Java-first ones.
 *
 * <p>This is the "separately reviewed" half of the feature named in this task:
 * the class exists here, in the connector module, and never in the base
 * standalone server distribution. An operator opts in the same way as any other
 * reviewed adapter — {@code -Dloader.path=<this module's jar>} — and sets
 * exactly one property, {@code dataprism.json-sources.config-location}, naming a
 * Spring resource location for the catalogue file {@link ConfiguredJsonSources}
 * parses. Absent that property, every bean here is inert: {@code
 * @ConditionalOnProperty} and the equivalent guard inside {@link
 * AdapterRegistrar} both no-op, so a server distribution without this jar on its
 * loader path, or with it present but unconfigured, starts and behaves exactly
 * as it always did.
 *
 * <p>{@link #configuredJsonSourcesContextOrchestrator} replaces the base
 * distribution's {@code ContextOrchestrator} rather than adding a second one,
 * which is what keeps a configured source answering through the platform's one
 * MCP tool instead of a route of its own: {@code @AutoConfigureBefore} makes
 * this class's {@code @ConditionalOnMissingBean} run first, so the base
 * auto-configuration's own orchestrator bean backs off once this one exists,
 * exactly the way an application-supplied bean of any {@code
 * @ConditionalOnMissingBean} type already does.
 */
@AutoConfiguration
@AutoConfigureBefore(name = "io.github.aindriub.dataprism.spring.boot.DataPrismAutoConfiguration")
public class ConfiguredJsonSourcesAutoConfiguration {

    private static final String CONFIG_LOCATION_PROPERTY = "dataprism.json-sources.config-location";

    /**
     * Registers one {@link ConfiguredJsonDataSourceAdapter} bean per configured
     * source, plus the {@link RestClient} they share.
     *
     * <p>{@code List<DataSourceAdapter<?>>} elsewhere in this platform is
     * populated by Spring collecting every bean of that type, not by a single
     * bean of the list type — a bean of a dynamic, config-determined count has to
     * be registered one definition at a time, which is what this class exists to
     * do rather than a plain {@code @Bean} method.
     */
    @Bean
    static BeanDefinitionRegistryPostProcessor configuredJsonSourcesAdapterRegistrar(Environment environment) {
        return new AdapterRegistrar(environment);
    }

    @Bean
    @ConditionalOnProperty(name = CONFIG_LOCATION_PROPERTY)
    @ConditionalOnMissingBean(ContextOrchestrator.class)
    ContextOrchestrator configuredJsonSourcesContextOrchestrator(Environment environment,
            List<DataSourceAdapter<?>> adapters, IdentityResolver identities, FieldMetadataResolver metadata,
            List<LlmResponseValidator> validators, SyntheticValueSource synthetics, ValueTokenSource tokens,
            SecretKeyProvider keys, AuditRecorder audit, ScopeBudget budget, PrivacyMetrics metrics, Clock clock,
            PrivacyPolicyResolver policy) {
        ConfiguredJsonSourcesConfig config = loadConfig(environment);

        JsonTreeScrubbingEngine javaFirst = new JsonTreeScrubbingEngine(metadata, policy, synthetics, tokens);
        ScrubbingEngine scrubber =
                new ConfiguredJsonScrubbingEngine(javaFirst, config.sources(), policy, synthetics, tokens);

        return new DefaultContextOrchestrator(adapters, scrubber, metadata, List.copyOf(validators), synthetics,
                new ParameterFingerprinter(keys), audit, identities,
                new SourceFanOut(SourceCircuitBreaker.disabled(), clock, metrics), budget, RequestLimits.DEFAULT,
                new NamespaceCorrelationService(metadata), new SourceAliasing(tokens), metrics);
    }

    private static ConfiguredJsonSourcesConfig loadConfig(Environment environment) {
        String location = environment.getProperty(CONFIG_LOCATION_PROPERTY);
        try (InputStream in = new DefaultResourceLoader().getResource(location).getInputStream()) {
            return ConfiguredJsonSources.fromYaml(in);
        } catch (IOException e) {
            throw new IllegalStateException(CONFIG_LOCATION_PROPERTY + " '" + location
                    + "' could not be read", e);
        }
    }

    /**
     * No-ops (registers nothing) when {@link #CONFIG_LOCATION_PROPERTY} is
     * unset, the same manual guard {@code DataPrismAutoConfiguration} uses for
     * its own early, property-driven preflight checks rather than {@code
     * @ConditionalOnProperty} on a static bean-defining method.
     */
    private static final class AdapterRegistrar implements BeanDefinitionRegistryPostProcessor {

        private final Environment environment;

        AdapterRegistrar(Environment environment) {
            this.environment = environment;
        }

        @Override
        public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) {
            String location = environment.getProperty(CONFIG_LOCATION_PROPERTY);
            if (location == null || location.isBlank()) {
                return;
            }
            ConfiguredJsonSourcesConfig config = loadConfig(environment);

            registry.registerBeanDefinition("dataPrismConfiguredJsonSourcesRestClient",
                    BeanDefinitionBuilder
                            .genericBeanDefinition(RestClient.class, () -> restClient(config))
                            .getBeanDefinition());

            for (Map.Entry<String, ConfiguredJsonSource> entry : config.sources().entrySet()) {
                BeanDefinitionHolder holder = new BeanDefinitionHolder(
                        BeanDefinitionBuilder.genericBeanDefinition(ConfiguredJsonDataSourceAdapter.class)
                                .addConstructorArgValue(entry.getValue())
                                .addConstructorArgReference("dataPrismConfiguredJsonSourcesRestClient")
                                .getBeanDefinition(),
                        "dataPrismConfiguredJsonSource_" + entry.getKey());
                registry.registerBeanDefinition(holder.getBeanName(), holder.getBeanDefinition());
            }
        }

        @Override
        public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
            // Nothing to do: every dependency the registered definitions need is
            // resolved normally, by the container, once it instantiates them.
        }

        private static RestClient restClient(ConfiguredJsonSourcesConfig config) {
            return config.tlsConfigured() ? MutualTlsRestClients.build(config.tls()) : RestClient.create();
        }
    }
}

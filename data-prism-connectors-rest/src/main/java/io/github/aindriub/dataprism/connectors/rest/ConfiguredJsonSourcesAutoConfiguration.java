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
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.util.List;

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
 * @ConditionalOnProperty}, and {@link ConfiguredJsonSourcesInitializer}'s
 * equivalent manual guard, both no-op, so a server distribution without this jar
 * on its loader path, or with it present but unconfigured, starts and behaves
 * exactly as it always did.
 *
 * <p>The per-source {@link DataSourceAdapter} beans are registered by {@link
 * ConfiguredJsonSourcesInitializer}, not here. {@code
 * DataPrismAutoConfiguration} gates several of its own beans behind {@code
 * @ConditionalOnBean(DataSourceAdapter.class)}, and that condition — like every
 * {@code @Conditional} on a {@code @Bean} method — is evaluated for every
 * auto-configuration class in one pass, before any {@code
 * BeanDefinitionRegistryPostProcessor} discovered from a {@code @Bean} method
 * runs. A dynamic adapter count registered that way would still be invisible to
 * that condition. An {@code ApplicationContextInitializer}, which Spring Boot
 * invokes before the context refreshes at all, registers the adapters early
 * enough for every {@code @ConditionalOnBean(DataSourceAdapter.class)} in the
 * platform to see them, the same way a test harness that calls {@code
 * registerSingleton} in a {@code SpringApplicationBuilder} initializer already
 * relies on for a hand-written stub adapter.
 *
 * <p>{@link #configuredJsonSourcesContextOrchestrator} replaces the base
 * distribution's {@code ContextOrchestrator} rather than adding a second one,
 * which is what keeps a configured source answering through the platform's one
 * MCP tool instead of a route of its own: {@code @AutoConfigureBefore} makes
 * this class's {@code @ConditionalOnMissingBean} run first, so the base
 * auto-configuration's own orchestrator bean backs off once this one exists,
 * exactly the way an application-supplied bean of any {@code
 * @ConditionalOnMissingBean} type already does. Unlike the adapters, this bean
 * method's own {@code @Conditional}s are evaluated in that same one-pass
 * evaluation of every auto-configuration class, so no equivalent early-timing
 * problem applies here.
 */
@AutoConfiguration
@AutoConfigureBefore(name = "io.github.aindriub.dataprism.spring.boot.DataPrismAutoConfiguration")
public class ConfiguredJsonSourcesAutoConfiguration {

    static final String CONFIG_LOCATION_PROPERTY = "dataprism.json-sources.config-location";

    @Bean
    @ConditionalOnProperty(name = CONFIG_LOCATION_PROPERTY)
    @ConditionalOnMissingBean(ContextOrchestrator.class)
    ContextOrchestrator configuredJsonSourcesContextOrchestrator(Environment environment,
            List<DataSourceAdapter<?>> adapters, IdentityResolver identities, FieldMetadataResolver metadata,
            List<LlmResponseValidator> validators, SyntheticValueSource synthetics, ValueTokenSource tokens,
            SecretKeyProvider keys, AuditRecorder audit, ScopeBudget budget, PrivacyMetrics metrics, Clock clock,
            PrivacyPolicyResolver policy) {
        ConfiguredJsonSourcesConfig config = ConfiguredJsonSourcesInitializer.loadConfig(environment);

        JsonTreeScrubbingEngine javaFirst = new JsonTreeScrubbingEngine(metadata, policy, synthetics, tokens);
        ScrubbingEngine scrubber =
                new ConfiguredJsonScrubbingEngine(javaFirst, config.sources(), policy, synthetics, tokens);

        return new DefaultContextOrchestrator(adapters, scrubber, metadata, List.copyOf(validators), synthetics,
                new ParameterFingerprinter(keys), audit, identities,
                new SourceFanOut(SourceCircuitBreaker.disabled(), clock, metrics), budget, RequestLimits.DEFAULT,
                new NamespaceCorrelationService(metadata), new SourceAliasing(tokens), metrics);
    }
}

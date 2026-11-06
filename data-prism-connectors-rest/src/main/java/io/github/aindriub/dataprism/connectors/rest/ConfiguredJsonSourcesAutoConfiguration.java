package io.github.aindriub.dataprism.connectors.rest;

import io.github.aindriub.dataprism.audit.AuditRecorder;
import io.github.aindriub.dataprism.core.spi.DataSourceAdapter;
import io.github.aindriub.dataprism.core.spi.FieldMetadataResolver;
import io.github.aindriub.dataprism.core.spi.IdentityResolver;
import io.github.aindriub.dataprism.core.engine.JsonTreeScrubbingEngine;
import io.github.aindriub.dataprism.core.metrics.PrivacyMetrics;
import io.github.aindriub.dataprism.core.limits.RequestLimits;
import io.github.aindriub.dataprism.core.limits.ScopeBudget;
import io.github.aindriub.dataprism.core.spi.SecretKeyProvider;
import io.github.aindriub.dataprism.core.spi.ScrubbingEngine;
import io.github.aindriub.dataprism.core.spi.SyntheticValueSource;
import io.github.aindriub.dataprism.core.spi.ValueTokenSource;
import io.github.aindriub.dataprism.core.policy.PrivacyPolicyResolver;
import io.github.aindriub.dataprism.orchestration.ContextOrchestrator;
import io.github.aindriub.dataprism.orchestration.DefaultContextOrchestrator;
import io.github.aindriub.dataprism.orchestration.OrchestrationOptions;
import io.github.aindriub.dataprism.core.correlation.CorrelationMdc;
import io.github.aindriub.dataprism.orchestration.NamespaceCorrelationService;
import io.github.aindriub.dataprism.orchestration.ParameterFingerprinter;
import io.github.aindriub.dataprism.orchestration.SourceAliasing;
import io.github.aindriub.dataprism.orchestration.SourceCircuitBreaker;
import io.github.aindriub.dataprism.orchestration.SourceFanOut;
import io.github.aindriub.dataprism.orchestration.SourceFanOutOptions;
import io.github.aindriub.dataprism.orchestration.catalog.SourceHosts;
import io.github.aindriub.dataprism.validation.LlmResponseValidator;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.core.env.Environment;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

    /**
     * Used only where no {@link RequestLimits} bean exists, that is, without the auto-configuration module, which
     * binds {@code dataprism.limits.*} into the one bean. This module cannot read those properties itself. A test pins
     * these values to the framework defaults.
     */
    static final RequestLimits STANDALONE_LIMITS = RequestLimits.DEFAULT;


    static final String CONFIG_LOCATION_PROPERTY = "dataprism.json-sources.config-location";

    /**
     * The bean name {@code DataPrismContractValidator} looks up by, in place
     * of a compile-time class reference — see {@link #configuredJsonSourceNames}
     * for why a plain {@code Set<String>} type alone is not enough on its own
     * to keep this lookup unambiguous should another {@code Set<String>} bean
     * ever appear in the same context.
     */
    static final String CONFIGURED_JSON_SOURCE_NAMES_BEAN = "dataPrismConfiguredJsonSourceNames";

    /**
     * Task 69: publishes exactly the source names {@link
     * ConfiguredJsonSourcesInitializer} registers a {@code DataSourceAdapter}
     * bean for, read straight off the same parsed catalogue that initializer
     * itself loads, rather than a second, hand-restated copy of the same
     * names that could drift from it.
     *
     * <p>Published as a plain {@link Set}{@code <String>}, not a type this
     * module declares, deliberately: {@code DataPrismContractValidator}
     * (owned by {@code data-prism-spring-boot-autoconfigure}) consumes this
     * through {@code ObjectProvider<Set<String>>}, and the JDK's own {@link
     * Set} class is always resolvable, on any classpath, with or without this
     * module present. An earlier attempt published a record type declared in
     * this module instead; that made {@code DataPrismContractValidator}'s own
     * {@code ObjectProvider<ConfiguredJsonSourceNames>} parameter reference a
     * class that is genuinely absent from the base standalone server's
     * classpath (this module is test-scope only there — see {@code
     * data-prism-server/pom.xml}), and Spring's autowire-candidate resolution
     * resolves every parameter type via {@code Class.forName} before {@code
     * ObjectProvider.getIfAvailable()} is ever reached, crashing context
     * refresh outright rather than seeing an empty provider. A plain {@code
     * Set<String>} needs no such dependency at all, so
     * {@code data-prism-spring-boot-autoconfigure}'s {@code pom.xml} keeps no
     * dependency, optional or otherwise, on this module.
     */
    @Bean(name = CONFIGURED_JSON_SOURCE_NAMES_BEAN)
    @Conditional(LocationConfigured.class)
    Set<String> configuredJsonSourceNames(Environment environment) {
        return ConfiguredJsonSourcesInitializer.loadConfig(environment).sources().keySet();
    }

    /**
     * The host of each configured source's base URL, for the catalog's check that a description does not name it.
     * The starter cannot see {@link ConfiguredJsonSource}, so it reads this type from the orchestration module.
     */
    @Bean
    @Conditional(LocationConfigured.class)
    SourceHosts dataPrismConfiguredSourceHosts(Environment environment) {
        Map<String, String> hosts = new HashMap<>();
        ConfiguredJsonSourcesInitializer.loadConfig(environment).sources()
                .forEach((name, source) -> hosts.put(name, source.baseUrlHost()));
        return new SourceHosts(hosts);
    }

    /**
     * The context's {@link RequestLimits} bean. Without one the deployment cannot be honouring
     * {@code dataprism.limits.*}, so if any such property is set the answer is a refusal, never the defaults.
     */
    private static RequestLimits requestLimits(ObjectProvider<RequestLimits> limits, Environment environment) {
        RequestLimits bean = limits.getIfAvailable();
        if (bean != null) {
            return bean;
        }
        if (limitsConfigured(environment)) {
            throw new IllegalStateException("INVALID_REQUEST_LIMITS: dataprism.limits.* is set but no RequestLimits"
                    + " bean is available; the data-prism auto-configuration that binds it is not active");
        }
        return STANDALONE_LIMITS;
    }

    /** Whether anything at all is set under {@code dataprism.limits}: a scalar, a known key, or an unknown or misspelt one. */
    private static boolean limitsConfigured(Environment environment) {
        Binder binder = Binder.get(environment);
        try {
            return binder.bind("dataprism.limits", Bindable.mapOf(String.class, String.class)).isBound();
        } catch (BindException scalarOnThePrefix) {
            return true;
        }
    }

    /** The condition {@code @ConditionalOnProperty} cannot express: the location may be a list. */
    static final class LocationConfigured implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            return !ConfiguredJsonSourcesInitializer.locations(context.getEnvironment()).isEmpty();
        }
    }

    /**
     * With {@code dataprism.privacy.descriptor-file} set, the Java-first engine takes the resolver and the
     * descriptors of the auto-configured engine, which the starter has already checked are the ones built from
     * that file. Without the starter there is no such engine and startup refuses. Without the file, the injected
     * resolver and no descriptors, as before.
     */
    static JsonTreeScrubbingEngine javaFirst(Environment environment,
            ObjectProvider<JsonTreeScrubbingEngine> engines, FieldMetadataResolver metadata,
            PrivacyPolicyResolver policy, SyntheticValueSource synthetics, ValueTokenSource tokens) {
        String file = Binder.get(environment).bind("dataprism.privacy.descriptor-file", String.class).orElse(null);
        if (file == null || file.isBlank()) {
            return new JsonTreeScrubbingEngine(metadata, policy, synthetics, tokens);
        }
        JsonTreeScrubbingEngine engine = engines.getIfUnique();
        if (engine == null) {
            throw new IllegalStateException("MODEL_DESCRIPTOR_RESOLVER_REPLACED: dataprism.privacy.descriptor-file is"
                    + " set but the FieldMetadataResolver bean is not the one built from it, or an engine does not"
                    + " use it");
        }
        return new JsonTreeScrubbingEngine(engine.resolver(), policy, synthetics, tokens, engine.descriptors());
    }

    @Bean
    @Conditional(LocationConfigured.class)
    @ConditionalOnMissingBean(ContextOrchestrator.class)
    ContextOrchestrator configuredJsonSourcesContextOrchestrator(Environment environment,
            List<DataSourceAdapter<?>> adapters, IdentityResolver identities, FieldMetadataResolver metadata,
            List<LlmResponseValidator> validators, SyntheticValueSource synthetics, ValueTokenSource tokens,
            SecretKeyProvider keys, AuditRecorder audit, ScopeBudget budget, PrivacyMetrics metrics, Clock clock,
            PrivacyPolicyResolver policy, ObjectProvider<CorrelationMdc> correlationMdc,
            ObjectProvider<RequestLimits> limits, ObjectProvider<JsonTreeScrubbingEngine> engines,
            ObjectProvider<OrchestrationOptions> options) {
        ConfiguredJsonSourcesConfig config = ConfiguredJsonSourcesInitializer.loadConfig(environment);

        JsonTreeScrubbingEngine javaFirst = javaFirst(environment, engines, metadata, policy, synthetics, tokens);
        ScrubbingEngine scrubber =
                new ConfiguredJsonScrubbingEngine(javaFirst, config.sources(), policy, synthetics, tokens);

        return new DefaultContextOrchestrator(adapters, scrubber, metadata, List.copyOf(validators), synthetics,
                new ParameterFingerprinter(keys), audit, identities,
                new SourceFanOut(SourceCircuitBreaker.disabled(), clock, new SourceFanOutOptions(metrics,
                        correlationMdc.getIfAvailable(CorrelationMdc::off))), budget, requestLimits(limits, environment),
                new NamespaceCorrelationService(metadata), new SourceAliasing(tokens), metrics,
                // The starter's catalog, real-names scopes and limit; the defaults only without the starter.
                options.getIfAvailable(OrchestrationOptions::defaults));
    }
}

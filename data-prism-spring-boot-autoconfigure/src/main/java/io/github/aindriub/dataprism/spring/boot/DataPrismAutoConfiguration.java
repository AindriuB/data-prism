package io.github.aindriub.dataprism.spring.boot;

import io.github.aindriub.dataprism.audit.AuditRecorder;
import io.github.aindriub.dataprism.audit.AuditSink;
import io.github.aindriub.dataprism.core.DataSourceAdapter;
import io.github.aindriub.dataprism.core.DefaultFieldMetadataResolver;
import io.github.aindriub.dataprism.core.EntityCorrelationService;
import io.github.aindriub.dataprism.core.FieldMetadataResolver;
import io.github.aindriub.dataprism.core.IdentityResolver;
import io.github.aindriub.dataprism.core.InMemoryScopeBudget;
import io.github.aindriub.dataprism.core.JsonTreeScrubbingEngine;
import io.github.aindriub.dataprism.core.PrivacyMetrics;
import io.github.aindriub.dataprism.core.PseudonymisationVersion;
import io.github.aindriub.dataprism.core.RequestLimits;
import io.github.aindriub.dataprism.core.ScopeBudget;
import io.github.aindriub.dataprism.core.SecretKeyProvider;
import io.github.aindriub.dataprism.core.SyntheticValueSource;
import io.github.aindriub.dataprism.core.ValueTokenSource;
import io.github.aindriub.dataprism.core.policy.PrivacyPolicyResolver;
import io.github.aindriub.dataprism.core.policy.PrivacyProfiles;
import io.github.aindriub.dataprism.core.policy.ProfilePrivacyPolicyResolver;
import io.github.aindriub.dataprism.mcp.DataPrismMcpServer;
import io.github.aindriub.dataprism.orchestration.ContextOrchestrator;
import io.github.aindriub.dataprism.orchestration.DefaultContextOrchestrator;
import io.github.aindriub.dataprism.orchestration.NamespaceCorrelationService;
import io.github.aindriub.dataprism.orchestration.ParameterFingerprinter;
import io.github.aindriub.dataprism.orchestration.SourceAliasing;
import io.github.aindriub.dataprism.orchestration.SourceCircuitBreaker;
import io.github.aindriub.dataprism.orchestration.SourceFanOut;
import io.github.aindriub.dataprism.pseudonymisation.HmacSyntheticGenerator;
import io.github.aindriub.dataprism.pseudonymisation.HmacValueTokenSource;
import io.github.aindriub.dataprism.pseudonymisation.vocabulary.Vocabulary;
import io.github.aindriub.dataprism.pseudonymisation.vocabulary.VocabularyRegistry;
import io.github.aindriub.dataprism.security.AuthorizationService;
import io.github.aindriub.dataprism.security.PurposeValidator;
import io.github.aindriub.dataprism.security.ScopeResolver;
import io.github.aindriub.dataprism.security.SecurityPolicy;
import io.github.aindriub.dataprism.validation.LlmResponseValidator;
import io.github.aindriub.dataprism.validation.RawValueLeakValidator;
import io.modelcontextprotocol.server.McpTransportContextExtractor;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.annotation.Primary;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;

import java.io.IOException;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Arrays;

/**
 * Wiring leaf for the reviewed privacy pipeline. It deliberately creates no
 * connector: applications must provide source adapters and identity resolution.
 * The HTTP transport is built only through {@link DataPrismMcpServer}, which
 * owns the single scrubbed MCP mapper.
 */
@AutoConfiguration
@EnableConfigurationProperties(DataPrismProperties.class)
public class DataPrismAutoConfiguration {
    /** Resolve this before singleton creation: an empty protected pipeline is never valid. */
    @Bean
    static BeanFactoryPostProcessor dataPrismIdentityResolverPreflight() {
        return factory -> {
            if (factory.getBeanNamesForType(IdentityResolver.class, true, false).length == 0) {
                throw new DataPrismConfigurationException("MISSING_IDENTITY_RESOLVER", "provide an IdentityResolver bean");
            }
        };
    }
    @Bean
    Object dataPrismPropertiesValidated(DataPrismProperties properties, List<DataSourceAdapter<?>> adapters,
            ObjectProvider<IdentityResolver> identities, ObjectProvider<HmacKeyReferenceResolver> keys,
            ObjectProvider<AuditSink> audit, ObjectProvider<PrivacyMetrics> metrics) {
        properties.validate();
        DataPrismContractValidator.validateIntegrations(properties, adapters, identities, keys, audit, metrics);
        validateProfile(properties);
        validateKey(properties, keys.getIfAvailable());
        return new Object();
    }
    @Bean
    DataPrismContractValidator dataPrismContractValidator(DataPrismProperties properties,
            ObjectProvider<DataSourceAdapter<?>> adapters, ObjectProvider<IdentityResolver> identities,
            ObjectProvider<HmacKeyReferenceResolver> keys, ObjectProvider<AuditSink> audit,
            ObjectProvider<PrivacyMetrics> metrics) {
        return new DataPrismContractValidator(properties, adapters, identities, keys, audit, metrics);
    }

    @Bean @ConditionalOnMissingBean
    Clock dataPrismClock() { return Clock.systemUTC(); }

    @Bean @ConditionalOnMissingBean
    FieldMetadataResolver dataPrismFieldMetadataResolver() { return new DefaultFieldMetadataResolver(); }

    @Bean @ConditionalOnMissingBean @DependsOn("dataPrismPropertiesValidated")
    Vocabulary dataPrismVocabulary(DataPrismProperties properties) {
        VocabularyRegistry registry = VocabularyRegistry.withBuiltIns();
        String locale = "neutral".equals(properties.getPrivacy().getLocale()) ? "und" : properties.getPrivacy().getLocale();
        if (!registry.locales().contains(locale) && !registry.locales().contains(locale == null ? "" : locale.split("-")[0])) {
            throw new DataPrismConfigurationException("UNSUPPORTED_LOCALE", "dataprism.privacy.locale");
        }
        return registry.resolve(locale);
    }

    @Bean @ConditionalOnMissingBean
    PseudonymisationVersion dataPrismPseudonymisationVersion(DataPrismProperties properties, Vocabulary vocabulary) {
        return PseudonymisationVersion.HMAC_SHA256_V1.withKey(properties.getPrivacy().getHmacKey().getKeyId()).withVocabulary(vocabulary.id());
    }

    @Bean @ConditionalOnMissingBean
    SyntheticValueSource dataPrismSyntheticValueSource(SecretKeyProvider keys, Vocabulary vocabulary) { return new HmacSyntheticGenerator(keys, vocabulary); }
    @Bean @ConditionalOnMissingBean
    ValueTokenSource dataPrismValueTokenSource(SecretKeyProvider keys) { return new HmacValueTokenSource(keys); }
    @Bean @Primary @ConditionalOnBean(HmacKeyReferenceResolver.class)
    SecretKeyProvider dataPrismSecretKeyProvider(DataPrismProperties properties, HmacKeyReferenceResolver resolver) {
        String reference = properties.getPrivacy().getHmacKey().getEnvironmentVariable();
        if (reference == null || reference.isBlank()) reference = properties.getPrivacy().getHmacKey().getProviderReference();
        return new ConfiguredSecretKeyProvider(properties.getPrivacy().getHmacKey().getKeyId(), reference, resolver);
    }
    @Bean @ConditionalOnMissingBean
    PrivacyPolicyResolver dataPrismPrivacyPolicyResolver(DataPrismProperties properties) {
        validateProfile(properties);
        try (var input = DataPrismAutoConfiguration.class.getResourceAsStream("/privacy-profiles-default.yaml")) {
            return new ProfilePrivacyPolicyResolver(PrivacyProfiles.fromYaml(input));
        } catch (IOException e) { throw new IllegalStateException("default privacy profiles could not be loaded", e); }
    }
    @Bean @ConditionalOnMissingBean
    JsonTreeScrubbingEngine dataPrismScrubber(FieldMetadataResolver metadata, PrivacyPolicyResolver policy,
                                               SyntheticValueSource synthetics, ValueTokenSource tokens) { return new JsonTreeScrubbingEngine(metadata, policy, synthetics, tokens); }
    @Bean @ConditionalOnMissingBean
    LlmResponseValidator dataPrismRawValueLeakValidator() { return new RawValueLeakValidator(); }
    @Bean @ConditionalOnMissingBean
    SecurityPolicy dataPrismSecurityPolicy(DataPrismProperties properties) {
        return new SecurityPolicy(Set.copyOf(properties.getSecurityPolicy().getPurposes()), properties.getSecurityPolicy().getRoles().entrySet().stream().collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, entry -> Set.copyOf(entry.getValue()))));
    }
    @Bean @ConditionalOnMissingBean
    AuthorizationService dataPrismAuthorizationService(SecurityPolicy policy, DataPrismProperties properties) { return new AuthorizationService(policy, properties.getPrivacy().getProfile(), io.github.aindriub.dataprism.core.PrivacyScopeType.INVESTIGATION); }
    @Bean @ConditionalOnMissingBean
    ScopeResolver dataPrismScopeResolver(PseudonymisationVersion version, DataPrismProperties properties) { return new ScopeResolver(version, properties.getPrivacy().getScopeLifetime(), new PurposeValidator(Set.copyOf(properties.getSecurityPolicy().getPurposes()))); }
    @Bean @ConditionalOnMissingBean @ConditionalOnBean(AuditSink.class)
    AuditRecorder dataPrismAuditRecorder(AuditSink sink, Clock clock, DataPrismProperties properties) { return new AuditRecorder(sink, clock, properties.getAudit().getWriterId()); }
    @Bean @ConditionalOnMissingBean
    @ConditionalOnBean({IdentityResolver.class, SecretKeyProvider.class, AuditSink.class, PrivacyMetrics.class, DataSourceAdapter.class})
    ScopeBudget dataPrismScopeBudget() { return new InMemoryScopeBudget(); }
    @Bean @ConditionalOnMissingBean
    ContextOrchestrator dataPrismContextOrchestrator(List<DataSourceAdapter<?>> adapters, IdentityResolver identities,
            JsonTreeScrubbingEngine scrubber, FieldMetadataResolver metadata, LlmResponseValidator validator,
            SyntheticValueSource synthetics, ValueTokenSource tokens, SecretKeyProvider keys, AuditRecorder audit,
            ScopeBudget budget, PrivacyMetrics metrics, Clock clock) {
        return new DefaultContextOrchestrator(adapters, scrubber, metadata, List.of(validator), synthetics,
                new ParameterFingerprinter(keys), audit, identities,
                new SourceFanOut(SourceCircuitBreaker.disabled(), clock, metrics), budget, RequestLimits.DEFAULT,
                new NamespaceCorrelationService(metadata), new SourceAliasing(tokens), metrics);
    }
    @Bean
    @ConditionalOnProperty(prefix = "dataprism.transport", name = "mode", havingValue = "HTTP",
            matchIfMissing = true)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    Object dataPrismHttpTransportValidated(
            ObjectProvider<McpTransportContextExtractor<HttpServletRequest>> extractors) {
        if (extractors.getIfAvailable() == null) {
            throw new DataPrismConfigurationException("MISSING_CALLER_CONTEXT_EXTRACTOR",
                    "HTTP transport requires an McpTransportContextExtractor<HttpServletRequest> bean");
        }
        return new Object();
    }

    @Bean @ConditionalOnMissingBean @ConditionalOnBean(McpTransportContextExtractor.class)
    @ConditionalOnProperty(prefix = "dataprism.transport", name = "mode", havingValue = "HTTP",
            matchIfMissing = true)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @DependsOn("dataPrismHttpTransportValidated")
    DataPrismMcpServer.HttpTransport dataPrismHttpTransport(ContextOrchestrator orchestrator, AuthorizationService authorization,
            ScopeResolver scopeResolver, McpTransportContextExtractor<HttpServletRequest> extractor,
            PrivacyMetrics metrics, AuditRecorder audit, Clock clock) {
        return DataPrismMcpServer.streamableHttp(orchestrator, authorization, scopeResolver, extractor, metrics, audit, clock);
    }

    @Bean(destroyMethod = "closeGracefully")
    @ConditionalOnBean(DataPrismMcpServer.HttpTransport.class)
    @ConditionalOnProperty(prefix = "dataprism.transport", name = "mode", havingValue = "HTTP",
            matchIfMissing = true)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    McpSyncServer dataPrismMcpSyncServer(DataPrismMcpServer.HttpTransport transport) {
        return transport.server();
    }

    @Bean
    @ConditionalOnMissingBean(name = "dataPrismMcpServlet")
    @ConditionalOnBean(DataPrismMcpServer.HttpTransport.class)
    @ConditionalOnProperty(prefix = "dataprism.transport", name = "mode", havingValue = "HTTP",
            matchIfMissing = true)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    ServletRegistrationBean<HttpServletStreamableServerTransportProvider> dataPrismMcpServlet(
            DataPrismMcpServer.HttpTransport transport, DataPrismProperties properties) {
        ServletRegistrationBean<HttpServletStreamableServerTransportProvider> registration =
                new ServletRegistrationBean<>(transport.transportProvider(),
                        properties.getTransport().getHttp().getPath());
        registration.setAsyncSupported(true);
        return registration;
    }

    private static void validateProfile(DataPrismProperties properties) {
        try (var input = DataPrismAutoConfiguration.class.getResourceAsStream("/privacy-profiles-default.yaml")) {
            if (!PrivacyProfiles.fromYaml(input).containsKey(properties.getPrivacy().getProfile())) {
                throw new DataPrismConfigurationException("UNKNOWN_PRIVACY_PROFILE", properties.getPrivacy().getProfile());
            }
        } catch (IOException e) { throw new IllegalStateException("default privacy profiles could not be loaded", e); }
    }

    private static void validateKey(DataPrismProperties properties, HmacKeyReferenceResolver provider) {
        if (provider == null) throw new DataPrismConfigurationException("MISSING_KEY_PROVIDER", "provide an HmacKeyReferenceResolver bean for the configured reference");
        String reference = properties.getPrivacy().getHmacKey().getEnvironmentVariable();
        if (reference == null || reference.isBlank()) reference = properties.getPrivacy().getHmacKey().getProviderReference();
        try {
            byte[] key = provider.resolve(properties.getPrivacy().getHmacKey().getKeyId(), reference);
            if (key == null || key.length < 32) throw new DataPrismConfigurationException("HMAC_KEY_WEAK", "configured key material is shorter than 32 bytes");
            Arrays.fill(key, (byte) 0);
        } catch (DataPrismConfigurationException e) { throw e;
        } catch (RuntimeException e) { throw new DataPrismConfigurationException("HMAC_KEY_UNRESOLVED", "configured HMAC key reference could not be resolved"); }
    }
}

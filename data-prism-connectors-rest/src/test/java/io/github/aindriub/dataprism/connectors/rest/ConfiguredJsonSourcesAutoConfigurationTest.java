package io.github.aindriub.dataprism.connectors.rest;

import io.github.aindriub.dataprism.audit.AuditRecorder;
import io.github.aindriub.dataprism.core.DataSourceAdapter;
import io.github.aindriub.dataprism.core.DefaultFieldMetadataResolver;
import io.github.aindriub.dataprism.core.FieldMetadataResolver;
import io.github.aindriub.dataprism.core.IdentityResolver;
import io.github.aindriub.dataprism.core.InMemoryScopeBudget;
import io.github.aindriub.dataprism.core.PassThroughIdentityResolver;
import io.github.aindriub.dataprism.core.PrivacyMetrics;
import io.github.aindriub.dataprism.core.ScopeBudget;
import io.github.aindriub.dataprism.core.SecretKeyProvider;
import io.github.aindriub.dataprism.core.SyntheticValueSource;
import io.github.aindriub.dataprism.core.ValueTokenSource;
import io.github.aindriub.dataprism.core.policy.PrivacyPolicyResolver;
import io.github.aindriub.dataprism.core.policy.PrivacyProfiles;
import io.github.aindriub.dataprism.core.policy.ProfilePrivacyPolicyResolver;
import io.github.aindriub.dataprism.orchestration.ContextOrchestrator;
import io.github.aindriub.dataprism.pseudonymisation.HmacSyntheticGenerator;
import io.github.aindriub.dataprism.pseudonymisation.HmacValueTokenSource;
import io.github.aindriub.dataprism.pseudonymisation.StaticSecretKeyProvider;
import io.github.aindriub.dataprism.pseudonymisation.vocabulary.VocabularyRegistry;
import io.github.aindriub.dataprism.validation.LlmResponseValidator;
import io.github.aindriub.dataprism.validation.RawValueLeakValidator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Spring wiring itself, not just the classes it assembles: proves that
 * {@link ConfiguredJsonSourcesAutoConfiguration} — the thing an operator
 * actually loads via {@code -Dloader.path} — registers one {@link
 * DataSourceAdapter} bean per configured source and replaces the platform's
 * {@link ContextOrchestrator} with one that knows about them, and that doing
 * nothing (no {@code dataprism.json-sources.config-location}) leaves a
 * deployment exactly as it was.
 */
class ConfiguredJsonSourcesAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(RequiredCollaborators.class)
            .withConfiguration(AutoConfigurations.of(
                    BaselineContextOrchestratorAutoConfiguration.class,
                    ConfiguredJsonSourcesAutoConfiguration.class));

    @Test
    @DisplayName("with no configured-JSON-sources property, nothing here registers")
    void inertWithoutTheProperty() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBeansOfType(DataSourceAdapter.class)).isEmpty();
            // The platform's own default is the only ContextOrchestrator bean.
            assertThat(context.getBeansOfType(ContextOrchestrator.class)).hasSize(1);
            assertThat(context.getBean(ContextOrchestrator.class))
                    .isSameAs(BaselineContextOrchestratorAutoConfiguration.INSTANCE);
        });
    }

    @Test
    @DisplayName("with the property set, one adapter bean per source is registered and the "
            + "orchestrator is replaced, not duplicated")
    void registersAdaptersAndReplacesTheOrchestrator() {
        runner.withPropertyValues(
                        "dataprism.json-sources.config-location=classpath:/task20-json-sources.yaml")
                .run((AssertableApplicationContext context) -> {
                    assertThat(context).hasNotFailed();

                    var adapters = context.getBeansOfType(DataSourceAdapter.class);
                    assertThat(adapters).hasSize(1);
                    assertThat(adapters.values().iterator().next().sourceName()).isEqualTo("customer-api");

                    // Exactly one ContextOrchestrator bean still exists -- replaced, not
                    // added alongside -- so the platform still exposes one MCP tool
                    // backed by one orchestrator, never a second route to this source.
                    assertThat(context.getBeansOfType(ContextOrchestrator.class)).hasSize(1);
                    assertThat(context.getBean(ContextOrchestrator.class))
                            .isNotSameAs(BaselineContextOrchestratorAutoConfiguration.INSTANCE);
                });
    }

    @Test
    @DisplayName("an invalid catalogue at the configured location refuses context startup")
    void invalidCatalogueRefusesStartup() {
        runner.withPropertyValues(
                        "dataprism.json-sources.config-location=classpath:/does-not-exist.yaml")
                .run(context -> assertThat(context).hasFailed());
    }

    /**
     * Every collaborator {@link
     * ConfiguredJsonSourcesAutoConfiguration#configuredJsonSourcesContextOrchestrator}
     * needs, built the same way the end-to-end test builds them.
     */
    @Configuration(proxyBeanMethods = false)
    static class RequiredCollaborators {

        @Bean
        Clock clock() {
            return Clock.systemUTC();
        }

        @Bean
        IdentityResolver identityResolver() {
            return new PassThroughIdentityResolver();
        }

        @Bean
        FieldMetadataResolver fieldMetadataResolver() {
            return new DefaultFieldMetadataResolver();
        }

        @Bean
        LlmResponseValidator rawValueLeakValidator() {
            return new RawValueLeakValidator();
        }

        @Bean
        SecretKeyProvider secretKeyProvider() {
            return StaticSecretKeyProvider.of("task-20-autoconfiguration-test-key-not-for-real-data");
        }

        @Bean
        SyntheticValueSource syntheticValueSource(SecretKeyProvider keys) {
            return new HmacSyntheticGenerator(keys, VocabularyRegistry.withBuiltIns().resolve("und"));
        }

        @Bean
        ValueTokenSource valueTokenSource(SecretKeyProvider keys) {
            return new HmacValueTokenSource(keys);
        }

        @Bean
        PrivacyPolicyResolver privacyPolicyResolver() throws IOException {
            try (var input = RequiredCollaborators.class.getResourceAsStream("/privacy-profiles-default.yaml")) {
                return new ProfilePrivacyPolicyResolver(PrivacyProfiles.fromYaml(input));
            }
        }

        @Bean
        AuditRecorder auditRecorder(Clock clock) {
            return new AuditRecorder(event -> { }, clock, "test-20-autoconfiguration");
        }

        @Bean
        ScopeBudget scopeBudget() {
            return new InMemoryScopeBudget();
        }

        @Bean
        PrivacyMetrics privacyMetrics() {
            return PrivacyMetrics.none();
        }
    }

    /**
     * Stands in for the platform's own default auto-configured {@code
     * ContextOrchestrator}: a separate {@code @AutoConfiguration}, exactly the
     * relationship {@code DataPrismAutoConfiguration} has to {@link
     * ConfiguredJsonSourcesAutoConfiguration} in production, so {@code
     * @ConditionalOnMissingBean} orders against it the same way here as it does
     * there. A plain {@code @Configuration} bean would win by Spring Boot's own
     * rule that user-supplied beans always precede auto-configured ones,
     * regardless of {@code @AutoConfigureBefore} — which would prove nothing
     * about the real ordering this test exists to check.
     */
    @AutoConfiguration
    @AutoConfigureAfter(ConfiguredJsonSourcesAutoConfiguration.class)
    static class BaselineContextOrchestratorAutoConfiguration {

        static final ContextOrchestrator INSTANCE = (request, context, investigationContext) -> {
            throw new UnsupportedOperationException("not invoked by this test");
        };

        @Bean
        @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
        ContextOrchestrator baselineContextOrchestrator() {
            return INSTANCE;
        }
    }
}

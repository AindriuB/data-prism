package io.github.aindriub.dataprism.connectors.rest;

import io.github.aindriub.dataprism.annotations.InternalIdentifier;
import io.github.aindriub.dataprism.annotations.LlmExposedModel;
import io.github.aindriub.dataprism.annotations.NonSensitive;
import io.github.aindriub.dataprism.core.limits.RequestLimits;
import io.github.aindriub.dataprism.core.model.InvestigationContext;
import io.github.aindriub.dataprism.core.model.PrivacyContext;
import io.github.aindriub.dataprism.core.model.PrivacyScopeType;
import io.github.aindriub.dataprism.core.model.PseudonymisationVersion;
import io.github.aindriub.dataprism.core.refusal.PrivacyRefusedException;
import io.github.aindriub.dataprism.core.spi.DataRequest;
import io.github.aindriub.dataprism.core.spi.DataSourceAdapter;
import io.github.aindriub.dataprism.orchestration.ContextOrchestrator;
import io.github.aindriub.dataprism.orchestration.ContextRequest;
import io.github.aindriub.dataprism.orchestration.ContextResponse;
import io.github.aindriub.dataprism.orchestration.SourceOutcome;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;

import io.github.aindriub.dataprism.pseudonymisation.vocabulary.VocabularyRegistry;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The orchestrator built by {@link ConfiguredJsonSourcesAutoConfiguration} uses the single
 * {@link RequestLimits} bean the application context holds, not a hard-wired default.
 */
class ConfiguredJsonSourcesLimitsTest {

    private static final String LOCATION = "dataprism.json-sources.config-location=classpath:/task20-json-sources.yaml";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfiguredJsonSourcesInitializer())
            .withUserConfiguration(ConfiguredJsonSourcesAutoConfigurationTest.RequiredCollaborators.class,
                    StubSource.class)
            .withConfiguration(AutoConfigurations.of(ConfiguredJsonSourcesAutoConfiguration.class))
            .withPropertyValues(LOCATION);

    @LlmExposedModel
    record StubDto(@InternalIdentifier String id, @NonSensitive(reason = "fixed label") String label) {
    }

    @Configuration(proxyBeanMethods = false)
    static class StubSource {
        /** Ordered first, so it is the one source within a limit of one. */
        @Bean
        @Order(1)
        DataSourceAdapter<StubDto> stubSource() {
            return new DataSourceAdapter<>() {
                public String sourceName() { return "stub-source"; }
                public Class<StubDto> responseType() { return StubDto.class; }
                public StubDto fetch(DataRequest request) { return new StubDto("S-1", "fixed"); }
            };
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class Limits {
        static RequestLimits value = RequestLimits.DEFAULT;

        @Bean
        RequestLimits dataPrismRequestLimits() {
            return value;
        }
    }

    private static PrivacyContext context() {
        return new PrivacyContext("SCOPE-1", PrivacyScopeType.INVESTIGATION, "DEFAULT", "investigation",
                Instant.parse("2030-01-01T00:00:00Z"), PseudonymisationVersion.HMAC_SHA256_V1.withKey("v1")
                        .withVocabulary(VocabularyRegistry.withBuiltIns().resolve("und").id()));
    }

    private static InvestigationContext caller() {
        return new InvestigationContext("investigator-1", "client-1", "CASE-1", Set.of());
    }

    private static RequestLimits with(int maxSources, int scopeReadBudget) {
        RequestLimits d = RequestLimits.DEFAULT;
        return new RequestLimits(maxSources, d.maxRecords(), d.maxResponseBytes(), Duration.ofSeconds(3),
                d.maxConcurrency(), scopeReadBudget);
    }

    @Test
    @DisplayName("a configured max-sources of 1 skips the second source as SKIPPED_OVER_LIMIT")
    void honoursMaxSources() {
        Limits.value = with(1, 100);
        runner.withUserConfiguration(Limits.class).run(context -> {
            assertThat(context).hasNotFailed();
            ContextResponse response = context.getBean(ContextOrchestrator.class)
                    .buildContext(ContextRequest.of("CUSTOMER", "CUST-1"), context(), caller());
            assertThat(response.sources().values())
                    .containsExactlyInAnyOrder(SourceOutcome.Status.ANSWERED.name(),
                            SourceOutcome.Status.SKIPPED_OVER_LIMIT.name());
        });
    }

    @Test
    @DisplayName("a configured scope-read-budget of 1 refuses the second read of a subject")
    void honoursScopeReadBudget() {
        Limits.value = with(8, 1);
        runner.withUserConfiguration(Limits.class).run(context -> {
            assertThat(context).hasNotFailed();
            ContextOrchestrator orchestrator = context.getBean(ContextOrchestrator.class);
            orchestrator.buildContext(ContextRequest.of("CUSTOMER", "CUST-1"), context(), caller());
            assertThatThrownBy(() ->
                    orchestrator.buildContext(ContextRequest.of("CUSTOMER", "CUST-1"), context(), caller()))
                    .isInstanceOfSatisfying(PrivacyRefusedException.class,
                            e -> assertThat(e.code()).isEqualTo("SCOPE_READ_BUDGET"));
        });
    }

    @Test
    @DisplayName("without a RequestLimits bean in the context, the documented defaults apply")
    void withoutABeanTheDocumentedDefaultsApply() {
        assertThat(ConfiguredJsonSourcesAutoConfiguration.STANDALONE_LIMITS).isEqualTo(RequestLimits.DEFAULT);
    }

    @Test
    @DisplayName("a dataprism.limits property with no RequestLimits bean refuses startup, naming no value")
    void limitsPropertyWithoutABeanRefusesStartup() {
        runner.withPropertyValues("dataprism.limits.max-sources=4711").run(context -> {
            assertThat(context).hasFailed();
            Throwable root = context.getStartupFailure();
            while (root.getCause() != null) {
                root = root.getCause();
            }
            assertThat(root.getMessage()).contains("INVALID_REQUEST_LIMITS").doesNotContain("4711");
        });
    }

    @Test
    @DisplayName("with no dataprism.limits property and no bean, the context starts on the documented defaults")
    void noLimitsPropertyAndNoBeanStarts() {
        runner.run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    @DisplayName("the refusal also sees a camelCase key, an environment-variable key and a scalar on the prefix")
    void relaxedAndScalarFormsAreDetected() {
        runner.withPropertyValues("dataprism.limits.maxSources=4711")
                .run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues("dataprism.limits=4711").run(context -> {
            assertThat(context).hasFailed();
            Throwable root = context.getStartupFailure();
            while (root.getCause() != null) {
                root = root.getCause();
            }
            assertThat(root.getMessage()).contains("INVALID_REQUEST_LIMITS").doesNotContain("4711");
        });
        runner.withInitializer(ctx -> ctx.getEnvironment().getPropertySources().addFirst(
                        new org.springframework.core.env.SystemEnvironmentPropertySource("test-systemEnvironment",
                                java.util.Map.of("DATAPRISM_LIMITS_MAXSOURCES", "4711"))))
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("a misspelt limits key, in kebab or environment-variable form, is refused rather than ignored")
    void misspeltKeysAreRefused() {
        runner.withPropertyValues("dataprism.limits.max-sorces=1")
                .run(context -> assertThat(context).hasFailed());
        runner.withInitializer(ctx -> ctx.getEnvironment().getPropertySources().addFirst(
                        new org.springframework.core.env.SystemEnvironmentPropertySource("test-systemEnvironment",
                                java.util.Map.of("DATAPRISM_LIMITS_MAXSORCES", "1"))))
                .run(context -> assertThat(context).hasFailed());
    }
}

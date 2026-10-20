package io.github.aindriub.dataprism.connectors.rest;

import io.github.aindriub.dataprism.annotations.InternalIdentifier;
import io.github.aindriub.dataprism.annotations.LlmExposedModel;
import io.github.aindriub.dataprism.annotations.NonSensitive;
import io.github.aindriub.dataprism.core.model.InvestigationContext;
import io.github.aindriub.dataprism.core.model.PrivacyContext;
import io.github.aindriub.dataprism.core.model.PrivacyScopeType;
import io.github.aindriub.dataprism.core.model.PseudonymisationVersion;
import io.github.aindriub.dataprism.core.refusal.PrivacyRefusedException;
import io.github.aindriub.dataprism.core.spi.DataRequest;
import io.github.aindriub.dataprism.core.spi.DataSourceAdapter;
import io.github.aindriub.dataprism.orchestration.ContextOrchestrator;
import io.github.aindriub.dataprism.orchestration.ContextRequest;
import io.github.aindriub.dataprism.pseudonymisation.vocabulary.VocabularyRegistry;
import io.github.aindriub.dataprism.validation.LlmResponseValidator;
import io.github.aindriub.dataprism.validation.SensitivePatternValidator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The orchestrator built by {@link ConfiguredJsonSourcesAutoConfiguration} runs every {@link LlmResponseValidator}
 * bean in the context, so with the framework's pattern validator present (this module cannot depend on the
 * auto-configuration module that registers it, so the test registers the same type) an email-shaped value in a
 * {@code @NonSensitive} field is refused. The framework bean itself is proven in the auto-configuration module's
 * {@code PatternValidatorWiringTest}.
 */
class ConfiguredJsonSourcesPatternValidatorTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfiguredJsonSourcesInitializer())
            .withUserConfiguration(ConfiguredJsonSourcesAutoConfigurationTest.RequiredCollaborators.class,
                    EmailShapedSource.class)
            .withConfiguration(AutoConfigurations.of(ConfiguredJsonSourcesAutoConfiguration.class))
            .withPropertyValues("dataprism.json-sources.config-location=classpath:/task20-json-sources.yaml");

    @LlmExposedModel
    record LabelDto(@InternalIdentifier String id, @NonSensitive(reason = "free label reviewed as inert") String label) {
    }

    @Configuration(proxyBeanMethods = false)
    static class EmailShapedSource {
        @Bean
        DataSourceAdapter<LabelDto> labelSource() {
            return new DataSourceAdapter<>() {
                public String sourceName() { return "label-source"; }
                public Class<LabelDto> responseType() { return LabelDto.class; }
                public LabelDto fetch(DataRequest request) {
                    return new LabelDto("S-1", "casework.sample@mail.invalid");
                }
            };
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class PatternValidator {
        @Bean
        LlmResponseValidator dataPrismSensitivePatternValidator() { return new SensitivePatternValidator(); }
    }

    private static PrivacyContext context() {
        return new PrivacyContext("SCOPE-1", PrivacyScopeType.INVESTIGATION, "DEFAULT", "investigation",
                Instant.parse("2030-01-01T00:00:00Z"), PseudonymisationVersion.HMAC_SHA256_V1.withKey("v1")
                        .withVocabulary(VocabularyRegistry.withBuiltIns().resolve("und").id()));
    }

    private static InvestigationContext caller() {
        return new InvestigationContext("investigator-1", "client-1", "CASE-1", Set.of());
    }

    @Test
    @DisplayName("the configured-sources orchestrator refuses an email-shaped value in a NonSensitive field")
    void refusesAnEmailShapedValue() {
        runner.withUserConfiguration(PatternValidator.class).run(context -> {
            assertThat(context).hasNotFailed();
            ContextOrchestrator orchestrator = context.getBean(ContextOrchestrator.class);
            assertThatThrownBy(() -> orchestrator.buildContext(ContextRequest.of("CUSTOMER", "CUST-1"),
                    context(), caller()))
                    .isInstanceOfSatisfying(PrivacyRefusedException.class, e -> {
                        assertThat(e.code()).isEqualTo("VALIDATION_FAILED");
                        assertThat(e.getMessage()).contains("SENSITIVE_PATTERN");
                    });
        });
    }

    @Test
    @DisplayName("control: without a pattern validator bean the same value is not refused by this orchestrator")
    void withoutTheValidatorTheValueIsNotRefused() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            ContextOrchestrator orchestrator = context.getBean(ContextOrchestrator.class);
            assertThat(orchestrator.buildContext(ContextRequest.of("CUSTOMER", "CUST-1"), context(), caller()))
                    .isNotNull();
        });
    }
}

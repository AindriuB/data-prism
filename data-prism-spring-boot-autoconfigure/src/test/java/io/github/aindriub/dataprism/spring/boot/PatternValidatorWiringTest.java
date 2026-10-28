package io.github.aindriub.dataprism.spring.boot;

import io.github.aindriub.dataprism.annotations.DataClassification;
import io.github.aindriub.dataprism.annotations.InternalIdentifier;
import io.github.aindriub.dataprism.annotations.LlmExposedModel;
import io.github.aindriub.dataprism.annotations.NonSensitive;
import io.github.aindriub.dataprism.audit.AuditSink;
import io.github.aindriub.dataprism.core.metrics.PrivacyMetrics;
import io.github.aindriub.dataprism.core.model.InvestigationContext;
import io.github.aindriub.dataprism.core.model.PrivacyContext;
import io.github.aindriub.dataprism.core.model.PrivacyScopeType;
import io.github.aindriub.dataprism.core.model.PseudonymisationVersion;
import io.github.aindriub.dataprism.core.refusal.PrivacyRefusedException;
import io.github.aindriub.dataprism.core.spi.DataRequest;
import io.github.aindriub.dataprism.core.spi.DataSourceAdapter;
import io.github.aindriub.dataprism.core.spi.IdentityResolver;
import io.github.aindriub.dataprism.core.spi.PassThroughIdentityResolver;
import io.github.aindriub.dataprism.orchestration.ContextOrchestrator;
import io.github.aindriub.dataprism.orchestration.ContextRequest;
import io.github.aindriub.dataprism.pseudonymisation.vocabulary.VocabularyRegistry;
import io.github.aindriub.dataprism.validation.FreeTextRecogniser;
import io.github.aindriub.dataprism.validation.SensitivePatternValidator;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpTransportContextExtractor;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The Spring-built orchestrator runs the shape scan: a {@code @NonSensitive} field that carries an
 * email-shaped value which is not an emitted pseudonym is refused, not passed through.
 *
 * <p>Mutation proof: removing {@code dataPrismSensitivePatternValidator} makes
 * {@link #an_email_shaped_non_sensitive_value_is_refused_by_the_spring_built_orchestrator()} fail, because the
 * raw-value leak check alone does not look at a value no source declared sensitive.
 */
class PatternValidatorWiringTest {

    @LlmExposedModel
    record PlainDto(@InternalIdentifier String id, @NonSensitive(reason = "free label reviewed as inert") String label) {
    }

    private final WebApplicationContextRunner context = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(DataPrismAutoConfiguration.class))
            .withUserConfiguration(Integrations.class)
            .withPropertyValues(
                    "dataprism.security.jwt.issuer=https://issuer.example", "dataprism.security.jwt.audience=mcp",
                    "dataprism.security.jwt.jwk-set-uri=https://issuer.example/jwks",
                    "dataprism.security.caller-claims.principal=sub", "dataprism.security.caller-claims.roles=roles",
                    "dataprism.security.caller-claims.investigation=case_id",
                    // A real deployment supplies a CaseAuthorizer bean and lists its allowed clients; this one trusts the token.
                    "dataprism.security-policy.case-authorisation=token-trusted",
                    "dataprism.security-policy.client-allowlist=unrestricted",
                    "dataprism.security-policy.purposes[0]=investigation",
                    "dataprism.security-policy.roles.investigator[0]=GET_ENTITY_CONTEXT",
                    "dataprism.privacy.profile=DEFAULT", "dataprism.privacy.scope-lifetime=8h",
                    "dataprism.privacy.hmac-key.key-id=v1",
                    "dataprism.privacy.hmac-key.environment-variable=DATAPRISM_HMAC_KEY_REF",
                    "dataprism.audit.sink=approved-sink", "dataprism.audit.writer-id=test",
                    "dataprism.metrics.sink=micrometer", "dataprism.hazelcast.topology=single-node",
                    "dataprism.sources.customer.base-url=https://customer.example",
                    "dataprism.sources.customer.timeout=2s");

    @Test
    void an_email_shaped_non_sensitive_value_is_refused_by_the_spring_built_orchestrator() {
        context.run(result -> {
            assertThat(result).hasNotFailed();
            assertThat(result.getBeansOfType(SensitivePatternValidator.class)).hasSize(1);
            ContextOrchestrator orchestrator = result.getBean(ContextOrchestrator.class);

            assertThatThrownBy(() -> orchestrator.buildContext(ContextRequest.of("CUSTOMER", "CUST-1"),
                    privacyContext(), new InvestigationContext("investigator-1", "client-1", "CASE-1", Set.of())))
                    .isInstanceOfSatisfying(PrivacyRefusedException.class, e -> {
                        assertThat(e.code()).isEqualTo("VALIDATION_FAILED");
                        assertThat(e.getMessage()).contains("SENSITIVE_PATTERN");
                    });
        });
    }

    @Test
    void application_recognisers_are_collected_and_a_recognition_refuses() {
        context.withBean("sentinel", FreeTextRecogniser.class,
                () -> value -> value.contains("casework.sample")
                        ? Set.of(DataClassification.PII) : Set.of()).run(result -> {
            assertThat(result).hasNotFailed();
            assertThat(result.getBeansOfType(SensitivePatternValidator.class)).hasSize(1);
            assertThatThrownBy(() -> buildContext(result.getBean(ContextOrchestrator.class)))
                    .isInstanceOfSatisfying(PrivacyRefusedException.class,
                            e -> assertThat(e.getMessage()).contains("FREE_TEXT_RECOGNISED"));
        });
    }

    @Test
    void an_unrecognised_inert_value_passes_with_a_recogniser_present() {
        context.withPropertyValues("test.label=plain inert label")
                .withBean("sentinel", FreeTextRecogniser.class,
                        () -> value -> value.contains("casework.sample")
                                ? Set.of(DataClassification.PII) : Set.of())
                .run(result -> {
                    assertThat(result).hasNotFailed();
                    buildContext(result.getBean(ContextOrchestrator.class));
                });
    }

    @Test
    void a_failing_recogniser_refuses_without_exposing_its_exception() {
        context.withBean("boom", FreeTextRecogniser.class, () -> value -> {
            throw new IllegalStateException("SENTINEL-EXC-MESSAGE");
        }).run(result -> assertThatThrownBy(() -> buildContext(result.getBean(ContextOrchestrator.class)))
                .isInstanceOfSatisfying(PrivacyRefusedException.class, e ->
                        assertThat(e.getMessage()).contains("FREE_TEXT_RECOGNISER_FAILED")
                                .doesNotContain("SENTINEL-EXC-MESSAGE").doesNotContain("IllegalState")));
    }

    private static void buildContext(ContextOrchestrator orchestrator) {
        orchestrator.buildContext(ContextRequest.of("CUSTOMER", "CUST-1"), privacyContext(),
                new InvestigationContext("investigator-1", "client-1", "CASE-1", Set.of()));
    }

    private static PrivacyContext privacyContext() {
        return new PrivacyContext("SCOPE-1", PrivacyScopeType.INVESTIGATION, "DEFAULT", "investigation",
                Instant.parse("2030-01-01T00:00:00Z"), PseudonymisationVersion.HMAC_SHA256_V1.withKey("v1")
                        .withVocabulary(VocabularyRegistry.withBuiltIns().resolve("und").id()));
    }

    @Configuration(proxyBeanMethods = false)
    static class Integrations {
        @Bean
        DataSourceAdapter<PlainDto> customerAdapter(org.springframework.core.env.Environment env) {
            // The label defaults to the email-shaped value 209's tests refuse; a test overrides it with an inert one.
            String label = env.getProperty("test.label", "casework.sample@mail.invalid");
            return new DataSourceAdapter<>() {
                public String sourceName() { return "customer"; }
                public Class<PlainDto> responseType() { return PlainDto.class; }
                public PlainDto fetch(DataRequest request) {
                    return new PlainDto("CUST-1", label);
                }
            };
        }
        @Bean IdentityResolver identities() { return new PassThroughIdentityResolver(); }
        @Bean HmacKeyReferenceResolver keys() { return (id, reference) -> (reference + ":" + id + ":resolved-key-material").getBytes(); }
        @Bean AuditSink audit() { return event -> { }; }
        @Bean PrivacyMetrics metrics() { return PrivacyMetrics.none(); }
        @Bean McpTransportContextExtractor<HttpServletRequest> callerExtractor() { return request -> McpTransportContext.EMPTY; }
    }
}

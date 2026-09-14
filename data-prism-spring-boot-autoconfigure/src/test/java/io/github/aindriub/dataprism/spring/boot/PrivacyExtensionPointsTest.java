package io.github.aindriub.dataprism.spring.boot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.aindriub.dataprism.annotations.PrivacyAction;
import io.github.aindriub.dataprism.annotations.PrivacyNamespace;
import io.github.aindriub.dataprism.audit.AuditSink;
import io.github.aindriub.dataprism.core.DataRequest;
import io.github.aindriub.dataprism.core.DataSourceAdapter;
import io.github.aindriub.dataprism.core.IdentityResolver;
import io.github.aindriub.dataprism.core.PassThroughIdentityResolver;
import io.github.aindriub.dataprism.core.PrivacyContext;
import io.github.aindriub.dataprism.core.PrivacyMetrics;
import io.github.aindriub.dataprism.core.PrivacyScopeType;
import io.github.aindriub.dataprism.core.PseudonymisationVersion;
import io.github.aindriub.dataprism.core.policy.EffectivePrivacyPolicy;
import io.github.aindriub.dataprism.core.policy.PrivacyPolicyResolver;
import io.github.aindriub.dataprism.orchestration.ContextOrchestrator;
import io.github.aindriub.dataprism.validation.LlmResponseValidator;
import io.github.aindriub.dataprism.validation.RawValueLeakValidator;
import io.github.aindriub.dataprism.validation.ValidationResult;
import io.github.aindriub.dataprism.validation.Violation;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Behavioural proof that {@link PrivacyExtensionPoints}' classification
 * matches reality for the two beans that are not simply {@code @Primary}:
 * an application {@link PrivacyPolicyResolver} is refused outright, and an
 * application {@link LlmResponseValidator} can only add to the built-in leak
 * check, never replace it.
 *
 * <p>Non-vacuity for the resolver refusal (task 22, acceptance item 3) was
 * checked by hand rather than shipped as a test: with the guard in
 * {@code DataPrismAutoConfiguration#dataPrismPrivacyPolicyResolverPreflight}
 * removed, {@link #application_privacy_policy_resolver_cannot_override_the_profile_backed_resolver()}
 * fails — the context in {@link IntegrationsWithPassThroughEverythingResolver}
 * boots successfully and the application's {@code PASS_THROUGH}-for-everything
 * resolver becomes the one the scrubber uses, so a sensitive value would reach
 * the response unscrubbed. With the guard restored, the same context refuses
 * startup with {@code FORBIDDEN_PRIVACY_OVERRIDE} before any bean using the
 * resolver is even created.
 */
class PrivacyExtensionPointsTest {

    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(DataPrismAutoConfiguration.class))
            .withPropertyValues(valid());

    @Test
    void application_privacy_policy_resolver_cannot_override_the_profile_backed_resolver() {
        context.withUserConfiguration(IntegrationsWithPassThroughEverythingResolver.class).run(result -> {
            assertThat(result).hasFailed();
            assertThat(rootMessage(result.getStartupFailure())).contains("FORBIDDEN_PRIVACY_OVERRIDE");
        });
    }

    @Test
    void application_llm_response_validator_is_additive_and_the_built_in_leak_check_still_runs() {
        context.withUserConfiguration(IntegrationsWithAdditionalValidator.class).run(result -> {
            assertThat(result).hasNotFailed();

            ContextOrchestrator orchestrator = result.getBean(ContextOrchestrator.class);
            @SuppressWarnings("unchecked")
            List<LlmResponseValidator> validators =
                    (List<LlmResponseValidator>) ReflectionTestUtils.getField(orchestrator, "validators");
            assertThat(validators).isNotNull();
            assertThat(validators).hasAtLeastOneElementOfType(RawValueLeakValidator.class);
            assertThat(validators).contains(result.getBean("extraValidator", LlmResponseValidator.class));

            // The wired list is exactly what the orchestrator would evaluate:
            // aggregate every validator's result, the same as buildContext does,
            // and confirm a raw source value still fails it.
            ObjectNode response = new ObjectMapper().createObjectNode();
            response.put("fullName", "Patrick Murphy");
            PrivacyContext privacyContext = new PrivacyContext("SCOPE-1", PrivacyScopeType.INVESTIGATION,
                    "DEFAULT", "test", Instant.parse("2030-01-01T00:00:00Z"), PseudonymisationVersion.HMAC_SHA256_V1);

            List<Violation> violations = new ArrayList<>();
            for (LlmResponseValidator validator : validators) {
                violations.addAll(validator.validate(response, Set.of("Patrick Murphy"), Set.of(), privacyContext)
                        .violations());
            }
            assertThat(violations).as("a raw source value must still fail the leak check").isNotEmpty();
        });
    }

    private static String rootMessage(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage();
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
                "dataprism.metrics.sink=micrometer",
                "dataprism.sources.customer.base-url=https://customer.example",
                "dataprism.sources.customer.timeout=2s"};
    }

    @Configuration(proxyBeanMethods = false)
    static class ReviewedIntegrations {
        @Bean
        DataSourceAdapter<String> customerAdapter() {
            return new DataSourceAdapter<>() {
                public String sourceName() { return "customer"; }
                public Class<String> responseType() { return String.class; }
                public String fetch(DataRequest request) { return null; }
            };
        }
        @Bean IdentityResolver identities() { return new PassThroughIdentityResolver(); }
        @Bean HmacKeyReferenceResolver keys() { return (id, reference) -> (reference + ":" + id + ":resolved-key-material").getBytes(); }
        @Bean AuditSink audit() { return event -> { }; }
        @Bean PrivacyMetrics metrics() { return PrivacyMetrics.none(); }
    }

    @Configuration(proxyBeanMethods = false)
    static class IntegrationsWithPassThroughEverythingResolver extends ReviewedIntegrations {
        @Bean
        PrivacyPolicyResolver passThroughEverything() {
            return (field, ctx) -> new EffectivePrivacyPolicy(PrivacyAction.PASS_THROUGH, PrivacyNamespace.NONE,
                    true, "PASS_THROUGH_UNSAFE", EffectivePrivacyPolicy.Decided.PROFILE_RULE);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class IntegrationsWithAdditionalValidator extends ReviewedIntegrations {
        @Bean
        LlmResponseValidator extraValidator() {
            return (response, prohibited, emitted, ctx) -> ValidationResult.ok();
        }
    }
}

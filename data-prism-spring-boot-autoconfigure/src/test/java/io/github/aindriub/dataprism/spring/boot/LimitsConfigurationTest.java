package io.github.aindriub.dataprism.spring.boot;

import io.github.aindriub.dataprism.audit.AuditSink;
import io.github.aindriub.dataprism.core.limits.RequestLimits;
import io.github.aindriub.dataprism.core.metrics.PrivacyMetrics;
import io.github.aindriub.dataprism.core.spi.DataRequest;
import io.github.aindriub.dataprism.core.spi.DataSourceAdapter;
import io.github.aindriub.dataprism.core.spi.IdentityResolver;
import io.github.aindriub.dataprism.core.spi.PassThroughIdentityResolver;
import io.github.aindriub.dataprism.orchestration.ContextOrchestrator;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpTransportContextExtractor;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/** {@code dataprism.limits.*} binds all six {@link RequestLimits} fields, and refuses a value that is not usable. */
class LimitsConfigurationTest {

    private final WebApplicationContextRunner context = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(DataPrismAutoConfiguration.class))
            .withUserConfiguration(ReviewedIntegrations.class)
            .withPropertyValues(valid());

    @Test
    void with_nothing_set_the_bean_equals_the_defaults() {
        context.run(result -> {
            assertThat(result).hasNotFailed();
            assertThat(result.getBean("dataPrismRequestLimits", RequestLimits.class))
                    .isEqualTo(RequestLimits.DEFAULT);
            assertThat(orchestratorLimits(result.getBean(ContextOrchestrator.class)))
                    .isEqualTo(RequestLimits.DEFAULT);
        });
    }

    @Test
    void max_sources_is_bound() {
        assertBound("dataprism.limits.max-sources=12", RequestLimits.DEFAULT.maxSources(), 12, "maxSources");
    }

    @Test
    void max_records_is_bound() {
        assertBound("dataprism.limits.max-records=77", RequestLimits.DEFAULT.maxRecords(), 77, "maxRecords");
    }

    @Test
    void max_response_size_is_bound() {
        assertBound("dataprism.limits.max-response-size=64KB", RequestLimits.DEFAULT.maxResponseBytes(),
                64 * 1024, "maxResponseBytes");
    }

    @Test
    void a_per_source_timeout_above_the_default_deadline_starts_with_the_deadline_raised_to_match() {
        context.withPropertyValues("dataprism.limits.per-source-timeout=15s").run(result -> {
            assertThat(result).hasNotFailed();
            RequestLimits limits = orchestratorLimits(result.getBean(ContextOrchestrator.class));
            assertThat(limits.perSourceTimeout()).isEqualTo(Duration.ofSeconds(15));
            assertThat(limits.requestTimeout()).isEqualTo(Duration.ofSeconds(15));
        });
    }

    @Test
    void per_source_timeout_is_bound() {
        context.withPropertyValues("dataprism.limits.per-source-timeout=7s").run(result -> {
            assertThat(result).hasNotFailed();
            assertThat(orchestratorLimits(result.getBean(ContextOrchestrator.class)).perSourceTimeout())
                    .isEqualTo(Duration.ofSeconds(7));
        });
    }

    @Test
    void max_concurrency_is_bound() {
        assertBound("dataprism.limits.max-concurrency=9", RequestLimits.DEFAULT.maxConcurrency(), 9,
                "maxConcurrency");
    }

    @Test
    void scope_read_budget_is_bound() {
        assertBound("dataprism.limits.scope-read-budget=3", RequestLimits.DEFAULT.scopeReadBudget(), 3,
                "scopeReadBudget");
    }

    @ParameterizedTest
    @CsvSource({
            "max-sources, 0", "max-sources, -7",
            "max-records, 0", "max-records, -7",
            "max-response-size, 0B", "max-response-size, -7B",
            "per-source-timeout, 0s", "per-source-timeout, -7s",
            "max-concurrency, 0", "max-concurrency, -7",
            "scope-read-budget, 0", "scope-read-budget, -7"})
    void a_non_positive_value_refuses_startup_naming_only_the_property(String key, String value) {
        context.withPropertyValues("dataprism.limits." + key + "=" + value).run(result -> {
            assertThat(result).hasFailed();
            DataPrismConfigurationException refusal = refusal(result.getStartupFailure());
            assertThat(refusal.code()).isEqualTo("INVALID_REQUEST_LIMITS");
            assertThat(refusal.getMessage()).contains("dataprism.limits." + key).doesNotContain("-7");
            for (String other : new String[] {"max-sources", "max-records", "max-response-size",
                    "per-source-timeout", "max-concurrency", "scope-read-budget"}) {
                if (!other.equals(key)) {
                    assertThat(refusal.getMessage()).doesNotContain(other);
                }
            }
        });
    }

    @Test
    void a_response_size_that_does_not_fit_in_an_int_refuses_startup() {
        context.withPropertyValues("dataprism.limits.max-response-size=3GB").run(result -> {
            assertThat(result).hasFailed();
            DataPrismConfigurationException refusal = refusal(result.getStartupFailure());
            assertThat(refusal.code()).isEqualTo("INVALID_REQUEST_LIMITS");
            assertThat(refusal.getMessage()).contains("dataprism.limits.max-response-size").doesNotContain("3GB");
        });
    }

    @Test
    void an_application_request_limits_bean_is_refused() {
        context.withUserConfiguration(CompetingLimits.class).run(result -> {
            assertThat(result).hasFailed();
            assertThat(refusal(result.getStartupFailure()).code()).isEqualTo("FORBIDDEN_PRIVACY_OVERRIDE");
        });
    }

    @Test
    void the_bean_is_classified_privacy_critical_with_a_competing_bean_refusal() {
        PrivacyExtensionPoints.BeanContract contract = PrivacyExtensionPoints.contractOf("dataPrismRequestLimits");
        assertThat(contract.classification()).isEqualTo(PrivacyExtensionPoints.Classification.PRIVACY_CRITICAL);
        assertThat(contract.guard()).isEqualTo(PrivacyExtensionPoints.Guard.COMPETING_BEAN_REFUSAL);
    }

    private void assertBound(String property, int defaultValue, int expected, String component) {
        context.withPropertyValues(property).run(result -> {
            assertThat(result).hasNotFailed();
            RequestLimits limits = orchestratorLimits(result.getBean(ContextOrchestrator.class));
            assertThat(limits).isEqualTo(result.getBean("dataPrismRequestLimits", RequestLimits.class));
            int actual = switch (component) {
                case "maxSources" -> limits.maxSources();
                case "maxRecords" -> limits.maxRecords();
                case "maxResponseBytes" -> limits.maxResponseBytes();
                case "maxConcurrency" -> limits.maxConcurrency();
                default -> limits.scopeReadBudget();
            };
            assertThat(actual).isEqualTo(expected).isNotEqualTo(defaultValue);
        });
    }

    private static RequestLimits orchestratorLimits(ContextOrchestrator orchestrator) {
        return (RequestLimits) ReflectionTestUtils.getField(orchestrator, "limits");
    }

    private static DataPrismConfigurationException refusal(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof DataPrismConfigurationException e) {
                return e;
            }
        }
        throw new AssertionError("no DataPrismConfigurationException in the cause chain", failure);
    }

    private static String[] valid() {
        return new String[] {
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
                "dataprism.metrics.sink=micrometer",
                "dataprism.hazelcast.topology=single-node",
                "dataprism.sources.customer.base-url=https://customer.example",
                "dataprism.sources.customer.timeout=2s"};
    }

    @Configuration(proxyBeanMethods = false)
    static class ReviewedIntegrations {
        @Bean
        DataSourceAdapter<TestPayload> customerAdapter() {
            return new DataSourceAdapter<>() {
                public String sourceName() { return "customer"; }
                public Class<TestPayload> responseType() { return TestPayload.class; }
                public TestPayload fetch(DataRequest request) { return null; }
            };
        }
        @Bean IdentityResolver identities() { return new PassThroughIdentityResolver(); }
        @Bean HmacKeyReferenceResolver keys() { return (id, reference) -> (reference + ":" + id + ":resolved-key-material").getBytes(); }
        @Bean AuditSink audit() { return event -> { }; }
        @Bean PrivacyMetrics metrics() { return PrivacyMetrics.none(); }
        @Bean McpTransportContextExtractor<HttpServletRequest> callerExtractor() { return request -> McpTransportContext.EMPTY; }
    }

    @Configuration(proxyBeanMethods = false)
    static class CompetingLimits {
        @Bean
        RequestLimits applicationLimits() {
            return RequestLimits.DEFAULT;
        }
    }
}

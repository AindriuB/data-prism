package io.github.aindriub.dataprism.spring.boot;

import io.github.aindriub.dataprism.audit.AuditSink;
import io.github.aindriub.dataprism.core.link.LinkCodec;
import io.github.aindriub.dataprism.core.link.LinkKeys;
import io.github.aindriub.dataprism.core.metrics.PrivacyMetrics;
import io.github.aindriub.dataprism.core.spi.DataRequest;
import io.github.aindriub.dataprism.core.spi.DataSourceAdapter;
import io.github.aindriub.dataprism.core.spi.IdentityResolver;
import io.github.aindriub.dataprism.core.spi.PassThroughIdentityResolver;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpTransportContextExtractor;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.charset.StandardCharsets;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The link key that page links need even while {@code dataprism.links.enabled} is false: bound as
 * {@code dataprism.links.key-id} and {@code dataprism.links.keys.<key-id>.environment-variable} or
 * {@code .provider-reference}. Every refusal names a property and never a reference or key byte.
 */
class LinkKeyConfigurationTest {

    private static final String HMAC_REFERENCE = "DATAPRISM_HMAC_KEY_REF";
    private static final String LEAK = "reference-that-must-not-appear";

    private final WebApplicationContextRunner context = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(DataPrismAutoConfiguration.class))
            .withUserConfiguration(ReviewedIntegrations.class)
            .withPropertyValues(valid());

    @Test
    void with_no_key_configured_there_is_no_link_codec() {
        context.run(result -> {
            assertThat(result).hasNotFailed();
            assertThat(result).doesNotHaveBean(LinkCodec.class);
            assertThat(result).doesNotHaveBean(LinkKeys.class);
        });
    }

    @Test
    void a_key_by_environment_variable_builds_the_keys_and_the_codec() {
        context.withPropertyValues("dataprism.links.key-id=lk1", "dataprism.links.keys.lk1.environment-variable=LINK_REF")
                .run(result -> {
                    assertThat(result).hasNotFailed();
                    assertThat(result.getBean("dataPrismLinkKeys", LinkKeys.class).currentKeyId()).isEqualTo("lk1");
                    assertThat(result).hasSingleBean(LinkCodec.class);
                });
    }

    @Test
    void a_key_by_provider_reference_is_read_through_the_provider_method_of_the_resolver() {
        context.withPropertyValues("dataprism.links.key-id=lk1", "dataprism.links.keys.lk1.provider-reference=vault:lk1")
                .run(result -> {
                    assertThat(result).hasNotFailed();
                    assertThat(result.getBean("dataPrismLinkKeys", LinkKeys.class).heldKeyIds()).containsExactly("lk1");
                });
    }

    @Test
    void a_retiring_key_is_held_alongside_the_current_one() {
        context.withPropertyValues("dataprism.links.key-id=lk2", "dataprism.links.keys.lk2.environment-variable=LINK_REF",
                "dataprism.links.keys.lk1.environment-variable=OLD_LINK_REF").run(result -> {
                    assertThat(result).hasNotFailed();
                    LinkKeys keys = result.getBean("dataPrismLinkKeys", LinkKeys.class);
                    assertThat(keys.currentKeyId()).isEqualTo("lk2");
                    assertThat(keys.heldKeyIds()).containsExactlyInAnyOrder("lk1", "lk2");
                });
    }

    @Test
    void a_current_key_id_with_no_entry_is_unresolved() {
        refused(context.withPropertyValues("dataprism.links.key-id=lk1"), "LINK_KEY_UNRESOLVED",
                "dataprism.links.keys");
    }

    @Test
    void an_entry_with_both_references_or_neither_is_unresolved() {
        refused(context.withPropertyValues("dataprism.links.key-id=lk1",
                "dataprism.links.keys.lk1.environment-variable=LINK_REF",
                "dataprism.links.keys.lk1.provider-reference=vault:lk1"), "LINK_KEY_UNRESOLVED", "dataprism.links.keys");
        refused(context.withPropertyValues("dataprism.links.key-id=lk1", "dataprism.links.keys.lk1.provider-reference="),
                "LINK_KEY_UNRESOLVED", "dataprism.links.keys");
    }

    @Test
    void a_reference_the_resolver_cannot_resolve_is_unresolved_and_the_message_names_no_reference() {
        refused(context.withPropertyValues("dataprism.links.key-id=lk1",
                "dataprism.links.keys.lk1.environment-variable=BROKEN_REF"), "LINK_KEY_UNRESOLVED",
                "dataprism.links.keys");
    }

    @Test
    void a_retiring_key_that_cannot_be_resolved_is_unresolved() {
        refused(context.withPropertyValues("dataprism.links.key-id=lk2",
                "dataprism.links.keys.lk2.environment-variable=LINK_REF",
                "dataprism.links.keys.lk1.environment-variable=BROKEN_REF"), "LINK_KEY_UNRESOLVED",
                "dataprism.links.keys");
    }

    @Test
    void material_under_32_bytes_is_weak() {
        refused(context.withPropertyValues("dataprism.links.key-id=lk1",
                "dataprism.links.keys.lk1.environment-variable=SHORT_REF"), "LINK_KEY_WEAK", "dataprism.links.keys");
    }

    @Test
    void material_of_exactly_32_bytes_is_accepted() {
        context.withPropertyValues("dataprism.links.key-id=lk1",
                "dataprism.links.keys.lk1.environment-variable=EXACT_REF")
                .run(result -> assertThat(result).hasNotFailed());
    }

    @Test
    void the_pseudonymisation_key_id_is_shared() {
        refused(context.withPropertyValues("dataprism.links.key-id=v1",
                "dataprism.links.keys.v1.environment-variable=LINK_REF"), "LINK_KEY_SHARED", "dataprism.links");
    }

    @Test
    void the_pseudonymisation_key_material_is_shared() {
        refused(context.withPropertyValues("dataprism.links.key-id=lk1",
                "dataprism.links.keys.lk1.environment-variable=" + HMAC_REFERENCE_ALIAS), "LINK_KEY_SHARED",
                "dataprism.links.keys");
    }

    @Test
    void a_retiring_key_with_the_pseudonymisation_key_material_is_shared() {
        refused(context.withPropertyValues("dataprism.links.key-id=lk2",
                "dataprism.links.keys.lk2.environment-variable=LINK_REF",
                "dataprism.links.keys.lk1.environment-variable=" + HMAC_REFERENCE_ALIAS), "LINK_KEY_SHARED",
                "dataprism.links.keys");
    }

    @Test
    void a_key_id_that_is_not_1_to_16_characters_of_the_allowed_set_is_refused() {
        refused(context.withPropertyValues("dataprism.links.key-id=abcdefghijklmnopq",
                "dataprism.links.keys.abcdefghijklmnopq.environment-variable=LINK_REF"), "INVALID_LINK_LIMIT",
                "dataprism.links");
    }

    @Test
    void the_keys_and_the_codec_are_privacy_critical_with_a_competing_bean_refusal() {
        for (String bean : new String[] {"dataPrismLinkKeys", "dataPrismLinkCodec"}) {
            PrivacyExtensionPoints.BeanContract contract = PrivacyExtensionPoints.contractOf(bean);
            assertThat(contract.classification()).as(bean)
                    .isEqualTo(PrivacyExtensionPoints.Classification.PRIVACY_CRITICAL);
            assertThat(contract.guard()).as(bean).isEqualTo(PrivacyExtensionPoints.Guard.COMPETING_BEAN_REFUSAL);
        }
    }

    /** Same bytes as the pseudonymisation key, by another reference: the resolver maps both to one array. */
    private static final String HMAC_REFERENCE_ALIAS = "SAME_AS_HMAC_REF";

    private static void refused(WebApplicationContextRunner runner, String code, String property) {
        runner.run(result -> {
            assertThat(result).hasFailed();
            DataPrismConfigurationException refusal = refusal(result.getStartupFailure());
            assertThat(refusal.code()).isEqualTo(code);
            assertThat(refusal.getMessage()).contains(property).doesNotContain("LINK_REF").doesNotContain(LEAK)
                    .doesNotContain("BROKEN_REF").doesNotContain("SHORT_REF").doesNotContain("SAME_AS_HMAC_REF")
                    .doesNotContain("vault:");
        });
    }

    private static DataPrismConfigurationException refusal(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof DataPrismConfigurationException e) {
                return e;
            }
        }
        throw new AssertionError("no DataPrismConfigurationException in the cause chain", failure);
    }

    private static byte[] derived(String text) {
        return (text + ":link-test-only-key-material-padding").getBytes(StandardCharsets.UTF_8);
    }

    /** Answers by reference; a reference it does not know yields distinct, long enough material. */
    static final class ReferenceResolver implements HmacKeyReferenceResolver {
        @Override
        public byte[] resolve(String keyId, String reference) {
            return switch (reference) {
                case "BROKEN_REF" -> throw new IllegalStateException(LEAK + " " + reference);
                case "SHORT_REF" -> "too-short".getBytes(StandardCharsets.UTF_8);
                case "EXACT_REF" -> "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);
                case "SAME_AS_HMAC_REF" -> derived(HMAC_REFERENCE + ":v1");
                case HMAC_REFERENCE -> derived(HMAC_REFERENCE + ":v1");
                default -> derived(reference + ":" + keyId);
            };
        }

        @Override
        public byte[] resolveProviderReference(String keyId, String reference) {
            return derived("provider:" + reference + ":" + keyId);
        }
    }

    private static String[] valid() {
        return new String[] {
                "dataprism.security.jwt.issuer=https://issuer.example", "dataprism.security.jwt.audience=mcp",
                "dataprism.security.jwt.jwk-set-uri=https://issuer.example/jwks",
                "dataprism.security.caller-claims.principal=sub", "dataprism.security.caller-claims.roles=roles",
                "dataprism.security.caller-claims.investigation=case_id",
                "dataprism.security-policy.case-authorisation=token-trusted",
                "dataprism.security-policy.client-allowlist=unrestricted",
                "dataprism.security-policy.purposes[0]=investigation",
                "dataprism.security-policy.roles.investigator[0]=GET_ENTITY_CONTEXT",
                "dataprism.privacy.profile=DEFAULT", "dataprism.privacy.scope-lifetime=8h",
                "dataprism.privacy.hmac-key.key-id=v1",
                "dataprism.privacy.hmac-key.environment-variable=" + HMAC_REFERENCE,
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
        @Bean HmacKeyReferenceResolver keys() { return new ReferenceResolver(); }
        @Bean AuditSink audit() { return event -> { }; }
        @Bean PrivacyMetrics metrics() { return PrivacyMetrics.none(); }
        @Bean McpTransportContextExtractor<HttpServletRequest> callerExtractor() { return request -> McpTransportContext.EMPTY; }
    }
}

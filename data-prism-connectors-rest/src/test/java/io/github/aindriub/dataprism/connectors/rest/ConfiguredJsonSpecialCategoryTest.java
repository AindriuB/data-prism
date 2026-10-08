package io.github.aindriub.dataprism.connectors.rest;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import io.github.aindriub.dataprism.annotations.DataClassification;
import io.github.aindriub.dataprism.annotations.PrivacyAction;
import io.github.aindriub.dataprism.annotations.PrivacyNamespace;
import io.github.aindriub.dataprism.core.model.FieldMetadata;
import io.github.aindriub.dataprism.core.model.PrivacyContext;
import io.github.aindriub.dataprism.core.model.PrivacyScopeType;
import io.github.aindriub.dataprism.core.model.PseudonymisationVersion;
import io.github.aindriub.dataprism.core.model.ScrubResult;
import io.github.aindriub.dataprism.core.spi.ScrubbingEngine;
import io.github.aindriub.dataprism.core.policy.PrivacyProfiles;
import io.github.aindriub.dataprism.core.policy.ProfilePrivacyPolicyResolver;
import io.github.aindriub.dataprism.pseudonymisation.HmacSyntheticGenerator;
import io.github.aindriub.dataprism.pseudonymisation.HmacValueTokenSource;
import io.github.aindriub.dataprism.pseudonymisation.StaticSecretKeyProvider;
import io.github.aindriub.dataprism.pseudonymisation.vocabulary.Vocabulary;
import io.github.aindriub.dataprism.pseudonymisation.vocabulary.VocabularyRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** A catalogue author cannot expose a GDPR Art. 9 special category by declaring PASS_THROUGH. */
class ConfiguredJsonSpecialCategoryTest {

    private static final StaticSecretKeyProvider KEYS =
            StaticSecretKeyProvider.of("task-94-special-category-test-key-not-for-any-real-data-32b");
    private static final String SYNTHETIC_TEMPLATE = "SYNTHETIC-TEMPLATE-ZZ-94-QX";

    @Test
    @DisplayName("a BIOMETRIC field declared PASS_THROUGH is removed and its value is absent")
    void biometricPassThroughIsRemoved() throws IOException {
        RestSource transport = new RestSource("customer-biometric",
                URI.create("https://customer.example"), "/v1/customers/{subject}", Duration.ofSeconds(2));
        Map<String, FieldMetadata> fields = Map.of(
                "id", new FieldMetadata("id", true, FieldMetadata.SELF, List.of(),
                        PrivacyNamespace.NONE, null, "", null, String.class, null),
                "template", new FieldMetadata("template", false, null, List.of(DataClassification.BIOMETRIC),
                        PrivacyNamespace.NONE, PrivacyAction.PASS_THROUGH, "", null, String.class, null));
        ConfiguredJsonSource source = new ConfiguredJsonSource(transport, "customer-v1", "id", fields, Map.of());

        Vocabulary vocabulary = VocabularyRegistry.withBuiltIns().resolve("und");
        ProfilePrivacyPolicyResolver policy;
        try (var in = getClass().getResourceAsStream("/privacy-profiles-default.yaml")) {
            policy = new ProfilePrivacyPolicyResolver(PrivacyProfiles.fromYaml(in));
        }
        ScrubbingEngine unreachable = (s, c) -> {
            throw new AssertionError("java-first delegate should never be reached");
        };
        var engine = new ConfiguredJsonScrubbingEngine(unreachable, Map.of(transport.name(), source), policy,
                new HmacSyntheticGenerator(KEYS, vocabulary), new HmacValueTokenSource(KEYS));
        PrivacyContext context = new PrivacyContext("SCOPE-1", PrivacyScopeType.INVESTIGATION, "DEFAULT",
                "investigation", Instant.parse("2030-01-01T00:00:00Z"),
                PseudonymisationVersion.HMAC_SHA256_V1.withKey("v1").withVocabulary(vocabulary.id()));

        ObjectNode body = (ObjectNode) JsonMapper.builder().build().readTree(
                "{\"id\":\"CUST-1\",\"template\":\"" + SYNTHETIC_TEMPLATE + "\"}");
        ScrubResult result = engine.scrub(new ConfiguredJsonPayload("customer-biometric", body), context);

        assertThat(result.tree().has("template")).isFalse();
        assertThat(result.tree().toString()).doesNotContain(SYNTHETIC_TEMPLATE);
    }
}

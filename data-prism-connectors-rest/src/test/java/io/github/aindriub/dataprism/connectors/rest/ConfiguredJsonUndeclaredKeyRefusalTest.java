package io.github.aindriub.dataprism.connectors.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.aindriub.dataprism.annotations.DataClassification;
import io.github.aindriub.dataprism.annotations.PrivacyAction;
import io.github.aindriub.dataprism.annotations.PrivacyNamespace;
import io.github.aindriub.dataprism.core.FieldMetadata;
import io.github.aindriub.dataprism.core.PrivacyContext;
import io.github.aindriub.dataprism.core.PrivacyRefusedException;
import io.github.aindriub.dataprism.core.PrivacyScopeType;
import io.github.aindriub.dataprism.core.PseudonymisationVersion;
import io.github.aindriub.dataprism.core.ScrubbingEngine;
import io.github.aindriub.dataprism.core.policy.PrivacyProfile;
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

/** A payload key is never written into a configured-source refusal path or message (task 118). */
class ConfiguredJsonUndeclaredKeyRefusalTest {

    /** Synthetic; reserved domain. */
    private static final String KEY = "zzUndeclaredKeyQx7@example.com";
    private static final String TOKEN = "zzUndeclaredKeyQx7";
    private static final StaticSecretKeyProvider KEYS =
            StaticSecretKeyProvider.of("task-118-undeclared-key-test-key-not-for-any-real-data");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Vocabulary VOCABULARY = VocabularyRegistry.withBuiltIns().resolve("und");
    private static final ScrubbingEngine UNREACHABLE_JAVA_FIRST = (source, context) -> {
        throw new AssertionError("java-first delegate should never be reached in this test");
    };

    private static ConfiguredJsonSource source() {
        RestSource transport = new RestSource("customer-with-address",
                URI.create("https://customer.example"), "/v1/customers/{subject}", Duration.ofSeconds(2));
        Map<String, FieldMetadata> addressCatalogue = Map.of(
                "line1", new FieldMetadata("line1", false, null, List.of(), PrivacyNamespace.NONE,
                        null, "", "street address line, reviewed as inert structure", String.class, null));
        Class<?> addressToken = ConfiguredJsonNestedCatalogueTokens.mint("customer-with-address", 0);
        Map<String, FieldMetadata> fields = Map.of(
                "id", new FieldMetadata("id", true, FieldMetadata.SELF, List.of(),
                        PrivacyNamespace.NONE, null, "", null, String.class, null),
                "ssn", new FieldMetadata("ssn", false, null, List.of(DataClassification.PII),
                        PrivacyNamespace.PERSON_IDENTITY, PrivacyAction.SYNTHESIZE, "", null, String.class, null),
                "address", new FieldMetadata("address", false, null, List.of(), PrivacyNamespace.NONE,
                        null, "", ConfiguredJsonSources.NESTED_FIELD_REASON_PREFIX + "address",
                        addressToken, addressToken));
        return new ConfiguredJsonSource(transport, "customer-v1", "id", fields,
                Map.of("address", addressCatalogue));
    }

    private static ConfiguredJsonScrubbingEngine engine(PrivacyProfile.UnclassifiedBehaviour behaviour) {
        var profile = new PrivacyProfile("DEFAULT", behaviour, Map.of(DataClassification.PII,
                PrivacyProfile.ClassificationRule.of(PrivacyAction.SYNTHESIZE)));
        ConfiguredJsonSource source = source();
        return new ConfiguredJsonScrubbingEngine(UNREACHABLE_JAVA_FIRST,
                Map.of(source.transport().name(), source),
                new ProfilePrivacyPolicyResolver(Map.of("DEFAULT", profile)),
                new HmacSyntheticGenerator(KEYS, VOCABULARY), new HmacValueTokenSource(KEYS));
    }

    private static PrivacyContext context() {
        return new PrivacyContext("SCOPE-1", PrivacyScopeType.INVESTIGATION, "DEFAULT", "investigation",
                Instant.parse("2030-01-01T00:00:00Z"),
                PseudonymisationVersion.HMAC_SHA256_V1.withKey("v1").withVocabulary(VOCABULARY.id()));
    }

    private static ConfiguredJsonPayload payload(String json) {
        try {
            return new ConfiguredJsonPayload("customer-with-address", (ObjectNode) JSON.readTree(json));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static PrivacyRefusedException refusal(PrivacyProfile.UnclassifiedBehaviour behaviour, String json) {
        Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(
                () -> engine(behaviour).scrub(payload(json), context()));
        assertThat(thrown).isInstanceOf(PrivacyRefusedException.class);
        return (PrivacyRefusedException) thrown;
    }

    @Test
    @DisplayName("UNKNOWN_FIELD from a configured source names the placeholder")
    void unknownField() {
        var refused = refusal(PrivacyProfile.UnclassifiedBehaviour.FAIL_REQUEST,
                "{\"id\":\"C-1\",\"ssn\":\"123-45-6789\",\"" + KEY + "\":\"x\"}");

        assertThat(refused.code()).isEqualTo("UNKNOWN_FIELD");
        assertThat(refused.path()).isEqualTo("$.<undeclared>");
        assertThat(refused.getMessage()).doesNotContain(TOKEN);
    }

    @Test
    @DisplayName("the raw-value leak check renders an undeclared key as the placeholder after the source name")
    void leakCheckPath() {
        // Under a pass-through profile the undeclared key survives into the tree, and
        // carries a value the catalogue marks sensitive: the leak check fires on it.
        var refused = refusal(PrivacyProfile.UnclassifiedBehaviour.PASS_THROUGH_UNSAFE,
                "{\"id\":\"C-1\",\"ssn\":\"123-45-6789\",\"" + KEY + "\":\"123-45-6789\"}");

        assertThat(refused.code()).isEqualTo("RAW_SOURCE_VALUE");
        assertThat(refused.path()).isEqualTo("customer-with-address$.<undeclared>");
        assertThat(refused.getMessage()).doesNotContain(TOKEN);
    }

    @Test
    @DisplayName("the nested-leaf shape guard's paths never include a payload key")
    void shapeGuardPaths() {
        var leaf = refusal(PrivacyProfile.UnclassifiedBehaviour.FAIL_REQUEST,
                "{\"id\":\"C-1\",\"address\":{\"line1\":{\"x\":1},\"" + KEY + "\":\"v\"},\"" + KEY + "\":\"v\"}");
        assertThat(leaf.code()).isEqualTo(ConfiguredJsonNestedLeafShapeGuard.CODE);
        assertThat(leaf.path()).isEqualTo("customer-with-address$.address.line1");
        assertThat(leaf.getMessage()).doesNotContain(TOKEN);

        var structure = refusal(PrivacyProfile.UnclassifiedBehaviour.FAIL_REQUEST,
                "{\"id\":\"C-1\",\"address\":[\"scalar\"],\"" + KEY + "\":\"v\"}");
        assertThat(structure.code()).isEqualTo(ConfiguredJsonNestedLeafShapeGuard.STRUCTURE_CODE);
        assertThat(structure.path()).isEqualTo("customer-with-address$.address[0]");
        assertThat(structure.getMessage()).doesNotContain(TOKEN);
    }
}

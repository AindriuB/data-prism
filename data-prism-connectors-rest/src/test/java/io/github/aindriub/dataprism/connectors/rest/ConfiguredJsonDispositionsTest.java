package io.github.aindriub.dataprism.connectors.rest;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import io.github.aindriub.dataprism.annotations.DataClassification;
import io.github.aindriub.dataprism.annotations.PrivacyAction;
import io.github.aindriub.dataprism.annotations.PrivacyNamespace;
import io.github.aindriub.dataprism.core.model.FieldMetadata;
import io.github.aindriub.dataprism.core.spi.FieldMetadataResolver;
import io.github.aindriub.dataprism.core.model.PrivacyContext;
import io.github.aindriub.dataprism.core.refusal.PrivacyRefusedException;
import io.github.aindriub.dataprism.core.model.PrivacyScopeType;
import io.github.aindriub.dataprism.core.model.PseudonymisationVersion;
import io.github.aindriub.dataprism.core.model.ScrubResult;
import io.github.aindriub.dataprism.core.spi.ScrubbingEngine;
import io.github.aindriub.dataprism.core.engine.SourceValues;
import io.github.aindriub.dataprism.core.spi.SyntheticValueSource;
import io.github.aindriub.dataprism.core.spi.ValueTokenSource;
import io.github.aindriub.dataprism.core.policy.PrivacyPolicyResolver;
import io.github.aindriub.dataprism.core.policy.PrivacyProfiles;
import io.github.aindriub.dataprism.core.policy.ProfilePrivacyPolicyResolver;
import io.github.aindriub.dataprism.pseudonymisation.HmacSyntheticGenerator;
import io.github.aindriub.dataprism.pseudonymisation.HmacValueTokenSource;
import io.github.aindriub.dataprism.pseudonymisation.StaticSecretKeyProvider;
import io.github.aindriub.dataprism.pseudonymisation.vocabulary.Vocabulary;
import io.github.aindriub.dataprism.pseudonymisation.vocabulary.VocabularyRegistry;
import io.github.aindriub.dataprism.validation.RawValueLeakValidator;
import io.github.aindriub.dataprism.validation.ValidationResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Dispositions pass through the configured-JSON engine from the engine it delegates to (task 93). */
class ConfiguredJsonDispositionsTest {

    private static final StaticSecretKeyProvider KEYS =
            StaticSecretKeyProvider.of("task-93-dispositions-test-key-not-for-any-real-data-32bytes");
    private static final ObjectMapper JSON = JsonMapper.builder().build();

    private static PrivacyPolicyResolver defaultProfilePolicy() {
        try (var input = ConfiguredJsonDispositionsTest.class
                .getResourceAsStream("/privacy-profiles-default.yaml")) {
            return new ProfilePrivacyPolicyResolver(PrivacyProfiles.fromYaml(input));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static PrivacyContext context(Vocabulary vocabulary) {
        return new PrivacyContext("SCOPE-1", PrivacyScopeType.INVESTIGATION, "DEFAULT", "investigation",
                Instant.parse("2030-01-01T00:00:00Z"),
                PseudonymisationVersion.HMAC_SHA256_V1.withKey("v1").withVocabulary(vocabulary.id()));
    }

    /** Never called: every payload this test builds is a {@link ConfiguredJsonPayload}. */
    private static final ScrubbingEngine UNREACHABLE_JAVA_FIRST = (source, context) -> {
        throw new AssertionError("java-first delegate should never be reached in this test");
    };

    private static ConfiguredJsonSource sourceWithAddress() {
        RestSource transport = new RestSource("customer-with-address",
                URI.create("https://customer.example"), "/v1/customers/{subject}", Duration.ofSeconds(2));

        Map<String, FieldMetadata> addressCatalogue = Map.of(
                "line1", new FieldMetadata("line1", false, null, List.of(), PrivacyNamespace.NONE,
                        null, "", "street address line, reviewed as inert structure", String.class, null),
                "ssn", new FieldMetadata("ssn", false, null, List.of(DataClassification.PII),
                        PrivacyNamespace.PERSON_IDENTITY, PrivacyAction.SYNTHESIZE, "", null, String.class, null));

        Class<?> addressToken = ConfiguredJsonNestedCatalogueTokens.mint("customer-with-address", 0);
        Map<String, FieldMetadata> fields = Map.of(
                "id", new FieldMetadata("id", true, FieldMetadata.SELF, List.of(),
                        PrivacyNamespace.NONE, null, "", null, String.class, null),
                "address", new FieldMetadata("address", false, null, List.of(), PrivacyNamespace.NONE,
                        null, "", ConfiguredJsonSources.NESTED_FIELD_REASON_PREFIX + "address",
                        addressToken, addressToken));

        return new ConfiguredJsonSource(transport, "customer-v1", "id", fields,
                Map.of("address", addressCatalogue));
    }

    private static ConfiguredJsonScrubbingEngine engineFor(ConfiguredJsonSource source, Vocabulary vocabulary) {
        PrivacyPolicyResolver policy = defaultProfilePolicy();
        SyntheticValueSource synthetics = new HmacSyntheticGenerator(KEYS, vocabulary);
        ValueTokenSource tokens = new HmacValueTokenSource(KEYS);
        return new ConfiguredJsonScrubbingEngine(UNREACHABLE_JAVA_FIRST,
                Map.of(source.transport().name(), source), policy, synthetics, tokens);
    }

    private static ObjectNode body(String json) {
        try {
            return (ObjectNode) JSON.readTree(json);
        } catch (JacksonException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("a one-level nested catalogue reports dispositions under declared names")
    void nestedCatalogueDispositions() {
        ConfiguredJsonSource source = sourceWithAddress();
        Vocabulary vocabulary = VocabularyRegistry.withBuiltIns().resolve("und");
        ScrubResult result = engineFor(source, vocabulary).scrub(new ConfiguredJsonPayload(
                "customer-with-address", body("{\"id\":\"CUST-1\",\"address\":{\"line1\":\"123 Main St\",\"ssn\":\"123-45-6789\"}}")),
                context(vocabulary));

        assertThat(result.dispositions()).containsOnlyKeys("/id", "/address", "/address/line1", "/address/ssn");
        assertThat(result.dispositions()).containsEntry("/address/line1", PrivacyAction.PASS_THROUGH)
                .containsEntry("/address/ssn", PrivacyAction.SYNTHESIZE);
        assertThat(result.dispositions().toString()).doesNotContain("123-45-6789", "123 Main St");
    }

    @Test
    @DisplayName("the Java-first path returns the delegate's dispositions")
    void javaFirstDispositions() {
        ScrubResult delegated = new ScrubResult(JSON.createObjectNode(), Set.of(),
                Map.of("/x", PrivacyAction.REDACT));
        ConfiguredJsonSource source = sourceWithAddress();
        Vocabulary vocabulary = VocabularyRegistry.withBuiltIns().resolve("und");
        var engine = new ConfiguredJsonScrubbingEngine((o, c) -> delegated,
                Map.of(source.transport().name(), source), defaultProfilePolicy(),
                new HmacSyntheticGenerator(KEYS, vocabulary), new HmacValueTokenSource(KEYS));

        assertThat(engine.scrub(new Object(), context(vocabulary)).dispositions())
                .containsExactlyEntriesOf(Map.of("/x", PrivacyAction.REDACT));
    }
}

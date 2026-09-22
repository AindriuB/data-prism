package io.github.aindriub.dataprism.connectors.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.aindriub.dataprism.annotations.DataClassification;
import io.github.aindriub.dataprism.annotations.PrivacyAction;
import io.github.aindriub.dataprism.annotations.PrivacyNamespace;
import io.github.aindriub.dataprism.core.FieldMetadata;
import io.github.aindriub.dataprism.core.FieldMetadataResolver;
import io.github.aindriub.dataprism.core.PrivacyContext;
import io.github.aindriub.dataprism.core.PrivacyRefusedException;
import io.github.aindriub.dataprism.core.PrivacyScopeType;
import io.github.aindriub.dataprism.core.PseudonymisationVersion;
import io.github.aindriub.dataprism.core.ScrubResult;
import io.github.aindriub.dataprism.core.ScrubbingEngine;
import io.github.aindriub.dataprism.core.SourceValues;
import io.github.aindriub.dataprism.core.SyntheticValueSource;
import io.github.aindriub.dataprism.core.ValueTokenSource;
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

/**
 * Exercises the one level of named nested catalogues (docs/plan/tasks/60-*.md)
 * against the real, unmodified core engine and validators: {@link
 * io.github.aindriub.dataprism.core.JsonTreeScrubbingEngine}, {@link
 * SourceValues} and {@link RawValueLeakValidator} are never told about "nested
 * catalogues" as a concept -- they only ever see a {@code Class} token, exactly
 * as they do for a Java-first nested model. This file proves that wiring
 * actually descends, actually scrubs, and actually closes the leak check the
 * same way it does at the root.
 */
class ConfiguredJsonNestedCatalogueScrubbingTest {

    private static final StaticSecretKeyProvider KEYS =
            StaticSecretKeyProvider.of("task-60-nested-catalogue-test-key-not-for-any-real-data-32b");
    private static final ObjectMapper JSON = new ObjectMapper();

    private static PrivacyPolicyResolver defaultProfilePolicy() {
        try (var input = ConfiguredJsonNestedCatalogueScrubbingTest.class
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
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("a nested object is scrubbed field by field under its own catalogue")
    void nestedObjectScrubbedFieldByField() {
        ConfiguredJsonSource source = sourceWithAddress();
        Vocabulary vocabulary = VocabularyRegistry.withBuiltIns().resolve("und");
        ConfiguredJsonScrubbingEngine engine = engineFor(source, vocabulary);

        ObjectNode body = body("""
                {"id":"CUST-1","address":{"line1":"123 Main St","ssn":"123-45-6789"}}
                """);
        ScrubResult result = engine.scrub(new ConfiguredJsonPayload("customer-with-address", body), context(vocabulary));

        // Same treatment a top-level PII/SYNTHESIZE field would get: not the raw
        // value, not passed through, not redacted -- synthesised.
        String synthesised = result.tree().at("/address/ssn").asText();
        assertThat(synthesised).isNotBlank().isNotEqualTo("123-45-6789");

        // Non-sensitive passthrough, exactly as at the root.
        assertThat(result.tree().at("/address/line1").asText()).isEqualTo("123 Main St");

        // No raw value anywhere in the emitted tree.
        assertThat(result.tree().toString()).doesNotContain("123-45-6789");
    }

    @Test
    @DisplayName("an array of objects at a nested: field is scrubbed element by element")
    void arrayOfObjectsScrubbedElementWise() {
        ConfiguredJsonSource source = sourceWithAddress();
        Vocabulary vocabulary = VocabularyRegistry.withBuiltIns().resolve("und");
        ConfiguredJsonScrubbingEngine engine = engineFor(source, vocabulary);

        ObjectNode body = body("""
                {"id":"CUST-1","address":[
                    {"line1":"123 Main St","ssn":"111-11-1111"},
                    {"line1":"456 Oak Ave","ssn":"222-22-2222"}
                ]}
                """);
        ScrubResult result = engine.scrub(new ConfiguredJsonPayload("customer-with-address", body), context(vocabulary));

        assertThat(result.tree().at("/address/0/line1").asText()).isEqualTo("123 Main St");
        assertThat(result.tree().at("/address/1/line1").asText()).isEqualTo("456 Oak Ave");
        assertThat(result.tree().at("/address/0/ssn").asText()).isNotEqualTo("111-11-1111");
        assertThat(result.tree().at("/address/1/ssn").asText()).isNotEqualTo("222-22-2222");
        assertThat(result.tree().toString()).doesNotContain("111-11-1111", "222-22-2222");
    }

    @Test
    @DisplayName("a property present inside a nested object but absent from its catalogue "
            + "is refused, the same way an undeclared top-level property is")
    void unknownPropertyInsideNestedObjectRefuses() {
        ConfiguredJsonSource source = sourceWithAddress();
        Vocabulary vocabulary = VocabularyRegistry.withBuiltIns().resolve("und");
        ConfiguredJsonScrubbingEngine engine = engineFor(source, vocabulary);

        ObjectNode body = body("""
                {"id":"CUST-1","address":{"line1":"123 Main St","ssn":"123-45-6789","country":"leaked-if-allowed"}}
                """);

        assertThatThrownBy(() -> engine.scrub(
                new ConfiguredJsonPayload("customer-with-address", body), context(vocabulary)))
                .isInstanceOf(PrivacyRefusedException.class)
                .hasMessageContaining("UNKNOWN_FIELD")
                .hasMessageContaining("address.country")
                // core's UNKNOWN_FIELD message interpolates type.getName() for the
                // descended nested type -- this must be a stable, greppable name
                // (a pre-declared marker slot), never a hidden class's per-run address.
                .hasMessageContaining("ConfiguredJsonNestedCatalogueSlot")
                .hasMessageNotContaining("0x")
                .hasMessageNotContaining("/");
    }

    @Test
    @DisplayName("PROOF: SourceValues.prohibited walks the nested catalogue, so a raw nested "
            + "value that leaks through a duplicated, wrongly non-sensitive field is refused")
    void leakCheckWalksNestedCatalogue() {
        // Same real-world bug shape as ConfiguredJsonSourceEndToEndTest's own leak
        // check proof, one level down: a nested field correctly classified PII, and
        // a second nested field on the same object -- catalogued nonSensitive on
        // its own, entirely reasonably -- happens to carry the exact same raw text.
        //
        // This only fails closed because SourceValues.prohibited (core, unmodified)
        // descends into "address" at all: that requires resolver.descendable(token)
        // to answer true for the minted nested-catalogue token, and resolver.resolve
        // (token) to return the nested catalogue rather than the root one. Without
        // that wiring the raw SSN is never collected as prohibited, and this exact
        // scrubbed tree -- "ssn" correctly synthesised, "line1" innocently carrying
        // the same raw digits under a different field -- would pass the check
        // instead of failing it.
        ConfiguredJsonSource source = sourceWithAddress();
        Vocabulary vocabulary = VocabularyRegistry.withBuiltIns().resolve("und");
        ConfiguredJsonScrubbingEngine engine = engineFor(source, vocabulary);

        ObjectNode leakyBody = body("""
                {"id":"CUST-1","address":{"line1":"123-45-6789","ssn":"123-45-6789"}}
                """);

        assertThatThrownBy(() -> engine.scrub(
                new ConfiguredJsonPayload("customer-with-address", leakyBody), context(vocabulary)))
                .isInstanceOf(PrivacyRefusedException.class)
                .hasMessageContaining("RAW_SOURCE_VALUE");
    }

    @Test
    @DisplayName("PROOF (direct): without a resolver that descends into the nested catalogue, "
            + "SourceValues.prohibited never collects the nested raw value, and the leak check "
            + "that would have caught it above passes instead")
    void leakCheckParityIsNonVacuous() {
        // Isolates exactly what the previous test relies on: a resolver whose
        // descendable() answers false for the nested token (the pre-task-60 shape
        // every configured-JSON resolver had) leaves SourceValues blind to
        // anything under "address", so the identical scrubbed tree above is
        // reported clean.
        ConfiguredJsonSource source = sourceWithAddress();

        FieldMetadataResolver blindResolver =
                new FieldMetadataResolver() {
                    @Override
                    public List<FieldMetadata> resolve(Class<?> type) {
                        return List.copyOf(source.fields().values());
                    }

                    @Override
                    public boolean exposed(Class<?> type) {
                        return true;
                    }

                    @Override
                    public boolean descendable(Class<?> type) {
                        return false;
                    }
                };

        ObjectNode leakyBody = body("""
                {"id":"CUST-1","address":{"line1":"123-45-6789","ssn":"123-45-6789"}}
                """);

        Set<String> prohibited = SourceValues.prohibited(leakyBody, blindResolver);
        assertThat(prohibited).doesNotContain("123-45-6789");

        // The scrubbed shape this source would actually emit for that body: "ssn"
        // synthesised, "line1" passed through carrying the same raw digits.
        ObjectNode scrubbed = body("""
                {"address":{"line1":"123-45-6789","ssn":"synthetic-value-not-the-raw-one"}}
                """);
        ValidationResult check = new RawValueLeakValidator()
                .validate(scrubbed, prohibited, Set.of(), context(VocabularyRegistry.withBuiltIns().resolve("und")));
        assertThat(check.valid())
                .as("blind resolver leaves the duplicated raw value uncaught")
                .isTrue();

        // The real resolver this source is actually configured with does not have
        // that gap -- see leakCheckWalksNestedCatalogue above.
        ConfiguredJsonFieldMetadataResolver realResolver = new ConfiguredJsonFieldMetadataResolver(source);
        Set<String> realProhibited = SourceValues.prohibited(leakyBody, realResolver);
        assertThat(realProhibited).contains("123-45-6789");
    }

    @Test
    @DisplayName("new refusal: a nested catalogue's scalar/classified leaf turns up as a "
            + "structure, refused with a code distinct from UNCLASSIFIED_STRUCTURE and UNKNOWN_FIELD")
    void nestedLeafDeclaredScalarButStructuredRefuses() {
        ConfiguredJsonSource source = sourceWithAddress();
        Vocabulary vocabulary = VocabularyRegistry.withBuiltIns().resolve("und");
        ConfiguredJsonScrubbingEngine engine = engineFor(source, vocabulary);

        ObjectNode body = body("""
                {"id":"CUST-1","address":{"line1":"123 Main St","ssn":{"unexpected":"structure"}}}
                """);

        assertThatThrownBy(() -> engine.scrub(
                new ConfiguredJsonPayload("customer-with-address", body), context(vocabulary)))
                .isInstanceOf(PrivacyRefusedException.class)
                .hasMessageContaining(ConfiguredJsonNestedLeafShapeGuard.CODE)
                .hasMessageNotContaining("unexpected")
                .hasMessageNotContaining("structure\"")
                .satisfies(ex -> {
                    assertThat(ConfiguredJsonNestedLeafShapeGuard.CODE).isNotEqualTo("UNCLASSIFIED_STRUCTURE");
                    assertThat(ConfiguredJsonNestedLeafShapeGuard.CODE).isNotEqualTo("UNKNOWN_FIELD");
                });

        PrivacyRefusedException thrown = (PrivacyRefusedException) catchThrowableForPath(() -> engine.scrub(
                new ConfiguredJsonPayload("customer-with-address", body), context(vocabulary)));
        assertThat(thrown.getMessage()).contains("customer-with-address").contains("$.address.ssn");
    }

    private static Throwable catchThrowableForPath(Runnable action) {
        try {
            action.run();
            return null;
        } catch (Throwable t) {
            return t;
        }
    }

    @Test
    @DisplayName("FAIL-OPEN CLOSED: a scalar where the catalogue declares `nested:` refuses under "
            + "the strictest profile rather than being emitted verbatim")
    void scalarAtNestedFieldRefusesInsteadOfLeaking() {
        ConfiguredJsonSource source = sourceWithAddress();
        Vocabulary vocabulary = VocabularyRegistry.withBuiltIns().resolve("und");
        ConfiguredJsonScrubbingEngine engine = engineFor(source, vocabulary);

        // The exact worked case from the attempt-1 write-up: "address" is
        // declared `nested:`, but the wire carries a bare SSN-shaped string.
        ObjectNode body = body("""
                {"id":"CUST-1","address":"123-45-6789"}
                """);

        assertThatThrownBy(() -> engine.scrub(
                new ConfiguredJsonPayload("customer-with-address", body), context(vocabulary)))
                .isInstanceOf(PrivacyRefusedException.class)
                .hasMessageContaining(ConfiguredJsonNestedLeafShapeGuard.STRUCTURE_CODE)
                .hasMessageNotContaining("123-45-6789");
    }

    @Test
    @DisplayName("FAIL-OPEN CLOSED: a scalar array element where the catalogue declares `nested:` "
            + "refuses, naming the element's index")
    void scalarArrayElementAtNestedFieldRefuses() {
        ConfiguredJsonSource source = sourceWithAddress();
        Vocabulary vocabulary = VocabularyRegistry.withBuiltIns().resolve("und");
        ConfiguredJsonScrubbingEngine engine = engineFor(source, vocabulary);

        ObjectNode body = body("""
                {"id":"CUST-1","address":[{"line1":"123 Main St","ssn":"111-11-1111"},"123-45-6789"]}
                """);

        assertThatThrownBy(() -> engine.scrub(
                new ConfiguredJsonPayload("customer-with-address", body), context(vocabulary)))
                .isInstanceOf(PrivacyRefusedException.class)
                .hasMessageContaining(ConfiguredJsonNestedLeafShapeGuard.STRUCTURE_CODE)
                .hasMessageContaining("address[1]")
                .hasMessageNotContaining("123-45-6789");
    }

    @Test
    @DisplayName("the deeper-than-declared refusal message names a stable type, not a hidden class's random address")
    void refusalMessageNamesAStableTypeAcrossRuns() {
        ConfiguredJsonSource firstParse = sourceWithAddress();
        ConfiguredJsonSource secondParse = sourceWithAddress();

        // The same catalogue, parsed twice independently -- simulating two
        // separate runs of the same configuration -- must resolve the same
        // ordinal to the same, stably-named token both times.
        Class<?> firstToken = firstParse.fields().get("address").valueType();
        Class<?> secondToken = secondParse.fields().get("address").valueType();
        assertThat(firstToken.getName()).isEqualTo(secondToken.getName());
        assertThat(firstToken.getName()).doesNotContain("0x").doesNotContain("/");
    }
}

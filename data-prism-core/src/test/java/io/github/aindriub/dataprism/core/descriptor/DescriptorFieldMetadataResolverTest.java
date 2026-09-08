package io.github.aindriub.dataprism.core.descriptor;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.aindriub.dataprism.annotations.DataClassification;
import io.github.aindriub.dataprism.annotations.LlmExposedModel;
import io.github.aindriub.dataprism.annotations.NonSensitive;
import io.github.aindriub.dataprism.annotations.PrivacyAction;
import io.github.aindriub.dataprism.annotations.PrivacyNamespace;
import io.github.aindriub.dataprism.annotations.SensitiveData;
import io.github.aindriub.dataprism.core.DefaultFieldMetadataResolver;
import io.github.aindriub.dataprism.core.FieldMetadata;
import io.github.aindriub.dataprism.core.FieldMetadataResolver;
import io.github.aindriub.dataprism.core.JsonTreeScrubbingEngine;
import io.github.aindriub.dataprism.core.PrivacyContext;
import io.github.aindriub.dataprism.core.PrivacyScopeType;
import io.github.aindriub.dataprism.core.PseudonymisationVersion;
import io.github.aindriub.dataprism.core.SyntheticValueSource;
import io.github.aindriub.dataprism.core.policy.PrivacyProfile;
import io.github.aindriub.dataprism.core.policy.ProfilePrivacyPolicyResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DescriptorFieldMetadataResolverTest {

    private static final String PACKAGE = DescriptorFieldMetadataResolverTest.class.getName();

    /** Stands in for a generated or third-party DTO: no annotations available. */
    record ExternalCustomer(String id, String fullName, String email, String status) {
    }

    @LlmExposedModel
    record Annotated(
            @SensitiveData(classifications = DataClassification.PII,
                    namespace = PrivacyNamespace.PERSON_NAME,
                    suggestedAction = PrivacyAction.SYNTHESIZE)
            String name,
            @NonSensitive(reason = "believed inert") String note) {
    }

    private static Map<String, ModelDescriptor> descriptors(String yaml) {
        return ModelDescriptors.fromYaml(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));
    }

    private static FieldMetadataResolver resolver(String yaml) {
        return new DescriptorFieldMetadataResolver(new DefaultFieldMetadataResolver(), descriptors(yaml));
    }

    private static FieldMetadata field(FieldMetadataResolver resolver, Class<?> type, String name) {
        return resolver.resolve(type).stream()
                .filter(f -> f.fieldName().equals(name))
                .findFirst()
                .orElseThrow();
    }

    @Test
    @DisplayName("a type with no annotations at all can be classified entirely by descriptor")
    void classifiesAnUnannotatedType() {
        var resolver = resolver("""
                models:
                  %s$ExternalCustomer:
                    exposed: true
                    undeclaredFields: DROP
                    fields:
                      id:
                        identifier: self
                      fullName:
                        classifications: [PII]
                        namespace: PERSON_NAME
                        action: SYNTHESIZE
                      email:
                        classifications: [CONTACT]
                        namespace: EMAIL
                        action: REDACT
                      status:
                        nonSensitive: enumerated lifecycle state
                """.formatted(PACKAGE));

        assertThat(resolver.exposed(ExternalCustomer.class)).isTrue();
        assertThat(field(resolver, ExternalCustomer.class, "id").internalIdentifier()).isTrue();
        assertThat(field(resolver, ExternalCustomer.class, "fullName").classifications())
                .containsExactly(DataClassification.PII);
        assertThat(field(resolver, ExternalCustomer.class, "status").nonSensitiveReason())
                .isEqualTo("enumerated lifecycle state");
    }

    @Test
    @DisplayName("the whole pipeline runs on a descriptor-only model")
    void scrubsADescriptorOnlyModel() {
        var resolver = resolver("""
                models:
                  %s$ExternalCustomer:
                    exposed: true
                    fields:
                      id: { identifier: self }
                      fullName: { classifications: [PII], namespace: PERSON_NAME, action: SYNTHESIZE }
                      email: { classifications: [CONTACT], namespace: EMAIL, action: REDACT }
                      status: { nonSensitive: enumerated }
                """.formatted(PACKAGE));

        var profile = new PrivacyProfile("DEFAULT",
                PrivacyProfile.UnclassifiedBehaviour.FAIL_REQUEST, Map.of());
        SyntheticValueSource synthetics = (subject, namespace, ctx) -> "synthetic:" + namespace;
        var engine = new JsonTreeScrubbingEngine(resolver,
                new ProfilePrivacyPolicyResolver(Map.of("DEFAULT", profile)), synthetics);

        ObjectNode out = engine.scrub(
                new ExternalCustomer("c-1", "Patrick Murphy", "patrick@example.invalid", "ACTIVE"),
                new PrivacyContext("C", PrivacyScopeType.CASE, "DEFAULT", "test",
                        Instant.parse("2030-01-01T00:00:00Z"),
                        PseudonymisationVersion.HMAC_SHA256_V1)).tree();

        assertThat(out.get("fullName").asText()).startsWith("synthetic:");
        assertThat(out.get("email").asText()).isEqualTo(JsonTreeScrubbingEngine.REDACTED);
        assertThat(out.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(out.has("id")).isFalse();
        assertThat(out.toString()).doesNotContain("Patrick Murphy").doesNotContain("c-1");
    }

    @Test
    @DisplayName("a descriptor may tighten an annotation's action")
    void tightensAction() {
        var resolver = resolver("""
                models:
                  %s$Annotated:
                    fields:
                      name: { action: REDACT }
                """.formatted(PACKAGE));

        // Code said SYNTHESIZE, descriptor said REDACT. REDACT is stricter.
        assertThat(field(resolver, Annotated.class, "name").suggestedAction())
                .isEqualTo(PrivacyAction.REDACT);
    }

    @Test
    @DisplayName("a descriptor may not relax an annotation's action")
    void cannotRelaxAction() {
        var resolver = resolver("""
                models:
                  %s$Annotated:
                    fields:
                      name: { action: PASS_THROUGH }
                """.formatted(PACKAGE));

        // If configuration could do this, anyone who can edit a file can disclose
        // data, and the classification in the source stops being worth reading.
        assertThat(field(resolver, Annotated.class, "name").suggestedAction())
                .isEqualTo(PrivacyAction.SYNTHESIZE);
    }

    @Test
    @DisplayName("a descriptor may classify a field the code called safe")
    void overridesNonSensitive() {
        var resolver = resolver("""
                models:
                  %s$Annotated:
                    fields:
                      note: { classifications: [CONFIDENTIAL], action: REDACT }
                """.formatted(PACKAGE));

        FieldMetadata note = field(resolver, Annotated.class, "note");
        assertThat(note.sensitive()).isTrue();
        assertThat(note.nonSensitiveReason())
                .as("the claim that it was safe does not survive being contradicted").isNull();
    }

    @Test
    @DisplayName("a descriptor may not declassify a field the code called sensitive")
    void cannotDeclassify() {
        var resolver = resolver("""
                models:
                  %s$Annotated:
                    fields:
                      name: { nonSensitive: someone decided this was fine }
                """.formatted(PACKAGE));

        FieldMetadata name = field(resolver, Annotated.class, "name");
        assertThat(name.sensitive()).isTrue();
        assertThat(name.classifications()).containsExactly(DataClassification.PII);
    }

    @Test
    @DisplayName("classifications from both sources are unioned")
    void unionsClassifications() {
        var resolver = resolver("""
                models:
                  %s$Annotated:
                    fields:
                      name: { classifications: [GOVERNMENT_IDENTIFIER] }
                """.formatted(PACKAGE));

        assertThat(field(resolver, Annotated.class, "name").classifications())
                .containsExactlyInAnyOrder(
                        DataClassification.PII, DataClassification.GOVERNMENT_IDENTIFIER);
    }

    @Test
    @DisplayName("a namespace disagreement fails rather than being guessed at")
    void namespaceConflictFails() {
        var resolver = resolver("""
                models:
                  %s$Annotated:
                    fields:
                      name: { namespace: ORGANISATION_NAME }
                """.formatted(PACKAGE));

        // Picking one would give the same subject two synthetic identities in two
        // sources, and read as two people.
        assertThatThrownBy(() -> resolver.resolve(Annotated.class))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("split one subject into two");
    }

    @Test
    @DisplayName("a descriptor cannot introduce NON_SENSITIVE as a type default")
    void cannotIntroduceNonSensitiveDefault() {
        var resolver = resolver("""
                models:
                  %s$ExternalCustomer:
                    exposed: true
                    undeclaredFields: NON_SENSITIVE
                """.formatted(PACKAGE));

        assertThatThrownBy(() -> resolver.resolve(ExternalCustomer.class))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("may only tighten");
    }

    @Test
    @DisplayName("a type not named in the descriptor is untouched")
    void unlistedTypesPassStraightThrough() {
        var resolver = resolver("""
                models:
                  %s$ExternalCustomer:
                    exposed: true
                """.formatted(PACKAGE));

        assertThat(field(resolver, Annotated.class, "name").suggestedAction())
                .isEqualTo(PrivacyAction.SYNTHESIZE);
        assertThat(resolver.exposed(Annotated.class)).isTrue();
    }

    @Test
    @DisplayName("a misspelled key fails at load, naming the line")
    void unknownEnumFails() {
        assertThatThrownBy(() -> descriptors("""
                models:
                  Some.Type:
                    fields:
                      x: { classifications: [PIII] }
                """))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("PIII");

        assertThatThrownBy(() -> descriptors("nothing: here\n"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("models");
    }

    @Test
    @DisplayName("a field cannot be both classified and declared safe in one descriptor")
    void contradictoryFieldFails() {
        assertThatThrownBy(() -> descriptors("""
                models:
                  Some.Type:
                    fields:
                      x: { classifications: [PII], nonSensitive: also fine }
                """))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("both classifications and nonSensitive");
    }
}

package io.github.aindriub.dataprism.core;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.aindriub.dataprism.annotations.DataClassification;
import io.github.aindriub.dataprism.annotations.InternalIdentifier;
import io.github.aindriub.dataprism.annotations.LlmExposedModel;
import io.github.aindriub.dataprism.annotations.NonSensitive;
import io.github.aindriub.dataprism.annotations.PrivacyAction;
import io.github.aindriub.dataprism.annotations.PrivacyNamespace;
import io.github.aindriub.dataprism.annotations.SensitiveData;
import io.github.aindriub.dataprism.annotations.SensitiveObject;
import io.github.aindriub.dataprism.annotations.UndeclaredFields;
import io.github.aindriub.dataprism.core.policy.PrivacyProfile;
import io.github.aindriub.dataprism.core.policy.ProfilePrivacyPolicyResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Class-level defaults: the retrofit path.
 *
 * <p>Annotating every field of a large existing model to say "this one is fine"
 * is hours of work producing no information, and the pressure that creates is to
 * reach for a global setting that turns fail-closed off everywhere. A per-type
 * default is scoped to a class someone actually looked at, sits where a reviewer
 * will see it, and leaves every other model under the strict profile.
 */
class ClassLevelDefaultsTest {

    private static final SyntheticValueSource SYNTHETICS =
            (subjectId, namespace, context) -> "synthetic:" + namespace;

    /** A legacy model: two classified fields and a long tail nobody has touched. */
    @LlmExposedModel(undeclaredFields = UndeclaredFields.NON_SENSITIVE)
    record Legacy(
            @InternalIdentifier String subjectRef,
            @SensitiveData(classifications = DataClassification.PII,
                    namespace = PrivacyNamespace.PERSON_NAME,
                    suggestedAction = PrivacyAction.SYNTHESIZE)
            String customerName,
            String status,
            String channel,
            String segment) {
    }

    @LlmExposedModel(undeclaredFields = UndeclaredFields.REDACT)
    record CautiousLegacy(@InternalIdentifier String subjectRef, String note) {
    }

    @LlmExposedModel(undeclaredFields = UndeclaredFields.DROP)
    record TerseLegacy(@InternalIdentifier String subjectRef, String note) {
    }

    /** A structured value whose parts are all the same kind of thing. */
    @SensitiveObject(classifications = DataClassification.ADDRESS,
            namespace = PrivacyNamespace.ADDRESS,
            suggestedAction = PrivacyAction.REDACT)
    record Address(String line1, String line2, String town, String postcode) {
    }

    @LlmExposedModel
    record WithAddress(
            @InternalIdentifier String subjectRef,
            @NonSensitive(reason = "structure classified by its own type") Address address) {
    }

    private static PrivacyContext context() {
        return new PrivacyContext("C", PrivacyScopeType.CASE, "DEFAULT", "test",
                Instant.parse("2030-01-01T00:00:00Z"), PseudonymisationVersion.HMAC_SHA256_V1);
    }

    /** Strictest possible profile, so anything permitted here came from the type. */
    private static JsonTreeScrubbingEngine engine() {
        var profile = new PrivacyProfile("DEFAULT",
                PrivacyProfile.UnclassifiedBehaviour.FAIL_REQUEST,
                Map.of(DataClassification.PII,
                        PrivacyProfile.ClassificationRule.of(PrivacyAction.SYNTHESIZE),
                        DataClassification.ADDRESS,
                        PrivacyProfile.ClassificationRule.of(PrivacyAction.REDACT)));
        return new JsonTreeScrubbingEngine(new DefaultFieldMetadataResolver(),
                new ProfilePrivacyPolicyResolver(Map.of("DEFAULT", profile)), SYNTHETICS);
    }

    @Test
    @DisplayName("one annotation on the class adopts a whole legacy model")
    void classLevelNonSensitiveAdoptsTheTail() {
        ObjectNode out = engine().scrub(
                new Legacy("s-1", "Patrick Murphy", "ACTIVE", "WEB", "RETAIL"), context()).tree();

        // The profile still says FAIL_REQUEST. Only this type opted out.
        assertThat(out.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(out.get("channel").asText()).isEqualTo("WEB");
        assertThat(out.get("segment").asText()).isEqualTo("RETAIL");
        // And the field someone did classify is unaffected by the default.
        assertThat(out.get("customerName").asText()).startsWith("synthetic:");
        assertThat(out.has("subjectRef")).isFalse();
    }

    @Test
    @DisplayName("the class-level default does not leak to other models")
    void defaultIsScopedToItsType() {
        // Same engine, same profile, a model that said nothing: still refused.
        var strict = engine();
        assertThat(strict.scrub(new Legacy("s-1", "x", "A", "B", "C"), context())).isNotNull();

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        strict.scrub(new Undefaulted("s-1", "unclassified"), context()))
                .isInstanceOf(PrivacyRefusedException.class);
    }

    @LlmExposedModel
    record Undefaulted(@InternalIdentifier String subjectRef, String loose) {
    }

    @Test
    @DisplayName("REDACT and DROP are available as class-level defaults too")
    void otherClassLevelDefaults() {
        assertThat(engine().scrub(new CautiousLegacy("s-1", "free text"), context()).tree()
                .get("note").asText()).isEqualTo(JsonTreeScrubbingEngine.REDACTED);

        assertThat(engine().scrub(new TerseLegacy("s-1", "free text"), context()).tree()
                .has("note")).isFalse();
    }

    @Test
    @DisplayName("a structured value can be classified once on its type")
    void typeLevelClassificationCoversEveryComponent() {
        ObjectNode out = engine().scrub(new WithAddress("s-1",
                new Address("12 Elm Street", "Apt 4", "Belmont", "D02 XY45")), context()).tree();

        ObjectNode address = (ObjectNode) out.get("address");
        assertThat(address.get("line1").asText()).isEqualTo(JsonTreeScrubbingEngine.REDACTED);
        assertThat(address.get("line2").asText()).isEqualTo(JsonTreeScrubbingEngine.REDACTED);
        assertThat(address.get("town").asText()).isEqualTo(JsonTreeScrubbingEngine.REDACTED);
        assertThat(address.get("postcode").asText()).isEqualTo(JsonTreeScrubbingEngine.REDACTED);
        assertThat(out.toString()).doesNotContain("Belmont").doesNotContain("D02 XY45");
    }

    @Test
    @DisplayName("type-level classification reaches the validator's prohibited set")
    void typeLevelClassificationIsProhibited() {
        var prohibited = SourceValues.prohibited(
                new WithAddress("s-1", new Address("12 Elm Street", "Apt 4", "Belmont", "D02 XY45")),
                new DefaultFieldMetadataResolver());

        assertThat(prohibited).contains("12 Elm Street", "Belmont", "D02 XY45");
    }

    @Test
    @DisplayName("SYNTHESIZE at type level gives every component the same value")
    void typeLevelSynthesiseCollapsesComponents() {
        // Documented rather than prevented, because it follows from what a
        // synthetic value is: a function of subject and namespace. One namespace
        // across four fields means one value across four fields, so a structure
        // that needs synthesis has to name a namespace per field.
        var resolver = new DefaultFieldMetadataResolver();

        assertThat(resolver.resolve(SynthesisedAddress.class))
                .allSatisfy(field -> assertThat(field.namespace())
                        .isEqualTo(PrivacyNamespace.ADDRESS));
    }

    @SensitiveObject(classifications = DataClassification.ADDRESS,
            namespace = PrivacyNamespace.ADDRESS,
            suggestedAction = PrivacyAction.SYNTHESIZE)
    record SynthesisedAddress(String line1, String town) {
    }
}

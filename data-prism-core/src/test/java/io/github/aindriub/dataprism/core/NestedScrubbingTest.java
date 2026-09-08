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
import io.github.aindriub.dataprism.core.policy.PrivacyProfile;
import io.github.aindriub.dataprism.core.policy.ProfilePrivacyPolicyResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Nested structures, and the profile setting that governs anything unclassified.
 *
 * <p>The first test here is a regression test for a real hole: a nested object
 * declared {@code @NonSensitive} was copied into the response wholesale, raw
 * name and email included, and the output validator did not catch it because it
 * only ever looked at top-level fields.
 */
class NestedScrubbingTest {

    private static final SyntheticValueSource SYNTHETICS =
            (subjectId, namespace, context) -> "synthetic:" + Integer.toHexString(subjectId.hashCode());

    @SensitiveObject
    record Reviewed(
            @SensitiveData(classifications = DataClassification.PII,
                    namespace = PrivacyNamespace.PERSON_NAME,
                    suggestedAction = PrivacyAction.SYNTHESIZE)
            String innerName,
            @NonSensitive(reason = "enumerated") String innerState) {
    }

    /** No annotation: the engine has no basis to descend into this. */
    record Unreviewed(String secretName, String secretEmail) {
    }

    @LlmExposedModel
    record WithReviewedChild(
            @InternalIdentifier String subjectRef,
            @NonSensitive(reason = "structure reviewed separately") Reviewed details,
            @NonSensitive(reason = "structure reviewed separately") List<Reviewed> history) {
    }

    @LlmExposedModel
    record WithUnreviewedChild(
            @InternalIdentifier String subjectRef,
            @NonSensitive(reason = "developer believed this block was inert") Unreviewed details) {
    }

    private static PrivacyContext context() {
        return new PrivacyContext("C", PrivacyScopeType.CASE, "DEFAULT", "test",
                Instant.parse("2030-01-01T00:00:00Z"), PseudonymisationVersion.HMAC_SHA256_V1);
    }

    private static JsonTreeScrubbingEngine engine(PrivacyProfile.UnclassifiedBehaviour unclassified) {
        var profile = new PrivacyProfile("DEFAULT", unclassified,
                Map.of(DataClassification.PII,
                        PrivacyProfile.ClassificationRule.of(PrivacyAction.SYNTHESIZE)));
        return new JsonTreeScrubbingEngine(new DefaultFieldMetadataResolver(),
                new ProfilePrivacyPolicyResolver(Map.of("DEFAULT", profile)), SYNTHETICS);
    }

    @Test
    @DisplayName("a nested object is descended into, not copied through")
    void descendsIntoReviewedChild() {
        var source = new WithReviewedChild("s-1",
                new Reviewed("Patrick Murphy", "ACTIVE"),
                List.of(new Reviewed("Pat Murphy", "CLOSED")));

        ObjectNode out = engine(PrivacyProfile.UnclassifiedBehaviour.FAIL_REQUEST)
                .scrub(source, context()).tree();

        assertThat(out.toString())
                .doesNotContain("Patrick Murphy")
                .doesNotContain("Pat Murphy");
        assertThat(out.get("details").get("innerName").asText()).startsWith("synthetic:");
        assertThat(out.get("details").get("innerState").asText()).isEqualTo("ACTIVE");
        assertThat(out.get("history").get(0).get("innerName").asText()).startsWith("synthetic:");
    }

    @Test
    @DisplayName("an unreviewed nested object is refused rather than released")
    void refusesUnreviewedChild() {
        // The hole this replaced: this exact shape previously emitted both raw
        // values, and reported success.
        var source = new WithUnreviewedChild("s-1",
                new Unreviewed("Patrick Murphy", "patrick@example.invalid"));

        assertThatThrownBy(() -> engine(PrivacyProfile.UnclassifiedBehaviour.FAIL_REQUEST)
                .scrub(source, context()))
                .isInstanceOf(PrivacyRefusedException.class)
                .extracting(e -> ((PrivacyRefusedException) e).code())
                .isEqualTo("UNCLASSIFIED_STRUCTURE");
    }

    @Test
    @DisplayName("the nested values reach the validator's prohibited set")
    void nestedValuesAreProhibited() {
        var source = new WithReviewedChild("s-1",
                new Reviewed("Patrick Murphy", "ACTIVE"),
                List.of(new Reviewed("Pat Murphy", "CLOSED")));

        var prohibited = SourceValues.prohibited(source, new DefaultFieldMetadataResolver());

        // Without this the validator could not catch a nested leak at all: it
        // would compare against an empty set and report the response clean.
        assertThat(prohibited).contains("Patrick Murphy", "Pat Murphy", "s-1");
        assertThat(prohibited).doesNotContain("ACTIVE");
    }

    @Test
    @DisplayName("REDACT_AND_WARN keeps the field and replaces the structure")
    void redactsUnreviewedChild() {
        var source = new WithUnreviewedChild("s-1",
                new Unreviewed("Patrick Murphy", "patrick@example.invalid"));

        ObjectNode out = engine(PrivacyProfile.UnclassifiedBehaviour.REDACT_AND_WARN)
                .scrub(source, context()).tree();

        assertThat(out.get("details").asText()).isEqualTo(JsonTreeScrubbingEngine.REDACTED);
        assertThat(out.toString()).doesNotContain("Patrick Murphy");
    }

    @Test
    @DisplayName("DROP_AND_WARN omits the field, the way Jackson ignores unknown properties")
    void dropsUnreviewedChild() {
        var source = new WithUnreviewedChild("s-1",
                new Unreviewed("Patrick Murphy", "patrick@example.invalid"));

        ObjectNode out = engine(PrivacyProfile.UnclassifiedBehaviour.DROP_AND_WARN)
                .scrub(source, context()).tree();

        assertThat(out.has("details")).isFalse();
        assertThat(out.toString()).doesNotContain("Patrick Murphy");
    }

    @Test
    @DisplayName("PASS_THROUGH_UNSAFE releases it, structure and all")
    void passThroughReleasesUnreviewedChild() {
        var source = new WithUnreviewedChild("s-1",
                new Unreviewed("Patrick Murphy", "patrick@example.invalid"));

        ObjectNode out = engine(PrivacyProfile.UnclassifiedBehaviour.PASS_THROUGH_UNSAFE)
                .scrub(source, context()).tree();

        // Documented rather than prevented. This is what the setting is for, and
        // what it costs: the subtree goes out exactly as the source held it.
        assertThat(out.get("details").get("secretName").asText()).isEqualTo("Patrick Murphy");
        assertThat(out.get("details").get("secretEmail").asText())
                .isEqualTo("patrick@example.invalid");
    }

    @Test
    @DisplayName("a classified nested field is still transformed under PASS_THROUGH_UNSAFE")
    void passThroughDoesNotDisableClassification() {
        // The setting governs what nobody classified. It is not an off switch for
        // the engine, and a field someone did classify is still transformed.
        var source = new WithReviewedChild("s-1",
                new Reviewed("Patrick Murphy", "ACTIVE"), List.of());

        ObjectNode out = engine(PrivacyProfile.UnclassifiedBehaviour.PASS_THROUGH_UNSAFE)
                .scrub(source, context()).tree();

        assertThat(out.get("details").get("innerName").asText()).startsWith("synthetic:");
        assertThat(out.toString()).doesNotContain("Patrick Murphy");
    }

    @Test
    @DisplayName("the correlation identifier is dropped at every depth")
    void identifiersNeverSurvive() {
        var source = new WithReviewedChild("s-1",
                new Reviewed("Patrick Murphy", "ACTIVE"), List.of());

        ObjectNode out = engine(PrivacyProfile.UnclassifiedBehaviour.PASS_THROUGH_UNSAFE)
                .scrub(source, context()).tree();

        assertThat(out.has("subjectRef")).isFalse();
        assertThat(out.toString()).doesNotContain("s-1");
    }
}

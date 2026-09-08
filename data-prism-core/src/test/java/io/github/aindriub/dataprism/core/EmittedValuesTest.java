package io.github.aindriub.dataprism.core;

import io.github.aindriub.dataprism.annotations.DataClassification;
import io.github.aindriub.dataprism.annotations.InternalIdentifier;
import io.github.aindriub.dataprism.annotations.LlmExposedModel;
import io.github.aindriub.dataprism.annotations.NonSensitive;
import io.github.aindriub.dataprism.annotations.PrivacyAction;
import io.github.aindriub.dataprism.annotations.PrivacyNamespace;
import io.github.aindriub.dataprism.annotations.SensitiveData;
import io.github.aindriub.dataprism.core.policy.PrivacyProfile;
import io.github.aindriub.dataprism.core.policy.ProfilePrivacyPolicyResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the engine reports having generated.
 *
 * <p>This set is the scope-aware allowlist the output validator needs: a
 * synthesised email is an email, so a shape-based detector matches it, and only
 * the engine can say it was not a leak. See docs/design-review.md §A5.
 */
class EmittedValuesTest {

    /** Shaped like the real generator's output, so it matches a detector the way one does. */
    private static final SyntheticValueSource SYNTHETICS =
            (subject, namespace, context) -> switch (namespace) {
                case EMAIL -> "person." + Integer.toHexString(subject.hashCode()) + "@example.invalid";
                default -> "Synthetic Person";
            };

    private static final PrivacyProfile PROFILE = new PrivacyProfile("DEFAULT",
            PrivacyProfile.UnclassifiedBehaviour.FAIL_REQUEST,
            Map.of(DataClassification.CONTACT, PrivacyProfile.ClassificationRule.of(PrivacyAction.SYNTHESIZE),
                    DataClassification.PII, PrivacyProfile.ClassificationRule.of(PrivacyAction.SYNTHESIZE)));

    private final JsonTreeScrubbingEngine engine = new JsonTreeScrubbingEngine(
            new DefaultFieldMetadataResolver(),
            new ProfilePrivacyPolicyResolver(Map.of("DEFAULT", PROFILE)),
            SYNTHETICS);

    private static PrivacyContext context() {
        return new PrivacyContext("CASE-1", PrivacyScopeType.CASE, "DEFAULT", "test",
                Instant.parse("2030-01-01T00:00:00Z"), PseudonymisationVersion.HMAC_SHA256_V1);
    }

    @LlmExposedModel
    record Contactable(
            @InternalIdentifier String subjectRef,
            @SensitiveData(classifications = DataClassification.CONTACT,
                    namespace = PrivacyNamespace.EMAIL,
                    suggestedAction = PrivacyAction.SYNTHESIZE)
            String email,
            @NonSensitive(reason = "Enumerated state, no free text")
            String state) {
    }

    @Test
    @DisplayName("every synthesised value is reported alongside the tree")
    void reportsSynthesisedValues() {
        ScrubResult result = engine.scrub(
                new Contactable("s-1", "patrick@example.invalid", "ACTIVE"), context());

        assertThat(result.emitted()).containsExactly(result.tree().get("email").asText());
    }

    @Test
    @DisplayName("a value the profile passed through is not reported as emitted")
    void doesNotReportPassedThroughValues() {
        ScrubResult result = engine.scrub(
                new Contactable("s-1", "patrick@example.invalid", "ACTIVE"), context());

        // The allowlist exists to exempt values the engine invented. Exempting a
        // value that came off the source would hide the leak it was meant to
        // distinguish itself from.
        assertThat(result.emitted()).doesNotContain("ACTIVE");
    }

    @Test
    @DisplayName("the result does not print the values it carries")
    void resultDoesNotPrintEmittedValues() {
        ScrubResult result = engine.scrub(
                new Contactable("s-1", "patrick@example.invalid", "ACTIVE"), context());

        // A record's generated toString would print every one of them, and this
        // object travels through orchestration code that logs and audits.
        assertThat(result.toString())
                .doesNotContain("@example.invalid")
                .contains("emitted=1");
    }

    @Test
    @DisplayName("nested and repeated values are reported at any depth")
    void reportsNestedValues() {
        ScrubResult result = engine.scrub(
                new Household("h-1", new Contactable("s-1", "a@example.invalid", "ACTIVE"),
                        java.util.List.of(new Contactable("s-2", "b@example.invalid", "ACTIVE"))),
                context());

        assertThat(result.emitted()).hasSize(2)
                .allSatisfy(value -> assertThat(value).endsWith("@example.invalid"));
    }

    @LlmExposedModel
    record Household(
            @InternalIdentifier String subjectRef,
            @SensitiveData(classifications = DataClassification.PII) Contactable head,
            @SensitiveData(classifications = DataClassification.PII) java.util.List<Contactable> others) {
    }
}

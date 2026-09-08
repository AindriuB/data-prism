package io.github.aindriub.dataprism.core;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.aindriub.dataprism.annotations.DataClassification;
import io.github.aindriub.dataprism.annotations.PrivacyAction;
import io.github.aindriub.dataprism.annotations.PrivacyNamespace;
import io.github.aindriub.dataprism.core.policy.PrivacyProfile;
import io.github.aindriub.dataprism.core.policy.ProfilePrivacyPolicyResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JsonTreeScrubbingEngineTest {

    /**
     * Deterministic, distinct per subject, and deliberately not echoing the
     * subject id — a stub that embedded it would make "no raw identifier
     * survives" pass or fail for the wrong reason.
     */
    private static final SyntheticValueSource FAKE_SYNTHETICS =
            (subjectId, namespace, context) ->
                    "synthetic:" + namespace + ":" + Integer.toHexString(subjectId.hashCode());

    private final PrivacyContext context = new PrivacyContext("CASE-1", PrivacyScopeType.CASE,
            "DEFAULT", "test", Instant.parse("2030-01-01T00:00:00Z"),
            PseudonymisationVersion.HMAC_SHA256_V1);

    /** Mirrors the shipped DEFAULT profile closely enough to exercise precedence. */
    private static final PrivacyProfile DEFAULT_PROFILE = new PrivacyProfile("DEFAULT",
            PrivacyProfile.UnclassifiedBehaviour.FAIL_REQUEST,
            Map.of(DataClassification.PII, PrivacyProfile.ClassificationRule.of(PrivacyAction.SYNTHESIZE),
                    DataClassification.CONTACT, PrivacyProfile.ClassificationRule.of(PrivacyAction.REDACT),
                    DataClassification.CONFIDENTIAL, PrivacyProfile.ClassificationRule.of(PrivacyAction.REMOVE)));

    private final JsonTreeScrubbingEngine engine = new JsonTreeScrubbingEngine(
            new DefaultFieldMetadataResolver(),
            new ProfilePrivacyPolicyResolver(Map.of("DEFAULT", DEFAULT_PROFILE)),
            FAKE_SYNTHETICS);

    @Test
    @DisplayName("each action is applied and the raw values do not survive")
    void appliesEachAction() {
        var source = new ScrubbingFixtures.Declared(
                "subject-1", "Patrick Murphy", "patrick@example.invalid", "internal note", "ACTIVE");

        ObjectNode out = engine.scrub(source, context).tree();

        assertThat(out.get("fullName").asText())
                .isEqualTo(FAKE_SYNTHETICS.syntheticValue("subject-1", PrivacyNamespace.PERSON_NAME, context));
        assertThat(out.get("contact").asText()).isEqualTo(JsonTreeScrubbingEngine.REDACTED);
        assertThat(out.has("note")).as("REMOVE drops the field entirely").isFalse();
        assertThat(out.get("state").asText()).as("declared non-sensitive passes through").isEqualTo("ACTIVE");
        assertThat(out.toString())
                .doesNotContain("Patrick Murphy")
                .doesNotContain("patrick@example.invalid")
                .doesNotContain("internal note");
    }

    @Test
    @DisplayName("the correlation identifier is never emitted")
    void dropsInternalIdentifier() {
        var source = new ScrubbingFixtures.Declared(
                "subject-1", "Patrick Murphy", "patrick@example.invalid", "note", "ACTIVE");

        ObjectNode out = engine.scrub(source, context).tree();

        assertThat(out.has("subjectRef")).isFalse();
        assertThat(out.toString()).doesNotContain("subject-1");
    }

    @Test
    @DisplayName("an unclassified field is refused, not passed through")
    void failsClosedOnUndeclaredField() {
        var source = new ScrubbingFixtures.Undeclared(
                "subject-1", "Patrick Murphy", "patrick@example.invalid", "ACTIVE");

        assertThatThrownBy(() -> engine.scrub(source, context))
                .isInstanceOf(PrivacyRefusedException.class)
                .extracting(e -> ((PrivacyRefusedException) e).code())
                .isEqualTo("UNDECLARED_FIELD");
    }

    @Test
    @DisplayName("the fail-closed test is measuring the annotation, not something else")
    void failClosedTestIsNotVacuous() {
        // Undeclared differs from Declared only by the missing @SensitiveData on
        // `contact`. If this passes while the test above fails, the check is real.
        var declared = new ScrubbingFixtures.Declared(
                "subject-1", "Patrick Murphy", "patrick@example.invalid", "note", "ACTIVE");

        assertThat(engine.scrub(declared, context)).isNotNull();
    }

    @Test
    @DisplayName("two subjects in one record get two different pseudonyms")
    void distinctSubjectsDoNotMerge() {
        var source = new ScrubbingFixtures.TwoSubjects(
                "app-1", "guarantor-9", "Patrick Murphy", "Aoife Byrne");

        ObjectNode out = engine.scrub(source, context).tree();

        // The failure this guards against is not a leak: it is the model being
        // told two people are one person. See docs/design-review.md A1.
        assertThat(out.get("applicantName").asText()).isNotEqualTo(out.get("guarantorName").asText());
        assertThat(out.get("guarantorName").asText()).isEqualTo(
                FAKE_SYNTHETICS.syntheticValue("guarantor-9", PrivacyNamespace.PERSON_NAME, context));
    }

    @Test
    @DisplayName("a type not approved for exposure is refused")
    void refusesUnexposedType() {
        assertThatThrownBy(() -> engine.scrub(new ScrubbingFixtures.NotExposed("x", "y"), context))
                .isInstanceOf(PrivacyRefusedException.class)
                .extracting(e -> ((PrivacyRefusedException) e).code())
                .isEqualTo("MODEL_NOT_EXPOSED");
    }

    @Test
    @DisplayName("a refusal never carries the value that caused it")
    void refusalCarriesNoValue() {
        var source = new ScrubbingFixtures.Undeclared(
                "subject-1", "Patrick Murphy", "patrick@example.invalid", "ACTIVE");

        assertThatThrownBy(() -> engine.scrub(source, context))
                .hasMessageNotContaining("patrick@example.invalid")
                .hasMessageNotContaining("Patrick Murphy");
    }
}

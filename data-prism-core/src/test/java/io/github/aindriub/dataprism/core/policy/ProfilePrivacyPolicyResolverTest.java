package io.github.aindriub.dataprism.core.policy;

import io.github.aindriub.dataprism.annotations.DataClassification;
import io.github.aindriub.dataprism.annotations.PrivacyAction;
import io.github.aindriub.dataprism.annotations.PrivacyNamespace;
import io.github.aindriub.dataprism.core.FieldMetadata;
import io.github.aindriub.dataprism.core.PrivacyContext;
import io.github.aindriub.dataprism.core.PrivacyScopeType;
import io.github.aindriub.dataprism.core.PseudonymisationVersion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProfilePrivacyPolicyResolverTest {

    private static PrivacyContext context(String profile) {
        return new PrivacyContext("CASE-1", PrivacyScopeType.CASE, profile, "test",
                Instant.parse("2030-01-01T00:00:00Z"), PseudonymisationVersion.HMAC_SHA256_V1);
    }

    private static FieldMetadata sensitive(PrivacyAction suggested, DataClassification... classifications) {
        return new FieldMetadata("field", false, null, List.of(classifications),
                PrivacyNamespace.PERSON_NAME, suggested, "", null, String.class, null);
    }

    private static ProfilePrivacyPolicyResolver resolver(PrivacyProfile profile) {
        return new ProfilePrivacyPolicyResolver(Map.of(profile.name(), profile));
    }

    private static PrivacyProfile profile(PrivacyProfile.UnclassifiedBehaviour unclassified,
                                          Map<DataClassification, PrivacyProfile.ClassificationRule> rules) {
        return new PrivacyProfile("DEFAULT", unclassified, rules);
    }

    @Test
    @DisplayName("the profile decides when the annotation suggests something weaker")
    void profileOverridesWeakerAnnotation() {
        var resolver = resolver(profile(PrivacyProfile.UnclassifiedBehaviour.FAIL_REQUEST,
                Map.of(DataClassification.PII, PrivacyProfile.ClassificationRule.of(PrivacyAction.REDACT))));

        var policy = resolver.resolve(
                sensitive(PrivacyAction.SYNTHESIZE, DataClassification.PII), context("DEFAULT"));

        assertThat(policy.action()).isEqualTo(PrivacyAction.REDACT);
        assertThat(policy.source()).isEqualTo(EffectivePrivacyPolicy.Decided.PROFILE_RULE);
    }

    @Test
    @DisplayName("an annotation may tighten the profile but never loosen it")
    void annotationMayOnlyTighten() {
        var resolver = resolver(profile(PrivacyProfile.UnclassifiedBehaviour.FAIL_REQUEST,
                Map.of(DataClassification.PII, PrivacyProfile.ClassificationRule.of(PrivacyAction.SYNTHESIZE))));

        // The author was more cautious than the operator. Honour the author:
        // a model class asking for less exposure is never a security problem.
        assertThat(resolver.resolve(sensitive(PrivacyAction.REDACT, DataClassification.PII),
                context("DEFAULT")).action()).isEqualTo(PrivacyAction.REDACT);

        // The reverse must not work, or an annotation would be a way to widen
        // disclosure by editing application code.
        assertThat(resolver.resolve(sensitive(PrivacyAction.PASS_THROUGH, DataClassification.PII),
                context("DEFAULT")).action()).isEqualTo(PrivacyAction.SYNTHESIZE);
    }

    @Test
    @DisplayName("override takes the rule verbatim, which is the only way to relax a field")
    void overrideRelaxesAnOverClassifiedField() {
        var resolver = resolver(profile(PrivacyProfile.UnclassifiedBehaviour.FAIL_REQUEST,
                Map.of(DataClassification.PII,
                        new PrivacyProfile.ClassificationRule(PrivacyAction.SYNTHESIZE, true))));

        assertThat(resolver.resolve(sensitive(PrivacyAction.REMOVE, DataClassification.PII),
                context("DEFAULT")).action()).isEqualTo(PrivacyAction.SYNTHESIZE);
    }

    @Test
    @DisplayName("a field with two classifications gets the stricter of the two rules")
    void strictestClassificationWins() {
        var resolver = resolver(profile(PrivacyProfile.UnclassifiedBehaviour.FAIL_REQUEST,
                Map.of(DataClassification.PII, PrivacyProfile.ClassificationRule.of(PrivacyAction.SYNTHESIZE),
                        DataClassification.CREDENTIAL, PrivacyProfile.ClassificationRule.of(PrivacyAction.REMOVE))));

        var policy = resolver.resolve(
                sensitive(null, DataClassification.PII, DataClassification.CREDENTIAL), context("DEFAULT"));

        // Not whichever the enum happens to list first.
        assertThat(policy.action()).isEqualTo(PrivacyAction.REMOVE);
    }

    @Test
    @DisplayName("with no rule the annotation stands, and with neither the safe default does")
    void fallsBackDownTheChain() {
        var resolver = resolver(profile(PrivacyProfile.UnclassifiedBehaviour.FAIL_REQUEST, Map.of()));

        assertThat(resolver.resolve(sensitive(PrivacyAction.SYNTHESIZE, DataClassification.PII),
                context("DEFAULT")))
                .satisfies(p -> {
                    assertThat(p.action()).isEqualTo(PrivacyAction.SYNTHESIZE);
                    assertThat(p.source()).isEqualTo(EffectivePrivacyPolicy.Decided.ANNOTATION);
                });

        assertThat(resolver.resolve(sensitive(null, DataClassification.PII), context("DEFAULT")))
                .satisfies(p -> {
                    assertThat(p.action()).isEqualTo(PrivacyAction.REDACT);
                    assertThat(p.source()).isEqualTo(EffectivePrivacyPolicy.Decided.SAFE_DEFAULT);
                });
    }

    @Test
    @DisplayName("an unclassified field fails the request under the production setting")
    void unclassifiedFailsClosed() {
        var resolver = resolver(profile(PrivacyProfile.UnclassifiedBehaviour.FAIL_REQUEST, Map.of()));
        var undeclared = FieldMetadata.undeclared("field");

        assertThat(resolver.resolve(undeclared, context("DEFAULT")).allowed()).isFalse();
    }

    @Test
    @DisplayName("redact-and-warn redacts, and still never passes through")
    void unclassifiedRedactsWhenConfigured() {
        var resolver = resolver(profile(PrivacyProfile.UnclassifiedBehaviour.REDACT_AND_WARN, Map.of()));
        var undeclared = FieldMetadata.undeclared("field");

        var policy = resolver.resolve(undeclared, context("DEFAULT"));

        assertThat(policy.allowed()).isTrue();
        assertThat(policy.action()).isEqualTo(PrivacyAction.REDACT);
    }

    @Test
    @DisplayName("an identifier is removed regardless of what any profile says")
    void identifiersAreAlwaysRemoved() {
        var resolver = resolver(profile(PrivacyProfile.UnclassifiedBehaviour.REDACT_AND_WARN, Map.of()));
        var identifier = new FieldMetadata("subjectRef", true, FieldMetadata.SELF, List.of(),
                PrivacyNamespace.NONE, null, "", null, String.class, null);

        assertThat(resolver.resolve(identifier, context("DEFAULT")).action())
                .isEqualTo(PrivacyAction.REMOVE);
    }

    @Test
    @DisplayName("an unknown profile name fails rather than falling back to a default")
    void unknownProfileFails() {
        var resolver = resolver(profile(PrivacyProfile.UnclassifiedBehaviour.FAIL_REQUEST, Map.of()));

        // A typo in configuration must not silently change what is disclosed.
        assertThatThrownBy(() -> resolver.resolve(
                sensitive(null, DataClassification.PII), context("TYPOD")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("TYPOD");
    }

    @Test
    @DisplayName("the strictness order is total, so no action silently ranks weakest")
    void everyActionIsRanked() {
        for (PrivacyAction action : PrivacyAction.values()) {
            assertThat(ActionStrictness.rank(action)).isNotNegative();
        }
        assertThat(ActionStrictness.stricter(PrivacyAction.SYNTHESIZE, PrivacyAction.REDACT))
                .isEqualTo(PrivacyAction.REDACT);
        assertThat(ActionStrictness.stricter(PrivacyAction.PASS_THROUGH, PrivacyAction.GENERALIZE))
                .isEqualTo(PrivacyAction.GENERALIZE);
    }
}

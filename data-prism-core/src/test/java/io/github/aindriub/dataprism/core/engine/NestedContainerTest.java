package io.github.aindriub.dataprism.core.engine;

import io.github.aindriub.dataprism.annotations.DataClassification;
import io.github.aindriub.dataprism.annotations.InternalIdentifier;
import io.github.aindriub.dataprism.annotations.LlmExposedModel;
import io.github.aindriub.dataprism.annotations.NonSensitive;
import io.github.aindriub.dataprism.annotations.PrivacyAction;
import io.github.aindriub.dataprism.annotations.PrivacyNamespace;
import io.github.aindriub.dataprism.annotations.SensitiveData;
import io.github.aindriub.dataprism.annotations.SensitiveObject;
import io.github.aindriub.dataprism.core.model.PrivacyContext;
import io.github.aindriub.dataprism.core.model.PrivacyScopeType;
import io.github.aindriub.dataprism.core.model.PseudonymisationVersion;
import io.github.aindriub.dataprism.core.model.ScrubResult;
import io.github.aindriub.dataprism.core.policy.PrivacyProfile;
import io.github.aindriub.dataprism.core.policy.PrivacyProfiles;
import io.github.aindriub.dataprism.core.policy.ProfilePrivacyPolicyResolver;
import io.github.aindriub.dataprism.core.refusal.PrivacyRefusedException;
import io.github.aindriub.dataprism.core.spi.SyntheticValueSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * A field's own policy applies to the scalars of its value, never to an array nested inside its
 * array (task 267). All values are invented.
 */
class NestedContainerTest {

    private static final SyntheticValueSource SYNTHETICS =
            (subjectId, namespace, context) -> "synthetic:" + Integer.toHexString(subjectId.hashCode());

    @SensitiveObject
    record Leaf(@SensitiveData(classifications = DataClassification.PII,
            namespace = PrivacyNamespace.PERSON_NAME, suggestedAction = PrivacyAction.SYNTHESIZE) String name) {
    }

    @LlmExposedModel
    record Open(@InternalIdentifier String ref, @NonSensitive(reason = "x") List<List<String>> v) {
    }

    @LlmExposedModel
    record Redacted(@InternalIdentifier String ref,
                    @SensitiveData(classifications = DataClassification.CONFIDENTIAL,
                            namespace = PrivacyNamespace.PERSON_NAME, suggestedAction = PrivacyAction.REDACT)
                    List<List<String>> v) {
    }

    @LlmExposedModel
    record Records(@InternalIdentifier String ref, @NonSensitive(reason = "x") List<List<Leaf>> v) {
    }

    @LlmExposedModel
    record Anything(@InternalIdentifier String ref, @NonSensitive(reason = "x") List<Object> v) {
    }

    private static PrivacyContext context() {
        return new PrivacyContext("C", PrivacyScopeType.CASE, "DEFAULT", "test",
                Instant.parse("2030-01-01T00:00:00Z"), PseudonymisationVersion.HMAC_SHA256_V1);
    }

    private static JsonTreeScrubbingEngine engine(Map<String, PrivacyProfile> profiles) {
        return new JsonTreeScrubbingEngine(new DefaultFieldMetadataResolver(),
                new ProfilePrivacyPolicyResolver(profiles), SYNTHETICS);
    }

    private static JsonTreeScrubbingEngine defaultEngine() throws Exception {
        try (InputStream in = PrivacyProfiles.class.getResourceAsStream("/privacy-profiles-default.yaml")) {
            return engine(PrivacyProfiles.fromYaml(in));
        }
    }

    /** An array holding {@code levels} arrays, one inside the next, around one scalar. */
    private static Object nested(int levels) {
        Object value = "invented-scalar";
        for (int i = 0; i < levels; i++) {
            List<Object> wrapper = new ArrayList<>();
            wrapper.add(value);
            value = wrapper;
        }
        return value;
    }

    @Test
    @DisplayName("a @NonSensitive List<List<String>> is released exactly as before")
    void nonSensitiveScalarsAreUnchanged() throws Exception {
        ScrubResult result = defaultEngine().scrub(
                new Open("r", List.of(List.of("alpha", "beta"), List.of("gamma"))), context());

        assertThat(result.tree().get("v").toString()).isEqualTo("[[\"alpha\",\"beta\"],[\"gamma\"]]");
    }

    @Test
    @DisplayName("REDACT on List<List<String>> redacts each inner scalar")
    void redactApplyToEachInnerScalar() throws Exception {
        ScrubResult result = defaultEngine().scrub(
                new Redacted("r", List.of(List.of("alpha", "beta"), List.of("gamma"))), context());

        assertThat(result.tree().get("v").toString())
                .isEqualTo("[[\"[REDACTED]\",\"[REDACTED]\"],[\"[REDACTED]\"]]");
    }

    @Test
    @DisplayName("an array nested 17 levels deep under one field is refused with TOO_DEEP")
    void aSeventeenthLevelIsTooDeep() throws Exception {
        Throwable thrown = catchThrowable(() -> defaultEngine().scrub(new Anything("r", asList(nested(16))), context()));

        assertThat(thrown).isInstanceOfSatisfying(PrivacyRefusedException.class,
                e -> assertThat(e.code()).isEqualTo("TOO_DEEP"));
    }

    @Test
    @DisplayName("sixteen levels are still released")
    void sixteenLevelsAreAllowed() throws Exception {
        ScrubResult result = defaultEngine().scrub(new Anything("r", asList(nested(15))), context());

        assertThat(result.tree().get("v").toString()).contains("invented-scalar");
    }

    @SuppressWarnings("unchecked")
    private static List<Object> asList(Object nested) {
        return (List<Object>) nested;
    }

    @Test
    @DisplayName("an object inside an inner array is recorded at its pointer, one /* per level")
    void dispositionsNameTheInnerArrayPointer() {
        var profile = new PrivacyProfile("DEFAULT", PrivacyProfile.UnclassifiedBehaviour.REDACT_AND_WARN, Map.of());

        ScrubResult result = engine(Map.of("DEFAULT", profile))
                .scrub(new Records("r", List.of(List.of(new Leaf("Zelda Invented-Probe")))), context());

        assertThat(result.dispositions()).containsEntry("/v/*/*", PrivacyAction.REDACT);
        assertThat(result.tree().get("v").toString()).isEqualTo("[[\"[REDACTED]\"]]")
                .doesNotContain("Zelda");
    }
}

package io.github.aindriub.dataprism.core;

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

/** The per-field record of decisions the engine reports (task 93). */
class ScrubDispositionsTest {

    private static final String DISTINCTIVE = "zq-distinctive-synthetic-7431";

    @SensitiveObject
    record Contact(
            @SensitiveData(classifications = DataClassification.PII,
                    namespace = PrivacyNamespace.EMAIL,
                    suggestedAction = PrivacyAction.REDACT)
            String email,
            @NonSensitive(reason = "enumerated") String kind) {
    }

    @SensitiveObject
    record Header(@NonSensitive(reason = "enumerated") String state) {
    }

    @LlmExposedModel
    record Sample(
            @InternalIdentifier String subjectRef,
            @NonSensitive(reason = "structure reviewed separately") Header header,
            @NonSensitive(reason = "structure reviewed separately") List<Contact> contacts) {
    }

    @LlmExposedModel
    record Unclassified(@InternalIdentifier String subjectRef, String loose) {
    }

    @LlmExposedModel
    record Open(@InternalIdentifier String subjectRef,
                @com.fasterxml.jackson.annotation.JsonAnyGetter Map<String, String> extra) {
    }

    private static PrivacyContext context() {
        return new PrivacyContext("C", PrivacyScopeType.CASE, "DEFAULT", "test",
                Instant.parse("2030-01-01T00:00:00Z"), PseudonymisationVersion.HMAC_SHA256_V1);
    }

    private static JsonTreeScrubbingEngine engine() {
        var profile = new PrivacyProfile("DEFAULT", PrivacyProfile.UnclassifiedBehaviour.FAIL_REQUEST,
                Map.of(DataClassification.PII, PrivacyProfile.ClassificationRule.of(PrivacyAction.REDACT)));
        return new JsonTreeScrubbingEngine(new DefaultFieldMetadataResolver(),
                new ProfilePrivacyPolicyResolver(Map.of("DEFAULT", profile)),
                (subject, namespace, ctx) -> "synthetic");
    }

    @Test
    @DisplayName("reports one disposition per resolved field, array indices collapsed")
    void exactMap() {
        var source = new Sample("s-1", new Header("OPEN"),
                List.of(new Contact("a@example.invalid", "WORK"), new Contact("b@example.invalid", "HOME")));

        ScrubResult result = engine().scrub(source, context());

        assertThat(result.dispositions()).containsExactlyEntriesOf(new java.util.TreeMap<>(Map.of(
                "/subjectRef", PrivacyAction.REMOVE,
                "/header", PrivacyAction.PASS_THROUGH,
                "/header/state", PrivacyAction.PASS_THROUGH,
                "/contacts", PrivacyAction.PASS_THROUGH,
                "/contacts/*/email", PrivacyAction.REDACT,
                "/contacts/*/kind", PrivacyAction.PASS_THROUGH)));
        assertThat(result.dispositions()).isUnmodifiable();
    }

    @Test
    @DisplayName("payload values appear in no key, no value and not in toString")
    void noPayloadValues() {
        var source = new Sample(DISTINCTIVE, new Header(DISTINCTIVE),
                List.of(new Contact(DISTINCTIVE, DISTINCTIVE)));

        ScrubResult result = engine().scrub(source, context());

        assertThat(result.dispositions().keySet()).noneMatch(k -> k.contains(DISTINCTIVE));
        assertThat(result.dispositions().values().toString()).doesNotContain(DISTINCTIVE);
        assertThat(result.toString()).doesNotContain(DISTINCTIVE);
    }

    @Test
    @DisplayName("the two-argument constructor yields an empty map")
    void twoArgumentConstructor() {
        assertThat(new ScrubResult(SourceTree.newObject(), java.util.Set.of()).dispositions()).isEmpty();
    }

    @Test
    @DisplayName("a refusal still throws with its path and returns nothing")
    void refusalUnchanged() {
        assertThatThrownBy(() -> engine().scrub(new Unclassified("s-1", "x"), context()))
                .isInstanceOf(PrivacyRefusedException.class)
                .extracting(e -> ((PrivacyRefusedException) e).path())
                .isEqualTo("$.loose");
    }

    @Test
    @DisplayName("an undeclared property's payload-supplied name never reaches dispositions")
    void undeclaredKeyNotRecorded() {
        String key = "alice@example.com";
        var profile = new PrivacyProfile("DEFAULT", PrivacyProfile.UnclassifiedBehaviour.REDACT_AND_WARN,
                Map.of());
        var engine = new JsonTreeScrubbingEngine(new DefaultFieldMetadataResolver(),
                new ProfilePrivacyPolicyResolver(Map.of("DEFAULT", profile)),
                (subject, namespace, ctx) -> "synthetic");

        ScrubResult result = engine.scrub(new Open("s-1", Map.of(key, "x")), context());

        assertThat(result.dispositions()).containsEntry("/<undeclared>", PrivacyAction.REDACT);
        assertThat(result.dispositions().keySet()).noneMatch(k -> k.contains(key));
        assertThat(result.toString()).doesNotContain(key);
    }
}

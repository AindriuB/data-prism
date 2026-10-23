package io.github.aindriub.dataprism.core.engine;

import io.github.aindriub.dataprism.annotations.Chainable;
import io.github.aindriub.dataprism.annotations.DataClassification;
import io.github.aindriub.dataprism.annotations.InternalIdentifier;
import io.github.aindriub.dataprism.annotations.LlmExposedModel;
import io.github.aindriub.dataprism.annotations.NonSensitive;
import io.github.aindriub.dataprism.annotations.PrivacyAction;
import io.github.aindriub.dataprism.annotations.PrivacyNamespace;
import io.github.aindriub.dataprism.annotations.SensitiveData;
import io.github.aindriub.dataprism.annotations.SensitiveObject;
import io.github.aindriub.dataprism.core.model.FieldProtection;
import io.github.aindriub.dataprism.core.model.FieldProtection.Protection;
import io.github.aindriub.dataprism.core.model.ModelDescription;
import io.github.aindriub.dataprism.core.model.ModelDescription.Undeclared;
import io.github.aindriub.dataprism.core.model.PrivacyContext;
import io.github.aindriub.dataprism.core.model.PrivacyScopeType;
import io.github.aindriub.dataprism.core.model.PseudonymisationVersion;
import io.github.aindriub.dataprism.core.policy.PrivacyProfile;
import io.github.aindriub.dataprism.core.policy.PrivacyProfile.UnclassifiedBehaviour;
import io.github.aindriub.dataprism.core.policy.PrivacyProfiles;
import io.github.aindriub.dataprism.core.policy.ProfilePrivacyPolicyResolver;
import io.github.aindriub.dataprism.core.spi.KeyNamespace;
import io.github.aindriub.dataprism.core.spi.ScrubbingEngine;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** What {@code describe} says about each field, computed from the plan the scrubber applies (task 248). */
class ScrubPlanDescriberTest {

    @SensitiveObject
    record Inner(
            @SensitiveData(classifications = DataClassification.PII,
                    namespace = PrivacyNamespace.PERSON_NAME,
                    suggestedAction = PrivacyAction.SYNTHESIZE) String innerName,
            @NonSensitive(reason = "enumerated") String innerState) {
    }

    @LlmExposedModel
    record Parcel(
            @InternalIdentifier @Chainable(keyNamespace = "parcel-ref") String parcel,
            @SensitiveData(classifications = DataClassification.PII,
                    namespace = PrivacyNamespace.PERSON_NAME,
                    suggestedAction = PrivacyAction.SYNTHESIZE) String recipientName,
            @SensitiveData(classifications = DataClassification.CONTACT,
                    namespace = PrivacyNamespace.EMAIL,
                    suggestedAction = PrivacyAction.REDACT) String recipientEmail,
            @SensitiveData(classifications = DataClassification.CONFIDENTIAL,
                    suggestedAction = PrivacyAction.REMOVE) String note,
            @NonSensitive(reason = "enumerated") String state,
            @NonSensitive(reason = "reviewed separately") Inner details,
            @NonSensitive(reason = "reviewed separately") List<Inner> history,
            @NonSensitive(reason = "enumerated") List<String> tags) {
    }

    @LlmExposedModel
    record Unfinished(
            @InternalIdentifier String ref,
            String unclassified,
            @NonSensitive(reason = "enumerated") String state) {
    }

    record Unreviewed(String a, String b) {
    }

    @LlmExposedModel
    record WithStructures(
            @InternalIdentifier String ref,
            @NonSensitive(reason = "believed inert") Unreviewed block,
            @NonSensitive(reason = "believed inert") Map<String, String> extras) {
    }

    @LlmExposedModel
    record NoSubject(
            @SensitiveData(classifications = DataClassification.PII,
                    namespace = PrivacyNamespace.PERSON_NAME,
                    suggestedAction = PrivacyAction.SYNTHESIZE) String fullName,
            @NonSensitive(reason = "enumerated") String state) {
    }

    @LlmExposedModel
    record NeedsBands(
            @InternalIdentifier String ref,
            @SensitiveData(classifications = DataClassification.CONFIDENTIAL,
                    namespace = PrivacyNamespace.FINANCIAL_VALUE,
                    suggestedAction = PrivacyAction.GENERALIZE) String amount,
            @NonSensitive(reason = "enumerated") String state) {
    }

    @LlmExposedModel
    record Tree(
            @InternalIdentifier @Chainable(keyNamespace = "node-ref") String id,
            @NonSensitive(reason = "reviewed separately") List<Tree> children) {
    }

    private static final Map<String, PrivacyProfile> BUNDLED = bundled();

    private static Map<String, PrivacyProfile> bundled() {
        try (var in = ScrubPlanDescriberTest.class.getResourceAsStream("/privacy-profiles-default.yaml")) {
            return PrivacyProfiles.fromYaml(in);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static PrivacyContext context(String profile) {
        return new PrivacyContext("C", PrivacyScopeType.CASE, profile, "test",
                Instant.parse("2030-01-01T00:00:00Z"), PseudonymisationVersion.HMAC_SHA256_V1);
    }

    private static JsonTreeScrubbingEngine engine(Map<String, PrivacyProfile> profiles) {
        return new JsonTreeScrubbingEngine(new DefaultFieldMetadataResolver(),
                new ProfilePrivacyPolicyResolver(profiles), (s, ns, c) -> "syn-" + ns);
    }

    private static ModelDescription describe(Class<?> model, String profile, boolean links) {
        return engine(BUNDLED).describe(model, context(profile), links).orElseThrow();
    }

    private static Map<String, Protection> byPath(ModelDescription d) {
        Map<String, Protection> out = new java.util.LinkedHashMap<>();
        d.fields().forEach(f -> out.put(f.path(), f.protection()));
        return out;
    }

    @Test
    @DisplayName("each effective action maps to its protection, nested and array paths use []")
    void mapsActions() {
        ModelDescription d = describe(Parcel.class, "DEFAULT", true);

        assertThat(byPath(d)).containsExactly(
                Map.entry("details.innerName", Protection.SYNTHETIC),
                Map.entry("details.innerState", Protection.VERBATIM),
                Map.entry("history[].innerName", Protection.SYNTHETIC),
                Map.entry("history[].innerState", Protection.VERBATIM),
                Map.entry("parcel", Protection.ABSENT),
                Map.entry("recipientEmail", Protection.MARKER),
                Map.entry("recipientName", Protection.SYNTHETIC),
                Map.entry("state", Protection.VERBATIM),
                Map.entry("tags[]", Protection.VERBATIM));
        assertThat(d.undeclared()).isEqualTo(Undeclared.REFUSE);
    }

    @Test
    @DisplayName("a chainable field is listed with its key namespace while links are enabled, even when removed")
    void chainableListed() {
        ModelDescription d = describe(Parcel.class, "DEFAULT", true);

        assertThat(d.fields()).contains(
                new FieldProtection("parcel", Protection.ABSENT, null, new KeyNamespace("parcel-ref")));
        assertThat(d.fields().stream().filter(f -> f.keyNamespace() != null)).hasSize(1);
    }

    @Test
    @DisplayName("with links disabled no key namespace appears and removed fields are omitted")
    void linksDisabled() {
        ModelDescription d = describe(Parcel.class, "DEFAULT", false);

        assertThat(d.fields()).noneMatch(f -> f.keyNamespace() != null);
        assertThat(d.fields()).noneMatch(f -> f.protection() == Protection.ABSENT);
        assertThat(byPath(d)).doesNotContainKeys("parcel", "note");
    }

    @Test
    @DisplayName("a synthetic field carries its value namespace, no other protection does")
    void valueNamespace() {
        ModelDescription d = describe(Parcel.class, "DEFAULT", true);

        assertThat(d.fields()).contains(
                new FieldProtection("recipientName", Protection.SYNTHETIC, "PERSON_NAME", null));
        assertThat(d.fields()).filteredOn(f -> f.protection() != Protection.SYNTHETIC
                && f.protection() != Protection.TOKEN).allMatch(f -> f.valueNamespace() == null);
    }

    @Test
    @DisplayName("the profile tightens the description: STRICT turns SYNTHETIC into MARKER")
    void profileTightens() {
        Map<String, Protection> strict = byPath(describe(Parcel.class, "STRICT", true));

        assertThat(strict).containsEntry("recipientName", Protection.MARKER)
                .containsEntry("details.innerName", Protection.MARKER)
                .containsEntry("state", Protection.VERBATIM);
    }

    @Test
    @DisplayName("a field the scrubber would refuse is omitted, never described as VERBATIM")
    void refusedOmitted() {
        ModelDescription d = describe(Unfinished.class, "DEFAULT", true);

        assertThat(byPath(d)).doesNotContainKey("unclassified");
        assertThat(byPath(d)).containsEntry("state", Protection.VERBATIM);
    }

    @Test
    @DisplayName("an action the scrubber cannot apply is omitted: GENERALIZE with no rule, SYNTHESIZE with no subject")
    void unappliableOmitted() {
        var noBands = new PrivacyProfile("DEFAULT", UnclassifiedBehaviour.FAIL_REQUEST, Map.of());

        ModelDescription bands = engine(Map.of("DEFAULT", noBands))
                .describe(NeedsBands.class, context("DEFAULT"), true).orElseThrow();
        ModelDescription subjectless = describe(NoSubject.class, "DEFAULT", true);

        assertThat(byPath(bands)).doesNotContainKey("amount").containsKey("state");
        assertThat(byPath(subjectless)).doesNotContainKey("fullName").containsKey("state");
    }

    @Test
    @DisplayName("an unclassified structure takes the profile's unclassified setting at the field's path")
    void unclassifiedStructure() {
        var redacting = new PrivacyProfile("DEFAULT", UnclassifiedBehaviour.REDACT_AND_WARN, Map.of());

        Map<String, Protection> redact = byPath(engine(Map.of("DEFAULT", redacting))
                .describe(WithStructures.class, context("DEFAULT"), false).orElseThrow());
        Map<String, Protection> refuse = byPath(describe(WithStructures.class, "DEFAULT", false));

        assertThat(redact).containsEntry("block", Protection.MARKER).containsEntry("extras", Protection.MARKER);
        assertThat(refuse).doesNotContainKeys("block", "extras");
    }

    @Test
    @DisplayName("a model containing itself lists only the shallowest path")
    void selfReferencing() {
        ModelDescription d = describe(Tree.class, "DEFAULT", true);

        assertThat(d.fields()).containsExactly(
                new FieldProtection("id", Protection.ABSENT, null, new KeyNamespace("node-ref")));
    }

    @Test
    @DisplayName("undeclared follows the profile's unclassified setting; a setting that releases them cannot be described")
    void undeclared() {
        for (var e : Map.of(UnclassifiedBehaviour.FAIL_REQUEST, Undeclared.REFUSE,
                UnclassifiedBehaviour.REDACT_AND_WARN, Undeclared.RENAME,
                UnclassifiedBehaviour.DROP_AND_WARN, Undeclared.DROP).entrySet()) {
            var profile = new PrivacyProfile("DEFAULT", e.getKey(), Map.of());
            assertThat(engine(Map.of("DEFAULT", profile))
                    .describe(NoSubject.class, context("DEFAULT"), false).orElseThrow().undeclared())
                    .isEqualTo(e.getValue());
        }
        var open = new PrivacyProfile("DEFAULT", UnclassifiedBehaviour.PASS_THROUGH_UNSAFE, Map.of());
        assertThat(engine(Map.of("DEFAULT", open)).describe(NoSubject.class, context("DEFAULT"), false)).isEmpty();
    }

    @Test
    @DisplayName("an engine that does not override describe, an unexposed model and an unknown profile give empty")
    void cannotDescribe() {
        ScrubbingEngine plain = (source, ctx) -> null;

        assertThat(plain.describe(Parcel.class, context("DEFAULT"), true)).isEmpty();
        assertThat(engine(BUNDLED).describe(Unreviewed.class, context("DEFAULT"), true)).isEmpty();
        assertThat(engine(BUNDLED).describe(Parcel.class, context("NOPE"), true)).isEmpty();
    }

    @Test
    @DisplayName("a description names no value, classification, model class or package")
    void leaksNothing() throws Exception {
        Optional<ModelDescription> d = engine(BUNDLED).describe(Parcel.class, context("DEFAULT"), true);
        String shown = d.orElseThrow().toString();
        String json = JsonMapper.builder().build().writeValueAsString(d.orElseThrow());

        for (String text : List.of(shown, json)) {
            assertThat(text).doesNotContain("Parcel", "Inner", "ScrubPlanDescriberTest", "io.github",
                    "PII", "CONTACT", "CONFIDENTIAL", "dataprism");
        }
    }
}

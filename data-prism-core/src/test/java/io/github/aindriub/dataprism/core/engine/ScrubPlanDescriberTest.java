package io.github.aindriub.dataprism.core.engine;

import com.fasterxml.jackson.annotation.JsonValue;
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
import io.github.aindriub.dataprism.core.descriptor.DescriptorFieldMetadataResolver;
import io.github.aindriub.dataprism.core.descriptor.ModelDescriptor;
import io.github.aindriub.dataprism.core.spi.ValueTokenSource;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.ser.std.ToStringSerializer;

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

    @SensitiveObject
    record Email(
            @SensitiveData(classifications = DataClassification.CONTACT,
                    namespace = PrivacyNamespace.EMAIL,
                    suggestedAction = PrivacyAction.REDACT) String value) {
        @JsonValue
        public String value() {
            return value;
        }
    }

    record Code(String v) {
        @JsonValue
        public String v() {
            return v;
        }
    }

    record Plain(String v) {
    }

    @LlmExposedModel
    record Holder(
            @InternalIdentifier String ref,
            @NonSensitive(reason = "wrapper") Email email,
            @NonSensitive(reason = "wrapper") Code code,
            @NonSensitive(reason = "wrapper") @JsonSerialize(using = ToStringSerializer.class) Plain plain,
            @SensitiveData(classifications = DataClassification.CONTACT,
                    namespace = PrivacyNamespace.EMAIL,
                    suggestedAction = PrivacyAction.REDACT) Code redactedCode) {
    }

    @LlmExposedModel
    record Blobs(
            @InternalIdentifier String ref,
            @NonSensitive(reason = "bytes") byte[] blob,
            @NonSensitive(reason = "grid") Object[][] grid,
            @NonSensitive(reason = "rows") List<Object[]> rows) {
    }

    @LlmExposedModel
    record Hashed(
            @InternalIdentifier String ref,
            @SensitiveData(classifications = DataClassification.PII,
                    namespace = PrivacyNamespace.PERSON_IDENTITY,
                    suggestedAction = PrivacyAction.TOKENIZE) String handle,
            @NonSensitive(reason = "enumerated") String state) {
    }

    @LlmExposedModel
    record Plainly(@InternalIdentifier String ref, @NonSensitive(reason = "enumerated") String state) {
    }

    /** Each level holds the next twice, so a walk of the top costs 2^14 type visits. */
    static final class Diamond {
        private Diamond() {
        }

        @SensitiveObject
        record D0(@NonSensitive(reason = "x") String v) {
        }

        @SensitiveObject
        record D1(@NonSensitive(reason = "x") D0 a, @NonSensitive(reason = "x") D0 b) {
        }

        @SensitiveObject
        record D2(@NonSensitive(reason = "x") D1 a, @NonSensitive(reason = "x") D1 b) {
        }

        @LlmExposedModel
        record D3(@NonSensitive(reason = "x") D2 a, @NonSensitive(reason = "x") D2 b) {
        }

        @SensitiveObject
        record D4(@NonSensitive(reason = "x") D3 a, @NonSensitive(reason = "x") D3 b) {
        }

        @SensitiveObject
        record D5(@NonSensitive(reason = "x") D4 a, @NonSensitive(reason = "x") D4 b) {
        }

        @SensitiveObject
        record D6(@NonSensitive(reason = "x") D5 a, @NonSensitive(reason = "x") D5 b) {
        }

        @SensitiveObject
        record D7(@NonSensitive(reason = "x") D6 a, @NonSensitive(reason = "x") D6 b) {
        }

        @SensitiveObject
        record D8(@NonSensitive(reason = "x") D7 a, @NonSensitive(reason = "x") D7 b) {
        }

        @SensitiveObject
        record D9(@NonSensitive(reason = "x") D8 a, @NonSensitive(reason = "x") D8 b) {
        }

        @SensitiveObject
        record D10(@NonSensitive(reason = "x") D9 a, @NonSensitive(reason = "x") D9 b) {
        }

        @SensitiveObject
        record D11(@NonSensitive(reason = "x") D10 a, @NonSensitive(reason = "x") D10 b) {
        }

        @SensitiveObject
        record D12(@NonSensitive(reason = "x") D11 a, @NonSensitive(reason = "x") D11 b) {
        }

        @SensitiveObject
        record D13(@NonSensitive(reason = "x") D12 a, @NonSensitive(reason = "x") D12 b) {
        }

        @LlmExposedModel
        record D14(@NonSensitive(reason = "x") D13 a, @NonSensitive(reason = "x") D13 b) {
        }

    }

    interface HasValue {
        @JsonValue
        String value();
    }

    @SensitiveObject
    record IEmail(
            @SensitiveData(classifications = DataClassification.CONTACT,
                    namespace = PrivacyNamespace.EMAIL,
                    suggestedAction = PrivacyAction.REDACT) String value) implements HasValue {
    }

    @LlmExposedModel
    record IHolder(@InternalIdentifier String ref, @NonSensitive(reason = "wrapper") IEmail email) {
    }

    @SensitiveObject
    record AInner(@NonSensitive(reason = "enumerated") String a) {
    }

    @LlmExposedModel
    record AsHolder(
            @InternalIdentifier String ref,
            @SensitiveData(classifications = DataClassification.CONFIDENTIAL,
                    suggestedAction = PrivacyAction.REDACT)
            @JsonSerialize(typing = JsonSerialize.Typing.STATIC) AInner details) {
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

    @Test
    @DisplayName("a record that serialises as a scalar is not described by its components")
    void scalarRecords() {
        Map<String, Protection> d = byPath(describe(Holder.class, "DEFAULT", false));

        assertThat(d).doesNotContainKeys("email", "email.value", "code", "code.v", "plain", "plain.v");
    }

    @Test
    @DisplayName("a scalar-serialised record is described when the scalar and structure readings agree")
    void scalarRecordsAgreeing() {
        var profile = new PrivacyProfile("DEFAULT", UnclassifiedBehaviour.REDACT_AND_WARN, Map.of(
                DataClassification.CONTACT, PrivacyProfile.ClassificationRule.of(PrivacyAction.REDACT)));
        Map<String, Protection> d = byPath(engine(Map.of("DEFAULT", profile))
                .describe(Holder.class, context("DEFAULT"), false).orElseThrow());

        assertThat(d).containsEntry("redactedCode", Protection.MARKER).doesNotContainKeys("email", "code");
    }

    @Test
    @DisplayName("a descriptor-declared chainable field carries its key namespace with no annotation")
    void descriptorChainable() {
        String name = Plainly.class.getName();
        Map<String, ModelDescriptor> descriptors = Map.of(name, new ModelDescriptor(name, null, null, null,
                Map.of("ref", new ModelDescriptor.FieldDescriptor(List.of(), null, null, null, null, null,
                        true, "parcel-ref"))));
        var engine = new JsonTreeScrubbingEngine(
                new DescriptorFieldMetadataResolver(new DefaultFieldMetadataResolver(), descriptors),
                new ProfilePrivacyPolicyResolver(BUNDLED), (s, ns, c) -> "syn", ValueTokenSource.unavailable(),
                descriptors);

        assertThat(engine.describe(Plainly.class, context("DEFAULT"), true).orElseThrow().fields()).contains(
                new FieldProtection("ref", Protection.ABSENT, null, new KeyNamespace("parcel-ref")));
        assertThat(engine.describe(Plainly.class, context("DEFAULT"), false).orElseThrow().fields())
                .noneMatch(f -> f.keyNamespace() != null);
    }

    @Test
    @DisplayName("HASH and TOKENIZE are omitted when the engine has no token source")
    void noTokenSource() {
        var strictest = new PrivacyProfile("DEFAULT", UnclassifiedBehaviour.FAIL_REQUEST, Map.of(
                DataClassification.PII, PrivacyProfile.ClassificationRule.of(PrivacyAction.TOKENIZE)));
        var tokenising = Map.of("DEFAULT", strictest);

        var noSource = new JsonTreeScrubbingEngine(new DefaultFieldMetadataResolver(),
                new ProfilePrivacyPolicyResolver(tokenising), (s, ns, c) -> "syn");
        var hasSource = new JsonTreeScrubbingEngine(new DefaultFieldMetadataResolver(),
                new ProfilePrivacyPolicyResolver(tokenising), (s, ns, c) -> "syn", new ValueTokenSource() {
                    @Override
                    public String hash(String v, PrivacyNamespace n, PrivacyContext c) {
                        return "h";
                    }

                    @Override
                    public String token(String v, PrivacyNamespace n, PrivacyContext c) {
                        return "t";
                    }
                });

        assertThat(byPath(noSource.describe(Hashed.class, context("DEFAULT"), false).orElseThrow()))
                .doesNotContainKey("handle").containsKey("state");
        assertThat(byPath(hasSource.describe(Hashed.class, context("DEFAULT"), false).orElseThrow()))
                .containsEntry("handle", Protection.TOKEN);
    }

    @Test
    @DisplayName("bytes are one scalar, and a nested array is not described with the field's policy")
    void bytesAndNestedArrays() {
        Map<String, Protection> d = byPath(describe(Blobs.class, "DEFAULT", false));

        assertThat(d).containsEntry("blob", Protection.VERBATIM).doesNotContainKey("blob[]");
        assertThat(d).doesNotContainKeys("grid", "grid[]", "rows", "rows[]");
    }

    @Test
    @DisplayName("a model too large to walk gives no description")
    void visitCap() {
        assertThat(engine(BUNDLED).describe(Diamond.D14.class, context("DEFAULT"), false)).isEmpty();
        assertThat(engine(BUNDLED).describe(Diamond.D3.class, context("DEFAULT"), false)).isPresent();
    }

    @Test
    @DisplayName("a @JsonValue inherited from an interface makes a record scalar-serialised")
    void inheritedJsonValue() {
        assertThat(byPath(describe(IHolder.class, "DEFAULT", false))).doesNotContainKeys("email", "email.value");
    }

    @Test
    @DisplayName("a descendable type carrying any @JsonSerialize is omitted, not compared with the structure reading")
    void descendableWithJsonSerialize() {
        var profile = new PrivacyProfile("DEFAULT", UnclassifiedBehaviour.REDACT_AND_WARN, Map.of(
                DataClassification.CONFIDENTIAL, PrivacyProfile.ClassificationRule.of(PrivacyAction.REDACT)));

        Map<String, Protection> d = byPath(engine(Map.of("DEFAULT", profile))
                .describe(AsHolder.class, context("DEFAULT"), false).orElseThrow());

        assertThat(d).doesNotContainKeys("details", "details.a");
    }
}

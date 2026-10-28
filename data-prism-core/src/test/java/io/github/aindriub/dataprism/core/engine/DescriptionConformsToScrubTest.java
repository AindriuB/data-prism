package io.github.aindriub.dataprism.core.engine;

import io.github.aindriub.dataprism.annotations.DataClassification;
import io.github.aindriub.dataprism.annotations.InternalIdentifier;
import io.github.aindriub.dataprism.annotations.LlmExposedModel;
import io.github.aindriub.dataprism.annotations.NonSensitive;
import io.github.aindriub.dataprism.annotations.PrivacyAction;
import io.github.aindriub.dataprism.annotations.PrivacyNamespace;
import io.github.aindriub.dataprism.annotations.SensitiveData;
import io.github.aindriub.dataprism.annotations.SensitiveObject;
import io.github.aindriub.dataprism.core.descriptor.DescriptorFieldMetadataResolver;
import io.github.aindriub.dataprism.core.descriptor.ModelDescriptor;
import io.github.aindriub.dataprism.core.model.FieldProtection;
import io.github.aindriub.dataprism.core.model.FieldProtection.Protection;
import io.github.aindriub.dataprism.core.model.ModelDescription;
import io.github.aindriub.dataprism.core.model.ScrubResult;
import io.github.aindriub.dataprism.core.policy.PrivacyProfile;
import io.github.aindriub.dataprism.core.policy.PrivacyProfiles;
import io.github.aindriub.dataprism.core.policy.ProfilePrivacyPolicyResolver;
import io.github.aindriub.dataprism.core.spi.FieldMetadataResolver;
import io.github.aindriub.dataprism.core.spi.ValueTokenSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonValue;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueSerializer;
import tools.jackson.databind.annotation.JsonSerialize;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * The description is the scrub plan, not a second reading of the annotations: every path a scrubbed
 * tree contains is described with the protection it was scrubbed under, and nothing described
 * {@code VERBATIM} is ever changed (task 248).
 */
class DescriptionConformsToScrubTest {

    private static final ValueTokenSource TOKENS = new ValueTokenSource() {
        @Override
        public String hash(String value, PrivacyNamespace namespace, io.github.aindriub.dataprism.core.model.PrivacyContext c) {
            return "h-" + namespace;
        }

        @Override
        public String token(String value, PrivacyNamespace namespace, io.github.aindriub.dataprism.core.model.PrivacyContext c) {
            return "t-" + namespace;
        }

        @Override
        public boolean available() {
            return true;
        }
    };

    private static Map<String, PrivacyProfile> profiles() {
        try (var in = DescriptionConformsToScrubTest.class.getResourceAsStream("/privacy-profiles-default.yaml")) {
            return PrivacyProfiles.fromYaml(in);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static JsonTreeScrubbingEngine engine(FieldMetadataResolver resolver) {
        return new JsonTreeScrubbingEngine(resolver, new ProfilePrivacyPolicyResolver(profiles()),
                (s, ns, c) -> "syn-" + ns, TOKENS);
    }

    private static ScrubPlanDescriberTest.Parcel parcel() {
        var inner = new ScrubPlanDescriberTest.Inner("Ada Example", "OPEN");
        return new ScrubPlanDescriberTest.Parcel("p-1", "Ada Example", "ada@example.invalid", "a note",
                "OPEN", inner, List.of(inner, new ScrubPlanDescriberTest.Inner("Bo Example", "SHUT")),
                List.of("x", "y"));
    }

    /** Tightens one VERBATIM field to REDACT and removes another: the annotations alone say otherwise. */
    private static Map<String, ModelDescriptor> tightening() {
        var parcel = ScrubPlanDescriberTest.Parcel.class.getName();
        var inner = ScrubPlanDescriberTest.Inner.class.getName();
        return Map.of(
                parcel, new ModelDescriptor(parcel, null, null, null, Map.of(
                        "state", new ModelDescriptor.FieldDescriptor(List.of(DataClassification.CONTACT),
                                PrivacyNamespace.EMAIL, PrivacyAction.REDACT, null, null, null))),
                inner, new ModelDescriptor(inner, null, null, null, Map.of(
                        "innerState", new ModelDescriptor.FieldDescriptor(
                                List.of(DataClassification.CONFIDENTIAL), null, PrivacyAction.REMOVE,
                                null, null, null))));
    }

    private static Protection protection(PrivacyAction action) {
        return switch (action) {
            case PASS_THROUGH -> Protection.VERBATIM;
            case SYNTHESIZE -> Protection.SYNTHETIC;
            case TOKENIZE, HASH -> Protection.TOKEN;
            case REDACT -> Protection.MARKER;
            case GENERALIZE -> Protection.BUCKET;
            case REMOVE -> Protection.ABSENT;
        };
    }

    /** The leaf paths of a tree, in the description grammar, with every value found at each. */
    private static void leaves(JsonNode node, String path, Map<String, List<JsonNode>> out) {
        if (node.isObject()) {
            node.properties().forEach(e -> leaves(e.getValue(), path.isEmpty() ? e.getKey() : path + "." + e.getKey(), out));
        } else if (node.isArray()) {
            node.forEach(child -> leaves(child, path + "[]", out));
        } else {
            out.computeIfAbsent(path, k -> new ArrayList<>()).add(node);
        }
    }

    private static String pointer(String path) {
        return "/" + path.replace(".", "/").replace("[]", "/*");
    }

    /** A scalar in an array has no disposition of its own; the field's is the one it was scrubbed under. */
    private static PrivacyAction disposition(ScrubResult scrubbed, String path) {
        PrivacyAction action = scrubbed.dispositions().get(pointer(path));
        return action == null && path.endsWith("[]")
                ? scrubbed.dispositions().get(pointer(path.substring(0, path.length() - 2)))
                : action;
    }

    private static void assertConforms(FieldMetadataResolver resolver, String profile, Object source) {
        assertConforms(resolver, profile, source, true);
    }

    /**
     * @param complete whether every scrubbed path must be described; false for a shape whose type
     *                 leaves the reading ambiguous, where omission is the answer but a description must
     *                 still never contradict the scrub
     */
    private static void assertConforms(FieldMetadataResolver resolver, String profile, Object source,
                                       boolean complete) {
        assertConforms(engine(resolver), profile, source, complete);
    }

    private static void assertConforms(JsonTreeScrubbingEngine engine, String profile, Object source,
                                       boolean complete) {
        var context = ScrubPlanDescriberTest.context(profile);
        ScrubResult scrubbed = engine.scrub(source, context);
        ModelDescription description = engine.describe(source.getClass(), context, true).orElseThrow();
        Map<String, FieldProtection> described = new java.util.TreeMap<>();
        description.fields().forEach(f -> described.put(f.path(), f));

        Map<String, List<JsonNode>> present = new java.util.TreeMap<>();
        leaves(scrubbed.tree(), "", present);
        Map<String, List<JsonNode>> original = new java.util.TreeMap<>();
        leaves(SourceTree.of(source), "", original);
        assertThat(present).isNotEmpty();

        List<String> problems = new ArrayList<>();
        present.forEach((path, values) -> {
            FieldProtection field = described.get(path);
            PrivacyAction action = disposition(scrubbed, path);
            if (field == null && !complete) {
                return;
            }
            if (field == null || action == null || field.protection() != protection(action)) {
                problems.add(path + " scrubbed under " + action + " described as " + field);
            }
        });
        described.forEach((path, field) -> {
            if (field.protection() == Protection.ABSENT && present.containsKey(path)) {
                problems.add(path + " described ABSENT but present");
            }
            if (field.protection() != Protection.ABSENT && !present.containsKey(path)) {
                problems.add(path + " described " + field.protection() + " but not present");
            }
            if (field.protection() == Protection.VERBATIM && present.containsKey(path)
                    && !texts(present.get(path)).equals(texts(original.get(path)))) {
                problems.add(path + " described VERBATIM but changed");
            }
        });
        assertThat(problems).isEmpty();
    }

    /** Compared as text: a POJO node (bytes) has no value equality across two readings. */
    private static List<String> texts(List<JsonNode> nodes) {
        return nodes.stream().map(JsonNode::toString).toList();
    }

    private static final List<String> BOTH = List.of("DEFAULT", "STRICT");

    @Test
    @DisplayName("annotated, nested and array models conform under both bundled profiles")
    void annotated() {
        for (String profile : BOTH) {
            assertConforms(new DefaultFieldMetadataResolver(), profile, parcel());
        }
    }

    @Test
    @DisplayName("descriptor-tightened models conform under both bundled profiles")
    void descriptorTightened() {
        var resolver = new DescriptorFieldMetadataResolver(new DefaultFieldMetadataResolver(), tightening());
        for (String profile : BOTH) {
            assertConforms(resolver, profile, parcel());
        }
    }

    @Test
    @DisplayName("a descriptor that tightens a field changes its description (kills describing from the annotation)")
    void descriptorChangesDescription() {
        var context = ScrubPlanDescriberTest.context("DEFAULT");
        var plain = engine(new DefaultFieldMetadataResolver())
                .describe(ScrubPlanDescriberTest.Parcel.class, context, false).orElseThrow();
        var tightened = engine(new DescriptorFieldMetadataResolver(new DefaultFieldMetadataResolver(), tightening()))
                .describe(ScrubPlanDescriberTest.Parcel.class, context, false).orElseThrow();

        assertThat(plain.fields()).contains(new FieldProtection("state", Protection.VERBATIM, null, null));
        assertThat(tightened.fields()).contains(new FieldProtection("state", Protection.MARKER, null, null))
                .doesNotContain(new FieldProtection("state", Protection.VERBATIM, null, null));
        assertThat(tightened.fields()).extracting(FieldProtection::path)
                .doesNotContain("details.innerState", "history[].innerState");
        assertThat(plain.fields()).extracting(FieldProtection::path)
                .contains("details.innerState", "history[].innerState");
    }

    @Test
    @DisplayName("the profile changes the description: the same model is described differently under STRICT")
    void profileChangesDescription() {
        var engine = engine(new DefaultFieldMetadataResolver());
        var normal = engine.describe(ScrubPlanDescriberTest.Parcel.class,
                ScrubPlanDescriberTest.context("DEFAULT"), false).orElseThrow();
        var strict = engine.describe(ScrubPlanDescriberTest.Parcel.class,
                ScrubPlanDescriberTest.context("STRICT"), false).orElseThrow();

        // STRICT maps PII to REDACT where DEFAULT synthesises it; recipientName is a PII field.
        assertThat(normal.fields()).extracting(FieldProtection::path, FieldProtection::protection)
                .contains(tuple("recipientName", Protection.SYNTHETIC));
        assertThat(strict.fields()).extracting(FieldProtection::path, FieldProtection::protection)
                .contains(tuple("recipientName", Protection.MARKER))
                .doesNotContain(tuple("recipientName", Protection.SYNTHETIC));
    }

    @Test
    @DisplayName("bytes and nested arrays, which stay dynamic, never contradict the scrub")
    void dynamicShapes() {
        var blobs = new ScrubPlanDescriberTest.Blobs("b-1", new byte[] {1, 2, 3},
                new Object[][] {{"a", "b"}}, java.util.Collections.singletonList(new Object[] {"c"}));
        for (String profile : BOTH) {
            assertConforms(new DefaultFieldMetadataResolver(), profile, blobs, false);
        }
    }

    @Test
    @DisplayName("a scalar-serialised record is described as scrubbed when the readings agree")
    void agreeingScalarRecord() {
        var profile = new PrivacyProfile("DEFAULT", PrivacyProfile.UnclassifiedBehaviour.REDACT_AND_WARN, Map.of(
                DataClassification.CONTACT, PrivacyProfile.ClassificationRule.of(PrivacyAction.REDACT)));
        var engine = new JsonTreeScrubbingEngine(new DefaultFieldMetadataResolver(),
                new ProfilePrivacyPolicyResolver(Map.of("DEFAULT", profile)), (s, ns, c) -> "syn", TOKENS);
        var holder = new ScrubPlanDescriberTest.Holder("h-1", new ScrubPlanDescriberTest.Email("e"),
                new ScrubPlanDescriberTest.Code("c"), new ScrubPlanDescriberTest.Plain("p"),
                new ScrubPlanDescriberTest.Code("d"));
        var context = ScrubPlanDescriberTest.context("DEFAULT");

        ScrubResult scrubbed = engine.scrub(holder, context);

        assertThat(scrubbed.dispositions()).containsEntry("/redactedCode", PrivacyAction.REDACT);
        assertThat(engine.describe(holder.getClass(), context, false).orElseThrow().fields())
                .contains(new FieldProtection("redactedCode", Protection.MARKER, null, null));
    }

    // ---- Annotation-free twins: the same model without any Jackson annotation (task 289) ----

    @SensitiveObject
    record BareEmail(@SensitiveData(classifications = DataClassification.CONTACT,
            namespace = PrivacyNamespace.EMAIL, suggestedAction = PrivacyAction.REDACT) String value) {
    }

    record BareCode(String v) {
    }

    record BarePlain(String v) {
    }

    @LlmExposedModel
    record BareHolder(
            @InternalIdentifier String ref,
            @NonSensitive(reason = "wrapper") BareEmail email,
            @NonSensitive(reason = "wrapper") BareCode code,
            @NonSensitive(reason = "wrapper") BarePlain plain,
            @SensitiveData(classifications = DataClassification.CONTACT,
                    namespace = PrivacyNamespace.EMAIL,
                    suggestedAction = PrivacyAction.REDACT) BareCode redactedCode) {
    }

    @LlmExposedModel
    record EmailHolder(@InternalIdentifier String ref,
                       @NonSensitive(reason = "wrapper") ScrubPlanDescriberTest.Email email) {
    }

    @LlmExposedModel
    record BareEmailHolder(@InternalIdentifier String ref, @NonSensitive(reason = "wrapper") BareEmail email) {
    }

    @LlmExposedModel
    record BareAsHolder(@InternalIdentifier String ref,
                        @SensitiveData(classifications = DataClassification.CONFIDENTIAL,
                                suggestedAction = PrivacyAction.REDACT) ScrubPlanDescriberTest.AInner details) {
    }

    @LlmExposedModel
    record BareArray(@InternalIdentifier String ref,
                     @NonSensitive(reason = "x") ScrubPlanDescriberTest.PContact[] contacts) {
    }

    @LlmExposedModel
    record BareNote(@InternalIdentifier String ref, @NonSensitive(reason = "x") String note) {
    }

    @LlmExposedModel
    record BareEnum(@InternalIdentifier String ref, @NonSensitive(reason = "x") Plain e) {
    }

    @LlmExposedModel
    record BareTyped(@InternalIdentifier String ref, @NonSensitive(reason = "x") Long n,
                     @NonSensitive(reason = "x") UUID u) {
    }

    /** An annotated source and the same model with every Jackson annotation removed. */
    private record Pair(Object annotated, Object bare) {
    }

    private static final String EMAIL = "ada@example.invalid";

    private static List<Pair> pairs() {
        var contact = new ScrubPlanDescriberTest.IdContact(EMAIL);
        var plain = new ScrubPlanDescriberTest.PContact(EMAIL);
        var plainList = new ScrubPlanDescriberTest.PlainList("m-5", List.of(plain, plain));
        var plainOpt = new ScrubPlanDescriberTest.PlainOpt("o-2", java.util.Optional.of(plain));
        var plainHolder = new ScrubPlanDescriberTest.PlainHolder("r-1", plain);
        var bareEmail = new BareEmailHolder("i-1", new BareEmail(EMAIL));
        var uuid = UUID.fromString("00000000-0000-0000-0000-000000000001");
        var bareEnum = new BareEnum("e-1", Plain.A);
        return List.of(
                new Pair(new ScrubPlanDescriberTest.Holder("h-1", new ScrubPlanDescriberTest.Email(EMAIL),
                        new ScrubPlanDescriberTest.Code("c"), new ScrubPlanDescriberTest.Plain("p"),
                        new ScrubPlanDescriberTest.Code("d")),
                        new BareHolder("h-1", new BareEmail(EMAIL), new BareCode("c"), new BarePlain("p"),
                                new BareCode("d"))),
                new Pair(new EmailHolder("i-1", new ScrubPlanDescriberTest.Email(EMAIL)), bareEmail),
                new Pair(new ScrubPlanDescriberTest.IHolder("i-1", new ScrubPlanDescriberTest.IEmail(EMAIL)),
                        bareEmail),
                new Pair(new ScrubPlanDescriberTest.AsHolder("a-1", new ScrubPlanDescriberTest.AInner("plain-a")),
                        new BareAsHolder("a-1", new ScrubPlanDescriberTest.AInner("plain-a"))),
                new Pair(new ScrubPlanDescriberTest.ArrHolder("r-1", new ScrubPlanDescriberTest.ArrContact(EMAIL)),
                        plainHolder),
                new Pair(new ScrubPlanDescriberTest.PropArrHolder("r-1", plain), plainHolder),
                new Pair(new ScrubPlanDescriberTest.MetaList("m-5", List.of(plain, plain)), plainList),
                new Pair(new ScrubPlanDescriberTest.MetaOpt("o-2", java.util.Optional.of(plain)), plainOpt),
                new Pair(new ScrubPlanDescriberTest.MetaArr("b-1",
                        new ScrubPlanDescriberTest.PContact[] {plain}), new BareArray("b-1",
                        new ScrubPlanDescriberTest.PContact[] {plain})),
                new Pair(new ScrubPlanDescriberTest.IfaceList("m-5", List.of(plain, plain)), plainList),
                new Pair(new ScrubPlanDescriberTest.OptOfArr("o-2",
                        java.util.Optional.of(new ScrubPlanDescriberTest.ArrContact(EMAIL))), plainOpt),
                new Pair(new ScrubPlanDescriberTest.IdOpt("o-2", java.util.Optional.of(contact)), plainOpt),
                new Pair(new ScrubPlanDescriberTest.IdHolder("m-5", List.of(contact, contact)), plainList),
                new Pair(new ObjectNote("n-1", "secret text"), new BareNote("n-1", "secret text")),
                new Pair(new CustomEnum("e-1", CustomSerialized.A), bareEnum),
                new Pair(new SpoofEnum("e-1", Spoofed.A), bareEnum),
                new Pair(new MapEnum("e-1", MapValued.A), bareEnum),
                new Pair(new TypedEnum("e-1", Typed.A), bareEnum),
                new Pair(new TypedLong("t-1", 7L, uuid), new BareTyped("t-1", 7L, uuid)));
    }

    private static final PrivacyProfile REDACTING = new PrivacyProfile("DEFAULT",
            PrivacyProfile.UnclassifiedBehaviour.REDACT_AND_WARN, Map.of(
            DataClassification.CONTACT, PrivacyProfile.ClassificationRule.of(PrivacyAction.REDACT),
            DataClassification.CONFIDENTIAL, PrivacyProfile.ClassificationRule.of(PrivacyAction.REDACT)));

    /**
     * The annotated source is scrubbed and described as its annotation-free twin: the same fields with the
     * same protections, and every path of the scrubbed tree described with the protection it was scrubbed under.
     */
    private static void assertAnnotationFree(JsonTreeScrubbingEngine engine, String profile, Pair pair) {
        var context = ScrubPlanDescriberTest.context(profile);
        String name = pair.annotated().getClass().getSimpleName() + " under " + profile;
        var annotated = engine.describe(pair.annotated().getClass(), context, true).orElseThrow();
        var bare = engine.describe(pair.bare().getClass(), context, true).orElseThrow();

        assertThat(annotated.fields()).as(name).isEqualTo(bare.fields());
        assertThat(annotated.undeclared()).as(name).isEqualTo(bare.undeclared());
        String refusal = refusalCode(engine, pair.bare(), context);
        if (refusal != null) {
            assertThat(refusalCode(engine, pair.annotated(), context)).as(name).isEqualTo(refusal);
        } else {
            assertConforms(engine, profile, pair.annotated(), true);
        }
    }

    private static String refusalCode(JsonTreeScrubbingEngine engine, Object source,
                                      io.github.aindriub.dataprism.core.model.PrivacyContext context) {
        try {
            engine.scrub(source, context);
            return null;
        } catch (io.github.aindriub.dataprism.core.refusal.PrivacyRefusedException refused) {
            return refused.code();
        }
    }

    @Test
    @DisplayName("every fixture carrying a Jackson annotation is described as its annotation-free twin, under both bundled profiles")
    void annotatedFixturesAreDescribedAsTheirBareTwins() {
        var engine = engine(new DefaultFieldMetadataResolver());
        for (String profile : BOTH) {
            for (Pair pair : pairs()) {
                assertAnnotationFree(engine, profile, pair);
            }
        }
    }

    @Test
    @DisplayName("the same holds under a profile that redacts unclassified data")
    void annotatedFixturesUnderRedactingProfile() {
        var engine = new JsonTreeScrubbingEngine(new DefaultFieldMetadataResolver(),
                new ProfilePrivacyPolicyResolver(Map.of("DEFAULT", REDACTING)), (s, ns, c) -> "syn", TOKENS);
        for (Pair pair : pairs()) {
            assertAnnotationFree(engine, "DEFAULT", pair);
        }
    }

    // ---- 270's repros 1 to 4 and 8 (a) to (d), as models ----

    @LlmExposedModel
    record OptEmail(@InternalIdentifier String ref,
                    @NonSensitive(reason = "x") java.util.Optional<ScrubPlanDescriberTest.Email> email) {
    }

    @LlmExposedModel
    record ListEmail(@InternalIdentifier String ref,
                     @NonSensitive(reason = "x") List<ScrubPlanDescriberTest.Email> emails) {
    }

    @LlmExposedModel
    record ArrayEmail(@InternalIdentifier String ref,
                      @NonSensitive(reason = "x") ScrubPlanDescriberTest.Email[] emails) {
    }

    @LlmExposedModel
    record ListArr(@InternalIdentifier String ref,
                   @NonSensitive(reason = "x") List<ScrubPlanDescriberTest.ArrContact> contacts) {
    }

    @LlmExposedModel
    record ArrArr(@InternalIdentifier String ref,
                  @NonSensitive(reason = "x") ScrubPlanDescriberTest.ArrContact[] contacts) {
    }

    interface Swappable {
        String email();

        String ref();
    }

    /** Writes {"email": ref, "ref": email}: every key is a component name, and the values are swapped. */
    static final class Swap extends ValueSerializer<Object> {
        @Override
        public void serialize(Object value, JsonGenerator gen, SerializationContext ctxt) {
            var s = (Swappable) value;
            gen.writeStartObject();
            gen.writeStringProperty("email", s.ref());
            gen.writeStringProperty("ref", s.email());
            gen.writeEndObject();
        }
    }

    @SensitiveObject
    @JsonSerialize(using = Swap.class)
    record SwapType(@SensitiveData(classifications = DataClassification.CONTACT,
            namespace = PrivacyNamespace.EMAIL, suggestedAction = PrivacyAction.REDACT) String email,
                    @NonSensitive(reason = "x") String ref) implements Swappable {
    }

    @SensitiveObject
    record SwapPlain(@SensitiveData(classifications = DataClassification.CONTACT,
            namespace = PrivacyNamespace.EMAIL, suggestedAction = PrivacyAction.REDACT) String email,
                     @NonSensitive(reason = "x") String ref) implements Swappable {
    }

    @SensitiveObject
    record SwapNames(@JsonProperty("ref") @SensitiveData(classifications = DataClassification.CONTACT,
            namespace = PrivacyNamespace.EMAIL, suggestedAction = PrivacyAction.REDACT) String email,
                     @JsonProperty("email") @NonSensitive(reason = "x") String ref) {
    }

    @LlmExposedModel
    record SwapTypeHolder(@InternalIdentifier String id, @NonSensitive(reason = "x") SwapType contact) {
    }

    @LlmExposedModel
    record SwapHolderLevel(@InternalIdentifier String id,
                           @NonSensitive(reason = "x") @JsonSerialize(using = Swap.class) SwapPlain contact) {
    }

    @LlmExposedModel
    record SwapContent(@InternalIdentifier String id,
                       @NonSensitive(reason = "x") @JsonSerialize(contentUsing = Swap.class)
                       List<SwapPlain> contacts) {
    }

    @LlmExposedModel
    record SwapNameHolder(@InternalIdentifier String id, @NonSensitive(reason = "x") SwapNames contact) {
    }

    private static Map<Object, List<String>> repros() {
        var email = new ScrubPlanDescriberTest.Email(EMAIL);
        var arr = new ScrubPlanDescriberTest.ArrContact(EMAIL);
        var id = new ScrubPlanDescriberTest.IdContact(EMAIL);
        Map<Object, List<String>> out = new java.util.LinkedHashMap<>();
        out.put(new EmailHolder("1", email), List.of("email.value"));
        out.put(new ScrubPlanDescriberTest.ArrHolder("2", arr), List.of("contact.email"));
        out.put(new ScrubPlanDescriberTest.PropArrHolder("2b", new ScrubPlanDescriberTest.PContact(EMAIL)),
                List.of("contact.email"));
        out.put(new ScrubPlanDescriberTest.IdHolder("3", List.of(id, id)), List.of("contacts[].email"));
        out.put(new OptEmail("4a", java.util.Optional.of(email)), List.of("email.value"));
        out.put(new ScrubPlanDescriberTest.OptOfArr("4b", java.util.Optional.of(arr)), List.of("contact.email"));
        out.put(new ListEmail("4c", List.of(email)), List.of("emails[].value"));
        out.put(new ArrayEmail("4d", new ScrubPlanDescriberTest.Email[] {email}), List.of("emails[].value"));
        out.put(new ListArr("4e", List.of(arr)), List.of("contacts[].email"));
        out.put(new ArrArr("4f", new ScrubPlanDescriberTest.ArrContact[] {arr}), List.of("contacts[].email"));
        out.put(new SwapTypeHolder("8a", new SwapType(EMAIL, "R-1")), List.of("contact.email"));
        out.put(new SwapHolderLevel("8b", new SwapPlain(EMAIL, "R-1")), List.of("contact.email"));
        out.put(new SwapContent("8c", List.of(new SwapPlain(EMAIL, "R-1"))), List.of("contacts[].email"));
        out.put(new SwapNameHolder("8d", new SwapNames(EMAIL, "R-1")), List.of("contact.email"));
        return out;
    }

    @Test
    @DisplayName("270's repros are described with the record's own classification, never VERBATIM, and conform to the scrub")
    void reprosAreDescribedByTheRecordsClassification() {
        var engine = engine(new DefaultFieldMetadataResolver());
        repros().forEach((source, emailPaths) -> {
            for (String profile : BOTH) {
                String name = source.getClass().getSimpleName() + " under " + profile;
                var context = ScrubPlanDescriberTest.context(profile);
                ScrubResult scrubbed = engine.scrub(source, context);
                assertThat(scrubbed.tree().toString()).as(name).doesNotContain(EMAIL);
                assertConforms(engine, profile, source, true);
                var fields = engine.describe(source.getClass(), context, true).orElseThrow().fields();
                for (String path : emailPaths) {
                    assertThat(fields).as(name).extracting(FieldProtection::path, FieldProtection::protection)
                            .contains(tuple(path, Protection.MARKER));
                }
            }
        });
    }

    /** A serializer that writes an object from a String component. */
    static final class ObjectFromString extends ValueSerializer<String> {
        @Override
        public void serialize(String value, JsonGenerator gen, SerializationContext ctxt) {
            gen.writeStartObject();
            gen.writeStringProperty("inner", value);
            gen.writeEndObject();
        }
    }

    @LlmExposedModel
    record ObjectNote(@InternalIdentifier String ref,
                      @NonSensitive(reason = "x") @JsonSerialize(using = ObjectFromString.class) String note) {
    }

    @Test
    @DisplayName("a String component with a serializer annotation is the scalar the walker writes, and is described as one")
    void scalarComponentWithObjectSerializer() {
        var source = new ObjectNote("n-1", "secret text");
        for (String profile : BOTH) {
            assertConforms(new DefaultFieldMetadataResolver(), profile, source);
        }
        assertThat(engine(new DefaultFieldMetadataResolver())
                .describe(ObjectNote.class, ScrubPlanDescriberTest.context("DEFAULT"), false).orElseThrow().fields())
                .contains(new FieldProtection("note", Protection.VERBATIM, null, null));
    }

    @JsonSerialize(using = ObjectFromEnum.class)
    enum CustomSerialized { A }

    @JsonSerialize(using = tools.jackson.databind.ext.javatime.ser.SpoofJavaTimeSerializer.class)
    enum Spoofed { A }

    enum MapValued {
        A;

        @JsonValue
        Map<String, String> value() {
            return Map.of("k", name());
        }
    }

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.WRAPPER_ARRAY)
    enum Typed { A }

    enum Plain { A }

    /** Writes an object from an enum constant. */
    static final class ObjectFromEnum extends ValueSerializer<Enum<?>> {
        @Override
        public void serialize(Enum<?> value, JsonGenerator gen, SerializationContext ctxt) {
            gen.writeStartObject();
            gen.writeStringProperty("inner", value.name());
            gen.writeEndObject();
        }
    }

    @LlmExposedModel
    record TypedLong(@InternalIdentifier String ref,
                     @NonSensitive(reason = "x") @JsonTypeInfo(use = JsonTypeInfo.Id.NAME,
                             include = JsonTypeInfo.As.WRAPPER_OBJECT) Long n,
                     @NonSensitive(reason = "x") @JsonTypeInfo(use = JsonTypeInfo.Id.CLASS,
                             include = JsonTypeInfo.As.PROPERTY) UUID u) {
    }

    @LlmExposedModel
    record CustomEnum(@InternalIdentifier String ref, @NonSensitive(reason = "x") CustomSerialized e) {
    }

    @LlmExposedModel
    record SpoofEnum(@InternalIdentifier String ref, @NonSensitive(reason = "x") Spoofed e) {
    }

    @LlmExposedModel
    record MapEnum(@InternalIdentifier String ref, @NonSensitive(reason = "x") MapValued e) {
    }

    @LlmExposedModel
    record TypedEnum(@InternalIdentifier String ref, @NonSensitive(reason = "x") Typed e) {
    }

    @LlmExposedModel
    record PlainScalars(@InternalIdentifier String ref, @NonSensitive(reason = "x") String s,
                        @NonSensitive(reason = "x") Long n, @NonSensitive(reason = "x") UUID u,
                        @NonSensitive(reason = "x") Plain e) {
    }

    @Test
    @DisplayName("plain String, Long, UUID and enum fields are still described")
    void plainScalarsStayDescribed() {
        for (String profile : BOTH) {
            assertConforms(new DefaultFieldMetadataResolver(), profile,
                    new PlainScalars("p-1", "s", 7L, UUID.randomUUID(), Plain.A));
        }
        assertThat(engine(new DefaultFieldMetadataResolver())
                .describe(PlainScalars.class, ScrubPlanDescriberTest.context("DEFAULT"), false).orElseThrow().fields())
                .extracting(FieldProtection::path).contains("s", "n", "u", "e");
    }

    @Test
    @DisplayName("a model with an interface-typed field is described around it and conforms to the scrub")
    void interfaceFieldIsLeftOut() {
        for (String profile : BOTH) {
            assertConforms(new DefaultFieldMetadataResolver(), profile,
                    new ScrubPlanDescriberTest.WithIface("w-1", null, null, null, "OPEN"), false);
        }
    }

    @SensitiveObject
    enum ProbeColour { RED, BLUE }

    @LlmExposedModel
    record ColourHolder(@InternalIdentifier String ref, @NonSensitive(reason = "x") ProbeColour colour) {
    }

    @Test
    @DisplayName("an enum is a scalar even when its type is marked descendable")
    void descendableEnumIsDescribed() {
        for (String profile : BOTH) {
            assertConforms(new DefaultFieldMetadataResolver(), profile, new ColourHolder("c-1", ProbeColour.RED));
        }
    }
}

package io.github.aindriub.dataprism.core.engine;

import io.github.aindriub.dataprism.annotations.DataClassification;
import io.github.aindriub.dataprism.annotations.PrivacyAction;
import io.github.aindriub.dataprism.annotations.PrivacyNamespace;
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
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

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
        JsonTreeScrubbingEngine engine = engine(resolver);
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

        assertThat(strict).isNotEqualTo(normal);
    }

    @Test
    @DisplayName("records that serialise as a scalar, bytes and nested arrays never contradict the scrub")
    void ambiguousShapes() {
        var email = new ScrubPlanDescriberTest.Email("ada@example.invalid");
        var holder = new ScrubPlanDescriberTest.Holder("h-1", email, new ScrubPlanDescriberTest.Code("c"),
                new ScrubPlanDescriberTest.Plain("p"), new ScrubPlanDescriberTest.Code("d"));
        var blobs = new ScrubPlanDescriberTest.Blobs("b-1", new byte[] {1, 2, 3},
                new Object[][] {{"a", "b"}}, java.util.Collections.singletonList(new Object[] {"c"}));
        for (String profile : BOTH) {
            assertConforms(new DefaultFieldMetadataResolver(), profile, holder, false);
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

    @Test
    @DisplayName("under a profile that redacts unclassified data, inherited and over-broad serialiser shapes never contradict the scrub")
    void ambiguousShapesRedacting() {
        var profile = new PrivacyProfile("DEFAULT", PrivacyProfile.UnclassifiedBehaviour.REDACT_AND_WARN, Map.of(
                DataClassification.CONTACT, PrivacyProfile.ClassificationRule.of(PrivacyAction.REDACT),
                DataClassification.CONFIDENTIAL, PrivacyProfile.ClassificationRule.of(PrivacyAction.REDACT)));
        var engine = new JsonTreeScrubbingEngine(new DefaultFieldMetadataResolver(),
                new ProfilePrivacyPolicyResolver(Map.of("DEFAULT", profile)), (s, ns, c) -> "syn", TOKENS);
        var context = ScrubPlanDescriberTest.context("DEFAULT");
        List<Object> sources = List.of(
                new ScrubPlanDescriberTest.IHolder("i-1", new ScrubPlanDescriberTest.IEmail("ada@example.invalid")),
                new ScrubPlanDescriberTest.AsHolder("a-1", new ScrubPlanDescriberTest.AInner("plain-a")),
                new ScrubPlanDescriberTest.Holder("h-1", new ScrubPlanDescriberTest.Email("e"),
                        new ScrubPlanDescriberTest.Code("c"), new ScrubPlanDescriberTest.Plain("p"),
                        new ScrubPlanDescriberTest.Code("d")));

        for (Object source : sources) {
            ScrubResult scrubbed = engine.scrub(source, context);
            Map<String, List<JsonNode>> present = new java.util.TreeMap<>();
            leaves(scrubbed.tree(), "", present);
            for (FieldProtection f : engine.describe(source.getClass(), context, false).orElseThrow().fields()) {
                PrivacyAction action = disposition(scrubbed, f.path());
                assertThat(present).as(f.path()).containsKey(f.path());
                assertThat(action).as(f.path()).isNotNull();
                assertThat(f.protection()).as(f.path()).isEqualTo(protection(action));
            }
        }
    }
}

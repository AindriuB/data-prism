package io.github.aindriub.dataprism.validation;

import io.github.aindriub.dataprism.annotations.DataClassification;
import io.github.aindriub.dataprism.annotations.InternalIdentifier;
import io.github.aindriub.dataprism.annotations.LlmExposedModel;
import io.github.aindriub.dataprism.annotations.NonSensitive;
import io.github.aindriub.dataprism.annotations.PrivacyAction;
import io.github.aindriub.dataprism.annotations.PrivacyNamespace;
import io.github.aindriub.dataprism.annotations.SensitiveData;
import io.github.aindriub.dataprism.annotations.SensitiveObject;
import io.github.aindriub.dataprism.core.engine.DefaultFieldMetadataResolver;
import io.github.aindriub.dataprism.core.engine.JsonTreeScrubbingEngine;
import io.github.aindriub.dataprism.core.engine.SourceModels;
import io.github.aindriub.dataprism.core.engine.SourceValues;
import io.github.aindriub.dataprism.core.model.PrivacyContext;
import io.github.aindriub.dataprism.core.model.PrivacyScopeType;
import io.github.aindriub.dataprism.core.model.PseudonymisationVersion;
import io.github.aindriub.dataprism.core.model.ScrubResult;
import io.github.aindriub.dataprism.core.policy.PrivacyProfiles;
import io.github.aindriub.dataprism.core.policy.ProfilePrivacyPolicyResolver;
import io.github.aindriub.dataprism.core.refusal.PrivacyRefusedException;
import io.github.aindriub.dataprism.core.spi.SyntheticValueSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A field's own policy must never be applied to an array nested inside its array (task 267). Runs the
 * shipped DEFAULT profile, the real engine, the startup model check and both validators: the
 * raw-value validator, which closes the gap once the prohibited set is right, and the pattern validator,
 * which sees only pattern-shaped values and is shown not to. Adopted
 * from a probe that found a {@code @NonSensitive List<List<Leaf>>} releasing every record raw.
 * All values are invented.
 */
class NestedContainerLeakTest {

    private static final String NAME = "Zelda Invented-Probe";
    private static final String EMAIL = "zelda.probe@invalid.example";
    private static final String NESTED = "SOURCE_MODEL_NESTED_COLLECTION_OF_RECORD";

    private static final SyntheticValueSource SYNTHETICS =
            (subjectId, namespace, context) -> "synthetic-" + Integer.toHexString(subjectId.hashCode());

    @SensitiveObject
    record Leaf(
            @SensitiveData(classifications = DataClassification.PII, namespace = PrivacyNamespace.PERSON_NAME,
                    suggestedAction = PrivacyAction.SYNTHESIZE) String name,
            @SensitiveData(classifications = DataClassification.CONTACT, namespace = PrivacyNamespace.EMAIL,
                    suggestedAction = PrivacyAction.REDACT) String email) {
    }

    @LlmExposedModel
    record ListList(@InternalIdentifier String ref, @NonSensitive(reason = "x") List<List<Leaf>> v) {
    }

    @LlmExposedModel
    record ArrArr(@InternalIdentifier String ref, @NonSensitive(reason = "x") Leaf[][] v) {
    }

    @LlmExposedModel
    record ListArr(@InternalIdentifier String ref, @NonSensitive(reason = "x") List<Leaf[]> v) {
    }

    @LlmExposedModel
    record ArrList(@InternalIdentifier String ref, @NonSensitive(reason = "x") List<Leaf>[] v) {
    }

    @LlmExposedModel
    record SetList(@InternalIdentifier String ref, @NonSensitive(reason = "x") Set<List<Leaf>> v) {
    }

    @LlmExposedModel
    record ListSet(@InternalIdentifier String ref, @NonSensitive(reason = "x") List<Set<Leaf>> v) {
    }

    @LlmExposedModel
    record OptList(@InternalIdentifier String ref, @NonSensitive(reason = "x") Optional<List<Leaf>> v) {
    }

    @LlmExposedModel
    record OptArr(@InternalIdentifier String ref, @NonSensitive(reason = "x") Optional<Leaf[]> v) {
    }

    @LlmExposedModel
    record ListOpt(@InternalIdentifier String ref, @NonSensitive(reason = "x") List<Optional<Leaf>> v) {
    }

    @LlmExposedModel
    record OptListList(@InternalIdentifier String ref,
                       @NonSensitive(reason = "x") Optional<List<List<Leaf>>> v) {
    }

    @LlmExposedModel
    record ListListList(@InternalIdentifier String ref, @NonSensitive(reason = "x") List<List<List<Leaf>>> v) {
    }

    @LlmExposedModel
    record ObjField(@InternalIdentifier String ref, @NonSensitive(reason = "x") Object v) {
    }

    @LlmExposedModel
    record ListObj(@InternalIdentifier String ref, @NonSensitive(reason = "x") List<Object> v) {
    }

    @LlmExposedModel
    record Unannotated(@InternalIdentifier String ref, List<List<Leaf>> v) {
    }

    @LlmExposedModel
    record SensList(@InternalIdentifier String ref,
                    @SensitiveData(classifications = DataClassification.CONFIDENTIAL,
                            namespace = PrivacyNamespace.PERSON_NAME, suggestedAction = PrivacyAction.REDACT)
                    List<List<Leaf>> v) {
    }

    private static PrivacyContext context() {
        return new PrivacyContext("C", PrivacyScopeType.CASE, "DEFAULT", "test",
                Instant.parse("2030-01-01T00:00:00Z"), PseudonymisationVersion.HMAC_SHA256_V1);
    }

    private static ProfilePrivacyPolicyResolver defaultPolicies() throws Exception {
        try (InputStream in = PrivacyProfiles.class.getResourceAsStream("/privacy-profiles-default.yaml")) {
            return new ProfilePrivacyPolicyResolver(PrivacyProfiles.fromYaml(in));
        }
    }

    private static Leaf leaf() {
        return new Leaf(NAME, EMAIL);
    }

    /** The eight declared shapes that put a record inside an inner array. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> declaredShapes() {
        Leaf l = leaf();
        Map<String, Object> shapes = new LinkedHashMap<>();
        shapes.put("List<List<Leaf>>", new ListList("r", List.of(List.of(l))));
        shapes.put("Leaf[][]", new ArrArr("r", new Leaf[][] {{l}}));
        shapes.put("List<Leaf[]>", new ListArr("r", List.<Leaf[]>of(new Leaf[] {l})));
        shapes.put("List<Leaf>[]", new ArrList("r", new List[] {List.of(l)}));
        shapes.put("Set<List<Leaf>>", new SetList("r", Set.of(List.of(l))));
        shapes.put("List<Set<Leaf>>", new ListSet("r", List.of(Set.of(l))));
        shapes.put("List<List<List<Leaf>>>", new ListListList("r", List.of(List.of(List.of(l)))));
        shapes.put("Optional<List<List<Leaf>>>", new OptListList("r", Optional.of(List.of(List.of(l)))));
        return shapes;
    }

    /** The two request-time shapes no startup check can see. */
    private static Map<String, Object> requestTimeShapes() {
        Leaf l = leaf();
        Map<String, Object> shapes = new LinkedHashMap<>();
        shapes.put("Object=[[Leaf]]", new ObjField("r", List.of(List.of(l))));
        shapes.put("List<Object>=[[Leaf]]", new ListObj("r", List.of(List.of(l))));
        return shapes;
    }

    private static String startup(Object source) {
        try {
            SourceModels.require(source.getClass());
            return "accepted";
        } catch (PrivacyRefusedException e) {
            return e.code();
        }
    }

    /** The code the scrub is refused with, or the output when it is not refused. */
    private static String scrub(Object source) throws Exception {
        try {
            return "NOT REFUSED " + engineScrub(source).tree();
        } catch (PrivacyRefusedException e) {
            return e.code();
        }
    }

    private static ScrubResult engineScrub(Object source) throws Exception {
        return new JsonTreeScrubbingEngine(new DefaultFieldMetadataResolver(), defaultPolicies(), SYNTHETICS)
                .scrub(source, context());
    }

    @Test
    @DisplayName("a record inside an inner array is refused at startup")
    void declaredShapesAreRefusedAtStartup() {
        declaredShapes().forEach((label, source) -> assertThat(startup(source)).as(label).isEqualTo(NESTED));
    }

    @Test
    @DisplayName("with the startup check skipped, the scrub refuses with the startup code and no invented value is released")
    void declaredShapesAreRefusedByTheEngine() throws Exception {
        for (var shape : declaredShapes().entrySet()) {
            assertThat(scrub(shape.getValue())).as(shape.getKey()).isEqualTo(NESTED);
        }
    }

    @Test
    @DisplayName("an Object or List<Object> holding [[record]] passes startup and is refused at scrub as not declared")
    void requestTimeShapesAreRefusedByTheEngine() throws Exception {
        for (var shape : requestTimeShapes().entrySet()) {
            assertThat(startup(shape.getValue())).as(shape.getKey()).isEqualTo("accepted");
            assertThat(scrub(shape.getValue())).as(shape.getKey()).isEqualTo("SOURCE_MODEL_RECORD_NOT_DECLARED");
        }
    }

    @Test
    @DisplayName("Optional<List<Leaf>>, Optional<Leaf[]> and List<Optional<Leaf>> are unchanged")
    void optionalShapesAreUnchanged() throws Exception {
        Leaf l = leaf();
        Map<String, Object> shapes = new LinkedHashMap<>();
        shapes.put("Optional<List<Leaf>>", new OptList("r", Optional.of(List.of(l))));
        shapes.put("Optional<Leaf[]>", new OptArr("r", Optional.of(new Leaf[] {l})));
        shapes.put("List<Optional<Leaf>>", new ListOpt("r", List.of(Optional.of(l))));
        for (var shape : shapes.entrySet()) {
            assertThat(startup(shape.getValue())).as(shape.getKey()).isEqualTo("accepted");
            assertThat(scrub(shape.getValue())).as(shape.getKey()).isEqualTo("UNCLASSIFIED_STRUCTURE");
        }
    }

    @Test
    @DisplayName("an unannotated or REDACT List<List<Leaf>> is now refused at startup; skipped, the scrub refuses with the same code")
    void unannotatedAndRedactedAreRefusedAtStartup() throws Exception {
        var unannotated = new Unannotated("r", List.of(List.of(leaf())));
        var redacted = new SensList("r", List.of(List.of(leaf())));

        assertThat(startup(unannotated)).isEqualTo(NESTED);
        assertThat(startup(redacted)).isEqualTo(NESTED);
        // The walker refuses while it builds the tree, before any field's policy applies.
        assertThat(scrub(unannotated)).isEqualTo(NESTED);
        assertThat(scrub(redacted)).isEqualTo(NESTED);
    }

    @Test
    @DisplayName("the prohibited set holds both invented values for each of the ten leaking shapes")
    void prohibitedValuesCoverEveryLeakingShape() {
        var resolver = new DefaultFieldMetadataResolver();
        Map<String, Object> all = new LinkedHashMap<>(declaredShapes());
        all.putAll(requestTimeShapes());

        all.forEach((label, source) ->
                assertThat(SourceValues.prohibited(source, resolver)).as(label).contains(NAME, EMAIL));
    }

    @Test
    @DisplayName("the base commit's leaking tree is caught by the raw-value validator given that set")
    void theLeakingTreeIsCaughtByTheRawValueValidator() {
        JsonNode leaking = new ObjectMapper().readTree(
                "{\"v\":[[{\"name\":\"" + NAME + "\",\"email\":\"" + EMAIL + "\"}]]}");
        Set<String> prohibited = SourceValues.prohibited(
                new ListList("r", List.of(List.of(leaf()))), new DefaultFieldMetadataResolver());

        var result = new RawValueLeakValidator().validate(leaking, prohibited, Set.of(), context());

        assertThat(result.valid()).isFalse();
    }

    @Test
    @DisplayName("the pattern validator catches only the email-shaped value, so the raw-value validator closes the gap")
    void thePatternValidatorCatchesTheEmailButNotTheName() {
        JsonNode both = new ObjectMapper().readTree(
                "{\"v\":[[{\"name\":\"" + NAME + "\",\"email\":\"" + EMAIL + "\"}]]}");
        JsonNode nameOnly = new ObjectMapper().readTree("{\"v\":[[{\"name\":\"" + NAME + "\"}]]}");
        Set<String> prohibited = SourceValues.prohibited(
                new ListList("r", List.of(List.of(leaf()))), new DefaultFieldMetadataResolver());

        var withEmail = new SensitivePatternValidator().validate(both, prohibited, Set.of(), context());
        var withoutEmail = new SensitivePatternValidator().validate(nameOnly, prohibited, Set.of(), context());

        assertThat(withEmail.valid()).isFalse();
        assertThat(withEmail.violations()).hasSize(1);
        assertThat(withEmail.violations().get(0).path()).contains("email");
        assertThat(withoutEmail.valid()).isTrue();
        assertThat(new RawValueLeakValidator().validate(nameOnly, prohibited, Set.of(), context()).valid())
                .isFalse();
    }
}

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

import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A user generic such as {@code Envelope<Leaf>} must never be scrubbed with the type argument's
 * rules, and a map of records is not a record. Both are refused at startup; with that check
 * skipped, the engine still refuses what it cannot classify. Runs the shipped DEFAULT profile, the
 * real engine, the startup model check and both validators. All values are invented.
 */
class GenericElementTypeCollisionTest {

    private static final String ENVELOPE_EMAIL = "alice@invalid.example";
    private static final String ENVELOPE_NAME = "Alice Invented-Person";
    private static final String LEAF_EMAIL = "a@b.example";

    private static final SyntheticValueSource SYNTHETICS =
            (subjectId, namespace, context) -> "synthetic-" + Integer.toHexString(subjectId.hashCode());

    // ---- shape 1: the literal sketch -----------------------------------------------------------

    @SensitiveObject
    record Envelope<T>(
            @NonSensitive(reason = "structure reviewed") T item,
            @SensitiveData(classifications = DataClassification.CONTACT,
                    namespace = PrivacyNamespace.EMAIL, suggestedAction = PrivacyAction.REDACT)
            String email) {
    }

    @LlmExposedModel
    record Leaf(@NonSensitive(reason = "x") String email) {
    }

    @LlmExposedModel
    record SketchResponse(
            @InternalIdentifier String ref,
            @NonSensitive(reason = "structure reviewed") Envelope<Leaf> env) {
    }

    // ---- shape 2: Leaf declares every Envelope field name --------------------------------------

    @SensitiveObject
    record Envelope2<T>(
            @NonSensitive(reason = "structure reviewed") T item,
            @SensitiveData(classifications = DataClassification.CONTACT,
                    namespace = PrivacyNamespace.EMAIL, suggestedAction = PrivacyAction.REDACT)
            String email,
            @SensitiveData(classifications = DataClassification.PII,
                    namespace = PrivacyNamespace.PERSON_NAME, suggestedAction = PrivacyAction.SYNTHESIZE)
            String name) {
    }

    @LlmExposedModel
    record Leaf2(
            @NonSensitive(reason = "x") String email,
            @NonSensitive(reason = "x") String name,
            @NonSensitive(reason = "x") Leaf2 item) {
    }

    @LlmExposedModel
    record CollidingResponse(
            @InternalIdentifier String ref,
            @NonSensitive(reason = "structure reviewed") Envelope2<Leaf2> env) {
    }

    // ---- shape 2b: as 2 but only a non-pattern-shaped PII name shared ------------------------

    @SensitiveObject
    record NameEnvelope<T>(
            @NonSensitive(reason = "structure reviewed") T item,
            @SensitiveData(classifications = DataClassification.PII,
                    namespace = PrivacyNamespace.PERSON_NAME, suggestedAction = PrivacyAction.SYNTHESIZE)
            String name) {
    }

    @LlmExposedModel
    record NameLeaf(
            @NonSensitive(reason = "x") String name,
            @NonSensitive(reason = "x") NameLeaf item) {
    }

    @LlmExposedModel
    record NameResponse(
            @InternalIdentifier String ref,
            @NonSensitive(reason = "structure reviewed") NameEnvelope<NameLeaf> env) {
    }

    // ---- shape 3: Map<String, Leaf> ------------------------------------------------------------

    @LlmExposedModel
    record MapResponse(
            @InternalIdentifier String ref,
            @NonSensitive(reason = "structure reviewed") Map<String, Leaf> byKey) {
    }

    // ---- shape 4: Page<Leaf> -------------------------------------------------------------------

    @SensitiveObject
    record Page<T>(@NonSensitive(reason = "structure reviewed") List<T> items) {
    }

    @LlmExposedModel
    record PageResponse(
            @InternalIdentifier String ref,
            @NonSensitive(reason = "structure reviewed") Page<Leaf> page) {
    }

    // ---- defence in depth: a non-generic record seen through a generic interface ---------------

    interface Box<T> {
    }

    @SensitiveObject
    record BoxImpl(
            @SensitiveData(classifications = DataClassification.CONTACT,
                    namespace = PrivacyNamespace.EMAIL, suggestedAction = PrivacyAction.REDACT)
            String email,
            @SensitiveData(classifications = DataClassification.PII,
                    namespace = PrivacyNamespace.PERSON_NAME, suggestedAction = PrivacyAction.SYNTHESIZE)
            String name) implements Box<BoxLeaf> {
    }

    @LlmExposedModel
    record BoxLeaf(
            @NonSensitive(reason = "x") String email,
            @NonSensitive(reason = "x") String name) {
    }

    @LlmExposedModel
    record BoxResponse(
            @InternalIdentifier String ref,
            @NonSensitive(reason = "structure reviewed") Box<BoxLeaf> box) {
    }

    // ---- harness -------------------------------------------------------------------------------

    private static PrivacyContext context() {
        return new PrivacyContext("C", PrivacyScopeType.CASE, "DEFAULT", "test",
                Instant.parse("2030-01-01T00:00:00Z"), PseudonymisationVersion.HMAC_SHA256_V1);
    }

    private record Outcome(String output, String refusal, boolean rawValid, boolean patternValid,
                           Set<String> prohibited) {
    }

    private static Outcome run(Object source) throws Exception {
        return run(source, true);
    }

    /** A refusal from the startup check or from the engine is an outcome with no output. */
    private static Outcome run(Object source, boolean startupCheck) throws Exception {
        if (startupCheck) {
            try {
                SourceModels.require(source.getClass());
            } catch (PrivacyRefusedException e) {
                return new Outcome(null, e.code(), true, true, Set.of());
            }
        }
        var resolver = new DefaultFieldMetadataResolver();
        ProfilePrivacyPolicyResolver policies;
        try (InputStream in = PrivacyProfiles.class.getResourceAsStream("/privacy-profiles-default.yaml")) {
            policies = new ProfilePrivacyPolicyResolver(PrivacyProfiles.fromYaml(in));
        }
        var engine = new JsonTreeScrubbingEngine(resolver, policies, SYNTHETICS);
        ScrubResult result;
        try {
            result = engine.scrub(source, context());
        } catch (PrivacyRefusedException e) {
            return new Outcome(null, e.code(), true, true, Set.of());
        }
        Set<String> prohibited = SourceValues.prohibited(source, resolver);
        var raw = new RawValueLeakValidator().validate(result.tree(), prohibited, result.emitted(), context());
        var pattern = new SensitivePatternValidator().validate(result.tree(), prohibited, result.emitted(), context());
        return new Outcome(result.tree().toString(), null, raw.valid(), pattern.valid(), prohibited);
    }

    /** Released only if the engine emitted it AND both validators passed the response. */
    private static void assertNotReleased(Outcome o, String value) {
        if (o.refusal() != null) {
            return;
        }
        boolean released = o.output().contains(value) && o.rawValid() && o.patternValid();
        assertThat(released).as("Envelope value released past engine and both validators").isFalse();
        assertThat(o.output()).as("engine emitted Envelope value").doesNotContain(value);
    }

    private static void assertRefusedWith(Outcome o, String code, String... values) {
        assertThat(o.refusal()).isEqualTo(code);
        assertThat(o.output()).isNull();
        for (String value : values) {
            assertNotReleased(o, value);
        }
    }

    @Test
    @DisplayName("sketch: Envelope<Leaf> with only `email` shared is refused at startup")
    void sketch() throws Exception {
        var o = run(new SketchResponse("r-1", new Envelope<>(new Leaf(LEAF_EMAIL), ENVELOPE_EMAIL)));
        assertRefusedWith(o, "SOURCE_MODEL_GENERIC", ENVELOPE_EMAIL);
    }

    @Test
    @DisplayName("collision: Leaf declares every Envelope field name; refused at startup")
    void collision() throws Exception {
        var o = run(new CollidingResponse("r-1",
                new Envelope2<>(new Leaf2(LEAF_EMAIL, "Leaf Name", null), ENVELOPE_EMAIL, ENVELOPE_NAME)));
        assertRefusedWith(o, "SOURCE_MODEL_GENERIC", ENVELOPE_EMAIL, ENVELOPE_NAME);
    }

    @Test
    @DisplayName("collision, name only: refused at startup")
    void nameOnlyCollision() throws Exception {
        var o = run(new NameResponse("r-1", new NameEnvelope<>(new NameLeaf("Leaf Name", null), ENVELOPE_NAME)));
        assertRefusedWith(o, "SOURCE_MODEL_GENERIC", ENVELOPE_NAME);
    }

    @Test
    @DisplayName("Map<String, Leaf> is refused at startup")
    void map() throws Exception {
        var o = run(new MapResponse("r-1", Map.of("k1", new Leaf(LEAF_EMAIL))));
        assertRefusedWith(o, "SOURCE_MODEL_MAP_OF_RECORD", LEAF_EMAIL);
    }

    @Test
    @DisplayName("Map<String, Leaf> keyed by a Leaf field name is refused at startup")
    void mapKeyedByFieldName() throws Exception {
        var o = run(new MapResponse("r-1", Map.of("email", new Leaf(LEAF_EMAIL))));
        assertRefusedWith(o, "SOURCE_MODEL_MAP_OF_RECORD", LEAF_EMAIL);
    }

    @Test
    @DisplayName("Page<Leaf>(List<T> items) is refused at startup")
    void page() throws Exception {
        var o = run(new PageResponse("r-1", new Page<>(List.of(new Leaf(LEAF_EMAIL)))));
        assertRefusedWith(o, "SOURCE_MODEL_GENERIC", LEAF_EMAIL);
    }

    @Test
    @DisplayName("with the startup check skipped, a non-generic record behind Box<Leaf> is refused as not declared")
    void boxBehindGenericInterfaceWithoutStartupCheck() throws Exception {
        var o = run(new BoxResponse("r-1", new BoxImpl(ENVELOPE_EMAIL, ENVELOPE_NAME)), false);
        // Runs both validators when the engine releases anything: neither may be the only guard.
        assertNotReleased(o, ENVELOPE_EMAIL);
        assertNotReleased(o, ENVELOPE_NAME);
        assertThat(o.refusal()).isEqualTo("SOURCE_MODEL_RECORD_NOT_DECLARED");
        assertThat(o.output()).isNull();
    }
}

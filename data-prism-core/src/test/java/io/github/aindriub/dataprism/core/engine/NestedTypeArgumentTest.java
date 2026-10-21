package io.github.aindriub.dataprism.core.engine;

import io.github.aindriub.dataprism.annotations.DataClassification;
import io.github.aindriub.dataprism.annotations.InternalIdentifier;
import io.github.aindriub.dataprism.annotations.LlmExposedModel;
import io.github.aindriub.dataprism.annotations.NonSensitive;
import io.github.aindriub.dataprism.annotations.PrivacyAction;
import io.github.aindriub.dataprism.annotations.PrivacyNamespace;
import io.github.aindriub.dataprism.annotations.SensitiveData;
import io.github.aindriub.dataprism.annotations.SensitiveObject;
import io.github.aindriub.dataprism.core.model.FieldMetadata;
import io.github.aindriub.dataprism.core.model.PrivacyContext;
import io.github.aindriub.dataprism.core.model.PrivacyScopeType;
import io.github.aindriub.dataprism.core.model.PseudonymisationVersion;
import io.github.aindriub.dataprism.core.policy.PrivacyProfile;
import io.github.aindriub.dataprism.core.policy.ProfilePrivacyPolicyResolver;
import io.github.aindriub.dataprism.core.refusal.PrivacyRefusedException;
import io.github.aindriub.dataprism.core.spi.FieldMetadataResolver;
import io.github.aindriub.dataprism.core.spi.SyntheticValueSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which type a nested value is scrubbed with (task 265). The type argument of a JDK collection,
 * optional or map value is followed; nothing else is. All values are invented.
 */
class NestedTypeArgumentTest {

    private static final String PII = "Alice Invented-Person";

    private static final SyntheticValueSource SYNTHETICS =
            (subjectId, namespace, context) -> "synthetic:" + Integer.toHexString(subjectId.hashCode());

    /** The type the scrubber should use for the shapes below. */
    @SensitiveObject
    record Leaf(
            @SensitiveData(classifications = DataClassification.PII,
                    namespace = PrivacyNamespace.PERSON_NAME, suggestedAction = PrivacyAction.SYNTHESIZE)
            String name) {
    }

    /** Declares the same field name as {@link Leaf} as harmless. */
    @SensitiveObject
    record LooseLeaf(@NonSensitive(reason = "x") String name) {
    }

    @SensitiveObject
    record NameEnvelope(
            @SensitiveData(classifications = DataClassification.PII,
                    namespace = PrivacyNamespace.PERSON_NAME, suggestedAction = PrivacyAction.SYNTHESIZE)
            String name) {
    }

    @LlmExposedModel
    record Shapes(
            @InternalIdentifier String ref,
            @NonSensitive(reason = "x") List<Leaf> list,
            @NonSensitive(reason = "x") Set<Leaf> set,
            @NonSensitive(reason = "x") Leaf[] array,
            @NonSensitive(reason = "x") Optional<Leaf> optional) {
    }

    @LlmExposedModel
    record StringMap(@InternalIdentifier String ref, @NonSensitive(reason = "x") Map<String, String> m) {
    }

    @LlmExposedModel
    record ObjectMap(@InternalIdentifier String ref, @NonSensitive(reason = "x") Map<String, Object> m) {
    }

    @LlmExposedModel
    record OptionalList(@InternalIdentifier String ref, @NonSensitive(reason = "x") Optional<List<Leaf>> v) {
    }

    @LlmExposedModel
    record ListList(@InternalIdentifier String ref, @NonSensitive(reason = "x") List<List<Leaf>> v) {
    }

    interface Box<T> {
    }

    /** Not generic itself, but read through a generic interface. */
    @SensitiveObject
    record BoxImpl(
            @SensitiveData(classifications = DataClassification.PII,
                    namespace = PrivacyNamespace.PERSON_NAME, suggestedAction = PrivacyAction.SYNTHESIZE)
            String name) implements Box<LooseLeaf> {
    }

    @LlmExposedModel
    record BoxResponse(@InternalIdentifier String ref, @NonSensitive(reason = "x") Box<LooseLeaf> box) {
    }

    @LlmExposedModel
    record EnvelopeHolder(@InternalIdentifier String ref, @NonSensitive(reason = "x") NameEnvelope env) {
    }

    /** Reports the metadata the resolver used to give a generic: its argument as the element type. */
    private static final class OldMetadata implements FieldMetadataResolver {
        private final DefaultFieldMetadataResolver delegate = new DefaultFieldMetadataResolver();

        @Override
        public List<FieldMetadata> resolve(Class<?> type) {
            return delegate.resolve(type).stream().map(md -> !md.fieldName().equals("env") ? md
                    : new FieldMetadata(md.fieldName(), md.identifier(), md.subjectRole(), md.classifications(),
                            md.namespace(), md.suggestedAction(), md.subjectField(), md.nonSensitiveReason(),
                            NameEnvelope.class, LooseLeaf.class)).toList();
        }

        @Override
        public boolean descendable(Class<?> type) {
            return delegate.descendable(type);
        }
    }

    private static PrivacyContext context() {
        return new PrivacyContext("C", PrivacyScopeType.CASE, "DEFAULT", "test",
                Instant.parse("2030-01-01T00:00:00Z"), PseudonymisationVersion.HMAC_SHA256_V1);
    }

    private static JsonTreeScrubbingEngine engine(FieldMetadataResolver resolver) {
        var profile = new PrivacyProfile("DEFAULT", PrivacyProfile.UnclassifiedBehaviour.FAIL_REQUEST,
                Map.of(DataClassification.PII, PrivacyProfile.ClassificationRule.of(PrivacyAction.SYNTHESIZE)));
        return new JsonTreeScrubbingEngine(resolver,
                new ProfilePrivacyPolicyResolver(Map.of("DEFAULT", profile)), SYNTHETICS);
    }

    private static JsonTreeScrubbingEngine engine() {
        return engine(new DefaultFieldMetadataResolver());
    }

    private static String code(Object source) {
        Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(() -> engine().scrub(source, context()));
        assertThat(thrown).as("the scrub is refused").isInstanceOf(PrivacyRefusedException.class);
        return ((PrivacyRefusedException) thrown).code();
    }

    @Test
    @DisplayName("List, Set, array and Optional of Leaf are scrubbed with Leaf's rules")
    void jdkContainersAreFollowed() {
        var leaf = new Leaf(PII);
        ObjectNode out = engine().scrub(new Shapes("s-1", List.of(leaf), Set.of(leaf), new Leaf[] {leaf},
                Optional.of(leaf)), context()).tree();

        assertThat(out.toString()).doesNotContain(PII);
        assertThat(out.get("list").get(0).get("name").asString()).startsWith("synthetic:");
        assertThat(out.get("set").get(0).get("name").asString()).startsWith("synthetic:");
        assertThat(out.get("array").get(0).get("name").asString()).startsWith("synthetic:");
        assertThat(out.get("optional").get("name").asString()).startsWith("synthetic:");
    }

    @Test
    @DisplayName("a Map of scalars or Objects takes the unclassified-structure path")
    void mapsAreUnclassified() {
        assertThat(code(new StringMap("s-1", Map.of("name", PII)))).isEqualTo("UNCLASSIFIED_STRUCTURE");
        assertThat(code(new ObjectMap("s-1", Map.of("name", PII)))).isEqualTo("UNCLASSIFIED_STRUCTURE");
    }

    @Test
    @DisplayName("Optional<List<Leaf>> and List<List<Leaf>> are both unclassified")
    void nestedContainersKeepTheirOutcome() {
        assertThat(code(new OptionalList("s-1", Optional.of(List.of(new Leaf(PII))))))
                .isEqualTo("UNCLASSIFIED_STRUCTURE");
        assertThat(code(new ListList("s-1", List.of(List.of(new Leaf(PII))))))
                .isEqualTo("UNCLASSIFIED_STRUCTURE");
    }

    @Test
    @DisplayName("with the startup check skipped, a record behind Box<Leaf> is not scrubbed as the Leaf")
    void aGenericInterfaceIsNotFollowed() {
        assertThat(code(new BoxResponse("s-1", new BoxImpl(PII)))).isEqualTo("UNCLASSIFIED_STRUCTURE");
    }

    @Test
    @DisplayName("the prohibited set uses the field's own type, not a differing element type")
    void prohibitedValuesFollowTheSameRule() {
        var source = new EnvelopeHolder("s-1", new NameEnvelope(PII));
        var resolver = new OldMetadata();

        assertThat(SourceValues.prohibited(source, resolver)).contains(PII);
        assertThat(engine(resolver).scrub(source, context()).tree().toString()).doesNotContain(PII);
    }
}

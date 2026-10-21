package io.github.aindriub.dataprism.core.engine;

import io.github.aindriub.dataprism.annotations.DataClassification;
import io.github.aindriub.dataprism.annotations.InternalIdentifier;
import io.github.aindriub.dataprism.annotations.LlmExposedModel;
import io.github.aindriub.dataprism.annotations.NonSensitive;
import io.github.aindriub.dataprism.annotations.PrivacyNamespace;
import io.github.aindriub.dataprism.annotations.SensitiveData;
import io.github.aindriub.dataprism.annotations.SubjectIdentifier;
import io.github.aindriub.dataprism.core.model.FieldMetadata;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DefaultFieldMetadataResolverTest {

    private final DefaultFieldMetadataResolver resolver = new DefaultFieldMetadataResolver();

    private FieldMetadata field(Class<?> type, String name) {
        return resolver.resolve(type).stream()
                .filter(f -> f.fieldName().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no metadata for " + name));
    }

    @Test
    @DisplayName("record components carry their annotations")
    void readsRecordComponents() {
        assertThat(field(ScrubbingFixtures.Declared.class, "fullName").classifications())
                .containsExactly(DataClassification.PII);
        assertThat(field(ScrubbingFixtures.Declared.class, "subjectRef").internalIdentifier()).isTrue();
        assertThat(field(ScrubbingFixtures.Declared.class, "state").nonSensitiveReason()).isNotBlank();
    }

    @Test
    @DisplayName("a plain class is read from its fields, not only records")
    void readsPlainClasses() {
        // S0 threw on anything that was not a record, so this is the S1 change.
        assertThat(field(ScrubbingFixtures.PlainClass.class, "fullName").sensitive()).isTrue();
        assertThat(field(ScrubbingFixtures.PlainClass.class, "subjectRef").internalIdentifier()).isTrue();
        assertThat(resolver.resolve(ScrubbingFixtures.PlainClass.class)).hasSize(3);
    }

    @Test
    @DisplayName("a second subject is an identifier, but not the record's own")
    void readsSubjectIdentifier() {
        FieldMetadata guarantor = field(ScrubbingFixtures.TwoSubjects.class, "guarantorRef");

        assertThat(guarantor.identifier()).isTrue();
        assertThat(guarantor.subjectRole()).isEqualTo("guarantor");
        // Only one field can be the default subject, or "which subject" becomes
        // ambiguous for every sensitive field that does not name one.
        assertThat(guarantor.internalIdentifier()).isFalse();
        assertThat(field(ScrubbingFixtures.TwoSubjects.class, "applicationRef").internalIdentifier()).isTrue();
    }

    @Test
    @DisplayName("metadata is resolved once per class")
    void cachesPerClass() {
        assertThat(resolver.resolve(ScrubbingFixtures.Declared.class))
                .isSameAs(resolver.resolve(ScrubbingFixtures.Declared.class));
    }

    @Test
    @DisplayName("contradictory annotations fail loudly rather than picking one")
    void rejectsContradictions() {
        assertThatThrownBy(() -> resolver.resolve(BothSensitiveAndNot.class))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("@SensitiveData and @NonSensitive");

        assertThatThrownBy(() -> resolver.resolve(BothIdentifierKinds.class))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("@InternalIdentifier and @SubjectIdentifier");
    }

    @Test
    @DisplayName("an unannotated field resolves as undeclared, not as safe")
    void unannotatedIsUndeclared() {
        FieldMetadata contact = field(ScrubbingFixtures.Undeclared.class, "contact");

        assertThat(contact.declared()).isFalse();
        assertThat(contact.sensitive()).isFalse();
        assertThat(contact.classifications()).isEmpty();
    }

    @LlmExposedModel
    record BothSensitiveAndNot(
            @InternalIdentifier String id,
            @SensitiveData(classifications = DataClassification.PII, namespace = PrivacyNamespace.PERSON_NAME)
            @NonSensitive(reason = "contradiction under test")
            String name) {
    }

    @LlmExposedModel
    record BothIdentifierKinds(
            @InternalIdentifier @SubjectIdentifier(role = "other") String id,
            @NonSensitive(reason = "irrelevant") String other) {
    }

    /** Kept so the fixture list above is exercised rather than only declared. */
    @Test
    @DisplayName("every fixture resolves without throwing")
    void allFixturesResolve() {
        List<Class<?>> types = List.of(ScrubbingFixtures.Declared.class,
                ScrubbingFixtures.Undeclared.class, ScrubbingFixtures.TwoSubjects.class,
                ScrubbingFixtures.NotExposed.class, ScrubbingFixtures.PlainClass.class);

        assertThat(types).allSatisfy(type -> assertThat(resolver.resolve(type)).isNotEmpty());
    }

    // ---- elementType: only JDK collections, optionals, arrays and map values -------------------

    @LlmExposedModel
    record ElemLeaf(@NonSensitive(reason = "x") String name) {
    }

    record ElemEnvelope<T>(@NonSensitive(reason = "x") T item) {
    }

    interface ElemBox<T> {
    }

    static class ElemList<T> extends java.util.ArrayList<T> {
    }

    @LlmExposedModel
    record ElemShapes(
            @NonSensitive(reason = "x") List<ElemLeaf> list,
            @NonSensitive(reason = "x") Set<ElemLeaf> set,
            @NonSensitive(reason = "x") ElemLeaf[] array,
            @NonSensitive(reason = "x") Optional<ElemLeaf> optional,
            @NonSensitive(reason = "x") Map<String, ElemLeaf> map,
            @NonSensitive(reason = "x") Map<String, String> scalarMap,
            @NonSensitive(reason = "x") ElemEnvelope<ElemLeaf> envelope,
            @NonSensitive(reason = "x") ElemBox<ElemLeaf> box,
            @NonSensitive(reason = "x") ElemList<ElemLeaf> userList,
            @NonSensitive(reason = "x") AtomicReference<ElemLeaf> atomic,
            @NonSensitive(reason = "x") List<List<ElemLeaf>> nestedList,
            @NonSensitive(reason = "x") Optional<List<ElemLeaf>> optionalList,
            @NonSensitive(reason = "x") List<?> wildcard) {
    }

    @Test
    @DisplayName("elementType is the argument of a JDK collection, optional or map value, and an array component")
    void elementTypeFollowsJdkContainers() {
        for (String name : List.of("list", "set", "array", "optional", "map")) {
            assertThat(field(ElemShapes.class, name).elementType()).as(name).isEqualTo(ElemLeaf.class);
        }
        assertThat(field(ElemShapes.class, "scalarMap").elementType()).isEqualTo(String.class);
    }

    @Test
    @DisplayName("elementType is null for a user generic, a user subtype of a collection and other JDK wrappers")
    void elementTypeIsNullOtherwise() {
        for (String name : List.of("envelope", "box", "userList", "atomic", "nestedList", "optionalList",
                "wildcard")) {
            assertThat(field(ElemShapes.class, name).elementType()).as(name).isNull();
        }
    }
}

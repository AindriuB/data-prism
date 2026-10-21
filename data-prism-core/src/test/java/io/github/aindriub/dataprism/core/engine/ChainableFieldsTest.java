package io.github.aindriub.dataprism.core.engine;

import io.github.aindriub.dataprism.annotations.Chainable;
import io.github.aindriub.dataprism.annotations.DataClassification;
import io.github.aindriub.dataprism.annotations.InternalIdentifier;
import io.github.aindriub.dataprism.annotations.LlmExposedModel;
import io.github.aindriub.dataprism.annotations.NonSensitive;
import io.github.aindriub.dataprism.annotations.SensitiveData;
import io.github.aindriub.dataprism.annotations.SensitiveObject;
import io.github.aindriub.dataprism.annotations.SubjectIdentifier;
import io.github.aindriub.dataprism.core.descriptor.ModelDescriptor;
import io.github.aindriub.dataprism.core.model.ChainableField;
import io.github.aindriub.dataprism.core.spi.KeyNamespace;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Which paths of a model are keys another source accepts (task 240). */
class ChainableFieldsTest {

    private static final String CODE = "INVALID_CHAINABLE_FIELD:";

    @LlmExposedModel
    record Scalar(@InternalIdentifier @Chainable(keyNamespace = "parcel-ref") String parcelRef,
                  @InternalIdentifier String other,
                  @NonSensitive(reason = "x") String state) {
    }

    @LlmExposedModel
    record Subject(@SubjectIdentifier(role = "guarantor") @Chainable(keyNamespace = "party-ref") String ref) {
    }

    @LlmExposedModel
    record KeyList(@InternalIdentifier @Chainable(keyNamespace = "depot-ref") List<String> keys) {
    }

    @SensitiveObject
    record Route(@InternalIdentifier @Chainable(keyNamespace = "depot-ref") String depot,
                 @NonSensitive(reason = "x") String label) {
    }

    @LlmExposedModel
    record Routes(@NonSensitive(reason = "structure") List<Route> routes) {
    }

    @SensitiveObject
    record Inner(@InternalIdentifier @Chainable(keyNamespace = "parcel-ref") String b) {
    }

    @LlmExposedModel
    record Nested(@NonSensitive(reason = "structure") Inner a) {
    }

    @LlmExposedModel
    record InRoutes(@NonSensitive(reason = "structure") Routes inner) {
    }

    @LlmExposedModel
    record Unclassified(@Chainable(keyNamespace = "parcel-ref") String loose) {
    }

    @LlmExposedModel
    record Safe(@NonSensitive(reason = "x") @Chainable(keyNamespace = "parcel-ref") String state) {
    }

    @LlmExposedModel
    record Sensitive(@SensitiveData(classifications = DataClassification.PII)
                     @Chainable(keyNamespace = "parcel-ref") String name) {
    }

    @LlmExposedModel
    record BadNamespace(@InternalIdentifier @Chainable(keyNamespace = "Zq Bad") String ref) {
    }

    @LlmExposedModel
    record Plain(@InternalIdentifier String ref, @NonSensitive(reason = "x") String state) {
    }

    @LlmExposedModel
    record Annotated(@InternalIdentifier @Chainable(keyNamespace = "parcel-ref") String ref) {
    }


    record Hidden(@InternalIdentifier @Chainable(keyNamespace = "parcel-ref") String b) {
    }

    @LlmExposedModel
    record InHidden(@NonSensitive(reason = "structure") Hidden h) {
    }

    @LlmExposedModel
    record Opt(@InternalIdentifier @Chainable(keyNamespace = "parcel-ref") java.util.Optional<String> ref) {
    }

    @LlmExposedModel
    record OptRoute(@NonSensitive(reason = "structure") java.util.Optional<Route> opt) {
    }

    @LlmExposedModel
    record MapField(@InternalIdentifier @Chainable(keyNamespace = "parcel-ref") Map<String, String> m) {
    }

    @LlmExposedModel
    record MapOfRoutes(@NonSensitive(reason = "structure") Map<String, Route> m) {
    }

    @LlmExposedModel
    record Both(@InternalIdentifier @SensitiveData(classifications = DataClassification.PII)
                @Chainable(keyNamespace = "parcel-ref") String ref) {
    }

    @LlmExposedModel
    record Long64(@InternalIdentifier @Chainable(keyNamespace = "parcel-ref") String aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa) {
    }

    @LlmExposedModel
    record Long65(@InternalIdentifier @Chainable(keyNamespace = "parcel-ref") String aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa) {
    }

    @LlmExposedModel
    record Long63List(@InternalIdentifier @Chainable(keyNamespace = "parcel-ref") List<String> aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa) {
    }

    @LlmExposedModel
    record Long63Array(@InternalIdentifier @Chainable(keyNamespace = "parcel-ref") String[] aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa) {
    }

    @LlmExposedModel
    record Long64List(@InternalIdentifier @Chainable(keyNamespace = "parcel-ref") List<String> aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa) {
    }

    private static Map<String, ModelDescriptor> descriptor(Class<?> type, String field,
                                                           Boolean chainable, String namespace) {
        return Map.of(type.getName(), new ModelDescriptor(type.getName(), null, null, null,
                Map.of(field, new ModelDescriptor.FieldDescriptor(List.of(), null, null, null, null, null,
                        chainable, namespace))));
    }

    @Test
    @DisplayName("a scalar identifier gives its name and namespace, not an element")
    void scalar() {
        assertThat(ChainableFields.of(Scalar.class)).containsExactly(
                new ChainableField("parcelRef", new KeyNamespace("parcel-ref"), false));
    }

    @Test
    @DisplayName("a @SubjectIdentifier may be chainable")
    void subjectIdentifier() {
        assertThat(ChainableFields.of(Subject.class)).containsExactly(
                new ChainableField("ref", new KeyNamespace("party-ref"), false));
    }

    @Test
    @DisplayName("a list of keys gives keys[]")
    void listOfKeys() {
        assertThat(ChainableFields.of(KeyList.class)).containsExactly(
                new ChainableField("keys[]", new KeyNamespace("depot-ref"), true));
    }

    @Test
    @DisplayName("a list of records gives routes[].depot")
    void listOfRecords() {
        assertThat(ChainableFields.of(Routes.class)).containsExactly(
                new ChainableField("routes[].depot", new KeyNamespace("depot-ref"), true));
    }

    @Test
    @DisplayName("a nested object gives a.b")
    void nested() {
        assertThat(ChainableFields.of(Nested.class)).containsExactly(
                new ChainableField("a.b", new KeyNamespace("parcel-ref"), false));
    }

    @Test
    @DisplayName("an element stays an element beneath a nested object")
    void elementUnderNested() {
        assertThat(ChainableFields.of(InRoutes.class)).containsExactly(
                new ChainableField("inner.routes[].depot", new KeyNamespace("depot-ref"), true));
    }

    @Test
    @DisplayName("a model with nothing chainable gives an empty list")
    void none() {
        assertThat(ChainableFields.of(Plain.class)).isEmpty();
    }

    @Test
    @DisplayName("an unclassified, non-sensitive or sensitive field is refused naming model and field")
    void notAnIdentifier() {
        for (Class<?> type : List.of(Unclassified.class, Safe.class, Sensitive.class)) {
            assertThatThrownBy(() -> ChainableFields.of(type))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageStartingWith(CODE)
                    .hasMessageContaining(type.getName());
        }
        assertThatThrownBy(() -> ChainableFields.of(Unclassified.class)).hasMessageContaining("loose");
    }

    @Test
    @DisplayName("a namespace outside the grammar is refused without echoing it")
    void badNamespace() {
        assertThatThrownBy(() -> ChainableFields.of(BadNamespace.class))
                .hasMessageStartingWith(CODE)
                .hasMessageContaining("ref")
                .hasMessageNotContaining("Zq Bad");
    }

    @Test
    @DisplayName("a descriptor may add a chainable flag to an identifier")
    void descriptorAdds() {
        var d = descriptor(Plain.class, "ref", true, "parcel-ref");

        assertThat(ChainableFields.of(Plain.class, d)).containsExactly(
                new ChainableField("ref", new KeyNamespace("parcel-ref"), false));
    }

    @Test
    @DisplayName("a descriptor may declare the identifier and the flag together")
    void descriptorDeclaresIdentifierToo() {
        var d = Map.of(Unclassified.class.getName(), new ModelDescriptor(Unclassified.class.getName(), null, null,
                null, Map.of("loose", new ModelDescriptor.FieldDescriptor(List.of(), null, null, null, null,
                        "self", true, "parcel-ref"))));

        // Annotated @Chainable on an unclassified field, made an identifier by the descriptor: valid.
        assertThat(ChainableFields.of(Unclassified.class, d)).hasSize(1);
    }

    @Test
    @DisplayName("a descriptor flag on a non-identifier is refused")
    void descriptorOnNonIdentifier() {
        assertThatThrownBy(() -> ChainableFields.of(Plain.class, descriptor(Plain.class, "state", true, "parcel-ref")))
                .hasMessageStartingWith(CODE).hasMessageContaining("state");
    }

    @Test
    @DisplayName("descriptor chainable true without keyNamespace is refused")
    void descriptorWithoutNamespace() {
        assertThatThrownBy(() -> ChainableFields.of(Plain.class, descriptor(Plain.class, "ref", true, null)))
                .hasMessageStartingWith(CODE).hasMessageContaining("ref");
    }

    @Test
    @DisplayName("a descriptor keyNamespace outside the grammar is refused")
    void descriptorBadNamespace() {
        assertThatThrownBy(() -> ChainableFields.of(Plain.class, descriptor(Plain.class, "ref", true, "NOPE")))
                .hasMessageStartingWith(CODE).hasMessageNotContaining("NOPE");
    }

    @Test
    @DisplayName("a descriptor may not remove a flag the annotation declared")
    void descriptorRemoves() {
        assertThatThrownBy(() -> ChainableFields.of(Annotated.class,
                descriptor(Annotated.class, "ref", false, null)))
                .hasMessageStartingWith(CODE).hasMessageContaining("ref");
    }

    @Test
    @DisplayName("a descriptor may not change the annotation's namespace")
    void descriptorChanges() {
        assertThatThrownBy(() -> ChainableFields.of(Annotated.class,
                descriptor(Annotated.class, "ref", true, "other-ref")))
                .hasMessageStartingWith(CODE).hasMessageContaining("ref");
    }

    @Test
    @DisplayName("a descriptor restating the annotation's namespace is accepted")
    void descriptorRestates() {
        assertThat(ChainableFields.of(Annotated.class, descriptor(Annotated.class, "ref", true, "parcel-ref")))
                .containsExactly(new ChainableField("ref", new KeyNamespace("parcel-ref"), false));
    }

    @Test
    @DisplayName("an Optional is the plain path, not an element")
    void optional() {
        assertThat(ChainableFields.of(Opt.class)).containsExactly(
                new ChainableField("ref", new KeyNamespace("parcel-ref"), false));
        assertThat(ChainableFields.of(OptRoute.class)).containsExactly(
                new ChainableField("opt.depot", new KeyNamespace("depot-ref"), false));
    }

    @Test
    @DisplayName("a chainable map, or a chainable inside a map value, is refused")
    void maps() {
        assertThatThrownBy(() -> ChainableFields.of(MapField.class))
                .hasMessageStartingWith(CODE).hasMessageContaining("m");
        assertThatThrownBy(() -> ChainableFields.of(MapOfRoutes.class))
                .hasMessageStartingWith(CODE).hasMessageContaining(Route.class.getName());
    }

    @Test
    @DisplayName("a chainable inside a type the scrubber does not descend into is refused")
    void notDescendable() {
        assertThatThrownBy(() -> ChainableFields.of(InHidden.class))
                .hasMessageStartingWith(CODE).hasMessageContaining(Hidden.class.getName());
    }

    @Test
    @DisplayName("an identifier that is also @SensitiveData may be chainable")
    void identifierAndSensitive() {
        assertThat(ChainableFields.of(Both.class)).hasSize(1);
    }

    @Test
    @DisplayName("a path of 64 bytes passes and 65 is refused, counting the [] suffix")
    void pathCap() {
        assertThat(ChainableFields.of(Long64.class)).hasSize(1);
        assertThatThrownBy(() -> ChainableFields.of(Long65.class)).hasMessageStartingWith(CODE);
        assertThat(ChainableFields.of(Long63List.class)).hasSize(1);
        assertThat(ChainableFields.of(Long63Array.class)).hasSize(1);
        assertThatThrownBy(() -> ChainableFields.of(Long64List.class)).hasMessageStartingWith(CODE);
    }
}

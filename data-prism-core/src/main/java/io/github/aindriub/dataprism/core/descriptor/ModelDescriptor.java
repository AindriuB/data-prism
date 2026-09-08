package io.github.aindriub.dataprism.core.descriptor;

import io.github.aindriub.dataprism.annotations.DataClassification;
import io.github.aindriub.dataprism.annotations.PrivacyAction;
import io.github.aindriub.dataprism.annotations.PrivacyNamespace;
import io.github.aindriub.dataprism.annotations.UndeclaredFields;

import java.util.List;
import java.util.Map;

/**
 * Classification for one type, stated outside the type.
 *
 * <p>The alternative to annotations, for the cases annotations cannot reach: a
 * DTO generated from a schema and regenerated on every build, a type from a
 * dependency, a model that has to be reclassified without shipping a release.
 * Annotations remain the better default — the classification sits beside the
 * field it describes, and moves with it — but they require owning the source.
 *
 * @param exposed          whether the type may be returned at all. A descriptor
 *                         may say yes for a type carrying no annotation, which is
 *                         the whole point for third-party models; exposure alone
 *                         discloses nothing, since every field still has to be
 *                         classified or the response is refused
 * @param descendable      whether the type may be descended into when nested
 * @param undeclaredFields what silence means on this type
 */
public record ModelDescriptor(
        String typeName,
        Boolean exposed,
        Boolean descendable,
        UndeclaredFields undeclaredFields,
        Map<String, FieldDescriptor> fields) {

    public ModelDescriptor {
        fields = Map.copyOf(fields);
    }

    public FieldDescriptor field(String name) {
        return fields.get(name);
    }

    /**
     * Classification for one field. Every part is optional; what is absent is
     * simply not stated, and whatever the annotations say stands.
     */
    public record FieldDescriptor(
            List<DataClassification> classifications,
            PrivacyNamespace namespace,
            PrivacyAction action,
            String subjectField,
            String nonSensitiveReason,
            String identifierRole) {

        public FieldDescriptor {
            classifications = classifications == null ? List.of() : List.copyOf(classifications);
        }

        public boolean sensitive() {
            return !classifications.isEmpty();
        }
    }
}

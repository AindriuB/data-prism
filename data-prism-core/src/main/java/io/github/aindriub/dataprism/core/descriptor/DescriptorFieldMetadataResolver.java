package io.github.aindriub.dataprism.core.descriptor;

import io.github.aindriub.dataprism.annotations.DataClassification;
import io.github.aindriub.dataprism.annotations.PrivacyAction;
import io.github.aindriub.dataprism.annotations.PrivacyNamespace;
import io.github.aindriub.dataprism.annotations.UndeclaredFields;
import io.github.aindriub.dataprism.core.FieldMetadata;
import io.github.aindriub.dataprism.core.FieldMetadataResolver;
import io.github.aindriub.dataprism.core.policy.ActionStrictness;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Merges descriptor metadata over another resolver's, and can only tighten it.
 *
 * <p>The rule throughout: a descriptor may say a field is more sensitive than
 * the code claims, never less. Classifications are unioned, the stricter action
 * wins, a sensitive descriptor overrides a {@code @NonSensitive} annotation, and
 * a descriptor cannot turn a classified field back into an unclassified one.
 *
 * <p>The reason is the same one that stops an annotation widening a profile: if
 * an external file could declassify a field, then anyone who can edit
 * configuration can disclose data, and the classification in the source stops
 * being worth reading. Relaxing an over-classified field is still possible, but
 * it happens in the privacy profile via {@code override: true}, where it is a
 * deliberate policy decision rather than a metadata edit.
 *
 * <p>Two things a descriptor decides outright rather than tightens. It may mark a
 * type exposed or descendable when nothing else does — without that it could not
 * classify a third-party model at all, and exposure by itself reveals nothing
 * because every field still has to be classified or the response is refused. And
 * it may declare an identifier, which only ever removes a field from the output.
 */
public final class DescriptorFieldMetadataResolver implements FieldMetadataResolver {

    private final FieldMetadataResolver delegate;
    private final Map<String, ModelDescriptor> descriptors;

    public DescriptorFieldMetadataResolver(FieldMetadataResolver delegate,
                                           Map<String, ModelDescriptor> descriptors) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.descriptors = Map.copyOf(Objects.requireNonNull(descriptors, "descriptors"));
    }

    @Override
    public List<FieldMetadata> resolve(Class<?> type) {
        List<FieldMetadata> base = delegate.resolve(type);
        ModelDescriptor descriptor = descriptors.get(type.getName());
        if (descriptor == null) {
            return base;
        }

        checkUndeclaredFields(type, descriptor);

        List<FieldMetadata> out = new ArrayList<>(base.size());
        for (FieldMetadata field : base) {
            ModelDescriptor.FieldDescriptor stated = descriptor.field(field.fieldName());
            FieldMetadata merged = stated == null ? field : tighten(type, field, stated);
            out.add(applyTypeDefault(merged, descriptor));
        }
        return List.copyOf(out);
    }

    @Override
    public boolean exposed(Class<?> type) {
        ModelDescriptor descriptor = descriptors.get(type.getName());
        if (descriptor != null && descriptor.exposed() != null) {
            return descriptor.exposed();
        }
        return delegate.exposed(type);
    }

    @Override
    public boolean descendable(Class<?> type) {
        ModelDescriptor descriptor = descriptors.get(type.getName());
        if (descriptor != null && descriptor.descendable() != null) {
            return descriptor.descendable() || delegate.descendable(type);
        }
        return exposed(type) || delegate.descendable(type);
    }

    /**
     * The type's stated default for fields still undeclared after merging. Only
     * ever REDACT or DROP here — NON_SENSITIVE is refused above, because it is
     * the one setting that releases data nobody classified.
     */
    private static FieldMetadata applyTypeDefault(FieldMetadata field, ModelDescriptor descriptor) {
        if (field.declared() || descriptor.undeclaredFields() == null) {
            return field;
        }
        PrivacyAction action = switch (descriptor.undeclaredFields()) {
            case REDACT -> PrivacyAction.REDACT;
            case DROP -> PrivacyAction.REMOVE;
            default -> null;
        };
        if (action == null) {
            return field;
        }
        // Classified rather than merely acted on, so the value also enters the
        // validator's prohibited set.
        return new FieldMetadata(field.fieldName(), field.identifier(), field.subjectRole(),
                List.of(DataClassification.CONFIDENTIAL), field.namespace(), action,
                field.subjectField(), null, field.valueType(), field.elementType());
    }

    private static FieldMetadata tighten(Class<?> type, FieldMetadata field,
                                         ModelDescriptor.FieldDescriptor stated) {
        String where = type.getName() + "." + field.fieldName();

        Set<DataClassification> classifications = new LinkedHashSet<>(field.classifications());
        classifications.addAll(stated.classifications());

        // A descriptor may not un-classify. The annotation's claim that a field is
        // safe is discarded as soon as anything says otherwise; the reverse is not
        // available, because that is the direction that discloses data.
        String reason = field.nonSensitiveReason();
        if (!classifications.isEmpty()) {
            reason = null;
        } else if (stated.nonSensitiveReason() != null) {
            reason = stated.nonSensitiveReason();
        }

        PrivacyAction action = stricter(field.suggestedAction(), stated.action());
        PrivacyNamespace namespace = mergeNamespace(where, field.namespace(), stated.namespace());
        String subjectField = mergeSubject(where, field.subjectField(), stated.subjectField());

        boolean identifier = field.identifier() || stated.identifierRole() != null;
        String role = field.identifier() ? field.subjectRole() : stated.identifierRole();

        return new FieldMetadata(field.fieldName(), identifier, role,
                List.copyOf(classifications), namespace, action, subjectField, reason,
                field.valueType(), field.elementType());
    }

    private static PrivacyAction stricter(PrivacyAction fromCode, PrivacyAction fromDescriptor) {
        if (fromCode == null) {
            return fromDescriptor;
        }
        if (fromDescriptor == null) {
            return fromCode;
        }
        return ActionStrictness.stricter(fromCode, fromDescriptor);
    }

    /**
     * Namespaces have no strictness order — they are semantic labels, not degrees
     * of protection — so a genuine disagreement cannot be resolved by picking one.
     * Getting it wrong would give the same person two synthetic identities and
     * read as two people, so it fails at startup instead.
     */
    private static PrivacyNamespace mergeNamespace(String where, PrivacyNamespace fromCode,
                                                   PrivacyNamespace fromDescriptor) {
        if (fromDescriptor == null || fromDescriptor == fromCode) {
            return fromCode;
        }
        if (fromCode == null || fromCode == PrivacyNamespace.NONE) {
            return fromDescriptor;
        }
        throw new IllegalStateException(where + " has namespace " + fromCode
                + " in code and " + fromDescriptor + " in the descriptor; one of them is wrong,"
                + " and guessing would split one subject into two");
    }

    private static String mergeSubject(String where, String fromCode, String fromDescriptor) {
        if (fromDescriptor == null || fromDescriptor.equals(fromCode)) {
            return fromCode == null ? "" : fromCode;
        }
        if (fromCode == null || fromCode.isEmpty()) {
            return fromDescriptor;
        }
        throw new IllegalStateException(where + " names subject '" + fromCode
                + "' in code and '" + fromDescriptor + "' in the descriptor");
    }

    /**
     * {@code NON_SENSITIVE} is the one setting here that releases data, so a
     * descriptor cannot introduce it. Everything else it may state freely.
     */
    private static void checkUndeclaredFields(Class<?> type, ModelDescriptor descriptor) {
        if (descriptor.undeclaredFields() == UndeclaredFields.NON_SENSITIVE) {
            throw new IllegalStateException("descriptor for " + type.getName()
                    + " sets undeclaredFields = NON_SENSITIVE; a descriptor may only tighten,"
                    + " and that setting releases fields nobody classified");
        }
    }
}

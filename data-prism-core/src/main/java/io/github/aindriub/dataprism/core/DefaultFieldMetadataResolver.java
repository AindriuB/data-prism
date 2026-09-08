package io.github.aindriub.dataprism.core;

import io.github.aindriub.dataprism.annotations.DataClassification;
import io.github.aindriub.dataprism.annotations.InternalIdentifier;
import io.github.aindriub.dataprism.annotations.LlmExposedModel;
import io.github.aindriub.dataprism.annotations.PrivacyAction;
import io.github.aindriub.dataprism.annotations.NonSensitive;
import io.github.aindriub.dataprism.annotations.PrivacyNamespace;
import io.github.aindriub.dataprism.annotations.SensitiveData;
import io.github.aindriub.dataprism.annotations.SensitiveObject;
import io.github.aindriub.dataprism.annotations.SubjectIdentifier;
import io.github.aindriub.dataprism.annotations.UndeclaredFields;

import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads metadata from records, classes and accessors.
 *
 * <p>Each field is read from every declaration it could have landed on — record
 * component, field, and accessor method — because javac propagates a record
 * component's annotations only to the targets the annotation permits, and which
 * of those a given annotation reaches is not something a model author should
 * have to think about. Reading all three means the metadata is the same wherever
 * it was written.
 */
public final class DefaultFieldMetadataResolver implements FieldMetadataResolver {

    /** ClassValue for correct classloader semantics: an undeployed app can still be collected. */
    private final ClassValue<List<FieldMetadata>> cache = new ClassValue<>() {
        @Override
        protected List<FieldMetadata> computeValue(Class<?> type) {
            return read(type);
        }
    };

    @Override
    public List<FieldMetadata> resolve(Class<?> type) {
        return cache.get(type);
    }

    private static List<FieldMetadata> read(Class<?> type) {
        Map<String, List<AnnotatedElement>> sources = new LinkedHashMap<>();
        Map<String, Type> declaredTypes = new LinkedHashMap<>();

        if (type.isRecord()) {
            for (RecordComponent rc : type.getRecordComponents()) {
                List<AnnotatedElement> elements = new ArrayList<>();
                elements.add(rc);
                addIfPresent(elements, declaredField(type, rc.getName()));
                elements.add(rc.getAccessor());
                sources.put(rc.getName(), elements);
                declaredTypes.put(rc.getName(), rc.getGenericType());
            }
        } else {
            for (Field field : type.getDeclaredFields()) {
                if (field.isSynthetic() || Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                List<AnnotatedElement> elements = new ArrayList<>();
                elements.add(field);
                addIfPresent(elements, accessor(type, field.getName()));
                sources.put(field.getName(), elements);
                declaredTypes.put(field.getName(), field.getGenericType());
            }
        }

        List<FieldMetadata> out = new ArrayList<>(sources.size());
        sources.forEach((name, elements) ->
                out.add(read(type, name, elements, declaredTypes.get(name))));
        return List.copyOf(out);
    }

    private static FieldMetadata read(Class<?> type, String name,
                                      List<AnnotatedElement> elements, Type declaredType) {
        SensitiveData sensitive = first(elements, SensitiveData.class);
        NonSensitive nonSensitive = first(elements, NonSensitive.class);
        boolean internal = first(elements, InternalIdentifier.class) != null;
        SubjectIdentifier subject = first(elements, SubjectIdentifier.class);

        if (sensitive != null && nonSensitive != null) {
            throw new IllegalStateException(type.getName() + "." + name
                    + " is annotated both @SensitiveData and @NonSensitive");
        }
        if (internal && subject != null) {
            throw new IllegalStateException(type.getName() + "." + name
                    + " is annotated both @InternalIdentifier and @SubjectIdentifier;"
                    + " @InternalIdentifier already means role \"" + FieldMetadata.SELF + "\"");
        }

        boolean identifier = internal || subject != null;
        String role = internal ? FieldMetadata.SELF : (subject == null ? null : subject.role());

        List<DataClassification> classifications =
                sensitive == null ? List.of() : List.of(sensitive.classifications());
        PrivacyNamespace namespace =
                sensitive == null ? PrivacyNamespace.NONE : sensitive.namespace();
        PrivacyAction action = sensitive == null ? null : sensitive.suggestedAction();
        String subjectField = sensitive == null ? "" : sensitive.subject();
        String reason = nonSensitive == null ? null : nonSensitive.reason();

        if (sensitive == null && nonSensitive == null && !identifier) {
            SensitiveObject typeLevel = type.getAnnotation(SensitiveObject.class);
            if (typeLevel != null && typeLevel.classifications().length > 0) {
                // The type classifies its own contents — an address split across
                // several components, say. Stated once rather than per field.
                classifications = List.of(typeLevel.classifications());
                namespace = typeLevel.namespace();
                action = typeLevel.suggestedAction();
            } else {
                switch (undeclaredFieldsOf(type)) {
                    case NON_SENSITIVE -> reason = "declared by " + type.getSimpleName()
                            + ".undeclaredFields = NON_SENSITIVE";
                    // Classified rather than merely acted on, so the value also
                    // enters the validator's prohibited set: if it escapes by some
                    // other route, the last line still catches it.
                    case REDACT -> {
                        classifications = List.of(DataClassification.CONFIDENTIAL);
                        action = PrivacyAction.REDACT;
                    }
                    case DROP -> {
                        classifications = List.of(DataClassification.CONFIDENTIAL);
                        action = PrivacyAction.REMOVE;
                    }
                    case PROFILE_DEFAULT -> {
                        // Left undeclared on purpose: the profile decides.
                    }
                }
            }
        }

        return new FieldMetadata(name, identifier, role, classifications, namespace, action,
                subjectField, reason, rawType(declaredType), elementType(declaredType));
    }

    /**
     * The type's stated default for its own unannotated fields. An explicit
     * setting on {@code @LlmExposedModel} wins over one on
     * {@code @SensitiveObject}, since the former is the more specific statement
     * about how this type is exposed.
     */
    private static UndeclaredFields undeclaredFieldsOf(Class<?> type) {
        LlmExposedModel exposed = type.getAnnotation(LlmExposedModel.class);
        if (exposed != null && exposed.undeclaredFields() != UndeclaredFields.PROFILE_DEFAULT) {
            return exposed.undeclaredFields();
        }
        SensitiveObject nested = type.getAnnotation(SensitiveObject.class);
        return nested == null ? UndeclaredFields.PROFILE_DEFAULT : nested.undeclaredFields();
    }

    private static Class<?> rawType(Type type) {
        if (type instanceof Class<?> c) {
            return c;
        }
        if (type instanceof ParameterizedType p && p.getRawType() instanceof Class<?> c) {
            return c;
        }
        return Object.class;
    }

    /**
     * The element type of a collection, where it is stated. Erasure means a raw
     * or wildcard collection yields nothing, which is correct: without a type
     * there is no metadata to descend with, and the engine must treat the
     * contents as unclassified rather than guess.
     */
    private static Class<?> elementType(Type type) {
        if (type instanceof ParameterizedType p) {
            Type[] arguments = p.getActualTypeArguments();
            if (arguments.length > 0 && arguments[arguments.length - 1] instanceof Class<?> c) {
                return c;
            }
        }
        if (type instanceof Class<?> c && c.isArray()) {
            return c.getComponentType();
        }
        return null;
    }

    private static <A extends java.lang.annotation.Annotation> A first(
            List<AnnotatedElement> elements, Class<A> annotation) {
        for (AnnotatedElement element : elements) {
            A found = element.getAnnotation(annotation);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static void addIfPresent(List<AnnotatedElement> elements, AnnotatedElement element) {
        if (element != null) {
            elements.add(element);
        }
    }

    private static Field declaredField(Class<?> type, String name) {
        try {
            return type.getDeclaredField(name);
        } catch (NoSuchFieldException e) {
            return null;
        }
    }

    private static Method accessor(Class<?> type, String name) {
        String capitalised = Character.toUpperCase(name.charAt(0)) + name.substring(1);
        for (String candidate : List.of(name, "get" + capitalised, "is" + capitalised)) {
            try {
                return type.getMethod(candidate);
            } catch (NoSuchMethodException ignored) {
                // Try the next shape; a field without an accessor is normal.
            }
        }
        return null;
    }
}

package io.github.aindriub.dataprism.core.engine;

import io.github.aindriub.dataprism.annotations.Chainable;
import io.github.aindriub.dataprism.core.descriptor.DescriptorFieldMetadataResolver;
import io.github.aindriub.dataprism.core.descriptor.ModelDescriptor;
import io.github.aindriub.dataprism.core.model.ChainableField;
import io.github.aindriub.dataprism.core.model.FieldMetadata;
import io.github.aindriub.dataprism.core.spi.FieldMetadataResolver;
import io.github.aindriub.dataprism.core.spi.KeyNamespace;

import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Lists the paths of a model that hold a key another source accepts.
 *
 * <p>Walks the same graph as {@link DefaultFieldMetadataResolver}: nested models and collections of them.
 * The flag is metadata only. It is read here and nowhere in scrubbing, so it can never change what happens
 * to the field.
 *
 * <p>A chainable flag is valid only on a field already classified as an identifier. A descriptor may add a
 * flag but may not remove one the annotation declared, nor change its namespace. Every violation throws
 * {@link IllegalStateException} whose message starts {@value #CODE}, naming the model and field and never
 * a namespace or a value.
 *
 * <p>Only the graph the scrubber walks is walked: collections, arrays, wrappers such as {@code Optional}
 * and nested records the resolver says are descendable. A chainable declaration on a map, inside a map
 * value, or inside a type the scrubber does not descend into is refused, because no link could be built
 * from it. A declared path over {@value #MAX_PATH_BYTES} UTF-8 bytes is refused.
 *
 * <p>The annotation and the descriptor are deliberately not symmetric: a descriptor that restates the
 * annotation's {@code keyNamespace} without {@code chainable} is accepted, but {@code chainable: true}
 * with no {@code keyNamespace} is always refused. A field that is both an identifier and
 * {@code @SensitiveData} is accepted, since it is already classified as an identifier.
 */
public final class ChainableFields {

    public static final String CODE = "INVALID_CHAINABLE_FIELD";

    /** The longest declared path, in UTF-8 bytes (OD-200-4). */
    public static final int MAX_PATH_BYTES = 64;

    private ChainableFields() {
    }

    public static List<ChainableField> of(Class<?> model) {
        return of(model, Map.of());
    }

    /**
     * @param descriptors model descriptors by type name, as returned by {@code ModelDescriptors.fromYaml}
     * @return the chainable paths in ascending path order
     * @throws IllegalStateException with a message starting {@code INVALID_CHAINABLE_FIELD:}
     */
    public static List<ChainableField> of(Class<?> model, Map<String, ModelDescriptor> descriptors) {
        Objects.requireNonNull(model, "model");
        FieldMetadataResolver resolver = new DescriptorFieldMetadataResolver(
                new DefaultFieldMetadataResolver(), Objects.requireNonNull(descriptors, "descriptors"));
        List<ChainableField> out = new ArrayList<>();
        walk(model, "", false, null, resolver, descriptors, new HashSet<>(), out);
        out.sort(Comparator.comparing(ChainableField::path));
        return List.copyOf(out);
    }

    private static void walk(Class<?> type, String prefix, boolean underElement, String refusal,
                             FieldMetadataResolver resolver, Map<String, ModelDescriptor> descriptors,
                             Set<Class<?>> inProgress, List<ChainableField> out) {
        if (!inProgress.add(type)) {
            return;
        }
        Map<String, List<AnnotatedElement>> declarations = declarations(type);
        refuseOrphans(type, declarations);
        refuseUnreadMembers(type);
        ModelDescriptor descriptor = descriptors.get(type.getName());
        for (FieldMetadata field : resolver.resolve(type)) {
            String name = field.fieldName();
            ModelDescriptor.FieldDescriptor stated = descriptor == null ? null : descriptor.field(name);
            KeyNamespace namespace = declared(type, name, declarations.getOrDefault(name, List.of()), stated);
            Class<?> valueType = field.valueType();
            boolean map = Map.class.isAssignableFrom(valueType);
            boolean collection = valueType.isArray() || Iterable.class.isAssignableFrom(valueType);
            // Optional<X> and other wrappers take the plain path; the type they hold is the last argument.
            Class<?> inner = field.elementType() != null ? field.elementType() : valueType;
            if (namespace != null) {
                if (refusal != null) {
                    throw invalid(type, name, refusal);
                }
                if (map) {
                    throw invalid(type, name, "is chainable inside a map, which the path grammar cannot name");
                }
                if (!field.identifier()) {
                    throw invalid(type, name, "is not classified as an identifier");
                }
                String path = prefix + name + (collection ? "[]" : "");
                if (path.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_PATH_BYTES) {
                    throw invalid(type, name, "has a path longer than " + MAX_PATH_BYTES + " bytes");
                }
                out.add(new ChainableField(path, namespace, underElement || collection));
            } else if (inner != null && userType(inner)) {
                String next = refusal;
                if (next == null && map) {
                    next = "is chainable inside a map value, which the path grammar cannot name";
                } else if (next == null && !resolver.descendable(inner)) {
                    next = "is chainable in a type the scrubber does not descend into";
                }
                walk(inner, prefix + name + (collection ? "[]." : "."), underElement || collection, next,
                        resolver, descriptors, inProgress, out);
            }
        }
        inProgress.remove(type);
    }

    /**
     * Refuses any {@code @Chainable} member declared on the type that no field consumes: a getter matching
     * no property, a static field, a method that is not an accessor, an interface method. A declaration the
     * walk cannot reach must never be silently ignored.
     */
    private static void refuseOrphans(Class<?> type, Map<String, List<AnnotatedElement>> declarations) {
        Set<AnnotatedElement> consumed = new HashSet<>();
        declarations.values().forEach(consumed::addAll);
        for (Field field : type.getDeclaredFields()) {
            if (field.isAnnotationPresent(Chainable.class) && !consumed.contains(field)) {
                throw invalid(type, field.getName(), "is declared chainable but is not a readable property");
            }
        }
        for (Method method : type.getDeclaredMethods()) {
            if (!method.isSynthetic() && method.isAnnotationPresent(Chainable.class)
                    && !consumed.contains(method)) {
                throw invalid(type, method.getName(), "is declared chainable but is not a readable property");
            }
        }
    }

    /**
     * The resolver reads only the members a type declares itself, so a {@code @Chainable} on a superclass
     * or an implemented interface is never read: refuse it. The same holds for any constructor parameter
     * other than a record's canonical one, whose annotations repeat those of the component.
     */
    private static void refuseUnreadMembers(Class<?> type) {
        Set<Class<?>> supertypes = new java.util.LinkedHashSet<>();
        collectSupertypes(type, supertypes);
        for (Class<?> supertype : supertypes) {
            for (Field field : supertype.getDeclaredFields()) {
                if (field.isAnnotationPresent(Chainable.class)) {
                    throw invalid(supertype, field.getName(), "is declared chainable on a supertype of "
                            + type.getName() + ", which the scrubber does not read");
                }
            }
            for (Method method : supertype.getDeclaredMethods()) {
                if (!method.isSynthetic() && method.isAnnotationPresent(Chainable.class)) {
                    throw invalid(supertype, method.getName(), "is declared chainable on a supertype of "
                            + type.getName() + ", which the scrubber does not read");
                }
            }
        }
        if (!type.isRecord()) {
            for (java.lang.reflect.Constructor<?> constructor : type.getDeclaredConstructors()) {
                for (java.lang.reflect.Parameter parameter : constructor.getParameters()) {
                    if (parameter.isAnnotationPresent(Chainable.class)) {
                        throw invalid(type, parameter.getName(),
                                "is declared chainable on a constructor parameter, which the scrubber does not read");
                    }
                }
            }
        }
    }

    private static void collectSupertypes(Class<?> type, Set<Class<?>> out) {
        List<Class<?>> direct = new ArrayList<>(List.of(type.getInterfaces()));
        if (type.getSuperclass() != null) {
            direct.add(type.getSuperclass());
        }
        for (Class<?> supertype : direct) {
            if (supertype != Object.class && supertype != Record.class && out.add(supertype)) {
                collectSupertypes(supertype, out);
            }
        }
    }

    /**
     * Whether a nested type may carry declarations to look at: anything but a primitive, an enum, an
     * annotation or a JDK type. The walk looks inside every such type, descendable or not, so a
     * declaration it cannot reach is refused rather than silently ignored.
     */
    private static boolean userType(Class<?> type) {
        String name = type.getName();
        return !type.isPrimitive() && !type.isEnum() && !type.isAnnotation()
                && !name.startsWith("java.") && !name.startsWith("javax.");
    }

    /** The effective namespace, or null when the field is not declared chainable. */
    private static KeyNamespace declared(Class<?> type, String name, List<AnnotatedElement> elements,
                                         ModelDescriptor.FieldDescriptor stated) {
        String fromCode = null;
        for (AnnotatedElement element : elements) {
            Chainable found = element.getAnnotation(Chainable.class);
            if (found != null) {
                fromCode = found.keyNamespace();
                break;
            }
        }
        Boolean flag = stated == null ? null : stated.chainable();
        String fromDescriptor = stated == null ? null : stated.keyNamespace();

        if (Boolean.TRUE.equals(flag) && fromDescriptor == null) {
            throw invalid(type, name, "is chainable in the descriptor without a keyNamespace");
        }
        if (Boolean.FALSE.equals(flag) && fromCode != null) {
            throw invalid(type, name, "is chainable in code and a descriptor may not remove that");
        }
        if (!Boolean.TRUE.equals(flag) && fromDescriptor != null && fromCode == null) {
            throw invalid(type, name, "has a descriptor keyNamespace without chainable: true");
        }
        if (fromCode != null && fromDescriptor != null && !fromCode.equals(fromDescriptor)) {
            throw invalid(type, name, "has a different keyNamespace in the descriptor than in code");
        }

        String value = fromCode != null ? fromCode : (Boolean.TRUE.equals(flag) ? fromDescriptor : null);
        if (value == null) {
            return null;
        }
        try {
            return new KeyNamespace(value);
        } catch (IllegalArgumentException e) {
            throw invalid(type, name, "has a keyNamespace outside [a-z][a-z0-9-]{0,31}");
        }
    }

    private static IllegalStateException invalid(Class<?> type, String field, String reason) {
        return new IllegalStateException(CODE + ": " + type.getName() + "." + field + " " + reason);
    }

    private static Map<String, List<AnnotatedElement>> declarations(Class<?> type) {
        Map<String, List<AnnotatedElement>> out = new java.util.LinkedHashMap<>();
        if (type.isRecord()) {
            for (RecordComponent rc : type.getRecordComponents()) {
                List<AnnotatedElement> elements = new ArrayList<>();
                elements.add(rc);
                elements.add(rc.getAccessor());
                try {
                    elements.add(type.getDeclaredField(rc.getName()));
                } catch (NoSuchFieldException ignored) {
                    // A record component always has a field; tolerated rather than assumed.
                }
                out.put(rc.getName(), elements);
            }
        } else {
            for (Field field : type.getDeclaredFields()) {
                if (field.isSynthetic() || Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                List<AnnotatedElement> elements = new ArrayList<>();
                elements.add(field);
                Method accessor = accessor(type, field.getName());
                if (accessor != null) {
                    elements.add(accessor);
                }
                out.put(field.getName(), elements);
            }
        }
        return out;
    }

    private static Method accessor(Class<?> type, String name) {
        String capitalised = Character.toUpperCase(name.charAt(0)) + name.substring(1);
        for (String candidate : List.of(name, "get" + capitalised, "is" + capitalised)) {
            try {
                return type.getMethod(candidate);
            } catch (NoSuchMethodException ignored) {
                // Try the next shape.
            }
        }
        return null;
    }
}

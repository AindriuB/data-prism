package io.github.aindriub.dataprism.core.engine;

import io.github.aindriub.dataprism.annotations.Chainable;
import io.github.aindriub.dataprism.core.descriptor.DescriptorFieldMetadataResolver;
import io.github.aindriub.dataprism.core.descriptor.ModelDescriptor;
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
 * <p>Recursion terminates per type and mode. A model that contains itself through a list or an
 * {@code Optional} lists only the shallowest path ({@code id}, not {@code children[].id}): the path
 * grammar cannot express unbounded depth, and the deeper occurrences are the same declaration, which
 * simply get no links. Through a map, or any other place the walk refuses, the same recursion is
 * refused.
 *
 * <p>The route rule: a field whose declared type the path grammar cannot express (a map, a nested
 * generic such as {@code List<List<X>>}, a multi-dimensional array, a type variable or a wildcard) is
 * searched in full, every type argument, array dimension and bound. A chainable declaration reachable
 * through it is refused, even when the same declaration is also consumed on an ordinary route elsewhere.
 * The analysis makes at most {@value #MAX_VISITS} type visits and refuses a model beyond that as too deep
 * to analyse.
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

    /** The most type visits one analysis may make; a diamond-shaped model would otherwise cost 2^depth. */
    public static final int MAX_VISITS = 10_000;

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
        Set<AnnotatedElement> consumed = new HashSet<>();
        Set<String> consumedFields = new HashSet<>();
        Reached rootSupertypes = new Reached();
        rootSupertypes.seed(model);
        collectSupertypes(model, rootSupertypes);
        refuseReached(model, "<supertypes>", rootSupertypes, descriptors);
        walk(model, "", false, null, resolver, descriptors, new HashSet<>(), out, consumed, consumedFields,
                new int[1]);
        refuseUnconsumed(model, descriptors, consumed, consumedFields);
        out.sort(Comparator.comparing(ChainableField::path));
        return List.copyOf(out);
    }

    private static void walk(Class<?> type, String prefix, boolean underElement, String refusal,
                             FieldMetadataResolver resolver, Map<String, ModelDescriptor> descriptors,
                             Set<String> inProgress, List<ChainableField> out,
                             Set<AnnotatedElement> consumed, Set<String> consumedFields, int[] visits) {
        if (++visits[0] > MAX_VISITS) {
            throw invalid(type, "*", "model too deep to analyse");
        }
        // Keyed by type and mode: meeting a type again in refusal mode (under a map, or where the
        // scrubber does not descend) must still be walked, or its chainable fields would slip through.
        String state = type.getName() + (refusal == null ? "" : "!refusal");
        if (!inProgress.add(state)) {
            return;
        }
        Map<String, List<AnnotatedElement>> declarations = declarations(type);
        ModelDescriptor descriptor = descriptors.get(type.getName());
        for (FieldMetadata field : resolver.resolve(type)) {
            String name = field.fieldName();
            ModelDescriptor.FieldDescriptor stated = descriptor == null ? null : descriptor.field(name);
            KeyNamespace namespace = declared(type, name, declarations.getOrDefault(name, List.of()), stated);
            refuseInexpressibleRoute(type, name, genericType(type, name), descriptors);
            Class<?> valueType = field.valueType();
            boolean map = Map.class.isAssignableFrom(valueType);
            boolean collection = valueType.isArray() || Iterable.class.isAssignableFrom(valueType);
            // Only an array, a JDK collection or Optional, or a JDK map's value has an element type. A user
            // generic has none, so its own type is walked, as the scrubber does: its fields are not its
            // type argument's.
            Class<?> inner = field.elementType() != null ? field.elementType() : valueType;
            if (namespace != null) {
                if (refusal != null) {
                    throw invalid(type, name, refusal);
                }
                if (map) {
                    throw invalid(type, name, "is chainable inside a map, which the path grammar cannot name");
                }
                java.lang.reflect.Type declaredType = genericType(type, name);
                if (declaredType instanceof Class<?> c && c.isArray()
                        && (c.getComponentType() == byte.class || c.getComponentType() == char.class)) {
                    throw invalid(type, name, "is a byte or char array, which is serialised as one string");
                }
                if (!expressible(declaredType)) {
                    throw invalid(type, name, "is chainable but its type cannot be named in a path");
                }
                if (!field.identifier()) {
                    throw invalid(type, name, "is not classified as an identifier");
                }
                String path = prefix + name + (collection ? "[]" : "");
                if (path.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_PATH_BYTES) {
                    throw invalid(type, name, "has a path longer than " + MAX_PATH_BYTES + " bytes");
                }
                out.add(new ChainableField(path, namespace, underElement || collection));
                consumedFields.add(type.getName() + "." + name);
                consume(type, name, declarations.getOrDefault(name, List.of()), consumed);
            } else if (inner != null && userType(inner)) {
                String next = refusal;
                if (next == null && map) {
                    next = "is chainable inside a map value, which the path grammar cannot name";
                } else if (next == null && !resolver.descendable(inner)) {
                    next = "is chainable in a type the scrubber does not descend into";
                }
                walk(inner, prefix + name + (collection ? "[]." : "."), underElement || collection, next,
                        resolver, descriptors, inProgress, out, consumed, consumedFields, visits);
            }
        }
        inProgress.remove(state);
    }

    /**
     * Marks the {@code @Chainable} occurrences a chainable field accounts for: its record component, field
     * and accessor, and the canonical-constructor parameter at the component's position. Identity and
     * position, not "any constructor of a record".
     */
    private static void consume(Class<?> type, String name, List<AnnotatedElement> elements,
                                Set<AnnotatedElement> consumed) {
        for (AnnotatedElement element : elements) {
            if (element.isAnnotationPresent(Chainable.class)) {
                consumed.add(element);
            }
        }
        if (type.isRecord()) {
            RecordComponent[] components = type.getRecordComponents();
            Class<?>[] types = new Class<?>[components.length];
            int position = -1;
            for (int i = 0; i < components.length; i++) {
                types[i] = components[i].getType();
                if (components[i].getName().equals(name)) {
                    position = i;
                }
            }
            try {
                var canonical = type.getDeclaredConstructor(types);
                if (position >= 0 && canonical.getParameters()[position].isAnnotationPresent(Chainable.class)) {
                    consumed.add(canonical.getParameters()[position]);
                }
            } catch (NoSuchMethodException ignored) {
                // A record always has its canonical constructor.
            }
        }
    }

    /**
     * Collect pass then refusal: visits every type the model's data can reach, through supertypes,
     * interfaces, field and record component types, constructor parameter types and every generic type
     * argument, stopping at primitives, annotations and JDK types (enums are visited). Method return and
     * parameter types are not followed: a method returning another model says nothing about this one's
     * data, though a stray {@code @Chainable} on such a method in a visited type is still refused. Any {@code @Chainable} occurrence found (field,
     * method, method or constructor parameter, record component) that the walk did not turn into a
     * chainable field is refused: a declaration the walk cannot reach must never be silently ignored.
     *
     * <p>Superclass fields are never read by the resolver. Its accessor lookup does see inherited public
     * getters, so a {@code @Chainable} there is refused too; that is over-refusal, and fails closed.
     *
     * <p>Descriptor entries stating {@code chainable} or {@code keyNamespace} must name a field the walk
     * consumed, so a typo cannot quietly declare nothing.
     */
    private static void refuseUnconsumed(Class<?> model, Map<String, ModelDescriptor> descriptors,
                                         Set<AnnotatedElement> consumed, Set<String> consumedFields) {
        Reached reachable = new Reached();
        collect(model, reachable);
        for (Class<?> type : reachable) {
            for (RecordComponent component : type.isRecord() ? type.getRecordComponents()
                    : new RecordComponent[0]) {
                // RecordComponent has identity equality and is re-created per call: match by name.
                if (component.isAnnotationPresent(Chainable.class)
                        && !consumedFields.contains(type.getName() + "." + component.getName())) {
                    throw invalid(type, component.getName(),
                            "is declared chainable but the scrubber does not read it");
                }
            }
            for (Field field : type.getDeclaredFields()) {
                refuseIfUnconsumed(type, field, field.getName(), consumed);
            }
            for (Method method : type.getDeclaredMethods()) {
                if (method.isSynthetic()) {
                    continue;
                }
                refuseIfUnconsumed(type, method, method.getName(), consumed);
                for (java.lang.reflect.Parameter parameter : method.getParameters()) {
                    refuseIfUnconsumed(type, parameter, method.getName() + "(" + parameter.getName() + ")",
                            consumed);
                }
            }
            for (java.lang.reflect.Constructor<?> constructor : type.getDeclaredConstructors()) {
                for (java.lang.reflect.Parameter parameter : constructor.getParameters()) {
                    refuseIfUnconsumed(type, parameter, "<init>(" + parameter.getName() + ")", consumed);
                }
            }
            ModelDescriptor descriptor = descriptors.get(type.getName());
            if (descriptor != null) {
                descriptor.fields().forEach((name, stated) -> {
                    if ((stated.chainable() != null || stated.keyNamespace() != null)
                            && !consumedFields.contains(type.getName() + "." + name)) {
                        throw invalid(type, name, "is named by a descriptor chainable entry but the model has"
                                + " no such chainable field");
                    }
                });
            }
        }
    }

    private static void refuseIfUnconsumed(Class<?> type, AnnotatedElement element, String member,
                                           Set<AnnotatedElement> consumed) {
        if (element.isAnnotationPresent(Chainable.class) && !consumed.contains(element)) {
            throw invalid(type, member, "is declared chainable but the scrubber does not read it");
        }
    }

    /** As {@link #userType}, but enums are visited too: a declaration on one is refused, not ignored. */
    private static boolean collectable(Class<?> type) {
        String name = type.getName();
        return !type.isPrimitive() && !type.isAnnotation()
                && !name.startsWith("java.") && !name.startsWith("javax.");
    }

    /** The classes reached, and the type variables and wildcards already followed (bounds may be cyclic). */
    private static final class Reached extends java.util.LinkedHashSet<Class<?>> {
        private final Set<java.lang.reflect.Type> seen = new HashSet<>();
        /** The type being examined: seeded so a reference back to it is not a route, and not checked. */
        private Class<?> self;

        void seed(Class<?> type) {
            self = type;
            add(type);
        }
    }

    /**
     * Whether a declared type maps to one path: a plain class, a one-dimensional array, or a collection
     * or wrapper of exactly one plain class. Maps, nested generics, multi-dimensional arrays, type
     * variables and wildcards cannot be named in the path grammar.
     */
    private static boolean expressible(java.lang.reflect.Type type) {
        if (type == null) {
            return false;
        }
        if (type instanceof Class<?> c) {
            Class<?> element = c;
            int dimensions = 0;
            while (element.isArray()) {
                element = element.getComponentType();
                dimensions++;
            }
            // A user subclass of a JDK collection or map hides its element type in its supertypes.
            boolean hiddenContainer = !element.getName().startsWith("java.")
                    && (Iterable.class.isAssignableFrom(element) || Map.class.isAssignableFrom(element));
            // A raw List, Optional and so on names no element type, which the contract requires.
            return dimensions <= 1 && !hiddenContainer && !EXPRESSIBLE_GENERICS.contains(element);
        }
        if (type instanceof java.lang.reflect.ParameterizedType p && p.getRawType() instanceof Class<?> raw) {
            java.lang.reflect.Type[] arguments = p.getActualTypeArguments();
            return EXPRESSIBLE_GENERICS.contains(raw) && p.getOwnerType() == null && arguments.length == 1
                    && arguments[0] instanceof Class<?> argument && !argument.isArray();
        }
        return false;
    }

    /** The only generic types the path grammar names: a list or set of one plain class, or an Optional. */
    private static final Set<Class<?>> EXPRESSIBLE_GENERICS = Set.of(java.util.List.class,
            java.util.Collection.class, java.util.Set.class, Iterable.class, java.util.Optional.class);

    /** The plain class an expressible type holds, or null. */
    private static Class<?> heldClass(java.lang.reflect.Type type) {
        java.lang.reflect.Type held = type;
        if (type instanceof java.lang.reflect.ParameterizedType p && p.getActualTypeArguments().length == 1) {
            held = p.getActualTypeArguments()[0];
        }
        if (held instanceof Class<?> c) {
            Class<?> element = c;
            while (element.isArray()) {
                element = element.getComponentType();
            }
            return element;
        }
        return null;
    }

    /** The first {@code @Chainable} member declared on the type, as {@code "member"}, or null. */
    private static String chainableMember(Class<?> type, Map<String, ModelDescriptor> descriptors) {
        ModelDescriptor descriptor = descriptors.get(type.getName());
        if (descriptor != null) {
            for (Map.Entry<String, ModelDescriptor.FieldDescriptor> entry : descriptor.fields().entrySet()) {
                if (Boolean.TRUE.equals(entry.getValue().chainable())) {
                    return entry.getKey();
                }
            }
        }
        if (type.isRecord()) {
            for (RecordComponent component : type.getRecordComponents()) {
                if (component.isAnnotationPresent(Chainable.class)) {
                    return component.getName();
                }
            }
        }
        for (Field field : type.getDeclaredFields()) {
            if (field.isAnnotationPresent(Chainable.class)) {
                return field.getName();
            }
        }
        for (Method method : type.getDeclaredMethods()) {
            if (method.isSynthetic()) {
                continue;
            }
            if (method.isAnnotationPresent(Chainable.class)) {
                return method.getName();
            }
            for (java.lang.reflect.Parameter parameter : method.getParameters()) {
                if (parameter.isAnnotationPresent(Chainable.class)) {
                    return method.getName() + "(" + parameter.getName() + ")";
                }
            }
        }
        for (java.lang.reflect.Constructor<?> constructor : type.getDeclaredConstructors()) {
            for (java.lang.reflect.Parameter parameter : constructor.getParameters()) {
                if (parameter.isAnnotationPresent(Chainable.class)) {
                    return "<init>(" + parameter.getName() + ")";
                }
            }
        }
        return null;
    }

    /**
     * The route rule: a field whose type the path grammar cannot express is searched in full, and any
     * chainable declaration reachable through it is refused, even if the same declaration is consumed on
     * another, ordinary route.
     */
    private static void refuseInexpressibleRoute(Class<?> owner, String field, java.lang.reflect.Type type,
                                                 Map<String, ModelDescriptor> descriptors) {
        if (type == null) {
            return;
        }
        Reached reached = new Reached();
        if (!expressible(type)) {
            collect(type, reached);
        } else {
            // The scrubber reads declared fields only, so whatever the held type inherits is unreachable.
            Class<?> held = heldClass(type);
            if (held != null && userType(held)) {
                reached.seed(held);
                collectSupertypes(held, reached);
            }
        }
        refuseReached(owner, field, reached, descriptors);
    }

    private static void collectSupertypes(Class<?> type, Reached out) {
        collect(type.getGenericSuperclass(), out);
        for (java.lang.reflect.Type supertype : type.getGenericInterfaces()) {
            collect(supertype, out);
        }
    }

    private static void refuseReached(Class<?> owner, String field, Reached reached,
                                      Map<String, ModelDescriptor> descriptors) {
        for (Class<?> candidate : reached) {
            if (candidate == reached.self) {
                continue;
            }
            String member = chainableMember(candidate, descriptors);
            if (member != null) {
                throw invalid(owner, field, "reaches the chainable declaration " + candidate.getName() + "."
                        + member + " through a route the scrubber does not read or the path grammar cannot express");
            }
        }
    }

    /** The field's full generic type, from the record component or the declared field. */
    private static java.lang.reflect.Type genericType(Class<?> type, String name) {
        if (type.isRecord()) {
            for (RecordComponent component : type.getRecordComponents()) {
                if (component.getName().equals(name)) {
                    return component.getGenericType();
                }
            }
            return null;
        }
        try {
            return type.getDeclaredField(name).getGenericType();
        } catch (NoSuchFieldException e) {
            return null;
        }
    }

    private static void collect(Class<?> type, Reached out) {
        if (!collectable(type) || type == Record.class || !out.add(type)) {
            return;
        }
        collectSupertypes(type, out);
        Class<?>[] permitted = type.getPermittedSubclasses();
        if (permitted != null) {
            for (Class<?> subclass : permitted) {
                collect(subclass, out);
            }
        }
        for (Field field : type.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) {
                continue; // a constant is not data; a @Chainable on the field itself is still refused
            }
            collect(field.getGenericType(), out);
        }
        for (java.lang.reflect.Constructor<?> constructor : type.getDeclaredConstructors()) {
            for (java.lang.reflect.Type parameter : constructor.getGenericParameterTypes()) {
                collect(parameter, out);
            }
        }
    }

    private static void collect(java.lang.reflect.Type type, Reached out) {
        switch (type) {
            case null -> { }
            case Class<?> c -> {
                Class<?> element = c;
                while (element.isArray()) {
                    element = element.getComponentType();
                }
                collect(element, out);
            }
            case java.lang.reflect.TypeVariable<?> v -> {
                if (out.seen.add(v)) {
                    for (java.lang.reflect.Type bound : v.getBounds()) {
                        collect(bound, out);
                    }
                }
            }
            case java.lang.reflect.ParameterizedType p -> {
                collect(p.getRawType(), out);
                collect(p.getOwnerType(), out);
                for (java.lang.reflect.Type argument : p.getActualTypeArguments()) {
                    collect(argument, out);
                }
            }
            case java.lang.reflect.GenericArrayType g -> collect(g.getGenericComponentType(), out);
            case java.lang.reflect.WildcardType w -> {
                if (!out.seen.add(w)) {
                    return;
                }
                for (java.lang.reflect.Type bound : w.getUpperBounds()) {
                    collect(bound, out);
                }
                for (java.lang.reflect.Type bound : w.getLowerBounds()) {
                    collect(bound, out);
                }
            }
            default -> { }
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
        List<String> fromParameters = new ArrayList<>();
        for (AnnotatedElement element : elements) {
            Chainable found = element.getAnnotation(Chainable.class);
            if (found == null) {
                continue;
            }
            if (element instanceof java.lang.reflect.Parameter) {
                // A constructor parameter alone does not declare the field (it is refused as unread),
                // but it must agree with the declaration it sits beside.
                fromParameters.add(found.keyNamespace());
                continue;
            }
            if (fromCode != null && !fromCode.equals(found.keyNamespace())) {
                throw invalid(type, name, "has conflicting keyNamespace declarations in code");
            }
            fromCode = found.keyNamespace();
        }
        for (String parameter : fromParameters) {
            if (fromCode != null && !fromCode.equals(parameter)) {
                throw invalid(type, name, "has conflicting keyNamespace declarations in code");
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
                canonicalParameter(type, rc.getName()).ifPresent(elements::add);
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

    /** The canonical constructor's parameter for a record component, matched by position. */
    private static java.util.Optional<java.lang.reflect.Parameter> canonicalParameter(Class<?> type,
                                                                                      String name) {
        RecordComponent[] components = type.getRecordComponents();
        Class<?>[] types = new Class<?>[components.length];
        int position = -1;
        for (int i = 0; i < components.length; i++) {
            types[i] = components[i].getType();
            if (components[i].getName().equals(name)) {
                position = i;
            }
        }
        try {
            return position < 0 ? java.util.Optional.empty()
                    : java.util.Optional.of(type.getDeclaredConstructor(types).getParameters()[position]);
        } catch (NoSuchMethodException e) {
            return java.util.Optional.empty();
        }
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

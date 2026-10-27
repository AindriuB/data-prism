package io.github.aindriub.dataprism.core.engine;

import com.fasterxml.jackson.annotation.JsonValue;
import io.github.aindriub.dataprism.annotations.PrivacyAction;
import io.github.aindriub.dataprism.annotations.PrivacyNamespace;
import io.github.aindriub.dataprism.core.model.FieldMetadata;
import io.github.aindriub.dataprism.core.model.FieldProtection;
import io.github.aindriub.dataprism.core.model.FieldProtection.Protection;
import io.github.aindriub.dataprism.core.model.ModelDescription;
import io.github.aindriub.dataprism.core.model.ModelDescription.Undeclared;
import io.github.aindriub.dataprism.core.model.PrivacyContext;
import io.github.aindriub.dataprism.core.policy.EffectivePrivacyPolicy;
import io.github.aindriub.dataprism.core.policy.PrivacyPolicyResolver;
import io.github.aindriub.dataprism.core.spi.FieldMetadataResolver;
import io.github.aindriub.dataprism.core.spi.KeyNamespace;
import tools.jackson.databind.annotation.JsonSerialize;

import java.lang.ref.Reference;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Describes how a model's fields are protected by walking the same plan the scrubber applies: the
 * same {@link FieldMetadataResolver} (annotations and descriptors), the same
 * {@link PrivacyPolicyResolver} (profile tightening) and the same nested-type rule.
 *
 * <p>The scrubber decides from the JSON value it meets; the describer has only the declared type, so
 * where the type does not say whether a field holds a scalar, an object or an array (an {@code Object},
 * a raw collection, a Jackson node), it describes the field only when every reading the scrubber could
 * take gives the same protection, and otherwise leaves it out. Leaving a field out is the fail-closed
 * answer: it is never described more loosely than the scrubber treats it.
 *
 * <p>A record or other structure type that may serialise as a scalar ({@code @JsonValue}, or a
 * {@code @JsonSerialize} on the type or on the component holding it) is treated the same way: the
 * scrubber then applies the field's own policy to the whole value, so it is described only when that
 * agrees with the structure reading.
 *
 * <p>A nested record is described through only when the scrubber's own reader
 * ({@link SourceTree#readsAsPlainBean}) writes it as a plain object of exactly its components: not as an
 * array, not with an object id, not through {@code @JsonValue} or a custom serializer, and with no
 * serializer or format override on the holding component. Anything else is omitted, which fails closed.
 * There is no mix-in route, because that reader is fixed. A type that may instead be written as a scalar
 * and that the scrubber would descend into is omitted too.
 *
 * <p>Whether the {@code ValueTokenSource} can hash and tokenize is read afresh for each description, by the
 * caller, and passed in. When it cannot (or its {@code available()} throws), every HASH or TOKENIZE field is
 * omitted, because the scrubber would refuse it. That is fail closed: a source reporting {@code true} wrongly
 * is the only way to over-claim, and the interface default is {@code false}.
 *
 * <p>A model that contains itself is described to its shallowest occurrence only. The path grammar
 * cannot express recursion, so the deeper paths the scrubber emits ({@code children[].name}) are
 * omitted and no claim is made about them.
 *
 * <p>Nothing here reads a value, a classification name or a class name into the result.
 */
final class ScrubPlanDescriber {

    private static final int MAX_DEPTH = 16;

    /** The most types one description may walk; a diamond-shaped model would otherwise cost k^depth. */
    static final int MAX_VISITS = 10_000;

    private final FieldMetadataResolver resolver;
    private final PrivacyPolicyResolver policies;
    private final PrivacyContext context;
    private final Map<String, KeyNamespace> chainables;
    private final boolean tokensAvailable;
    private final Map<String, FieldProtection> found = new TreeMap<>();
    private int visits;

    private ScrubPlanDescriber(FieldMetadataResolver resolver, PrivacyPolicyResolver policies,
                               PrivacyContext context, Map<String, KeyNamespace> chainables,
                               boolean tokensAvailable) {
        this.resolver = resolver;
        this.policies = policies;
        this.context = context;
        this.chainables = chainables;
        this.tokensAvailable = tokensAvailable;
    }

    /**
     * @param chainables the model's chainable paths, empty when links are disabled
     * @return empty when the plan cannot be described (an unknown profile, a profile that releases
     *         undeclared properties, a model nested deeper than the scrubber allows)
     */
    static Optional<ModelDescription> describe(Class<?> model, PrivacyContext context, boolean linksEnabled,
                                               FieldMetadataResolver resolver, PrivacyPolicyResolver policies,
                                               List<ChainableField> chainables, boolean tokensAvailable) {
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(context, "context");
        Map<String, KeyNamespace> keys = new HashMap<>();
        if (linksEnabled) {
            chainables.forEach(c -> keys.put(c.path(), c.keyNamespace()));
        }
        ScrubPlanDescriber describer = new ScrubPlanDescriber(resolver, policies, context, keys, tokensAvailable);
        try {
            Undeclared undeclared = describer.undeclared();
            if (undeclared == null) {
                return Optional.empty();
            }
            describer.walk(model, "", false, false, new HashSet<>(), 0);
            List<FieldProtection> fields = describer.found.values().stream()
                    // A removed field is not listed, unless a link names it and the agent must find it here.
                    .filter(f -> f.protection() != Protection.ABSENT || f.keyNamespace() != null)
                    .toList();
            return Optional.of(new ModelDescription(fields, undeclared));
        } catch (IllegalStateException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /** What the profile does to a property the model does not declare; null when it cannot be stated. */
    private Undeclared undeclared() {
        EffectivePrivacyPolicy policy = policies.resolve(FieldMetadata.undeclared("x"), context);
        if (!policy.allowed()) {
            return Undeclared.REFUSE;
        }
        return switch (policy.action()) {
            case REMOVE -> Undeclared.DROP;
            case REDACT -> Undeclared.RENAME;
            default -> null;
        };
    }

    /**
     * @param gone       an enclosing field is removed, so nothing below it is emitted
     * @param hasSubject an enclosing object, or this one, has a subject to synthesise from
     */
    private void walk(Class<?> type, String prefix, boolean gone, boolean hasSubject,
                      Set<Class<?>> inProgress, int depth) {
        if (depth > MAX_DEPTH) {
            throw new IllegalStateException("nesting exceeded " + MAX_DEPTH + " levels");
        }
        if (++visits > MAX_VISITS) {
            throw new IllegalStateException("model too large to describe");
        }
        if (!inProgress.add(type)) {
            // The path grammar cannot express unbounded depth; the shallowest occurrence is listed.
            return;
        }
        List<FieldMetadata> fields = resolver.resolve(type);
        Map<String, FieldMetadata> byName = fields.stream()
                .collect(Collectors.toMap(FieldMetadata::fieldName, Function.identity()));
        boolean subject = hasSubject || fields.stream().anyMatch(FieldMetadata::internalIdentifier);

        for (FieldMetadata md : fields) {
            field(type, md, byName, prefix, gone, subject, inProgress, depth);
        }
        inProgress.remove(type);
    }

    private void field(Class<?> owner, FieldMetadata md, Map<String, FieldMetadata> siblings, String prefix, boolean gone,
                       boolean subject, Set<Class<?>> inProgress, int depth) {
        EffectivePrivacyPolicy policy = null;
        if (!gone) {
            policy = resolve(md);
            if (policy == null || !policy.allowed()) {
                // The scrubber refuses the response, or cannot apply the action: not described.
                return;
            }
        }
        boolean removed = gone || policy.action() == PrivacyAction.REMOVE;
        String path = prefix + md.fieldName();

        Shape shape = shapeOf(owner, md);
        String leaf = shape.array ? path + "[]" : path;
        switch (shape.kind) {
            case RECORD -> walk(shape.record, leaf + ".", removed, subject, inProgress, depth + 1);
            case SCALAR -> {
                if (removed) {
                    add(leaf, Protection.ABSENT, null);
                } else if (applicable(md, policy, siblings, subject)) {
                    add(leaf, protection(policy.action()), namespace(policy));
                }
            }
            case STRUCTURE -> structure(md, leaf, removed, null);
            case DYNAMIC -> structure(md, leaf, removed, policy);
            case OMIT -> {
                // Descended into or written as a scalar, depending on serialiser settings this cannot read.
            }
        }
    }

    /**
     * An object the scrubber does not descend into. The field's own policy is ignored for it; the
     * profile's setting for unclassified data decides. A {@code fieldPolicy} is given for a value of
     * unknown shape, which the scrubber may instead meet as a scalar under that policy.
     */
    private void structure(FieldMetadata md, String leaf, boolean removed, EffectivePrivacyPolicy fieldPolicy) {
        if (removed) {
            add(leaf, Protection.ABSENT, null);
            return;
        }
        EffectivePrivacyPolicy structure = resolve(FieldMetadata.unannotated(md.fieldName()));
        if (structure == null || !structure.allowed()) {
            return;
        }
        Protection asStructure = switch (structure.action()) {
            case PASS_THROUGH -> Protection.VERBATIM;
            case REDACT -> Protection.MARKER;
            default -> Protection.ABSENT;
        };
        if (fieldPolicy != null) {
            boolean sameReading = protection(fieldPolicy.action()) == asStructure
                    && namespace(fieldPolicy) == null
                    && fieldPolicy.generalization() == null;
            if (!sameReading) {
                return;
            }
        }
        add(leaf, asStructure, null);
    }

    private void add(String path, Protection protection, String valueNamespace) {
        found.putIfAbsent(path, new FieldProtection(path, protection, valueNamespace, keyNamespace(path)));
    }

    private String keyNamespace(String path) {
        KeyNamespace key = chainables.get(path);
        return key == null ? null : key.value();
    }

    private EffectivePrivacyPolicy resolve(FieldMetadata md) {
        try {
            return policies.resolve(md, context);
        } catch (IllegalStateException e) {
            // GENERALIZE with no rule for its namespace: the scrubber cannot apply it either.
            return null;
        }
    }

    /** Whether the scrubber can apply the action to a scalar here without refusing for want of input. */
    private boolean applicable(FieldMetadata md, EffectivePrivacyPolicy policy,
                                      Map<String, FieldMetadata> siblings, boolean subject) {
        if (policy.action() == PrivacyAction.HASH || policy.action() == PrivacyAction.TOKENIZE) {
            return tokensAvailable;
        }
        if (policy.action() != PrivacyAction.SYNTHESIZE) {
            return true;
        }
        return md.subjectField().isEmpty() ? subject : siblings.containsKey(md.subjectField());
    }

    private static Protection protection(PrivacyAction action) {
        return switch (action) {
            case PASS_THROUGH -> Protection.VERBATIM;
            case SYNTHESIZE -> Protection.SYNTHETIC;
            case TOKENIZE, HASH -> Protection.TOKEN;
            case REDACT -> Protection.MARKER;
            case GENERALIZE -> Protection.BUCKET;
            case REMOVE -> Protection.ABSENT;
        };
    }

    /** Only a synthetic or token value has a namespace worth stating. */
    private static String namespace(EffectivePrivacyPolicy policy) {
        Protection p = protection(policy.action());
        boolean namespaced = p == Protection.SYNTHETIC || p == Protection.TOKEN;
        return namespaced && policy.namespace() != null && policy.namespace() != PrivacyNamespace.NONE
                ? policy.namespace().name()
                : null;
    }

    private enum Kind { SCALAR, RECORD, STRUCTURE, DYNAMIC, OMIT }

    private record Shape(Kind kind, boolean array, Class<?> record) {
    }

    /**
     * What a field's declared type says it holds, by the scrubber's own rule for nested types. A
     * collection or array is described by its element; an array inside an array, or an element whose
     * type is not stated, is dynamic because the scrubber reads it by the value it meets.
     */
    private Shape shapeOf(Class<?> owner, FieldMetadata md) {
        Class<?> value = md.valueType();
        if (value == byte[].class || value == char[].class) {
            // Written as one string (base64 for bytes), not as an array of elements.
            return overridden(owner, md) ? new Shape(Kind.DYNAMIC, false, null) : new Shape(Kind.SCALAR, false, null);
        }
        boolean array = value != null && (value.isArray() || Collection.class.isAssignableFrom(value));
        Class<?> nested = nestedType(md, array);
        if (nested == null || nested.isArray() && nested != byte[].class && nested != char[].class) {
            // An array inside an array: its object elements take the unclassified-structure path and
            // its scalars the field's policy, so no single protection is stated for it.
            return new Shape(Kind.DYNAMIC, array, null);
        }
        if (nested == byte[].class || nested == char[].class) {
            return overridden(owner, md) ? new Shape(Kind.DYNAMIC, array, null) : new Shape(Kind.SCALAR, array, null);
        }
        boolean mayBeScalar = mayBeScalar(owner, md, nested);
        if (resolver.descendable(nested)) {
            return new Shape(mayBeScalar || !plain(owner, md, nested) ? Kind.OMIT : Kind.RECORD, array, nested);
        }
        if (nested.isPrimitive() || nested.isEnum() || scalar(nested)) {
            return overridden(owner, md) ? new Shape(Kind.DYNAMIC, array, null) : new Shape(Kind.SCALAR, array, null);
        }
        if (Map.class.isAssignableFrom(nested) || concrete(nested) && !platform(nested)) {
            if (mayBeScalar) {
                return new Shape(Kind.DYNAMIC, array, null);
            }
            return new Shape(Map.class.isAssignableFrom(nested) || plain(owner, md, nested)
                    ? Kind.STRUCTURE : Kind.OMIT, array, null);
        }
        return new Shape(Kind.DYNAMIC, array, null);
    }

    /**
     * Whether the holding component carries a serializer or shape override, as Jackson merges them, so
     * that a scalar-typed value may be written as an object. Such a field is dynamic: the agreement rule
     * decides, which usually omits it.
     */
    private static boolean overridden(Class<?> owner, FieldMetadata md) {
        return SourceTree.overridesSerialization(owner, md.fieldName());
    }

    /** Whether the scrubber's own reader writes the type as a plain object of its components. */
    private static boolean plain(Class<?> owner, FieldMetadata md, Class<?> nested) {
        return SourceTree.readsAsPlainBean(owner, md.fieldName(), nested);
    }

    private static void supertypes(Class<?> type, Set<Class<?>> out) {
        if (type == null || type == Object.class || !out.add(type)) {
            return;
        }
        supertypes(type.getSuperclass(), out);
        for (Class<?> i : type.getInterfaces()) {
            supertypes(i, out);
        }
    }

    /**
     * Whether a record or user class may be written as a single scalar: a {@code @JsonValue} member or a
     * {@code @JsonSerialize} on the type, or a {@code @JsonSerialize} on the component, field or accessor
     * that holds it.
     */
    private static boolean mayBeScalar(Class<?> owner, FieldMetadata md, Class<?> nested) {
        if (platform(nested)) {
            return false;
        }
        // Jackson honours these on any supertype, so every superclass and interface is searched.
        Set<Class<?>> types = new HashSet<>();
        supertypes(nested, types);
        for (Class<?> type : types) {
            if (type.isAnnotationPresent(JsonSerialize.class)) {
                return true;
            }
            for (Method m : type.getDeclaredMethods()) {
                if (m.isAnnotationPresent(JsonValue.class)) {
                    return true;
                }
            }
            for (Field f : type.getDeclaredFields()) {
                if (f.isAnnotationPresent(JsonValue.class)) {
                    return true;
                }
            }
        }
        List<AnnotatedElement> holders = new ArrayList<>();
        if (owner.isRecord()) {
            for (RecordComponent rc : owner.getRecordComponents()) {
                if (rc.getName().equals(md.fieldName())) {
                    holders.add(rc);
                    holders.add(rc.getAccessor());
                }
            }
        }
        try {
            holders.add(owner.getDeclaredField(md.fieldName()));
        } catch (NoSuchFieldException e) {
            // A record component has its field; anything else without one is covered above.
        }
        return holders.stream().anyMatch(h -> h.isAnnotationPresent(JsonSerialize.class));
    }

    /**
     * The type a nested value is read with, by one rule shared (as a copy) with {@code SourceValues},
     * {@code JsonTreeScrubbingEngine} and the configured-JSON shape guard. An object value takes the
     * field's own {@code valueType()}: a user generic or a map is that object, not the type argument it
     * carries. Only an {@code Optional} is transparent. An element of an array value takes
     * {@code elementType()} when one is stated.
     */
    private static Class<?> nestedType(FieldMetadata md, boolean element) {
        Class<?> elementType = md.elementType();
        boolean stated = elementType != null && elementType != Object.class;
        if (element) {
            return stated ? elementType : md.valueType();
        }
        return md.valueType() == Optional.class && stated ? elementType : md.valueType();
    }

    private static boolean scalar(Class<?> type) {
        return platform(type)
                && !Map.class.isAssignableFrom(type)
                && !Collection.class.isAssignableFrom(type)
                && !Iterable.class.isAssignableFrom(type)
                && type != Object.class
                && type != Optional.class
                // A reference to a value is written as that value, so it is not a scalar of its own.
                && !AtomicReference.class.isAssignableFrom(type)
                && !Reference.class.isAssignableFrom(type)
                && !type.isInterface()
                || Number.class.isAssignableFrom(type) || CharSequence.class.isAssignableFrom(type);
    }

    private static boolean platform(Class<?> type) {
        ClassLoader loader = type.getClassLoader();
        return loader == null || loader == ClassLoader.getPlatformClassLoader();
    }

    /** A class the scrubber meets as a JSON object: a record or a concrete user class. */
    private static boolean concrete(Class<?> type) {
        return !type.isInterface() && !Modifier.isAbstract(type.getModifiers()) && !type.isArray();
    }
}

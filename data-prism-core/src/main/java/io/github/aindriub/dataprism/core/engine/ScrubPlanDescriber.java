package io.github.aindriub.dataprism.core.engine;

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

import java.lang.reflect.Modifier;
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
 * <p>Nothing here reads a value, a classification name or a class name into the result.
 */
final class ScrubPlanDescriber {

    private static final int MAX_DEPTH = 16;

    private final FieldMetadataResolver resolver;
    private final PrivacyPolicyResolver policies;
    private final PrivacyContext context;
    private final Map<String, KeyNamespace> chainables;
    private final Map<String, FieldProtection> found = new TreeMap<>();

    private ScrubPlanDescriber(FieldMetadataResolver resolver, PrivacyPolicyResolver policies,
                               PrivacyContext context, Map<String, KeyNamespace> chainables) {
        this.resolver = resolver;
        this.policies = policies;
        this.context = context;
        this.chainables = chainables;
    }

    /**
     * @param chainables the model's chainable paths, empty when links are disabled
     * @return empty when the plan cannot be described (an unknown profile, a profile that releases
     *         undeclared properties, a model nested deeper than the scrubber allows)
     */
    static Optional<ModelDescription> describe(Class<?> model, PrivacyContext context, boolean linksEnabled,
                                               FieldMetadataResolver resolver, PrivacyPolicyResolver policies,
                                               List<ChainableField> chainables) {
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(context, "context");
        Map<String, KeyNamespace> keys = new HashMap<>();
        if (linksEnabled) {
            chainables.forEach(c -> keys.put(c.path(), c.keyNamespace()));
        }
        ScrubPlanDescriber describer = new ScrubPlanDescriber(resolver, policies, context, keys);
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
        if (!inProgress.add(type)) {
            // The path grammar cannot express unbounded depth; the shallowest occurrence is listed.
            return;
        }
        List<FieldMetadata> fields = resolver.resolve(type);
        Map<String, FieldMetadata> byName = fields.stream()
                .collect(Collectors.toMap(FieldMetadata::fieldName, Function.identity()));
        boolean subject = hasSubject || fields.stream().anyMatch(FieldMetadata::internalIdentifier);

        for (FieldMetadata md : fields) {
            field(md, byName, prefix, gone, subject, inProgress, depth);
        }
        inProgress.remove(type);
    }

    private void field(FieldMetadata md, Map<String, FieldMetadata> siblings, String prefix, boolean gone,
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

        Shape shape = shapeOf(md);
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
        found.putIfAbsent(path, new FieldProtection(path, protection, valueNamespace, chainables.get(path)));
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
    private static boolean applicable(FieldMetadata md, EffectivePrivacyPolicy policy,
                                      Map<String, FieldMetadata> siblings, boolean subject) {
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

    private enum Kind { SCALAR, RECORD, STRUCTURE, DYNAMIC }

    private record Shape(Kind kind, boolean array, Class<?> record) {
    }

    /**
     * What a field's declared type says it holds, by the scrubber's own rule for nested types. A
     * collection or array is described by its element; an array inside an array, or an element whose
     * type is not stated, is dynamic because the scrubber reads it by the value it meets.
     */
    private Shape shapeOf(FieldMetadata md) {
        Class<?> value = md.valueType();
        boolean array = value != null && (value.isArray() || Collection.class.isAssignableFrom(value));
        Class<?> nested = nestedType(md, array);
        if (nested == null) {
            return new Shape(Kind.DYNAMIC, array, null);
        }
        if (resolver.descendable(nested)) {
            return new Shape(Kind.RECORD, array, nested);
        }
        if (nested.isPrimitive() || nested.isEnum() || scalar(nested)) {
            return new Shape(Kind.SCALAR, array, null);
        }
        if (Map.class.isAssignableFrom(nested) || concrete(nested) && !platform(nested)) {
            return new Shape(Kind.STRUCTURE, array, null);
        }
        return new Shape(Kind.DYNAMIC, array, null);
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

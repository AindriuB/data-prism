package io.github.aindriub.dataprism.core.engine;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import io.github.aindriub.dataprism.core.model.FieldMetadata;
import io.github.aindriub.dataprism.core.spi.FieldMetadataResolver;
import java.util.HashSet;
import java.util.Set;

/**
 * Extracts the raw values a response must not contain.
 *
 * <p>Everything classified sensitive, plus every correlation identifier, read
 * straight off the source at any depth. The validator compares against these,
 * which is what makes it independent of the engine: it checks what was actually
 * in the input rather than trusting the transformation to report on itself.
 *
 * <p>Nested structures are walked with the same rules the engine uses. A version
 * of this that only read top-level fields left the validator blind to exactly
 * the values a nested leak would expose — the check passed, and reported that it
 * had passed, while raw data went out.
 *
 * <p>Unclassified values are deliberately not collected. Whether those may be
 * released is the profile's decision, and a validator that refused them anyway
 * would make {@code PASS_THROUGH_UNSAFE} unusable rather than merely unwise.
 */
public final class SourceValues {

    private static final int MAX_DEPTH = 16;

    private SourceValues() {
    }

    public static Set<String> prohibited(Object source, FieldMetadataResolver resolver) {
        Set<String> out = new HashSet<>();
        JsonNode tree = SourceTree.of(source);
        if (tree.isObject()) {
            collect((ObjectNode) tree, source.getClass(), resolver, out, 0);
        }
        return out;
    }

    private static void collect(ObjectNode node, Class<?> type, FieldMetadataResolver resolver,
                                Set<String> out, int depth) {
        if (depth > MAX_DEPTH) {
            return;
        }
        for (FieldMetadata md : resolver.resolve(type)) {
            JsonNode value = node.get(md.fieldName());
            if (value == null || value.isNull()) {
                continue;
            }
            if (md.sensitive() || md.identifier()) {
                addScalars(value, out);
            }
            descend(value, md, resolver, out, depth);
        }
    }

    private static void descend(JsonNode value, FieldMetadata md, FieldMetadataResolver resolver,
                                Set<String> out, int depth) {
        if (value.isObject()) {
            Class<?> nested = nestedType(md, false);
            if (nested != null && resolver.descendable(nested)) {
                collect((ObjectNode) value, nested, resolver, out, depth + 1);
            }
            return;
        }
        if (value.isArray()) {
            Class<?> nested = nestedType(md, true);
            boolean follow = nested != null && resolver.descendable(nested);
            value.forEach(element -> {
                if (element.isObject() && follow) {
                    collect((ObjectNode) element, nested, resolver, out, depth + 1);
                } else if (element.isArray()) {
                    addScalarsOfObjects(element, out);
                }
            });
        }
    }

    /**
     * An object inside an array nested in the field's array has no type that can be determined, and
     * the scrubber treats it as an unclassified structure. So every scalar under it is prohibited.
     */
    private static void addScalarsOfObjects(JsonNode node, Set<String> out) {
        if (node.isObject()) {
            addScalars(node, out);
        } else if (node.isArray()) {
            node.forEach(child -> addScalarsOfObjects(child, out));
        }
    }

    /**
     * The type a nested value is read with, by one rule shared (as a copy) with {@code JsonTreeScrubbingEngine}
     * and the configured-JSON shape guard. An object value takes the field's own {@code valueType()}: a
     * user generic or a map is that object, not the type argument it carries. Only an {@code Optional}
     * is transparent. An element of an array value takes {@code elementType()} when one is stated.
     */
    private static Class<?> nestedType(FieldMetadata md, boolean element) {
        Class<?> elementType = md.elementType();
        boolean stated = elementType != null && elementType != Object.class;
        if (element) {
            return stated ? elementType : md.valueType();
        }
        return md.valueType() == java.util.Optional.class && stated ? elementType : md.valueType();
    }

    /** Every scalar under this node, so a sensitive collection contributes each element. */
    private static void addScalars(JsonNode node, Set<String> out) {
        if (node.isValueNode()) {
            String text = node.asString();
            if (text != null && !text.isBlank()) {
                out.add(text);
            }
            return;
        }
        node.forEach(child -> addScalars(child, out));
    }
}

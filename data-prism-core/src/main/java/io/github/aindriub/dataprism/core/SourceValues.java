package io.github.aindriub.dataprism.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.aindriub.dataprism.annotations.LlmExposedModel;
import io.github.aindriub.dataprism.annotations.SensitiveObject;

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

    private static final ObjectMapper READER = new ObjectMapper();
    private static final int MAX_DEPTH = 16;

    private SourceValues() {
    }

    public static Set<String> prohibited(Object source, FieldMetadataResolver resolver) {
        Set<String> out = new HashSet<>();
        JsonNode tree = READER.valueToTree(source);
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
        Class<?> nested = md.elementType() != null && md.elementType() != Object.class
                ? md.elementType()
                : md.valueType();
        if (nested == null || !descendable(nested)) {
            return;
        }
        if (value.isObject()) {
            collect((ObjectNode) value, nested, resolver, out, depth + 1);
            return;
        }
        if (value.isArray()) {
            value.forEach(element -> {
                if (element.isObject()) {
                    collect((ObjectNode) element, nested, resolver, out, depth + 1);
                }
            });
        }
    }

    private static boolean descendable(Class<?> type) {
        return type.getAnnotation(LlmExposedModel.class) != null
                || type.getAnnotation(SensitiveObject.class) != null;
    }

    /** Every scalar under this node, so a sensitive collection contributes each element. */
    private static void addScalars(JsonNode node, Set<String> out) {
        if (node.isValueNode()) {
            String text = node.asText();
            if (text != null && !text.isBlank()) {
                out.add(text);
            }
            return;
        }
        node.forEach(child -> addScalars(child, out));
    }
}

package io.github.aindriub.dataprism.connectors.rest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.aindriub.dataprism.core.FieldMetadata;
import io.github.aindriub.dataprism.core.PrivacyRefusedException;

import java.util.Map;

/**
 * Refuses a response where a nested catalogue's own leaf field -- a scalar,
 * classified or identifier entry that carries no {@code nested:} pointer of
 * its own -- turns up as a structure instead.
 *
 * <p>Without this, that shape mismatch would fall through to {@link
 * io.github.aindriub.dataprism.core.JsonTreeScrubbingEngine}'s generic {@code
 * UNCLASSIFIED_STRUCTURE} refusal, the same one an entirely unclassified
 * nested object gets. That conflates two different failures: a field nobody
 * ever reviewed, and a field that was reviewed and classified as a leaf, but
 * whose wire shape no longer matches what was reviewed. The second is a
 * schema drift the operator's catalogue is stale against, not a gap in the
 * catalogue, and it gets its own stable code so the two are never confused
 * downstream.
 *
 * <p>Runs before the real engine scrubs anything, so the refusal fires
 * instead of core's, not merely alongside it.
 */
final class ConfiguredJsonNestedLeafShapeGuard {

    /** Distinct from {@code UNCLASSIFIED_STRUCTURE} and {@code UNKNOWN_FIELD}. See class Javadoc. */
    static final String CODE = "NESTED_LEAF_NOT_SCALAR";

    private ConfiguredJsonNestedLeafShapeGuard() {
    }

    static void check(String sourceName, ObjectNode body, Map<String, FieldMetadata> rootFields,
                      ConfiguredJsonFieldMetadataResolver resolver) {
        for (Map.Entry<String, FieldMetadata> entry : rootFields.entrySet()) {
            String field = entry.getKey();
            FieldMetadata md = entry.getValue();
            Class<?> token = descendType(md);
            Map<String, FieldMetadata> nestedFields = token == null ? null : resolver.nestedFields(token);
            if (nestedFields == null) {
                continue;
            }
            JsonNode value = body.get(field);
            if (value == null || value.isNull()) {
                continue;
            }
            if (value.isObject()) {
                checkLeaves(sourceName, (ObjectNode) value, nestedFields, "$." + field);
            } else if (value.isArray()) {
                for (int i = 0; i < value.size(); i++) {
                    JsonNode element = value.get(i);
                    if (element.isObject()) {
                        checkLeaves(sourceName, (ObjectNode) element, nestedFields, "$." + field + "[" + i + "]");
                    }
                }
            }
        }
    }

    private static void checkLeaves(String sourceName, ObjectNode nested,
                                    Map<String, FieldMetadata> nestedFields, String path) {
        for (String leafField : nestedFields.keySet()) {
            JsonNode leafValue = nested.get(leafField);
            if (leafValue == null || leafValue.isNull()) {
                continue;
            }
            boolean structured = leafValue.isObject() || (leafValue.isArray() && containsObject(leafValue));
            if (structured) {
                throw new PrivacyRefusedException(CODE, sourceName + path + "." + leafField,
                        "nested catalogue leaf field is declared scalar/classified but the response"
                                + " carries a structure there; the catalogue is stale against the wire shape");
            }
        }
    }

    private static boolean containsObject(JsonNode array) {
        for (JsonNode element : array) {
            if (element.isObject()) {
                return true;
            }
        }
        return false;
    }

    /** Mirrors {@code JsonTreeScrubbingEngine.scrubNestedObject}'s own type selection, deliberately not shared. */
    private static Class<?> descendType(FieldMetadata md) {
        if (md.elementType() != null && md.elementType() != Object.class) {
            return md.elementType();
        }
        return md.valueType();
    }
}

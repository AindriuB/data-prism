package io.github.aindriub.dataprism.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.aindriub.dataprism.annotations.PrivacyAction;
import io.github.aindriub.dataprism.core.policy.EffectivePrivacyPolicy;
import io.github.aindriub.dataprism.core.policy.PrivacyPolicyResolver;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The tree-based scrubbing engine.
 *
 * <p>The engine works on a data tree rather than the Java object graph. Records
 * are immutable and their canonical constructors may validate, so there is no
 * way to write a scrubbed value back into one; and a tree makes unknown fields
 * visible, which is what fail-closed needs. See docs/design-review.md §A4.
 *
 * <p>It applies decisions, it does not make them. Every field goes through
 * {@link PrivacyPolicyResolver}, so what happens to a value is a configuration
 * question rather than a property of the model class.
 *
 * <p>Nested objects and collections are descended into rather than copied. A
 * nested object is not a value, it is more fields, and copying one would emit
 * every field inside it without anything having classified them — which is
 * exactly the hole this replaced. Descent needs the declared Java type, because
 * a JSON tree does not remember what it was built from; where the type says
 * nothing (a raw collection, a bare {@code Object}, a type with no annotation)
 * the contents are treated as unclassified and the profile decides.
 */
public final class JsonTreeScrubbingEngine implements ScrubbingEngine {

    public static final String REDACTED = "[REDACTED]";

    /**
     * Guards against a self-referencing structure. A cycle in the source object
     * would already have failed when Jackson built the tree, so this catches
     * pathologically deep data rather than true cycles.
     */
    private static final int MAX_DEPTH = 16;

    /**
     * Reads source objects into a tree. It never serialises output, so it is not
     * the mapper the boundary rule is about — MCP output goes through the single
     * mapper in the mcp module.
     */
    private final ObjectMapper reader = new ObjectMapper();

    private final FieldMetadataResolver resolver;
    private final PrivacyPolicyResolver policies;
    private final SyntheticValueSource synthetics;

    public JsonTreeScrubbingEngine(FieldMetadataResolver resolver, PrivacyPolicyResolver policies,
                                   SyntheticValueSource synthetics) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.policies = Objects.requireNonNull(policies, "policies");
        this.synthetics = Objects.requireNonNull(synthetics, "synthetics");
    }

    @Override
    public ObjectNode scrub(Object source, PrivacyContext context) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(context, "context");

        Class<?> type = source.getClass();
        if (!resolver.exposed(type)) {
            throw new PrivacyRefusedException("MODEL_NOT_EXPOSED", type.getName(),
                    "type is not annotated @LlmExposedModel");
        }

        JsonNode read = reader.valueToTree(source);
        if (!read.isObject()) {
            throw new PrivacyRefusedException("NOT_AN_OBJECT", type.getName(),
                    "source did not read as a JSON object");
        }
        return scrubObject((ObjectNode) read, type, context, "$", 0, null);
    }

    /**
     * @param inheritedSubject the enclosing object's subject. A nested structure
     *                         usually describes the same subject as its parent
     *                         and carries no identifier of its own; without
     *                         inheritance every nested synthesised field would
     *                         fail for want of a subject
     */
    private ObjectNode scrubObject(ObjectNode in, Class<?> type, PrivacyContext context,
                                   String path, int depth, String inheritedSubject) {
        if (depth > MAX_DEPTH) {
            throw new PrivacyRefusedException("TOO_DEEP", path,
                    "nesting exceeded " + MAX_DEPTH + " levels");
        }

        Map<String, FieldMetadata> byName = resolver.resolve(type).stream()
                .collect(Collectors.toMap(FieldMetadata::fieldName, Function.identity()));

        // Read subjects before anything is removed: identifier fields are dropped
        // below, and synthesis still needs their values.
        String declaredSubject = subjectValue(in, byName, null, type);
        String ownSubject = declaredSubject != null ? declaredSubject : inheritedSubject;

        ObjectNode out = reader.createObjectNode();
        for (String field : fieldNames(in)) {
            String fieldPath = path + "." + field;

            FieldMetadata md = byName.get(field);
            boolean unknownProperty = md == null;
            if (unknownProperty) {
                // Present in the serialised source, absent from the model. Same
                // question as an unannotated field, so the same setting answers it.
                md = FieldMetadata.undeclared(field);
            }

            EffectivePrivacyPolicy policy = policies.resolve(md, context);
            if (!policy.allowed()) {
                throw new PrivacyRefusedException(
                        unknownProperty ? "UNKNOWN_FIELD" : "UNDECLARED_FIELD", fieldPath,
                        unknownProperty
                                ? "property present in the source but not declared on " + type.getName()
                                : "field on an @LlmExposedModel carries neither @SensitiveData nor @NonSensitive");
            }

            JsonNode value = in.get(field);
            JsonNode scrubbed = apply(value, md, policy, context, fieldPath, depth, ownSubject,
                    in, byName, type);
            if (scrubbed != null) {
                out.set(field, scrubbed);
            }
        }
        return out;
    }

    /** @return the value to emit, or null to drop the field entirely */
    private JsonNode apply(JsonNode value, FieldMetadata md, EffectivePrivacyPolicy policy,
                           PrivacyContext context, String path, int depth, String ownSubject,
                           ObjectNode parent, Map<String, FieldMetadata> siblings, Class<?> owner) {
        if (policy.action() == PrivacyAction.REMOVE) {
            return null;
        }
        if (value == null || value.isNull()) {
            // Nothing to protect, and dropping it would change the shape of the
            // response for a reason unrelated to privacy.
            return value;
        }

        if (value.isObject()) {
            return scrubNestedObject((ObjectNode) value, md, context, path, depth, ownSubject);
        }
        if (value.isArray()) {
            ArrayNode out = reader.createArrayNode();
            ArrayNode in = (ArrayNode) value;
            for (int i = 0; i < in.size(); i++) {
                JsonNode element = in.get(i);
                String elementPath = path + "[" + i + "]";
                JsonNode scrubbed = element.isObject()
                        ? scrubNestedObject((ObjectNode) element, md, context, elementPath, depth, ownSubject)
                        : scalar(element, md, policy, context, elementPath, ownSubject, parent, siblings, owner);
                if (scrubbed != null) {
                    out.add(scrubbed);
                }
            }
            return out;
        }
        return scalar(value, md, policy, context, path, ownSubject, parent, siblings, owner);
    }

    /**
     * A nested object is only descended into when its declared type says it was
     * reviewed. Anything else — a bare {@code Object}, a third-party type, a
     * class nobody annotated — is unclassified, and the profile's setting for
     * unclassified data decides, exactly as it would for a scalar.
     */
    private JsonNode scrubNestedObject(ObjectNode value, FieldMetadata md, PrivacyContext context,
                                       String path, int depth, String inheritedSubject) {
        Class<?> nested = md.elementType() != null && md.elementType() != Object.class
                ? md.elementType()
                : md.valueType();

        if (nested != null && resolver.descendable(nested)) {
            return scrubObject(value, nested, context, path, depth + 1, inheritedSubject);
        }

        // The field holding this structure may well be declared non-sensitive --
        // that is the natural thing to write for something believed inert. It
        // says nothing about the fields inside, which nobody has classified, so
        // the profile's setting for unclassified data decides, not the field's.
        EffectivePrivacyPolicy structure =
                policies.resolve(FieldMetadata.undeclared(md.fieldName()), context);

        if (!structure.allowed()) {
            throw new PrivacyRefusedException("UNCLASSIFIED_STRUCTURE", path,
                    "nested value has no descendable type; annotate it @SensitiveObject, "
                            + "classify the field that holds it, or choose a looser "
                            + "`unclassified` setting for this profile");
        }
        return switch (structure.action()) {
            case PASS_THROUGH -> value;
            case REDACT -> reader.getNodeFactory().textNode(REDACTED);
            default -> null;
        };
    }

    private JsonNode scalar(JsonNode value, FieldMetadata md, EffectivePrivacyPolicy policy,
                            PrivacyContext context, String path, String ownSubject,
                            ObjectNode parent, Map<String, FieldMetadata> siblings, Class<?> owner) {
        return switch (policy.action()) {
            case PASS_THROUGH -> value;
            case REDACT -> reader.getNodeFactory().textNode(REDACTED);
            case REMOVE -> null;
            case SYNTHESIZE -> {
                String subject = md.subjectField().isEmpty()
                        ? ownSubject
                        : subjectValue(parent, siblings, md.subjectField(), owner);
                if (subject == null || subject.isBlank()) {
                    throw new PrivacyRefusedException("NO_SUBJECT", path,
                            "SYNTHESIZE needs a subject identifier and none resolved");
                }
                yield reader.getNodeFactory().textNode(
                        synthetics.syntheticValue(subject, policy.namespace(), context));
            }
            default -> throw new PrivacyRefusedException("UNSUPPORTED_ACTION", path,
                    "action " + policy.action() + " is not implemented yet");
        };
    }

    /**
     * @param named the field to read, or null for the type's own
     *              {@code @InternalIdentifier}
     */
    private static String subjectValue(ObjectNode in, Map<String, FieldMetadata> byName,
                                       String named, Class<?> type) {
        String field = named;
        if (field == null) {
            field = byName.values().stream()
                    .filter(FieldMetadata::internalIdentifier)
                    .map(FieldMetadata::fieldName)
                    .findFirst()
                    .orElse(null);
            if (field == null) {
                return null;
            }
        } else if (!byName.containsKey(field)) {
            throw new PrivacyRefusedException("UNKNOWN_SUBJECT_FIELD", field,
                    "subject field named on " + type.getName() + " does not exist");
        }
        JsonNode node = in.get(field);
        return node == null || node.isNull() ? null : node.asText();
    }

    private static List<String> fieldNames(ObjectNode node) {
        List<String> names = new ArrayList<>();
        for (Map.Entry<String, JsonNode> e : node.properties()) {
            names.add(e.getKey());
        }
        return names;
    }
}

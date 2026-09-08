package io.github.aindriub.dataprism.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.aindriub.dataprism.annotations.LlmExposedModel;
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
 * question rather than a property of the model class — the specification is
 * explicit that the annotation is a suggestion and the server-side policy has
 * final authority.
 *
 * <p>S1 handles flat objects. Nesting, collections and maps are S3; each throws
 * rather than falling through, because a privacy action that quietly does
 * nothing is worse than one that fails.
 */
public final class JsonTreeScrubbingEngine implements ScrubbingEngine {

    public static final String REDACTED = "[REDACTED]";

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
        if (type.getAnnotation(LlmExposedModel.class) == null) {
            throw new PrivacyRefusedException("MODEL_NOT_EXPOSED", type.getName(),
                    "type is not annotated @LlmExposedModel");
        }

        Map<String, FieldMetadata> byName = resolver.resolve(type).stream()
                .collect(Collectors.toMap(FieldMetadata::fieldName, Function.identity()));

        JsonNode read = reader.valueToTree(source);
        if (!read.isObject()) {
            throw new PrivacyRefusedException("NOT_AN_OBJECT", type.getName(),
                    "source did not read as a JSON object");
        }
        ObjectNode in = (ObjectNode) read;

        // Read the subject before anything is removed: identifier fields are
        // dropped below, and synthesis still needs their values.
        String ownSubject = subjectValue(in, byName, null, type);

        ObjectNode out = reader.createObjectNode();
        for (String field : fieldNames(in)) {
            FieldMetadata md = byName.get(field);
            if (md == null) {
                // A JSON property with no corresponding declaration at all. Not
                // even the fail-closed path can classify this, because there is
                // nothing to classify.
                throw new PrivacyRefusedException("UNKNOWN_FIELD", field,
                        "property present in the serialised source but not on " + type.getName());
            }

            EffectivePrivacyPolicy policy = policies.resolve(md, context);
            if (!policy.allowed()) {
                throw new PrivacyRefusedException("UNDECLARED_FIELD", field,
                        "field on an @LlmExposedModel carries neither @SensitiveData nor @NonSensitive");
            }

            JsonNode value = in.get(field);
            if (policy.action() == PrivacyAction.PASS_THROUGH) {
                out.set(field, value);
                continue;
            }
            if (value == null || value.isNull()) {
                // Nothing to protect, and dropping it would change the shape of
                // the response for a reason unrelated to privacy.
                if (policy.action() != PrivacyAction.REMOVE) {
                    out.set(field, value);
                }
                continue;
            }

            switch (policy.action()) {
                case REDACT -> out.put(field, REDACTED);
                case REMOVE -> { }
                case SYNTHESIZE -> {
                    String subject = md.subjectField().isEmpty()
                            ? ownSubject
                            : subjectValue(in, byName, md.subjectField(), type);
                    if (subject == null || subject.isBlank()) {
                        throw new PrivacyRefusedException("NO_SUBJECT", field,
                                "SYNTHESIZE needs a subject identifier and none resolved");
                    }
                    out.put(field, synthetics.syntheticValue(subject, policy.namespace(), context));
                }
                default -> throw new PrivacyRefusedException("UNSUPPORTED_ACTION", field,
                        "action " + policy.action() + " is not implemented yet");
            }
        }
        return out;
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

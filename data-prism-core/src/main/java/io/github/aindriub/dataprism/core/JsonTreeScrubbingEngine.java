package io.github.aindriub.dataprism.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.aindriub.dataprism.annotations.LlmExposedModel;
import io.github.aindriub.dataprism.annotations.PrivacyAction;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The tree-based scrubbing engine.
 *
 * <p>S0 handles flat records and the {@code SYNTHESIZE}, {@code REDACT} and
 * {@code REMOVE} actions. Nesting, collections, maps and the remaining actions
 * are S3; each throws here rather than falling through, because a privacy action
 * that quietly does nothing is worse than one that fails.
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
    private final SyntheticValueSource synthetics;

    public JsonTreeScrubbingEngine(FieldMetadataResolver resolver, SyntheticValueSource synthetics) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
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

        // Read the subject before anything is removed: the identifier field is
        // itself dropped below, and synthesis still needs its value.
        String ownSubject = subjectValue(in, byName, null, type);

        ObjectNode out = reader.createObjectNode();
        for (String field : fieldNames(in)) {
            FieldMetadata md = byName.get(field);
            if (md == null || !md.declared()) {
                // Nobody classified this. Fail closed: an unclassified field on an
                // exposed model means the decision was never made, not that it was
                // made in favour of exposure.
                throw new PrivacyRefusedException("UNDECLARED_FIELD", field,
                        "field on an @LlmExposedModel carries neither @SensitiveData nor @NonSensitive");
            }

            if (md.internalIdentifier()) {
                // Source identifiers are operational metadata and are not exposed
                // without an explicit capability. See docs/pack.md §32, §53.
                continue;
            }

            if (!md.sensitive()) {
                out.set(field, in.get(field));
                continue;
            }

            JsonNode value = in.get(field);
            if (value == null || value.isNull()) {
                out.set(field, value);
                continue;
            }

            PrivacyAction action = md.suggestedAction();
            switch (action) {
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
                    out.put(field, synthetics.syntheticValue(subject, md.namespace(), context));
                }
                default -> throw new PrivacyRefusedException("UNSUPPORTED_ACTION", field,
                        "action " + action + " is not implemented in S0");
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

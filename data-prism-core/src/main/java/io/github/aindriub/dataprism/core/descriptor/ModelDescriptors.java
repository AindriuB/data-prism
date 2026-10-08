package io.github.aindriub.dataprism.core.descriptor;

import io.github.aindriub.dataprism.annotations.DataClassification;
import io.github.aindriub.dataprism.annotations.PrivacyAction;
import io.github.aindriub.dataprism.annotations.PrivacyNamespace;
import io.github.aindriub.dataprism.annotations.UndeclaredFields;
import io.github.aindriub.dataprism.core.model.StrictYaml;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Loads model descriptors from YAML.
 *
 * <p>Parsed by hand from a generic map rather than data-bound, for the same
 * reason the privacy profiles are: an unknown classification, action or key must
 * be a startup failure naming the offending line. Binding would produce a null
 * and carry on, and a descriptor that quietly loses a rule is the worst kind of
 * configuration bug — nothing fails, and less is protected than the file says.
 */
public final class ModelDescriptors {

    private static final String KIND = "model descriptors";
    private static final Set<String> ROOT_KEYS = Set.of("models");
    private static final Set<String> MODEL_KEYS = Set.of("exposed", "descendable", "undeclaredFields", "fields");
    private static final Set<String> FIELD_KEYS =
            Set.of("classifications", "namespace", "action", "subject", "nonSensitive", "identifier");

    private ModelDescriptors() {
    }

    @SuppressWarnings("unchecked")
    public static Map<String, ModelDescriptor> fromYaml(InputStream in) {
        Map<String, Object> root = StrictYaml.readMapping(in, KIND);
        if (root == null) {
            throw new IllegalArgumentException("descriptor file has no `models` section");
        }
        StrictYaml.requireOnlyKeys(root.keySet(), ROOT_KEYS, KIND);

        Object models = root.get("models");
        if (!(models instanceof Map<?, ?> map) || map.isEmpty()) {
            throw new IllegalArgumentException("descriptor file has no `models` section");
        }

        Map<String, ModelDescriptor> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String typeName = String.valueOf(entry.getKey());
            if (!(entry.getValue() instanceof Map<?, ?> body)) {
                throw new IllegalArgumentException("model " + typeName + " is not a mapping");
            }
            StrictYaml.requireOnlyKeys(body.keySet(), MODEL_KEYS, KIND + " models." + StrictYaml.shown(typeName));
            out.put(typeName, model(typeName, (Map<String, Object>) body));
        }
        return Map.copyOf(out);
    }

    @SuppressWarnings("unchecked")
    private static ModelDescriptor model(String typeName, Map<String, Object> body) {
        String modelWhere = KIND + " models." + StrictYaml.shown(typeName);
        Map<String, ModelDescriptor.FieldDescriptor> fields = new LinkedHashMap<>();
        Object fieldsNode = body.get("fields");
        if (fieldsNode instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> e : map.entrySet()) {
                String name = String.valueOf(e.getKey());
                if (!(e.getValue() instanceof Map<?, ?> fieldBody)) {
                    throw new IllegalArgumentException(
                            "field " + typeName + "." + name + " is not a mapping");
                }
                StrictYaml.requireOnlyKeys(fieldBody.keySet(), FIELD_KEYS,
                        KIND + " models." + StrictYaml.shown(typeName) + ".fields." + StrictYaml.shown(name));
                fields.put(name, field(typeName, name, (Map<String, Object>) fieldBody));
            }
        }

        return new ModelDescriptor(
                typeName,
                StrictYaml.optionalBoolean(body, "exposed", modelWhere),
                StrictYaml.optionalBoolean(body, "descendable", modelWhere),
                StrictYaml.optionalEnum(UndeclaredFields.class, body, "undeclaredFields",
                        typeName + ".undeclaredFields"),
                fields);
    }

    private static ModelDescriptor.FieldDescriptor field(String typeName, String name,
                                                         Map<String, Object> body) {
        String where = typeName + "." + name;
        String fieldWhere = KIND + " models." + StrictYaml.shown(typeName) + ".fields." + StrictYaml.shown(name);

        List<DataClassification> classifications = new ArrayList<>();
        Object raw = body.get("classifications");
        if (raw instanceof List<?> list) {
            for (Object value : list) {
                classifications.add(StrictYaml.enumValue(DataClassification.class, value,
                        where + ".classifications"));
            }
        } else if (raw != null) {
            classifications.add(StrictYaml.enumValue(DataClassification.class, raw, where + ".classifications"));
        } else if (body.containsKey("classifications")) {
            throw new IllegalArgumentException(StrictYaml.NON_STRING_SCALAR + ": " + fieldWhere
                    + ".classifications must be a quoted string");
        }

        String nonSensitive = StrictYaml.optionalString(body, "nonSensitive", fieldWhere);
        if (nonSensitive != null && !classifications.isEmpty()) {
            throw new IllegalArgumentException(
                    where + " states both classifications and nonSensitive");
        }

        return new ModelDescriptor.FieldDescriptor(
                classifications,
                StrictYaml.optionalEnum(PrivacyNamespace.class, body, "namespace", where + ".namespace"),
                StrictYaml.optionalEnum(PrivacyAction.class, body, "action", where + ".action"),
                StrictYaml.optionalString(body, "subject", fieldWhere),
                nonSensitive,
                StrictYaml.optionalString(body, "identifier", fieldWhere));
    }
}

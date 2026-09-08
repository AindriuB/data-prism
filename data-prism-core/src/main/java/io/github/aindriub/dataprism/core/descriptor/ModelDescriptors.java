package io.github.aindriub.dataprism.core.descriptor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.github.aindriub.dataprism.annotations.DataClassification;
import io.github.aindriub.dataprism.annotations.PrivacyAction;
import io.github.aindriub.dataprism.annotations.PrivacyNamespace;
import io.github.aindriub.dataprism.annotations.UndeclaredFields;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    private ModelDescriptors() {
    }

    @SuppressWarnings("unchecked")
    public static Map<String, ModelDescriptor> fromYaml(InputStream in) {
        Map<String, Object> root;
        try {
            root = YAML.readValue(in, Map.class);
        } catch (IOException e) {
            throw new UncheckedIOException("model descriptors could not be read", e);
        }

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
            out.put(typeName, model(typeName, (Map<String, Object>) body));
        }
        return Map.copyOf(out);
    }

    @SuppressWarnings("unchecked")
    private static ModelDescriptor model(String typeName, Map<String, Object> body) {
        Map<String, ModelDescriptor.FieldDescriptor> fields = new LinkedHashMap<>();
        Object fieldsNode = body.get("fields");
        if (fieldsNode instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> e : map.entrySet()) {
                String name = String.valueOf(e.getKey());
                if (!(e.getValue() instanceof Map<?, ?> fieldBody)) {
                    throw new IllegalArgumentException(
                            "field " + typeName + "." + name + " is not a mapping");
                }
                fields.put(name, field(typeName, name, (Map<String, Object>) fieldBody));
            }
        }

        return new ModelDescriptor(
                typeName,
                bool(body.get("exposed")),
                bool(body.get("descendable")),
                body.get("undeclaredFields") == null
                        ? null
                        : enumValue(UndeclaredFields.class, body.get("undeclaredFields"),
                                typeName + ".undeclaredFields"),
                fields);
    }

    private static ModelDescriptor.FieldDescriptor field(String typeName, String name,
                                                         Map<String, Object> body) {
        String where = typeName + "." + name;

        List<DataClassification> classifications = new ArrayList<>();
        Object raw = body.get("classifications");
        if (raw instanceof List<?> list) {
            for (Object value : list) {
                classifications.add(enumValue(DataClassification.class, value,
                        where + ".classifications"));
            }
        } else if (raw != null) {
            classifications.add(enumValue(DataClassification.class, raw, where + ".classifications"));
        }

        Object nonSensitive = body.get("nonSensitive");
        if (nonSensitive != null && !classifications.isEmpty()) {
            throw new IllegalArgumentException(
                    where + " states both classifications and nonSensitive");
        }

        return new ModelDescriptor.FieldDescriptor(
                classifications,
                body.get("namespace") == null ? null
                        : enumValue(PrivacyNamespace.class, body.get("namespace"), where + ".namespace"),
                body.get("action") == null ? null
                        : enumValue(PrivacyAction.class, body.get("action"), where + ".action"),
                body.get("subject") == null ? null : String.valueOf(body.get("subject")),
                nonSensitive == null ? null : String.valueOf(nonSensitive),
                body.get("identifier") == null ? null : String.valueOf(body.get("identifier")));
    }

    private static Boolean bool(Object raw) {
        return raw == null ? null : Boolean.parseBoolean(String.valueOf(raw));
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, Object raw, String where) {
        String value = String.valueOf(raw).trim().toUpperCase(Locale.ROOT);
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "unknown " + type.getSimpleName() + " '" + value + "' at " + where, e);
        }
    }
}

package io.github.aindriub.dataprism.core.policy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.github.aindriub.dataprism.annotations.DataClassification;
import io.github.aindriub.dataprism.annotations.PrivacyAction;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Loads privacy profiles from YAML.
 *
 * <p>Parsed by hand from a generic map rather than data-bound, so that an
 * unknown classification, action or behaviour is a startup failure naming the
 * offending key. Binding would have quietly produced a null or a default, and a
 * privacy profile that silently loses a rule is the worst kind of configuration
 * bug: everything works, and less is protected than the file says.
 */
public final class PrivacyProfiles {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    private PrivacyProfiles() {
    }

    @SuppressWarnings("unchecked")
    public static Map<String, PrivacyProfile> fromYaml(InputStream in) {
        Map<String, Object> root;
        try {
            root = YAML.readValue(in, Map.class);
        } catch (IOException e) {
            throw new UncheckedIOException("privacy profiles could not be read", e);
        }

        Object profilesNode = root.get("profiles");
        if (!(profilesNode instanceof Map<?, ?> profilesMap) || profilesMap.isEmpty()) {
            throw new IllegalArgumentException("privacy profile file has no `profiles` section");
        }

        Map<String, PrivacyProfile> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : profilesMap.entrySet()) {
            String name = String.valueOf(entry.getKey());
            if (!(entry.getValue() instanceof Map<?, ?> body)) {
                throw new IllegalArgumentException("profile " + name + " is not a mapping");
            }
            out.put(name, profile(name, (Map<String, Object>) body));
        }
        return Map.copyOf(out);
    }

    @SuppressWarnings("unchecked")
    private static PrivacyProfile profile(String name, Map<String, Object> body) {
        PrivacyProfile.UnclassifiedBehaviour unclassified = enumValue(
                PrivacyProfile.UnclassifiedBehaviour.class,
                body.getOrDefault("unclassified", "FAIL_REQUEST"),
                name + ".unclassified");

        Map<DataClassification, PrivacyProfile.ClassificationRule> rules = new LinkedHashMap<>();
        Object classifications = body.get("classifications");
        if (classifications instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> e : map.entrySet()) {
                String key = String.valueOf(e.getKey());
                DataClassification classification =
                        enumValue(DataClassification.class, key, name + ".classifications." + key);

                if (!(e.getValue() instanceof Map<?, ?> ruleBody)) {
                    throw new IllegalArgumentException(
                            "rule " + name + "." + key + " is not a mapping");
                }
                Map<String, Object> rule = (Map<String, Object>) ruleBody;
                Object action = rule.get("action");
                if (action == null) {
                    throw new IllegalArgumentException(
                            "rule " + name + "." + key + " has no action");
                }
                PrivacyAction resolved = enumValue(PrivacyAction.class, action,
                        name + ".classifications." + key + ".action");
                boolean override = Boolean.parseBoolean(
                        String.valueOf(rule.getOrDefault("override", "false")));
                rules.put(classification, new PrivacyProfile.ClassificationRule(resolved, override));
            }
        }
        return new PrivacyProfile(name, unclassified, rules);
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

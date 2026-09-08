package io.github.aindriub.dataprism.core.policy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.github.aindriub.dataprism.annotations.DataClassification;
import io.github.aindriub.dataprism.annotations.PrivacyAction;
import io.github.aindriub.dataprism.annotations.PrivacyNamespace;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.util.ArrayList;
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
        return new PrivacyProfile(name, unclassified, rules, generalizations(name, body));
    }

    @SuppressWarnings("unchecked")
    private static Map<PrivacyNamespace, GeneralizationRule> generalizations(
            String profile, Map<String, Object> body) {
        Map<PrivacyNamespace, GeneralizationRule> out = new LinkedHashMap<>();
        Object node = body.get("generalization");
        if (!(node instanceof Map<?, ?> map)) {
            return out;
        }
        for (Map.Entry<?, ?> e : map.entrySet()) {
            String key = String.valueOf(e.getKey());
            String where = profile + ".generalization." + key;
            PrivacyNamespace namespace = enumValue(PrivacyNamespace.class, key, where);
            if (!(e.getValue() instanceof Map<?, ?> ruleBody)) {
                throw new IllegalArgumentException(where + " is not a mapping");
            }
            out.put(namespace, rule(where, (Map<String, Object>) ruleBody));
        }
        return out;
    }

    private static GeneralizationRule rule(String where, Map<String, Object> body) {
        GeneralizationRule.Kind kind = enumValue(GeneralizationRule.Kind.class,
                body.getOrDefault("type", "NUMERIC_BAND"), where + ".type");

        if (kind == GeneralizationRule.Kind.DATE_TRUNCATION) {
            return GeneralizationRule.dates(enumValue(GeneralizationRule.Precision.class,
                    body.getOrDefault("precision", "YEAR"), where + ".precision"));
        }

        Object bounds = body.get("bounds");
        if (!(bounds instanceof java.util.List<?> list) || list.isEmpty()) {
            throw new IllegalArgumentException(where + " has no bounds");
        }
        java.util.List<BigDecimal> parsed = new ArrayList<>();
        for (Object bound : list) {
            try {
                parsed.add(new BigDecimal(String.valueOf(bound).trim()));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(
                        where + " has a non-numeric bound '" + bound + "'", e);
            }
        }
        Object unit = body.get("unit");
        return GeneralizationRule.bands(parsed, unit == null ? null : String.valueOf(unit));
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

package io.github.aindriub.dataprism.core.policy;

import io.github.aindriub.dataprism.annotations.DataClassification;
import io.github.aindriub.dataprism.annotations.PrivacyAction;
import io.github.aindriub.dataprism.annotations.PrivacyNamespace;
import io.github.aindriub.dataprism.core.model.StrictYaml;

import java.io.InputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

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

    private static final String KIND = "privacy profiles";
    private static final Set<String> ROOT_KEYS = Set.of("profiles");
    private static final Set<String> PROFILE_KEYS = Set.of("unclassified", "classifications", "generalization");
    private static final Set<String> RULE_KEYS = Set.of("action", "override");
    private static final Set<String> GENERALIZATION_KEYS = Set.of("type", "bounds", "unit", "precision");

    private PrivacyProfiles() {
    }

    @SuppressWarnings("unchecked")
    public static Map<String, PrivacyProfile> fromYaml(InputStream in) {
        Map<String, Object> root = StrictYaml.readMapping(in, KIND);
        if (root == null) {
            throw new IllegalArgumentException("privacy profile file has no `profiles` section");
        }
        StrictYaml.requireOnlyKeys(root.keySet(), ROOT_KEYS, KIND);

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
            StrictYaml.requireOnlyKeys(body.keySet(), PROFILE_KEYS, KIND + " profiles." + StrictYaml.shown(name));
            out.put(name, profile(name, (Map<String, Object>) body));
        }
        return Map.copyOf(out);
    }

    @SuppressWarnings("unchecked")
    private static PrivacyProfile profile(String name, Map<String, Object> body) {
        String profileWhere = KIND + " profiles." + StrictYaml.shown(name);
        PrivacyProfile.UnclassifiedBehaviour unclassified = body.containsKey("unclassified")
                ? StrictYaml.optionalEnum(PrivacyProfile.UnclassifiedBehaviour.class, body, "unclassified",
                        name + ".unclassified")
                : PrivacyProfile.UnclassifiedBehaviour.FAIL_REQUEST;

        Map<DataClassification, PrivacyProfile.ClassificationRule> rules = new LinkedHashMap<>();
        Map<String, Object> map = StrictYaml.optionalMapping(body, "classifications", profileWhere);
        if (map != null) {
            for (Map.Entry<?, ?> e : map.entrySet()) {
                String key = String.valueOf(e.getKey());
                DataClassification classification =
                        StrictYaml.enumValue(DataClassification.class, key, name + ".classifications." + key);

                if (!(e.getValue() instanceof Map<?, ?> ruleBody)) {
                    throw new IllegalArgumentException(
                            "rule " + name + "." + key + " is not a mapping");
                }
                Map<String, Object> rule = (Map<String, Object>) ruleBody;
                StrictYaml.requireOnlyKeys(rule.keySet(), RULE_KEYS,
                        profileWhere + ".classifications." + StrictYaml.shown(key));
                Object action = rule.get("action");
                if (action == null) {
                    throw new IllegalArgumentException(
                            "rule " + name + "." + key + " has no action");
                }
                PrivacyAction resolved = StrictYaml.enumValue(PrivacyAction.class, action,
                        name + ".classifications." + key + ".action");
                Boolean overrideRaw = StrictYaml.optionalBoolean(rule, "override",
                        profileWhere + ".classifications." + StrictYaml.shown(key));
                boolean override = overrideRaw != null && overrideRaw;
                if (DataClassification.SPECIAL_CATEGORIES.contains(classification)
                        && ActionStrictness.stricter(resolved, PrivacyAction.REDACT) != resolved) {
                    throw new IllegalArgumentException("SPECIAL_CATEGORY_EXPOSED: profile " + name
                            + " maps " + classification + " to " + resolved
                            + "; special categories must be REDACT or REMOVE");
                }
                rules.put(classification, new PrivacyProfile.ClassificationRule(resolved, override));
            }
        }
        return new PrivacyProfile(name, unclassified, rules, generalizations(name, body));
    }

    @SuppressWarnings("unchecked")
    private static Map<PrivacyNamespace, GeneralizationRule> generalizations(
            String profile, Map<String, Object> body) {
        Map<PrivacyNamespace, GeneralizationRule> out = new LinkedHashMap<>();
        Map<String, Object> map = StrictYaml.optionalMapping(body, "generalization",
                KIND + " profiles." + StrictYaml.shown(profile));
        if (map == null) {
            return out;
        }
        for (Map.Entry<?, ?> e : map.entrySet()) {
            String key = String.valueOf(e.getKey());
            String where = profile + ".generalization." + key;
            PrivacyNamespace namespace = StrictYaml.enumValue(PrivacyNamespace.class, key, where);
            if (!(e.getValue() instanceof Map<?, ?> ruleBody)) {
                throw new IllegalArgumentException(where + " is not a mapping");
            }
            StrictYaml.requireOnlyKeys(ruleBody.keySet(), GENERALIZATION_KEYS,
                    KIND + " profiles." + StrictYaml.shown(profile) + ".generalization." + StrictYaml.shown(key));
            out.put(namespace, rule(where, (Map<String, Object>) ruleBody,
                    KIND + " profiles." + StrictYaml.shown(profile) + ".generalization." + StrictYaml.shown(key)));
        }
        return out;
    }

    private static GeneralizationRule rule(String where, Map<String, Object> body, String ruleWhere) {
        GeneralizationRule.Kind kind = body.containsKey("type")
                ? StrictYaml.optionalEnum(GeneralizationRule.Kind.class, body, "type", where + ".type")
                : GeneralizationRule.Kind.NUMERIC_BAND;

        if (kind == GeneralizationRule.Kind.DATE_TRUNCATION) {
            return GeneralizationRule.dates(body.containsKey("precision")
                    ? StrictYaml.optionalEnum(GeneralizationRule.Precision.class, body, "precision",
                            where + ".precision")
                    : GeneralizationRule.Precision.YEAR);
        }

        Object bounds = body.get("bounds");
        if (!(bounds instanceof java.util.List<?> list) || list.isEmpty()) {
            throw new IllegalArgumentException(where + " has no bounds");
        }
        java.util.List<BigDecimal> parsed = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            Object bound = list.get(i);
            try {
                parsed.add(StrictYaml.decimal(bound, ruleWhere + ".bounds[" + i + "]"));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(
                        where + " has a non-numeric bound '" + bound + "'", e);
            }
        }
        return GeneralizationRule.bands(parsed, StrictYaml.optionalString(body, "unit", ruleWhere));
    }
}

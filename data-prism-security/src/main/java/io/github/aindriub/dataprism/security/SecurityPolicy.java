package io.github.aindriub.dataprism.security;

import io.github.aindriub.dataprism.core.model.Capability;
import io.github.aindriub.dataprism.core.model.StrictYaml;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The allowed purposes and the role-to-capability map, loaded once at
 * startup.
 *
 * <p>Hand-parsed from a generic map, matching {@code core.policy.PrivacyProfiles}'s
 * house style: an unknown top-level key, an empty purpose list or a capability
 * outside {@link Capability#KNOWN} is a startup failure naming the offending
 * entry, never a policy that is silently narrower than the file claims.
 */
public record SecurityPolicy(Set<String> allowedPurposes, Map<String, Set<String>> roleCapabilities) {

    private static final String KIND = "security policy";
    private static final Set<String> TOP_LEVEL_KEYS = Set.of("purposes", "roles");

    public SecurityPolicy {
        Objects.requireNonNull(allowedPurposes, "allowedPurposes");
        Objects.requireNonNull(roleCapabilities, "roleCapabilities");
        allowedPurposes = Set.copyOf(allowedPurposes);
        Map<String, Set<String>> copy = new LinkedHashMap<>();
        roleCapabilities.forEach((role, capabilities) -> copy.put(role, Set.copyOf(capabilities)));
        roleCapabilities = Map.copyOf(copy);
    }

    /**
     * Exactly the union of the mapped capabilities of the given roles. A role
     * with no entry in {@link #roleCapabilities()} contributes nothing rather
     * than failing the call.
     */
    public Set<String> capabilitiesFor(Set<String> roles) {
        Objects.requireNonNull(roles, "roles");
        Set<String> out = new LinkedHashSet<>();
        for (String role : roles) {
            out.addAll(roleCapabilities.getOrDefault(role, Set.of()));
        }
        return Set.copyOf(out);
    }

    public static SecurityPolicy fromYaml(InputStream in) {
        Map<String, Object> root = StrictYaml.readMapping(in, KIND);
        if (root == null) {
            throw new IllegalArgumentException("security policy file is empty");
        }
        StrictYaml.requireOnlyKeys(root.keySet(), TOP_LEVEL_KEYS, KIND);

        return new SecurityPolicy(purposes(root.get("purposes")),
                root.containsKey("roles") ? roles(root.get("roles")) : Map.of());
    }

    private static Set<String> purposes(Object node) {
        if (!(node instanceof List<?> list) || list.isEmpty()) {
            throw new IllegalArgumentException("security policy has no purposes");
        }
        Set<String> out = new LinkedHashSet<>();
        for (int i = 0; i < list.size(); i++) {
            String purpose = StrictYaml.text(list.get(i), KIND + " purposes[" + i + "]").trim();
            if (purpose.isBlank()) {
                throw new IllegalArgumentException("security policy has a blank purpose");
            }
            out.add(purpose);
        }
        return out;
    }

    private static Map<String, Set<String>> roles(Object node) {
        if (!(node instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException(StrictYaml.INVALID_SHAPE + ": " + KIND + " roles must be a mapping");
        }
        Map<String, Set<String>> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String role = String.valueOf(entry.getKey());
            if (!(entry.getValue() instanceof List<?> list)) {
                throw new IllegalArgumentException("role '" + role + "' capabilities must be a list");
            }
            Set<String> capabilities = new LinkedHashSet<>();
            for (int i = 0; i < list.size(); i++) {
                String capability = StrictYaml.text(list.get(i),
                        KIND + " roles." + StrictYaml.shown(role) + "[" + i + "]").trim();
                if (!Capability.KNOWN.contains(capability)) {
                    throw new IllegalArgumentException(
                            "role '" + role + "' names unknown capability '" + capability + "'");
                }
                capabilities.add(capability);
            }
            out.put(role, capabilities);
        }
        return out;
    }
}

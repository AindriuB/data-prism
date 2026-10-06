package io.github.aindriub.dataprism.reidentification;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * @param purposes    the re-identification-only purposes a request may name
 * @param roles       role name to the permissions it grants
 * @param fourEyes    whether a second, distinct principal must approve before anything resolves
 * @param approvalTtl how long a pending or approved request stays usable
 */
public record ReidentificationPolicy(
        Set<String> purposes,
        Map<String, Set<Permission>> roles,
        boolean fourEyes,
        Duration approvalTtl) {

    public ReidentificationPolicy {
        Objects.requireNonNull(purposes, "purposes");
        Objects.requireNonNull(roles, "roles");
        Objects.requireNonNull(approvalTtl, "approvalTtl");
        if (approvalTtl.isZero() || approvalTtl.isNegative()) {
            throw new IllegalArgumentException("approvalTtl must be positive");
        }
        purposes = Set.copyOf(purposes);
        Map<String, Set<Permission>> copy = new HashMap<>();
        roles.forEach((role, permissions) -> copy.put(role, Set.copyOf(permissions)));
        roles = Map.copyOf(copy);
    }

    boolean grants(Set<String> callerRoles, Permission permission) {
        return callerRoles.stream().anyMatch(role -> roles.getOrDefault(role, Set.of()).contains(permission));
    }
}

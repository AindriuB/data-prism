package io.github.aindriub.dataprism.spring.boot;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** {@code dataprism.reidentification.*}: the controlled reverse lookup. Off by default. */
public class ReidentificationProperties {
    /** What a role may do; mirrors the library's own permission names. */
    public enum Permission { REQUEST, APPROVE }

    /** Builds the ReidentificationService bean. It is never an MCP tool. Defaults to false. */
    private boolean enabled;
    /** Re-identification-only purposes a request may name. Required once enabled. */
    private List<String> purposes = new java.util.ArrayList<>();
    /** Map of role name to the permissions REQUEST and/or APPROVE. Required once enabled. */
    private Map<String, Set<Permission>> roles = new LinkedHashMap<>();
    /** Whether a second, distinct principal must approve before anything resolves. Defaults to true. */
    private boolean fourEyes = true;
    /** How long a pending or approved request stays usable. Defaults to 15 minutes. */
    private Duration approvalTtl = Duration.ofMinutes(15);
    /** Live pending requests one requester may hold. Defaults to 5. */
    private int maxPendingPerRequester = 5;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean v) {
        enabled = v;
    }

    public List<String> getPurposes() {
        return purposes;
    }

    public void setPurposes(List<String> v) {
        purposes = v == null ? new java.util.ArrayList<>() : new java.util.ArrayList<>(v);
    }

    public Map<String, Set<Permission>> getRoles() {
        return roles;
    }

    public void setRoles(Map<String, Set<Permission>> v) {
        roles = v == null ? new LinkedHashMap<>() : new LinkedHashMap<>(v);
    }

    public boolean isFourEyes() {
        return fourEyes;
    }

    public void setFourEyes(boolean v) {
        fourEyes = v;
    }

    public Duration getApprovalTtl() {
        return approvalTtl;
    }

    public void setApprovalTtl(Duration v) {
        approvalTtl = v;
    }

    public int getMaxPendingPerRequester() {
        return maxPendingPerRequester;
    }

    public void setMaxPendingPerRequester(int v) {
        maxPendingPerRequester = v;
    }
}

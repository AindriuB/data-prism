package io.github.aindriub.dataprism.spring.boot;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class SecurityPolicyProperties {
    /** Permitted purposes. At least one is required. */
    private List<String> purposes = List.of();
    /** Map of role name to the capabilities it holds. At least one role is required. */
    private Map<String, List<String>> roles = new LinkedHashMap<>();

    public List<String> getPurposes() {
        return purposes;
    }

    public void setPurposes(List<String> v) {
        purposes = v;
    }

    public Map<String, List<String>> getRoles() {
        return roles;
    }

    public void setRoles(Map<String, List<String>> v) {
        roles = v;
    }
}

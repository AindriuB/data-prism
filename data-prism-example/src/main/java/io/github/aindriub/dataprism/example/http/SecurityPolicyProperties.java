package io.github.aindriub.dataprism.example.http;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;
import java.util.Map;

/**
 * The purpose list and role-to-capability map, bound from
 * {@code application.yaml}'s {@code dataprism.security-policy} section.
 *
 * <p>This record only carries what Spring bound; it is not itself validated
 * the way {@link io.github.aindriub.dataprism.security.SecurityPolicy} is.
 * {@link McpAssemblyConfig} re-serialises it and hands it to
 * {@code SecurityPolicy.fromYaml}, so an unknown capability or an empty
 * purpose list still fails startup with that class's own message — the
 * validation lives in one place regardless of which file the values started
 * in.
 */
@ConfigurationProperties(prefix = "dataprism.security-policy")
public record SecurityPolicyProperties(List<String> purposes, Map<String, List<String>> roles) {

    public SecurityPolicyProperties {
        purposes = purposes == null ? List.of() : List.copyOf(purposes);
        roles = roles == null ? Map.of() : Map.copyOf(roles);
    }
}

package io.github.aindriub.dataprism.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecurityPolicyTest {

    private static InputStream yaml(String body) {
        return new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("a valid policy loads purposes and role capabilities")
    void loadsValidPolicy() {
        SecurityPolicy policy = SecurityPolicy.fromYaml(yaml("""
                purposes:
                  - fraud_investigation
                  - customer_support
                roles:
                  investigator:
                    - GET_ENTITY_CONTEXT
                    - COMPARE_ENTITY_SOURCES
                  admin:
                    - EXPOSE_SOURCE_NAMES
                """));

        assertThat(policy.allowedPurposes()).containsExactlyInAnyOrder("fraud_investigation", "customer_support");
        assertThat(policy.roleCapabilities().get("investigator"))
                .containsExactlyInAnyOrder("GET_ENTITY_CONTEXT", "COMPARE_ENTITY_SOURCES");
        assertThat(policy.roleCapabilities().get("admin")).containsExactly("EXPOSE_SOURCE_NAMES");
    }

    @Test
    @DisplayName("an unknown top-level key fails at load, naming the key")
    void unknownTopLevelKeyFails() {
        assertThatThrownBy(() -> SecurityPolicy.fromYaml(yaml("""
                purposes: [fraud_investigation]
                rolez:
                  investigator: [GET_ENTITY_CONTEXT]
                """)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rolez");
    }

    @Test
    @DisplayName("an empty purpose list fails at load")
    void emptyPurposeListFails() {
        assertThatThrownBy(() -> SecurityPolicy.fromYaml(yaml("""
                purposes: []
                roles: {}
                """)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("purposes");
    }

    @Test
    @DisplayName("a capability outside Capability.KNOWN fails at load, naming the entry")
    void unknownCapabilityFails() {
        assertThatThrownBy(() -> SecurityPolicy.fromYaml(yaml("""
                purposes: [fraud_investigation]
                roles:
                  investigator:
                    - GET_ENTITY_CONTEXT
                    - DELETE_EVERYTHING
                """)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("DELETE_EVERYTHING")
                .hasMessageContaining("investigator");
    }

    @Test
    @DisplayName("capabilitiesFor is the union of the given roles' mapped capabilities")
    void capabilitiesForIsUnion() {
        SecurityPolicy policy = SecurityPolicy.fromYaml(yaml("""
                purposes: [fraud_investigation]
                roles:
                  investigator: [GET_ENTITY_CONTEXT]
                  reviewer: [COMPARE_ENTITY_SOURCES]
                """));

        assertThat(policy.capabilitiesFor(Set.of("investigator", "reviewer")))
                .containsExactlyInAnyOrder("GET_ENTITY_CONTEXT", "COMPARE_ENTITY_SOURCES");
    }

    @Test
    @DisplayName("an unmapped role contributes nothing and does not fail the call")
    void unmappedRoleContributesNothing() {
        SecurityPolicy policy = SecurityPolicy.fromYaml(yaml("""
                purposes: [fraud_investigation]
                roles:
                  investigator: [GET_ENTITY_CONTEXT]
                """));

        assertThat(policy.capabilitiesFor(Set.of("investigator", "unknown-role")))
                .containsExactly("GET_ENTITY_CONTEXT");
    }
}

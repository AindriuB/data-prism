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

    private static void assertRefused(String yaml, String message) {
        assertThatThrownBy(() -> SecurityPolicy.fromYaml(yaml(yaml)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(message);
    }

    @Test
    @DisplayName("an unknown top-level key is refused with UNKNOWN_CONFIG_KEY")
    void unknownTopLevelKeyCode() {
        assertRefused("purposes: [p]\nextra: 1\n", "UNKNOWN_CONFIG_KEY: security policy has an unknown key 'extra'");
    }

    @Test
    @DisplayName("an unknown key longer than 64 characters is named cut to 64")
    void longUnknownKeyIsTruncated() {
        assertRefused("purposes: [p]\n" + "k".repeat(100) + ": 1\n",
                "UNKNOWN_CONFIG_KEY: security policy has an unknown key '" + "k".repeat(64) + "'");
    }

    @Test
    @DisplayName("a duplicate top-level key is refused with DUPLICATE_CONFIG_KEY")
    void duplicateTopLevelKey() {
        assertRefused("purposes: [first]\npurposes: [second]\n",
                "DUPLICATE_CONFIG_KEY: security policy has a duplicate key 'purposes'");
    }

    @Test
    @DisplayName("a duplicate role is refused with DUPLICATE_CONFIG_KEY, naming the path")
    void duplicateRole() {
        assertRefused("purposes: [p]\nroles:\n  r: [GET_ENTITY_CONTEXT]\n  r: [EXPOSE_SOURCE_NAMES]\n",
                "DUPLICATE_CONFIG_KEY: security policy has a duplicate key 'r' in roles");
    }

    @Test
    @DisplayName("a second YAML document is refused with TRAILING_CONFIG_CONTENT")
    void secondDocument() {
        assertThatThrownBy(() -> SecurityPolicy.fromYaml(yaml("purposes: [p]\n---\npurposes: [q]\n")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("TRAILING_CONFIG_CONTENT: security policy ");
    }

    @Test
    @DisplayName("a purpose written as a number or boolean is refused with NON_STRING_CONFIG_SCALAR, without the value")
    void purposeMustBeAString() {
        assertRefused("purposes: [p, 12345]\n",
                "NON_STRING_CONFIG_SCALAR: security policy purposes[1] must be a quoted string");
        assertRefused("purposes: [true]\n",
                "NON_STRING_CONFIG_SCALAR: security policy purposes[0] must be a quoted string");
        assertThat(SecurityPolicy.fromYaml(yaml("purposes: [\"12345\", 010]\n")).allowedPurposes())
                .containsExactlyInAnyOrder("12345", "010");
    }

    @Test
    @DisplayName("a capability written as a number is refused with NON_STRING_CONFIG_SCALAR")
    void capabilityMustBeAString() {
        assertRefused("purposes: [p]\nroles:\n  r: [1]\n",
                "NON_STRING_CONFIG_SCALAR: security policy roles.r[0] must be a quoted string");
    }

    @Test
    @DisplayName("roles that are not a mapping, including an empty value, are refused with INVALID_CONFIG_SHAPE")
    void wrongShapedRoles() {
        assertRefused("purposes: [p]\nroles: [r]\n", "INVALID_CONFIG_SHAPE: security policy roles must be a mapping");
        assertRefused("purposes: [p]\nroles:\n", "INVALID_CONFIG_SHAPE: security policy roles must be a mapping");
    }

    @Test
    @DisplayName("a null-like purpose is refused with NULL_LIKE_CONFIG_SCALAR")
    void nullLikePurpose() {
        assertThatThrownBy(() -> SecurityPolicy.fromYaml(yaml("purposes: [Null]\n")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("NULL_LIKE_CONFIG_SCALAR: security policy purposes[0] ");
    }

    @Test
    @DisplayName("an alias is refused with UNSUPPORTED_CONFIG_YAML")
    void aliasRefused() {
        assertThatThrownBy(() -> SecurityPolicy.fromYaml(yaml("purposes: &p [a]\nroles:\n  r: *p\n")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("UNSUPPORTED_CONFIG_YAML: ");
    }
}

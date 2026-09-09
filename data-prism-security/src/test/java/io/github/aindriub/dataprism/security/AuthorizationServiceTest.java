package io.github.aindriub.dataprism.security;

import io.github.aindriub.dataprism.core.PrivacyScopeType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class AuthorizationServiceTest {

    private static final ToolInvocation GET_ENTITY_CONTEXT =
            new ToolInvocation("get_entity_context", "GET_ENTITY_CONTEXT");

    private static SecurityPolicy policy(Map<String, Set<String>> roleCapabilities) {
        return new SecurityPolicy(Set.of("fraud_investigation"), roleCapabilities);
    }

    private static AuthenticatedCaller caller(Set<String> roles) {
        return new AuthenticatedCaller(
                "principal-1", "client-1", roles, "fraud_investigation", "case-1",
                Instant.parse("2030-01-01T00:00:00Z"));
    }

    @Test
    @DisplayName("capabilities are exactly the union of the caller's mapped roles")
    void capabilitiesAreUnionOfMappedRoles() {
        AuthorizationService service = new AuthorizationService(
                policy(Map.of(
                        "investigator", Set.of("GET_ENTITY_CONTEXT"),
                        "reviewer", Set.of("COMPARE_ENTITY_SOURCES"))),
                "DEFAULT", PrivacyScopeType.CASE);

        AuthorizationDecision decision =
                service.authorize(caller(Set.of("investigator", "reviewer")), GET_ENTITY_CONTEXT);

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.capabilities()).containsExactlyInAnyOrder("GET_ENTITY_CONTEXT", "COMPARE_ENTITY_SOURCES");
    }

    @Test
    @DisplayName("an unmapped role contributes nothing and does not fail the call")
    void unmappedRoleDoesNotFail() {
        AuthorizationService service = new AuthorizationService(
                policy(Map.of("investigator", Set.of("GET_ENTITY_CONTEXT"))),
                "DEFAULT", PrivacyScopeType.CASE);

        AuthorizationDecision decision =
                service.authorize(caller(Set.of("investigator", "unmapped-role")), GET_ENTITY_CONTEXT);

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.capabilities()).containsExactly("GET_ENTITY_CONTEXT");
    }

    @Test
    @DisplayName("a caller with no mapped role is denied with NO_CAPABILITIES")
    void noMappedRoleDeniesWithNoCapabilities() {
        AuthorizationService service = new AuthorizationService(
                policy(Map.of("investigator", Set.of("GET_ENTITY_CONTEXT"))),
                "DEFAULT", PrivacyScopeType.CASE);

        AuthorizationDecision decision = service.authorize(caller(Set.of("stranger")), GET_ENTITY_CONTEXT);

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.denialCode()).isEqualTo("NO_CAPABILITIES");
        assertThat(decision.capabilities()).isEmpty();
    }

    @Test
    @DisplayName("a caller lacking the invoked tool's capability is denied with TOOL_NOT_PERMITTED")
    void lackingToolCapabilityDeniesWithToolNotPermitted() {
        AuthorizationService service = new AuthorizationService(
                policy(Map.of("investigator", Set.of("COMPARE_ENTITY_SOURCES"))),
                "DEFAULT", PrivacyScopeType.CASE);

        AuthorizationDecision decision = service.authorize(caller(Set.of("investigator")), GET_ENTITY_CONTEXT);

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.denialCode()).isEqualTo("TOOL_NOT_PERMITTED");
        // There is no path back to a decision a denied caller could act on: capabilities is empty
        // even though the caller does hold COMPARE_ENTITY_SOURCES.
        assertThat(decision.capabilities()).isEmpty();
    }

    @Test
    @DisplayName("an allowed decision carries the configured privacy profile and scope type")
    void allowedDecisionCarriesProfileAndScopeType() {
        AuthorizationService service = new AuthorizationService(
                policy(Map.of("investigator", Set.of("GET_ENTITY_CONTEXT"))),
                "STRICT", PrivacyScopeType.CASE);

        AuthorizationDecision decision = service.authorize(caller(Set.of("investigator")), GET_ENTITY_CONTEXT);

        assertThat(decision.privacyProfile()).isEqualTo("STRICT");
        assertThat(decision.scopeType()).isEqualTo(PrivacyScopeType.CASE);
        assertThat(decision.denialCode()).isNull();
    }
}

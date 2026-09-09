package io.github.aindriub.dataprism.security;

import io.github.aindriub.dataprism.core.PrivacyScopeType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthorizationDecisionTest {

    @Test
    @DisplayName("a denied decision cannot carry capabilities")
    void deniedDecisionRejectsCapabilities() {
        assertThatThrownBy(() -> new AuthorizationDecision(
                false, "DEFAULT", PrivacyScopeType.CASE, Set.of("GET_ENTITY_CONTEXT"), "TOOL_NOT_PERMITTED"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("an allowed decision cannot carry a denialCode")
    void allowedDecisionRejectsDenialCode() {
        assertThatThrownBy(() -> new AuthorizationDecision(
                true, "DEFAULT", PrivacyScopeType.CASE, Set.of("GET_ENTITY_CONTEXT"), "TOOL_NOT_PERMITTED"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

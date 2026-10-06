package io.github.aindriub.dataprism.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ToolCallsDenyDecisionTest {

    @Test
    @DisplayName("a well-formed code is recorded as DENY:<code>")
    void wellFormedCode() {
        assertThat(ToolCalls.denyDecision("TOOL_NOT_PERMITTED")).isEqualTo("DENY:TOOL_NOT_PERMITTED");
    }

    @Test
    @DisplayName("a code with a space is recorded as DENY:INVALID_REFUSAL_CODE")
    void malformedCode() {
        assertThat(ToolCalls.denyDecision("bad code")).isEqualTo("DENY:INVALID_REFUSAL_CODE");
    }

    @Test
    @DisplayName("a 65-character code is recorded as DENY:INVALID_REFUSAL_CODE")
    void overlongCode() {
        assertThat(ToolCalls.denyDecision("A".repeat(65))).isEqualTo("DENY:INVALID_REFUSAL_CODE");
        assertThat(ToolCalls.denyDecision("A".repeat(64))).isEqualTo("DENY:" + "A".repeat(64));
    }

    @Test
    @DisplayName("a null code is recorded as DENY:INVALID_REFUSAL_CODE")
    void nullCode() {
        assertThat(ToolCalls.denyDecision(null)).isEqualTo("DENY:INVALID_REFUSAL_CODE");
    }
}

package io.github.aindriub.dataprism.core;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RefusalCodesTest {

    @Test
    @DisplayName("a valid token is returned unchanged")
    void valid() {
        assertThat(RefusalCodes.sanitise("TOOL_PAUSED")).isEqualTo("TOOL_PAUSED");
    }

    @Test
    @DisplayName("64 characters pass, 65 do not")
    void length() {
        assertThat(RefusalCodes.sanitise("A".repeat(64))).isEqualTo("A".repeat(64));
        assertThat(RefusalCodes.sanitise("A".repeat(65))).isEqualTo(RefusalCodes.INVALID);
    }

    @Test
    @DisplayName("lower case, a leading digit, a space and a newline are invalid")
    void malformed() {
        assertThat(RefusalCodes.sanitise("tool_paused")).isEqualTo("INVALID_REFUSAL_CODE");
        assertThat(RefusalCodes.sanitise("1ABC")).isEqualTo("INVALID_REFUSAL_CODE");
        assertThat(RefusalCodes.sanitise("A B")).isEqualTo("INVALID_REFUSAL_CODE");
        assertThat(RefusalCodes.sanitise("ABC\n")).isEqualTo("INVALID_REFUSAL_CODE");
    }

    @Test
    @DisplayName("null and blank are invalid")
    void nullAndBlank() {
        assertThat(RefusalCodes.sanitise(null)).isEqualTo("INVALID_REFUSAL_CODE");
        assertThat(RefusalCodes.sanitise("")).isEqualTo("INVALID_REFUSAL_CODE");
        assertThat(RefusalCodes.sanitise("  ")).isEqualTo("INVALID_REFUSAL_CODE");
    }
}

package io.github.aindriub.dataprism.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClientAllowlistTest {

    @Test
    @DisplayName("unrestricted permits any client id")
    void unrestrictedPermitsAnyClient() {
        assertThat(ClientAllowlist.unrestricted().permits("anything")).isTrue();
    }

    @Test
    @DisplayName("matching is exact and case-sensitive")
    void matchingIsExactAndCaseSensitive() {
        ClientAllowlist list = ClientAllowlist.of(Set.of("agent-a"));

        assertThat(list.permits("agent-a")).isTrue();
        assertThat(list.permits("Agent-A")).isFalse();
        assertThat(list.permits("agent-a ")).isFalse();
        assertThat(list.permits("agent-b")).isFalse();
    }

    @Test
    @DisplayName("an empty set is rejected")
    void emptySetIsRejected() {
        assertThatThrownBy(() -> ClientAllowlist.of(Set.of())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a blank entry is rejected")
    void blankEntryIsRejected() {
        assertThatThrownBy(() -> ClientAllowlist.of(Set.of("agent-a", " ")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

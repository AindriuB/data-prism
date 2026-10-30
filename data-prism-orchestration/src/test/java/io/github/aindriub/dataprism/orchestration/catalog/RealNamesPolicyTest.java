package io.github.aindriub.dataprism.orchestration.catalog;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RealNamesPolicyTest {

    @Test
    @DisplayName("the default permits no scope")
    void noneByDefault() {
        assertThat(RealNamesPolicy.none().permits("case-1")).isFalse();
        assertThat(new RealNamesPolicy(List.of()).permits("case-1")).isFalse();
    }

    @Test
    @DisplayName("an exact pattern matches that scope id and no other")
    void exact() {
        RealNamesPolicy policy = new RealNamesPolicy(List.of("case-1"));

        assertThat(policy.permits("case-1")).isTrue();
        assertThat(policy.permits("case-10")).isFalse();
        assertThat(policy.permits("case-")).isFalse();
        assertThat(policy.permits("CASE-1")).isFalse();
    }

    @Test
    @DisplayName("a trailing * matches every scope id that starts with the prefix, the prefix itself included")
    void trailingStar() {
        RealNamesPolicy policy = new RealNamesPolicy(List.of("case-*"));

        assertThat(policy.permits("case-1")).isTrue();
        assertThat(policy.permits("case-")).isTrue();
        assertThat(policy.permits("case")).isFalse();
        assertThat(policy.permits("other-case-1")).isFalse();
    }

    @Test
    @DisplayName("a lone * matches every scope")
    void loneStar() {
        assertThat(new RealNamesPolicy(List.of("*")).permits("anything")).isTrue();
    }

    @Test
    @DisplayName("a pattern is never a regular expression")
    void notARegularExpression() {
        RealNamesPolicy policy = new RealNamesPolicy(List.of("case.1"));

        assertThat(policy.permits("case.1")).isTrue();
        assertThat(policy.permits("caseX1")).isFalse();
    }

    @Test
    @DisplayName("any one of several patterns is enough")
    void anyPattern() {
        RealNamesPolicy policy = new RealNamesPolicy(List.of("a-1", "b-*"));

        assertThat(policy.permits("a-1")).isTrue();
        assertThat(policy.permits("b-9")).isTrue();
        assertThat(policy.permits("a-2")).isFalse();
    }

    @Test
    @DisplayName("a null scope id is not permitted")
    void nullScope() {
        assertThat(new RealNamesPolicy(List.of("*")).permits(null)).isFalse();
    }

    @Test
    @DisplayName("a blank, null, multi-star or mid-pattern star throws INVALID_CATALOG_SETTING without the pattern")
    void invalidPatterns() {
        for (String pattern : new String[] {"", "  ", "a*b", "**", "a**", "*a", "a*b*", null}) {
            assertThatThrownBy(() -> new RealNamesPolicy(Arrays.asList(pattern)))
                    .as("pattern '%s'", pattern)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageStartingWith("INVALID_CATALOG_SETTING:");
        }
        assertThatThrownBy(() -> new RealNamesPolicy(List.of("secret-scope*x")))
                .hasMessageNotContaining("secret-scope");
    }
}

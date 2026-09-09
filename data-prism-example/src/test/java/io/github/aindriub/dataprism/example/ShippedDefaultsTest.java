package io.github.aindriub.dataprism.example;

import io.github.aindriub.dataprism.core.Capability;
import io.github.aindriub.dataprism.security.SecurityPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Holds the shipped defaults to what the tests assert, rather than to a
 * scoped-down stand-in a test builds for itself.
 *
 * <p>Two controls in particular exist, are tested, and were not what actually
 * shipped: {@code DataPrismAssembly} minted an {@code InvestigationContext}
 * holding {@code EXPOSE_SOURCE_NAMES} by construction, and {@code
 * ExampleApplication}'s {@code developer} role was asserted on by nothing,
 * because every capability test built its own policy. This class asserts on
 * the actual factories both callers use.
 */
class ShippedDefaultsTest {

    @Test
    @DisplayName("the shipped investigation context never holds EXPOSE_SOURCE_NAMES")
    void standardAssemblyContextIsMasked() {
        var context = DataPrismAssembly.standard().investigationContext();

        assertThat(context.capabilities()).isEqualTo(Set.of(Capability.GET_ENTITY_CONTEXT));
        assertThat(context.has(Capability.EXPOSE_SOURCE_NAMES)).isFalse();
    }

    @Test
    @DisplayName("the shipped developer role holds only GET_ENTITY_CONTEXT")
    void shippedDeveloperRoleIsNarrow() {
        SecurityPolicy policy = ExampleApplication.shippedSecurityPolicy();

        Set<String> capabilities = policy.capabilitiesFor(Set.of("developer"));

        assertThat(capabilities).isEqualTo(Set.of(Capability.GET_ENTITY_CONTEXT));
        assertThat(capabilities).doesNotContain(Capability.EXPOSE_SOURCE_NAMES);
    }
}

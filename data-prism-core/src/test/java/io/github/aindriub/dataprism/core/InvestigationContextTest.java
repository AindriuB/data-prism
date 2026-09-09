package io.github.aindriub.dataprism.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InvestigationContextTest {

    @Test
    @DisplayName("has() reflects a granted capability and nothing else")
    void reportsGrantedCapability() {
        InvestigationContext context = new InvestigationContext(
                "principal-1", "client-1", "case-1", Set.of(Capability.GET_ENTITY_CONTEXT));

        assertThat(context.has(Capability.GET_ENTITY_CONTEXT)).isTrue();
        assertThat(context.has(Capability.EXPOSE_SOURCE_NAMES)).isFalse();
    }

    @Test
    @DisplayName("capabilities are copied immutably")
    void copiesCapabilitiesImmutably() {
        Set<String> mutable = new HashSet<>(Set.of(Capability.GET_ENTITY_CONTEXT));
        InvestigationContext context = new InvestigationContext("principal-1", "client-1", "case-1", mutable);

        mutable.add(Capability.EXPOSE_SOURCE_NAMES);
        assertThat(context.has(Capability.EXPOSE_SOURCE_NAMES)).isFalse();
        assertThatThrownBy(() -> context.capabilities().add(Capability.EXPOSE_SOURCE_NAMES))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("a null principalId is rejected")
    void rejectsNullPrincipalId() {
        assertThatThrownBy(() -> new InvestigationContext(null, "client-1", "case-1", Set.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a blank clientId is rejected")
    void rejectsBlankClientId() {
        assertThatThrownBy(() -> new InvestigationContext("principal-1", "  ", "case-1", Set.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a blank caseId is rejected")
    void rejectsBlankCaseId() {
        assertThatThrownBy(() -> new InvestigationContext("principal-1", "client-1", "", Set.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("null capabilities are rejected rather than defaulted to empty")
    void rejectsNullCapabilities() {
        assertThatThrownBy(() -> new InvestigationContext("principal-1", "client-1", "case-1", null))
                .isInstanceOf(NullPointerException.class);
    }
}

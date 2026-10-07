package io.github.aindriub.dataprism.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ReservedArgumentsTest {

    @Test
    @DisplayName("NAMES covers at least the documented reserved argument names")
    void namesCoversDocumentedSet() {
        assertThat(ReservedArguments.NAMES).containsAll(Set.of(
                "principalId", "scopeId", "scopeType", "purpose", "caseId", "profile", "capabilities"));
    }

    @Test
    @DisplayName("NAMES reserves the correlation names a caller must never be able to set")
    void namesReservesCorrelationNames() {
        assertThat(ReservedArguments.NAMES).contains("correlationId", "externalCorrelationId", "traceparent");
        assertThat(ReservedArguments.rejected(Set.of("traceparent", "correlationId", "externalCorrelationId")))
                .containsExactlyInAnyOrder("traceparent", "correlationId", "externalCorrelationId");
    }

    @Test
    @DisplayName("rejected returns only the reserved names present in the call's arguments")
    void rejectedReturnsIntersection() {
        Set<String> rejected = ReservedArguments.rejected(Set.of("entityId", "caseId", "note", "purpose"));

        assertThat(rejected).containsExactlyInAnyOrder("caseId", "purpose");
    }

    @Test
    @DisplayName("an argument set with no reserved names rejects nothing")
    void rejectsNothingWhenNoneReserved() {
        assertThat(ReservedArguments.rejected(Set.of("entityId", "note"))).isEmpty();
    }
}

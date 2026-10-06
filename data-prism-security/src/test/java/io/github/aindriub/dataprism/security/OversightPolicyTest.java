package io.github.aindriub.dataprism.security;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.OptionalInt;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OversightPolicyTest {

    @Test
    void noneHasNoToolsAndNoLimit() {
        OversightPolicy none = OversightPolicy.none();
        assertTrue(none.approvalRequiredTools().isEmpty());
        assertTrue(none.callerRequestLimit().isEmpty());
    }

    @Test
    void rejectsNonPositiveValues() {
        Duration ok = Duration.ofMinutes(1);
        assertThrows(IllegalArgumentException.class,
                () -> new OversightPolicy(Set.of(), OptionalInt.of(0), ok, ok));
        assertThrows(IllegalArgumentException.class,
                () -> new OversightPolicy(Set.of(), OptionalInt.of(-1), ok, ok));
        assertThrows(IllegalArgumentException.class,
                () -> new OversightPolicy(Set.of(), OptionalInt.empty(), Duration.ZERO, ok));
        assertThrows(IllegalArgumentException.class,
                () -> new OversightPolicy(Set.of(), OptionalInt.empty(), ok, Duration.ofSeconds(-1)));
        assertEquals(3, new OversightPolicy(Set.of(), OptionalInt.of(3), ok, ok).callerRequestLimit().getAsInt());
    }
}

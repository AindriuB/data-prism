package io.github.aindriub.dataprism.core.limits;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScopeBudgetDefaultsTest {

    /** An implementation of only the 0.7 methods. */
    private static final class LegacyBudget implements ScopeBudget {
        @Override
        public boolean tryRead(String scopeId, String subjectId, int budget) {
            return true;
        }

        @Override
        public int reads(String scopeId, String subjectId) {
            return 0;
        }

        @Override
        public void forget(String scopeId) {
        }
    }

    @Test
    @DisplayName("an implementation that has not been updated refuses every reservation")
    void refusesEverything() {
        ScopeBudget legacy = new LegacyBudget();

        ReadReservation r = legacy.reserve("CASE-A", "s-1", 1, 100, 100);

        assertThat(r.granted()).isFalse();
        assertThat(r.refusedBy()).isNotNull();
    }

    @Test
    @DisplayName("the default release does nothing and the default scope count is zero")
    void defaultReleaseIsNoOp() {
        ScopeBudget legacy = new LegacyBudget();
        ReadReservation r = legacy.reserve("CASE-A", "s-1", 1, 100, 100);

        legacy.release(r, 1);

        assertThat(legacy.scopeReads("CASE-A")).isZero();
    }

    @Test
    @DisplayName("the default validates its arguments before refusing")
    void defaultValidates() {
        ScopeBudget legacy = new LegacyBudget();

        assertThatThrownBy(() -> legacy.reserve("S", "a", 0, 5, 5)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> legacy.reserve("S", "a", 1, 0, 5)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> legacy.reserve("S", null, 1, 5, 5)).isInstanceOf(NullPointerException.class);
    }
}

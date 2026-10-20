package io.github.aindriub.dataprism.core.limits;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReadReservationTest {

    @Test
    @DisplayName("a granted reservation holds its reads and has no refusal")
    void granted() {
        var r = ReadReservation.granted("CASE-A", "s-1", 3);

        assertThat(r.granted()).isTrue();
        assertThat(r.reads()).isEqualTo(3);
        assertThat(r.refusedBy()).isNull();
    }

    @Test
    @DisplayName("a refused reservation names who refused and holds nothing")
    void refused() {
        var r = ReadReservation.refused("CASE-A", "s-1", 3, ReadReservation.Refusal.SCOPE_TOTAL);

        assertThat(r.granted()).isFalse();
        assertThat(r.refusedBy()).isEqualTo(ReadReservation.Refusal.SCOPE_TOTAL);
        assertThat(r.takeUnused(3)).isZero();
    }

    @Test
    @DisplayName("takeUnused hands back at most what is still held, once per read")
    void takeUnusedIsBounded() {
        var r = ReadReservation.granted("CASE-A", "s-1", 5);

        assertThat(r.takeUnused(2)).isEqualTo(2);
        assertThat(r.takeUnused(10)).isEqualTo(3);
        assertThat(r.takeUnused(1)).isZero();
    }

    @Test
    @DisplayName("a negative amount is rejected")
    void negativeRejected() {
        var r = ReadReservation.granted("CASE-A", "s-1", 5);

        assertThatThrownBy(() -> r.takeUnused(-1)).isInstanceOf(IllegalArgumentException.class);
    }
}

package io.github.aindriub.dataprism.hazelcast;

import io.github.aindriub.dataprism.hazelcast.ClusterSecuritySettings.UnverifiedMemberAction;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClusterSecuritySettingsTest {

    private static final Duration LEASE = Duration.ofSeconds(30);
    private static final Duration SKEW = Duration.ofSeconds(5);
    private static final Duration GRACE = Duration.ofSeconds(30);

    private static ClusterSecuritySettings with(Duration lease, Duration skew, Duration grace) {
        return new ClusterSecuritySettings(lease, skew, UnverifiedMemberAction.REFUSE, grace, true);
    }

    @Test
    void defaultsAreThoseOfDesign4_1() {
        ClusterSecuritySettings defaults = ClusterSecuritySettings.defaults();
        assertThat(defaults.pauseLease()).isEqualTo(Duration.parse("PT30S"));
        assertThat(defaults.clockSkewTolerance()).isEqualTo(Duration.parse("PT5S"));
        assertThat(defaults.unverifiedMemberAction()).isEqualTo(UnverifiedMemberAction.REFUSE);
        assertThat(defaults.unverifiedMemberGrace()).isEqualTo(Duration.parse("PT30S"));
        assertThat(defaults.localFloorEnabled()).isTrue();
    }

    @Test
    void pauseLeaseIsCheckedAtItsBoundsAndOneUnitInsideAndOutside() {
        Duration min = Duration.ofSeconds(5);
        Duration max = Duration.ofMinutes(5);
        assertThat(with(min, Duration.ZERO, GRACE).pauseLease()).isEqualTo(min);
        assertThat(with(min.plusNanos(1), Duration.ZERO, GRACE).pauseLease()).isEqualTo(min.plusNanos(1));
        assertThat(with(max, SKEW, GRACE).pauseLease()).isEqualTo(max);
        assertThat(with(max.minusNanos(1), SKEW, GRACE).pauseLease()).isEqualTo(max.minusNanos(1));
        assertRefused(() -> with(min.minusNanos(1), Duration.ZERO, GRACE), "pauseLease");
        assertRefused(() -> with(max.plusNanos(1), SKEW, GRACE), "pauseLease");
    }

    @Test
    void clockSkewToleranceIsCheckedAtItsBoundsAndOneUnitInsideAndOutside() {
        assertThat(with(LEASE, Duration.ZERO, GRACE).clockSkewTolerance()).isEqualTo(Duration.ZERO);
        assertThat(with(LEASE, Duration.ofNanos(1), GRACE).clockSkewTolerance()).isEqualTo(Duration.ofNanos(1));
        Duration half = LEASE.dividedBy(2);
        assertThat(with(LEASE, half, GRACE).clockSkewTolerance()).isEqualTo(half);
        assertThat(with(LEASE, half.minusNanos(1), GRACE).clockSkewTolerance()).isEqualTo(half.minusNanos(1));
        assertRefused(() -> with(LEASE, Duration.ofNanos(-1), GRACE), "clockSkewTolerance");
        assertRefused(() -> with(LEASE, half.plusNanos(1), GRACE), "clockSkewTolerance");
    }

    @Test
    void clockSkewAboveHalfOfThePauseLeaseIsRefusedWhateverTheLease() {
        assertRefused(() -> with(Duration.ofSeconds(5), Duration.ofSeconds(5), GRACE), "clockSkewTolerance");
        assertRefused(() -> with(Duration.ofMinutes(5), Duration.ofSeconds(151), GRACE), "clockSkewTolerance");
    }

    @Test
    void unverifiedMemberGraceIsCheckedAtItsBoundsAndOneUnitInsideAndOutside() {
        Duration max = Duration.ofMinutes(10);
        assertThat(with(LEASE, SKEW, Duration.ZERO).unverifiedMemberGrace()).isEqualTo(Duration.ZERO);
        assertThat(with(LEASE, SKEW, Duration.ofNanos(1)).unverifiedMemberGrace()).isEqualTo(Duration.ofNanos(1));
        assertThat(with(LEASE, SKEW, max).unverifiedMemberGrace()).isEqualTo(max);
        assertThat(with(LEASE, SKEW, max.minusNanos(1)).unverifiedMemberGrace()).isEqualTo(max.minusNanos(1));
        assertRefused(() -> with(LEASE, SKEW, Duration.ofNanos(-1)), "unverifiedMemberGrace");
        assertRefused(() -> with(LEASE, SKEW, max.plusNanos(1)), "unverifiedMemberGrace");
    }

    @Test
    void aMissingComponentIsRefusedByName() {
        assertRefused(() -> new ClusterSecuritySettings(null, SKEW, UnverifiedMemberAction.REFUSE, GRACE, true),
                "pauseLease");
        assertRefused(() -> new ClusterSecuritySettings(LEASE, null, UnverifiedMemberAction.REFUSE, GRACE, true),
                "clockSkewTolerance");
        assertRefused(() -> new ClusterSecuritySettings(LEASE, SKEW, null, GRACE, true), "unverifiedMemberAction");
        assertRefused(() -> new ClusterSecuritySettings(LEASE, SKEW, UnverifiedMemberAction.ALERT, null, true),
                "unverifiedMemberGrace");
    }

    @Test
    void aRefusalNamesTheComponentAndNeverTheValue() {
        Duration odd = Duration.ofSeconds(4321);
        assertThatThrownBy(() -> with(odd, Duration.ZERO, GRACE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pauseLease")
                .hasMessageNotContaining("4321").hasMessageNotContaining("PT1H12M1S");
        assertThatThrownBy(() -> with(LEASE, SKEW, odd))
                .hasMessageContaining("unverifiedMemberGrace").hasMessageNotContaining("4321");
    }

    private static void assertRefused(Runnable construct, String component) {
        assertThatThrownBy(construct::run).isInstanceOf(IllegalArgumentException.class).hasMessageContaining(component);
    }
}

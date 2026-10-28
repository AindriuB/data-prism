package io.github.aindriub.dataprism.core.limits;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RequestLimitsTest {

    private static RequestLimits with(Duration perSource, Duration request, int scopeTotal) {
        return new RequestLimits(8, 500, 512 * 1024, perSource, 4, 100, request, scopeTotal);
    }

    @Test
    @DisplayName("the defaults are the decided ones: a 10 s deadline and a scope total of 50,000")
    void defaults() {
        assertThat(RequestLimits.DEFAULT.requestTimeout()).isEqualTo(Duration.ofSeconds(10));
        assertThat(RequestLimits.DEFAULT.scopeTotalReadBudget()).isEqualTo(50_000);
        assertThat(RequestLimits.DEFAULT.scopeReadBudget()).isEqualTo(100);
        assertThat(RequestLimits.DEFAULT.perSourceTimeout()).isEqualTo(Duration.ofSeconds(3));
    }

    @Test
    @DisplayName("a deadline equal to the per-source timeout is allowed")
    void deadlineMayEqualPerSource() {
        assertThat(with(Duration.ofSeconds(3), Duration.ofSeconds(3), 1).requestTimeout())
                .isEqualTo(Duration.ofSeconds(3));
    }

    @Test
    @DisplayName("a deadline below the per-source timeout is refused, naming the component")
    void deadlineBelowPerSource() {
        assertThatThrownBy(() -> with(Duration.ofSeconds(3), Duration.ofSeconds(2), 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requestTimeout")
                .hasMessageNotContaining("PT2S");
    }

    @Test
    @DisplayName("a zero or negative deadline is refused, naming the component and not the value")
    void deadlineMustBePositive() {
        assertThatThrownBy(() -> with(Duration.ofSeconds(3), Duration.ZERO, 1))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("requestTimeout");
        assertThatThrownBy(() -> with(Duration.ofSeconds(3), Duration.ofSeconds(-7), 1))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("requestTimeout")
                .hasMessageNotContaining("-7");
    }

    @Test
    @DisplayName("a scope total that is not positive is refused, naming the component and not the value")
    void scopeTotalMustBePositive() {
        assertThatThrownBy(() -> with(Duration.ofSeconds(3), Duration.ofSeconds(10), 0))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("scopeTotalReadBudget");
        assertThatThrownBy(() -> with(Duration.ofSeconds(3), Duration.ofSeconds(10), -4242))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("scopeTotalReadBudget")
                .hasMessageNotContaining("4242");
    }

    @Test
    @DisplayName("a missing deadline is refused, naming the component")
    void deadlineMustBePresent() {
        assertThatThrownBy(() -> with(Duration.ofSeconds(3), null, 1))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("requestTimeout");
    }

    @Test
    @DisplayName("a zero per-source timeout is refused, naming the component and not the value")
    void perSourceTimeoutMustBePositive() {
        assertThatThrownBy(() -> with(Duration.ZERO, Duration.ofSeconds(10), 1))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("perSourceTimeout")
                .hasMessageNotContaining("PT0S");
        assertThatThrownBy(() -> with(Duration.ofSeconds(-9), Duration.ofSeconds(10), 1))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("perSourceTimeout")
                .hasMessageNotContaining("-9").hasMessageNotContaining("PT-9S");
    }

    @Test
    @DisplayName("a zero deadline names the component and not the value")
    void zeroDeadlineMessage() {
        assertThatThrownBy(() -> with(Duration.ofSeconds(3), Duration.ZERO, 1))
                .hasMessageContaining("requestTimeout").hasMessageNotContaining("PT0S");
    }

    @Test
    @DisplayName("a deadline below the per-source timeout names neither value")
    void deadlineBelowPerSourceMessage() {
        assertThatThrownBy(() -> with(Duration.ofSeconds(37), Duration.ofSeconds(23), 1))
                .hasMessageContaining("requestTimeout").hasMessageContaining("perSourceTimeout")
                .hasMessageNotContaining("37").hasMessageNotContaining("23");
    }
}

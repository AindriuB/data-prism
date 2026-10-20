package io.github.aindriub.dataprism.loadtest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Every wait the harness adds has a bound, and each bound is proved by something that never finishes. */
@Timeout(60)
class BoundedTest {

    @Test
    void aTaskThatNeverReturnsIsAbandonedAtTheBound() {
        CountDownLatch never = new CountDownLatch(1);
        long started = System.nanoTime();

        boolean finished = Bounded.run(() -> {
            try {
                never.await();
            } catch (InterruptedException ignored) {
                // abandoned
            }
        }, Duration.ofMillis(300));

        assertThat(finished).isFalse();
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(20));
    }

    @Test
    void aTaskThatReturnsReportsItFinishedAndAThrowingOneDoesNotEscape() {
        assertThat(Bounded.run(() -> { }, Duration.ofSeconds(5))).isTrue();
        assertThat(Bounded.run(() -> {
            throw new IllegalStateException("boom");
        }, Duration.ofSeconds(5))).isTrue();
    }

    @Test
    void aCommandThatNeverEndsFailsAtTheBoundInsteadOfHangingTheRun() {
        long started = System.nanoTime();

        assertThatThrownBy(() -> Bounded.command(List.of("sh", "-c", "echo partial; exec sleep 60"),
                Duration.ofMillis(500))).isInstanceOf(IOException.class).hasMessageContaining("within");

        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(30));
    }

    @Test
    void aCommandThatEndsReturnsItsOutputAndANonZeroExitIsAnError() throws Exception {
        assertThat(Bounded.command(List.of("sh", "-c", "echo hello"), Duration.ofSeconds(10))).isEqualTo("hello");
        assertThatThrownBy(() -> Bounded.command(List.of("sh", "-c", "echo bad; exit 3"), Duration.ofSeconds(10)))
                .isInstanceOf(IOException.class).hasMessageContaining("bad");
    }

    @Test
    void theDriversHttpClientHasAConnectTimeout() {
        HttpClient client = LoadDriver.boundConnect(HttpClient.newBuilder()).build();

        assertThat(client.connectTimeout()).contains(LoadDriver.CONNECT_TIMEOUT);
    }
}

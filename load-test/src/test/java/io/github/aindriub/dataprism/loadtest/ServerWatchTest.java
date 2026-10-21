package io.github.aindriub.dataprism.loadtest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@Timeout(60)
class ServerWatchTest {

    @Test
    void aLiveServerNeverHaltsTheRun() throws Exception {
        try (ServerWatch watch = ServerWatch.start(() -> null, Duration.ofMillis(10))) {
            Thread.sleep(100);

            assertThat(watch.halted()).isFalse();
            assertThat(watch.exitCode()).isNull();
        }
    }

    @Test
    void aServerThatExitsHaltsTheRunAndTheExitCodeIsKept() throws Exception {
        AtomicReference<Integer> exit = new AtomicReference<>();
        try (ServerWatch watch = ServerWatch.start(exit::get, Duration.ofMillis(10))) {
            exit.set(137);
            long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
            while (!watch.halted() && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }

            assertThat(watch.halted()).isTrue();
            assertThat(watch.exitCode()).isEqualTo(137);
        }
    }

    @Test
    void aHaltedRunStopsDispatchingAtOnce() {
        java.util.concurrent.atomic.AtomicLong dispatched = new java.util.concurrent.atomic.AtomicLong();
        java.util.concurrent.atomic.AtomicBoolean halted = new java.util.concurrent.atomic.AtomicBoolean();

        LoadDriver.workerLoop(new java.util.concurrent.atomic.AtomicLong(), dispatched, 0, 0, 0, Long.MAX_VALUE,
                System::nanoTime, nanos -> { }, (index, due) -> {
                    if (index == 4) {
                        halted.set(true);
                    }
                }, halted::get);

        assertThat(dispatched.get()).isEqualTo(5);
    }
}

package io.github.aindriub.dataprism.loadtest;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/** The worker loop against a fake clock: no server, no sleeping, no timing dependence. */
class PacedDispatchTest {

    private static final long SECOND = Duration.ofSeconds(1).toNanos();
    private static final long MILLI = Duration.ofMillis(1).toNanos();

    private final AtomicLong now = new AtomicLong();
    private final List<Long> dues = new ArrayList<>();

    @Test
    void aWorkerThatFallsBehindStopsDispatchingAtTheRealDeadline() {
        // One worker, 100 calls/s for 1 s, each call taking 100 ms: the schedule has 100 calls, the clock fits 10.
        AtomicLong dispatched = new AtomicLong();
        LoadDriver.workerLoop(new AtomicLong(), dispatched, 0, 0, 100, SECOND,
                now::get, wait -> now.addAndGet(wait), this::slowCall);

        assertThat(dispatched.get()).isEqualTo(10);
        assertThat(now.get()).isEqualTo(SECOND);
    }

    @Test
    void latencyIsStillMeasuredFromTheScheduledTime() {
        LoadDriver.workerLoop(new AtomicLong(), new AtomicLong(), 0, 0, 100, SECOND,
                now::get, wait -> now.addAndGet(wait), this::slowCall);

        assertThat(dues).startsWith(0L, 10 * MILLI, 20 * MILLI).hasSize(10);
    }

    @Test
    void aWorkerKeepingUpDispatchesTheWholeSchedule() {
        AtomicLong dispatched = new AtomicLong();
        LoadDriver.workerLoop(new AtomicLong(), dispatched, 0, 0, 100, SECOND,
                now::get, wait -> now.addAndGet(wait), (index, due) -> { });

        assertThat(dispatched.get()).isEqualTo(100);
    }

    @Test
    void scheduledCallsThatWereNeverDispatchedAreCounted() {
        assertThat(LoadDriver.notDispatched(100, 1, 0, 10)).isEqualTo(90);
        assertThat(LoadDriver.notDispatched(100, 1, 50, 10)).isEqualTo(40);
        assertThat(LoadDriver.notDispatched(100, 1, 0, 100)).isZero();
        assertThat(LoadDriver.notDispatched(0, 1, 0, 10)).isZero();
        assertThat(LoadDriver.notDispatched(100, 0, 20, 5)).isZero();
    }

    private void slowCall(long index, long due) {
        dues.add(due);
        now.addAndGet(100 * MILLI);
    }
}

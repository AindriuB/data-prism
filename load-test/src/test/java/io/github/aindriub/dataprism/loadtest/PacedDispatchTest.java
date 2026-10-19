package io.github.aindriub.dataprism.loadtest;

import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.util.Map;

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

    @Test
    void anEarlyReturnFromParkDoesNotDispatchBeforeTheDueTime() {
        List<Long> clockAtDispatch = new ArrayList<>();
        // Each park wakes after a third of the wait, as a spurious wakeup would.
        LoadDriver.workerLoop(new AtomicLong(), new AtomicLong(), 3, 0, 100, SECOND,
                now::get, wait -> now.addAndGet(Math.max(1, wait / 3)),
                (index, due) -> clockAtDispatch.add(now.get() - due));

        assertThat(clockAtDispatch).hasSize(3).allSatisfy(late -> assertThat(late).isGreaterThanOrEqualTo(0));
    }

    @Test
    void aStuckInterruptFlagStopsTheWorkerInsteadOfSpinningOrDispatchingEarly() {
        AtomicLong dispatched = new AtomicLong();
        Thread.currentThread().interrupt();
        try {
            LoadDriver.workerLoop(new AtomicLong(), dispatched, 3, 0, 100, SECOND,
                    now::get, wait -> { }, (index, due) -> dues.add(due));

            // Index 0 is due at once and needs no wait; index 1 would wait while interrupted.
            assertThat(dues).containsExactly(0L);
            assertThat(dispatched.get()).isEqualTo(1);
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void latencyIsMeasuredFromTheScheduledTimeNotFromWhenTheCallWasSent() {
        now.set(500 * MILLI);
        McpSchema.CallToolResult result = McpSchema.CallToolResult.builder()
                .structuredContent(Map.of()).addTextContent("{}").build();

        CallOutcome outcome = LoadDriver.measuredCall(subject -> {
            now.addAndGet(5 * MILLI);
            return result;
        }, now::get, "m-1", 100 * MILLI, Duration.ofSeconds(60));

        assertThat(outcome.completed()).isTrue();
        assertThat(outcome.latencyMicros()).isEqualTo(405_000);
    }

    @Test
    void aFastTransportFailureIsNotTimed() {
        now.set(500 * MILLI);

        CallOutcome outcome = LoadDriver.measuredCall(subject -> {
            now.addAndGet(200_000);
            throw new IllegalStateException("connection refused");
        }, now::get, "m-1", 100 * MILLI, Duration.ofSeconds(60));

        assertThat(outcome.completed()).isFalse();
        assertThat(outcome.timedOut()).isFalse();
        assertThat(outcome.latencyMicros()).isZero();
    }

    @Test
    void aTimeoutIsTimedFromTheScheduledTime() {
        now.set(500 * MILLI);

        CallOutcome outcome = LoadDriver.measuredCall(subject -> {
            now.addAndGet(60 * SECOND);
            throw new IllegalStateException("timed out");
        }, now::get, "m-1", 100 * MILLI, Duration.ofSeconds(60));

        assertThat(outcome.completed()).isFalse();
        assertThat(outcome.timedOut()).isTrue();
        assertThat(outcome.transportFailure()).isEqualTo("IllegalStateException");
        assertThat(outcome.latencyMicros()).isEqualTo(60_400_000);
    }

    private void slowCall(long index, long due) {
        dues.add(due);
        now.addAndGet(100 * MILLI);
    }
}

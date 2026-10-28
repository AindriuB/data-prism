package io.github.aindriub.dataprism.orchestration;

import io.github.aindriub.dataprism.core.limits.RequestLimits;
import io.github.aindriub.dataprism.core.spi.DataRequest;
import io.github.aindriub.dataprism.core.spi.DataSourceAdapter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two places the clock can move between a thread's check and its act: the waiter pausing across the deadline
 * while a call finishes, and a call's thread pausing between getting its permit and starting. Both are driven with
 * latches and a clock the test moves; nothing sleeps.
 */
class SourceFanOutDeadlineRacesTest {

    private static final long WAIT_SECONDS = 10;

    private static SourceOutcome failed(String name) {
        return new SourceOutcome(name, SourceOutcome.Status.FAILED, Duration.ZERO, "x");
    }

    private static Map<String, DataRequest> ask(List<DataSourceAdapter<?>> adapters) {
        Map<String, DataRequest> requests = new LinkedHashMap<>();
        adapters.forEach(a -> requests.put(a.sourceName(), DataRequest.of("THING", "1")));
        return requests;
    }

    /** Holds the waiting thread at its next pass until the test lets it go. */
    private static final class WaiterPause {
        final AtomicBoolean armed = new AtomicBoolean();
        final CountDownLatch reached = new CountDownLatch(1);
        final CountDownLatch resume = new CountDownLatch(1);

        void install(SourceFanOut fanOut) {
            fanOut.tickObserver(now -> {
                if (armed.compareAndSet(true, false)) {
                    reached.countDown();
                    await(resume);
                }
            });
        }

        void pauseNextPass() throws InterruptedException {
            armed.set(true);
            assertThat(reached.await(WAIT_SECONDS, TimeUnit.SECONDS)).as("the waiter is paused").isTrue();
        }
    }

    private static void await(CountDownLatch latch) {
        boolean interrupted = false;
        try {
            while (true) {
                try {
                    if (!latch.await(WAIT_SECONDS, TimeUnit.SECONDS)) {
                        throw new AssertionError("latch not released");
                    }
                    return;
                } catch (InterruptedException e) {
                    // A thread that is being cancelled keeps waiting: the test, not the interrupt, decides when it goes on.
                    interrupted = true;
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static CountDownLatch endOf(SourceFanOut fanOut, String source) {
        CountDownLatch ended = new CountDownLatch(1);
        fanOut.callEndObserver(name -> {
            if (name.equals(source)) {
                ended.countDown();
            }
        });
        return ended;
    }

    @Test
    @DisplayName("a result that arrives after the call's own timeout is a timeout of the source, however late the waiter looks")
    void aLateResultPastTheOwnTimeoutIsAFailure() throws Exception {
        var clock = new SourceFanOutDeadlineTest.MutableClock();
        var breaker = new SourceCircuitBreaker(1, Duration.ofSeconds(5), clock);
        var fanOut = new SourceFanOut(breaker, clock, SourceFanOutOptions.defaults());
        breaker.record(failed("s"));
        clock.advance(Duration.ofSeconds(6));
        var source = new SourceFanOutDeadlineTest.Blocking("s");
        List<DataSourceAdapter<?>> adapters = List.of(source);
        var limits = TestLimits.defaults().perSourceTimeout(Duration.ofSeconds(3)).maxConcurrency(1).build();
        var pause = new WaiterPause();
        pause.install(fanOut);
        var ended = endOf(fanOut, "s");
        var running = TestAsync.supply(() -> fanOut.execute(fanOut.plan(adapters, ask(adapters), limits), limits));
        assertThat(source.entered.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();

        pause.pauseNextPass();
        clock.advance(Duration.ofSeconds(10));
        source.release.countDown();
        await(ended);
        pause.resume.countDown();
        var execution = running.get(WAIT_SECONDS, TimeUnit.SECONDS);

        assertThat(execution.results()).singleElement().satisfies(f -> {
            assertThat(f.outcome().status()).isEqualTo(SourceOutcome.Status.TIMED_OUT);
            assertThat(f.record()).as("the late answer is not used").isNull();
        });
        assertThat(breaker.admit("s").allowed())
                .as("no success was recorded: the trial failed and the breaker is open again").isFalse();
    }

    @Test
    @DisplayName("a result that arrives after the overall deadline only is a cut call: not a success, not a failure")
    void aLateResultPastTheDeadlineOnlyIsACutCall() throws Exception {
        var clock = new SourceFanOutDeadlineTest.MutableClock();
        var breaker = new SourceCircuitBreaker(1, Duration.ofSeconds(5), clock);
        var fanOut = new SourceFanOut(breaker, clock, SourceFanOutOptions.defaults());
        breaker.record(failed("one"));
        breaker.record(failed("two"));
        clock.advance(Duration.ofSeconds(6));
        var order = new LinkedBlockingQueue<SourceFanOutDeadlineTest.Blocking>();
        var one = new SourceFanOutDeadlineTest.Blocking("one");
        var two = new SourceFanOutDeadlineTest.Blocking("two");
        one.order = order;
        two.order = order;
        List<DataSourceAdapter<?>> adapters = List.of(one, two);
        var limits = TestLimits.defaults().perSourceTimeout(Duration.ofSeconds(10)).maxConcurrency(1).build();
        var pause = new WaiterPause();
        pause.install(fanOut);
        var running = TestAsync.supply(() -> fanOut.execute(fanOut.plan(adapters, ask(adapters), limits), limits));

        var first = order.poll(WAIT_SECONDS, TimeUnit.SECONDS);
        assertThat(first).isNotNull();
        clock.advance(Duration.ofSeconds(8));
        first.release.countDown();
        var second = order.poll(WAIT_SECONDS, TimeUnit.SECONDS);
        assertThat(second).isNotNull();
        var ended = endOf(fanOut, second.sourceName());
        // The second call began 8 s into a 10 s deadline, so its own 10 s timeout outlasts the deadline.
        pause.pauseNextPass();
        clock.advance(Duration.ofSeconds(3));
        second.release.countDown();
        await(ended);
        pause.resume.countDown();
        var execution = running.get(WAIT_SECONDS, TimeUnit.SECONDS);

        assertThat(execution.results()).filteredOn(f -> f.outcome().sourceName().equals(first.sourceName()))
                .singleElement().satisfies(f -> assertThat(f.outcome().status()).isEqualTo(SourceOutcome.Status.ANSWERED));
        assertThat(execution.results()).filteredOn(f -> f.outcome().sourceName().equals(second.sourceName()))
                .singleElement().satisfies(f -> {
                    assertThat(f.outcome().status()).isEqualTo(SourceOutcome.Status.TIMED_OUT);
                    assertThat(f.record()).isNull();
                });
        assertThat(breaker.admit(second.sourceName()).holdsTrial())
                .as("neither a success nor a failure was recorded: the trial was handed back").isTrue();
    }

    @Test
    @DisplayName("a result that finished on time is still ANSWERED when the waiter only sees it after the deadline (it was in the queue)")
    void anOnTimeResultSeenLateIsStillAccepted() throws Exception {
        var clock = new SourceFanOutDeadlineTest.MutableClock();
        var breaker = new SourceCircuitBreaker(1, Duration.ofSeconds(5), clock);
        var fanOut = new SourceFanOut(breaker, clock, SourceFanOutOptions.defaults());
        breaker.record(failed("s"));
        clock.advance(Duration.ofSeconds(6));
        var source = new SourceFanOutDeadlineTest.Blocking("s");
        List<DataSourceAdapter<?>> adapters = List.of(source);
        var limits = TestLimits.defaults().perSourceTimeout(Duration.ofSeconds(3)).maxConcurrency(1).build();
        var pause = new WaiterPause();
        pause.install(fanOut);
        var ended = endOf(fanOut, "s");
        var running = TestAsync.supply(() -> fanOut.execute(fanOut.plan(adapters, ask(adapters), limits), limits));
        assertThat(source.entered.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();

        pause.pauseNextPass();
        source.release.countDown();
        await(ended);
        // It finished at 6 s; the waiter looks at 16 s, past the 10 s deadline and the 3 s timeout.
        clock.advance(Duration.ofSeconds(10));
        pause.resume.countDown();
        var execution = running.get(WAIT_SECONDS, TimeUnit.SECONDS);

        assertThat(execution.results()).singleElement().satisfies(f -> {
            assertThat(f.outcome().status()).isEqualTo(SourceOutcome.Status.ANSWERED);
            assertThat(f.record()).isEqualTo("answer-s");
        });
        assertThat(breaker.admit("s").holdsTrial()).as("a success closed the breaker").isFalse();
        assertThat(breaker.admit("s").allowed()).isTrue();
    }

    @Test
    @DisplayName("a result that finished on time is still ANSWERED when the deadline is found before the waiter hears of it")
    void anOnTimeResultFoundAtExpiryIsStillAccepted() throws Exception {
        var clock = new SourceFanOutDeadlineTest.MutableClock();
        var breaker = new SourceCircuitBreaker(1, Duration.ofSeconds(5), clock);
        var fanOut = new SourceFanOut(breaker, clock, SourceFanOutOptions.defaults());
        breaker.record(failed("s"));
        clock.advance(Duration.ofSeconds(6));
        var source = new SourceFanOutDeadlineTest.Blocking("s");
        List<DataSourceAdapter<?>> adapters = List.of(source);
        var limits = TestLimits.defaults().perSourceTimeout(Duration.ofSeconds(10)).maxConcurrency(1).build();
        var finishedAndHeld = new CountDownLatch(1);
        var letGo = new CountDownLatch(1);
        // The call has recorded its result and is held before it tells the waiter.
        fanOut.callFinishedObserver(name -> {
            finishedAndHeld.countDown();
            await(letGo);
        });
        var running = TestAsync.supply(() -> fanOut.execute(fanOut.plan(adapters, ask(adapters), limits), limits));
        assertThat(source.entered.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();

        source.release.countDown();
        await(finishedAndHeld);
        // Finished at 6 s; looked at 17 s: past the 16 s deadline, and past its own 16 s limit too.
        clock.advance(Duration.ofSeconds(11));
        var execution = running.get(WAIT_SECONDS, TimeUnit.SECONDS);
        letGo.countDown();

        assertThat(execution.results()).singleElement().satisfies(f -> {
            assertThat(f.outcome().status()).isEqualTo(SourceOutcome.Status.ANSWERED);
            assertThat(f.record()).isEqualTo("answer-s");
        });
        assertThat(breaker.admit("s").holdsTrial()).as("a success closed the breaker").isFalse();
    }

    /** Holds a call's thread between getting its permit and starting, until the test lets it go. */
    private static final class WorkerPause {
        final CountDownLatch reached = new CountDownLatch(1);
        final CountDownLatch resume = new CountDownLatch(1);

        void install(SourceFanOut fanOut) {
            fanOut.callStartObserver(source -> {
                reached.countDown();
                await(resume);
            });
        }
    }

    @Test
    @DisplayName("a call that gets its permit and is held until after the deadline is never started: its read and trial go back once")
    void aCallThatStartsAfterTheDeadlineIsCancelledByItself() throws Exception {
        var clock = new SourceFanOutDeadlineTest.MutableClock();
        var breaker = new SourceCircuitBreaker(1, Duration.ofSeconds(5), clock);
        var fanOut = new SourceFanOut(breaker, clock, SourceFanOutOptions.defaults());
        breaker.record(failed("s"));
        clock.advance(Duration.ofSeconds(6));
        var source = new SourceFanOutDeadlineTest.Blocking("s");
        List<DataSourceAdapter<?>> adapters = List.of(source);
        var limits = TestLimits.defaults().maxConcurrency(1).build();
        var worker = new WorkerPause();
        worker.install(fanOut);
        var waiter = new WaiterPause();
        waiter.install(fanOut);
        var ended = endOf(fanOut, "s");
        var running = TestAsync.supply(() -> fanOut.execute(fanOut.plan(adapters, ask(adapters), limits), limits));
        assertThat(worker.reached.await(WAIT_SECONDS, TimeUnit.SECONDS)).as("the call holds a permit").isTrue();

        // The waiter is held before it can see the deadline, so the call's own check is what decides.
        waiter.pauseNextPass();
        clock.advance(Duration.ofSeconds(10));
        worker.resume.countDown();
        await(ended);
        waiter.resume.countDown();
        var execution = running.get(WAIT_SECONDS, TimeUnit.SECONDS);

        assertThat(source.calls.get()).as("the adapter was never called").isZero();
        assertThat(execution.notStarted()).as("the read goes back, once").isEqualTo(1);
        assertThat(execution.results()).singleElement().satisfies(f -> {
            assertThat(f.outcome().status()).isEqualTo(SourceOutcome.Status.TIMED_OUT);
            assertThat(f.outcome().detail()).isEqualTo("not started before the request deadline");
        });
        assertThat(breaker.admit("s").holdsTrial()).as("the trial was handed back").isTrue();
    }

    @Test
    @DisplayName("when the waiter's expiry gets there first, the held call is cancelled once and still never calls the source")
    void theWaitersExpiryAndTheCallsOwnCheckAgreeOnOneCancellation() throws Exception {
        var clock = new SourceFanOutDeadlineTest.MutableClock();
        var breaker = new SourceCircuitBreaker(1, Duration.ofSeconds(5), clock);
        var fanOut = new SourceFanOut(breaker, clock, SourceFanOutOptions.defaults());
        breaker.record(failed("s"));
        clock.advance(Duration.ofSeconds(6));
        var source = new SourceFanOutDeadlineTest.Blocking("s");
        List<DataSourceAdapter<?>> adapters = List.of(source);
        var limits = TestLimits.defaults().maxConcurrency(1).build();
        var worker = new WorkerPause();
        worker.install(fanOut);
        var ended = endOf(fanOut, "s");
        var running = TestAsync.supply(() -> fanOut.execute(fanOut.plan(adapters, ask(adapters), limits), limits));
        assertThat(worker.reached.await(WAIT_SECONDS, TimeUnit.SECONDS)).as("the call holds a permit").isTrue();

        clock.advance(Duration.ofSeconds(10));
        var execution = running.get(WAIT_SECONDS, TimeUnit.SECONDS);
        worker.resume.countDown();
        await(ended);

        assertThat(source.calls.get()).as("the adapter was never called").isZero();
        assertThat(execution.notStarted()).isEqualTo(1);
        assertThat(execution.results()).singleElement()
                .satisfies(f -> assertThat(f.outcome().status()).isEqualTo(SourceOutcome.Status.TIMED_OUT));
        assertThat(breaker.admit("s").holdsTrial()).as("the trial was handed back").isTrue();
        assertThat(fanOut.leftovers("s")).isZero();
    }
}

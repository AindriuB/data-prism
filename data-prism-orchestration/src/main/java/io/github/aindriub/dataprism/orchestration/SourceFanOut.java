package io.github.aindriub.dataprism.orchestration;

import io.github.aindriub.dataprism.core.spi.DataRequest;
import io.github.aindriub.dataprism.core.spi.DataSourceAdapter;
import io.github.aindriub.dataprism.core.metrics.Metric;
import io.github.aindriub.dataprism.core.metrics.PrivacyMetrics;
import io.github.aindriub.dataprism.core.limits.RequestLimits;
import io.github.aindriub.dataprism.core.correlation.CorrelationMdc;
import io.github.aindriub.dataprism.core.refusal.PrivacyRefusedException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Calls the sources for one request, in parallel and under limits.
 *
 * <p>Virtual threads, because the work is blocking HTTP and nothing else: a
 * platform thread per source would cap the useful fan-out at whatever the pool
 * was sized to, for no reason other than the threading model.
 *
 * <p>Four things bound it, and they bound different failures. The bulkhead caps
 * concurrent calls, so a request over many sources cannot be the reason a
 * source system falls over. The per-source timeout caps how long any one call
 * may delay the answer, and is measured from when that call starts, once it
 * holds a bulkhead permit. The overall deadline caps the whole request,
 * including the wait for a permit: a source not started by then is never called.
 * The source cap refuses the request outright rather than trying and truncating.
 *
 * <p>A source that fails or times out is recorded as absent, not propagated. A
 * response built from four systems out of five is a real answer with a stated
 * gap; failing the whole request because one system is slow would make the
 * platform less available than the systems behind it.
 *
 * <p>The work is in two steps so that a caller can charge a budget between
 * them: {@link #plan} decides which sources will be called (and refuses a
 * selection that is too large), {@link #execute} calls them. {@link #fetchAll}
 * is both, for a caller that charges nothing.
 *
 * <p>Waiting is by short real-time slices against the injected clock, so a
 * test that moves the clock is noticed within a slice, and a production clock
 * costs nothing noticeable.
 */
public final class SourceFanOut {

    /** How often the waiting thread looks at the clock when nothing has finished. */
    private static final long TICK_MILLIS = 5;
    /** Real time allowed for interrupted calls to unwind before the request returns anyway. */
    private static final Duration CANCEL_GRACE = Duration.ofMillis(500);

    private static final int NEW = 0;
    private static final int STARTED = 1;
    private static final int CANCELLED = 2;

    private final SourceCircuitBreaker breaker;
    private final Clock clock;
    private final PrivacyMetrics metrics;
    private final CorrelationMdc mdc;
    private volatile Consumer<Semaphore> bulkheadObserver = bulkhead -> { };

    /** @param options the metrics and MDC; {@link SourceFanOutOptions#defaults()} for neither */
    public SourceFanOut(SourceCircuitBreaker breaker, Clock clock, SourceFanOutOptions options) {
        Objects.requireNonNull(options, "options");
        this.mdc = options.mdc();
        this.breaker = Objects.requireNonNull(breaker, "breaker");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.metrics = options.metrics();
    }

    /** Test hook: sees the bulkhead of every request, so a test can assert no permit leaked. */
    void bulkheadObserver(Consumer<Semaphore> observer) {
        this.bulkheadObserver = Objects.requireNonNull(observer, "observer");
    }

    /**
     * The sources one request will call, decided but not yet called.
     *
     * @param callable    the adapters to call, in the order given
     * @param circuitOpen the sources not called because their breaker is open
     * @param requests    the request to put to each source, keyed by source name
     */
    public record Plan(List<DataSourceAdapter<?>> callable, List<Fetched> circuitOpen,
                       Map<String, DataRequest> requests) {
        public Plan {
            callable = List.copyOf(callable);
            circuitOpen = List.copyOf(circuitOpen);
            requests = Map.copyOf(requests);
        }
    }

    /**
     * @param results    one entry per source in the plan, circuit-open sources first
     * @param notStarted how many sources were never called because the request
     *                   deadline passed (or the caller gave up) before they held a permit.
     *                   Their reads were reserved and never used
     */
    public record Execution(List<Fetched> results, int notStarted) {
        public Execution {
            results = List.copyOf(results);
        }
    }

    /**
     * Decides which sources to call. A source absent from {@code requests} is not
     * called at all, one whose breaker is open is reported and not called, and more
     * callable sources than {@code maxSources} refuses the whole request with
     * {@code SOURCE_SELECTION_TOO_LARGE}: an answer silently built from the first
     * eight of twenty sources is worse than a refusal, because nothing downstream
     * can tell it happened.
     */
    public Plan plan(List<DataSourceAdapter<?>> adapters, Map<String, DataRequest> requests, RequestLimits limits) {
        List<DataSourceAdapter<?>> callable = new ArrayList<>();
        List<Fetched> circuitOpen = new ArrayList<>();
        for (DataSourceAdapter<?> adapter : adapters) {
            String name = adapter.sourceName();
            if (!requests.containsKey(name)) {
                continue;
            }
            if (!breaker.allows(name)) {
                circuitOpen.add(new Fetched(new SourceOutcome(name,
                        SourceOutcome.Status.CIRCUIT_OPEN, Duration.ZERO,
                        "breaker open after repeated failures"), null));
                continue;
            }
            callable.add(adapter);
        }
        Plan plan = new Plan(callable, circuitOpen, requests);
        if (callable.size() > limits.maxSources()) {
            abandon(plan);
            throw new PrivacyRefusedException("SOURCE_SELECTION_TOO_LARGE", "$",
                    callable.size() + " sources selected, at most " + limits.maxSources() + " allowed");
        }
        return plan;
    }

    /**
     * Gives up a plan that will not be executed (its reads were refused). A source
     * that was never called is not a failing source, so nothing is recorded for it
     * unless the breaker let it through as a half-open trial: that trial is held
     * until an outcome is recorded, and nothing else would ever end it.
     */
    public void abandon(Plan plan) {
        for (DataSourceAdapter<?> adapter : plan.callable()) {
            releaseTrial(adapter.sourceName());
        }
    }

    /**
     * A source that was let through and then not called. {@link SourceCircuitBreaker#allows}
     * is side-effect free for a closed breaker and takes the single half-open
     * trial otherwise, so asking again tells the two apart: still allowed means
     * closed, nothing to do; refused means this request holds the trial, which is
     * ended by recording one failure. The source then waits another cooldown for
     * its next trial, the conservative reading of a trial that never ran. If the
     * breaker opened concurrently the extra failure is recorded on an open
     * breaker, which changes nothing that matters.
     */
    private void releaseTrial(String sourceName) {
        if (!breaker.allows(sourceName)) {
            breaker.record(new SourceOutcome(sourceName, SourceOutcome.Status.TIMED_OUT,
                    Duration.ZERO, "not called"));
        }
    }

    /**
     * @param requests the request to put to each source, keyed by source name.
     *                 A source absent from the map is not called at all
     * @return one entry per source, in the order the adapters were given, each
     *         carrying its outcome and its record where it answered
     * @throws PrivacyRefusedException {@code SOURCE_SELECTION_TOO_LARGE} when more
     *         sources are to be called than {@code limits.maxSources()}
     */
    public List<Fetched> fetchAll(List<DataSourceAdapter<?>> adapters,
                                  Map<String, DataRequest> requests,
                                  RequestLimits limits) {
        return execute(plan(adapters, requests, limits), limits).results();
    }

    /** One source's call, shared between its own thread and the thread waiting on it. */
    private static final class Call {
        final DataSourceAdapter<?> adapter;
        final DataRequest request;
        /** NEW until a permit is held; then STARTED, or CANCELLED by the waiter first. Decided once. */
        final AtomicInteger state = new AtomicInteger(NEW);
        final CountDownLatch ended = new CountDownLatch(1);
        volatile Instant startedAt;
        volatile Instant finishedAt;
        volatile boolean succeeded;
        volatile Object record;
        volatile Throwable failure;
        volatile Thread thread;

        Call(DataSourceAdapter<?> adapter, DataRequest request) {
            this.adapter = adapter;
            this.request = request;
        }
    }

    /** Calls every source in the plan, and returns when each has an outcome or the deadline has passed. */
    public Execution execute(Plan plan, RequestLimits limits) {
        Instant started = Instant.now(clock);
        Instant deadline = started.plus(limits.requestTimeout());
        Semaphore bulkhead = new Semaphore(limits.maxConcurrency());
        bulkheadObserver.accept(bulkhead);
        LinkedBlockingQueue<Call> finished = new LinkedBlockingQueue<>();
        List<Call> calls = new ArrayList<>();
        for (DataSourceAdapter<?> adapter : plan.callable()) {
            Call call = new Call(adapter, plan.requests().get(adapter.sourceName()));
            call.thread = Thread.ofVirtual().unstarted(() -> run(call, bulkhead, deadline, finished));
            calls.add(call);
        }
        calls.forEach(call -> call.thread.start());

        Map<Call, Fetched> resolved = new IdentityHashMap<>();
        Set<Call> neverCalled = Collections.newSetFromMap(new IdentityHashMap<>());
        boolean interrupted = false;
        try {
            while (resolved.size() < calls.size()) {
                Instant now = Instant.now(clock);
                if (!now.isBefore(deadline)) {
                    expire(calls, resolved, neverCalled, started, "no answer before the request deadline of "
                            + limits.requestTimeout().toMillis() + "ms");
                    break;
                }
                for (Call call : calls) {
                    if (!resolved.containsKey(call) && call.state.get() == STARTED && call.startedAt != null
                            && !now.isBefore(call.startedAt.plus(limits.perSourceTimeout()))) {
                        resolveRunning(call, resolved, SourceOutcome.Status.TIMED_OUT,
                                "no answer within " + limits.perSourceTimeout().toMillis() + "ms");
                    }
                }
                Call done = finished.poll(TICK_MILLIS, TimeUnit.MILLISECONDS);
                while (done != null) {
                    if (!resolved.containsKey(done) && done.state.get() == STARTED) {
                        resolved.put(done, completed(done));
                    }
                    done = finished.poll();
                }
            }
        } catch (InterruptedException e) {
            interrupted = true;
            for (Call call : calls) {
                if (resolved.containsKey(call)) {
                    continue;
                }
                if (call.state.compareAndSet(NEW, CANCELLED)) {
                    neverCalled.add(call);
                    resolved.put(call, new Fetched(new SourceOutcome(call.adapter.sourceName(),
                            SourceOutcome.Status.FAILED, since(started), "interrupted"), null));
                } else {
                    resolveRunning(call, resolved, SourceOutcome.Status.FAILED, "interrupted");
                }
            }
        }
        awaitUnwind(calls);
        if (interrupted) {
            Thread.currentThread().interrupt();
        }

        List<Fetched> results = new ArrayList<>(plan.circuitOpen());
        for (Call call : calls) {
            results.add(resolved.get(call));
        }
        Set<String> notCalled = new java.util.HashSet<>();
        neverCalled.forEach(call -> notCalled.add(call.adapter.sourceName()));
        results.forEach(fetched -> {
            if (notCalled.contains(fetched.outcome().sourceName())) {
                // Never called, so nothing was learned about the source.
                releaseTrial(fetched.outcome().sourceName());
            } else {
                breaker.record(fetched.outcome());
            }
            metrics.record(Metric.SOURCE_LATENCY, fetched.outcome().sourceName(), fetched.outcome().took());
            if (fetched.outcome().failure()) {
                metrics.increment(Metric.SOURCE_ERRORS, fetched.outcome().sourceName());
            }
        });
        return new Execution(results, neverCalled.size());
    }

    /** One source's thread: wait for a permit within the deadline, then call, then give the permit back. */
    private void run(Call call, Semaphore bulkhead, Instant deadline, LinkedBlockingQueue<Call> finished) {
        try {
            // Opened from this task's own request, never copied from the
            // submitting thread: virtual threads do not inherit MDC.
            try (CorrelationMdc.Scope ignored = mdc.open(call.request.context().externalCorrelationId())) {
                if (!acquire(call, bulkhead, deadline)) {
                    return;
                }
                try {
                    call.startedAt = Instant.now(clock);
                    if (!call.state.compareAndSet(NEW, STARTED)) {
                        return;
                    }
                    try {
                        call.record = call.adapter.fetch(call.request);
                        call.succeeded = true;
                    } catch (Throwable t) {
                        call.failure = t;
                    }
                    call.finishedAt = Instant.now(clock);
                } finally {
                    bulkhead.release();
                }
            }
        } finally {
            call.ended.countDown();
            finished.add(call);
        }
    }

    /** Waits for a permit, but never past the deadline or the call being cancelled. */
    private boolean acquire(Call call, Semaphore bulkhead, Instant deadline) {
        try {
            while (call.state.get() == NEW && Instant.now(clock).isBefore(deadline)) {
                if (bulkhead.tryAcquire(TICK_MILLIS, TimeUnit.MILLISECONDS)) {
                    if (call.state.get() == NEW && Instant.now(clock).isBefore(deadline)) {
                        return true;
                    }
                    bulkhead.release();
                    return false;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return false;
    }

    /** Past the overall deadline: nothing not yet called is called, and everything running is interrupted. */
    private void expire(List<Call> calls, Map<Call, Fetched> resolved, Set<Call> neverCalled, Instant started,
                        String detail) {
        // Every call not yet started is cancelled before any running one is
        // interrupted, so an interrupted call's permit cannot start a queued one.
        for (Call call : calls) {
            if (!resolved.containsKey(call) && call.state.compareAndSet(NEW, CANCELLED)) {
                neverCalled.add(call);
                resolved.put(call, new Fetched(new SourceOutcome(call.adapter.sourceName(),
                        SourceOutcome.Status.TIMED_OUT, since(started),
                        "not started before the request deadline"), null));
            }
        }
        for (Call call : calls) {
            if (!resolved.containsKey(call)) {
                resolveRunning(call, resolved, SourceOutcome.Status.TIMED_OUT, detail);
            }
        }
    }

    /** A started call that has not answered in time: interrupt it, unless it has just finished. */
    private void resolveRunning(Call call, Map<Call, Fetched> resolved, SourceOutcome.Status status, String detail) {
        if (call.ended.getCount() == 0 && call.finishedAt != null) {
            resolved.put(call, completed(call));
            return;
        }
        Instant from = call.startedAt == null ? Instant.now(clock) : call.startedAt;
        // Interrupt the virtual thread so a slow source stops occupying its
        // bulkhead permit; without this the next request inherits the queue.
        call.thread.interrupt();
        resolved.put(call, new Fetched(new SourceOutcome(call.adapter.sourceName(), status,
                Duration.between(from, Instant.now(clock)), detail), null));
    }

    private Fetched completed(Call call) {
        String name = call.adapter.sourceName();
        Duration took = Duration.between(call.startedAt, call.finishedAt);
        if (call.succeeded) {
            return call.record == null
                    ? new Fetched(SourceOutcome.noData(name, took), null)
                    : new Fetched(SourceOutcome.answered(name, took), call.record);
        }
        // The cause's message can carry a fragment of the record that caused
        // it, so only the exception type is recorded.
        return new Fetched(new SourceOutcome(name, SourceOutcome.Status.FAILED, took,
                call.failure.getClass().getSimpleName()), null);
    }

    /** Gives interrupted calls a bounded real-time interval to let go of their permits. */
    private void awaitUnwind(List<Call> calls) {
        long limit = System.nanoTime() + CANCEL_GRACE.toNanos();
        for (Call call : calls) {
            long left = limit - System.nanoTime();
            if (left <= 0) {
                return;
            }
            try {
                call.ended.await(left, TimeUnit.NANOSECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private Duration since(Instant started) {
        return Duration.between(started, Instant.now(clock));
    }

    /** One source's outcome, and its record where it answered. */
    public record Fetched(SourceOutcome outcome, Object record) {
    }
}

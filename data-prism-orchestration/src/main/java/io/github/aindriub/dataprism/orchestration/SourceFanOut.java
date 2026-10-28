package io.github.aindriub.dataprism.orchestration;

import io.github.aindriub.dataprism.core.spi.DataRequest;
import io.github.aindriub.dataprism.core.spi.DataSourceAdapter;
import io.github.aindriub.dataprism.core.metrics.Metric;
import io.github.aindriub.dataprism.core.metrics.PrivacyMetrics;
import io.github.aindriub.dataprism.core.limits.RequestLimits;
import io.github.aindriub.dataprism.core.correlation.CorrelationMdc;
import io.github.aindriub.dataprism.core.refusal.PrivacyRefusedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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
 * <p>What the breaker and the metrics are told follows what was learned about the
 * source. An answer, an error, and a call that outran its own per-source timeout
 * are outcomes of the source. A call cut short by the overall deadline before its
 * own timeout, a call interrupted because the caller gave up, and a source never
 * called say nothing about it: the breaker records nothing (and gets back any
 * half-open trial it was given), and no error is counted.
 *
 * <p>The work is in two steps so that a caller can charge a budget between
 * them: {@link #plan} decides which sources will be called (and refuses a
 * selection that is too large), {@link #execute} calls them. {@link #fetchAll}
 * is both, for a caller that charges nothing.
 *
 * <p>The thread that waits looks at the injected clock in short real-time slices,
 * so a test that moves the clock is noticed within a slice, and a production clock
 * costs nothing noticeable. A call waiting for a permit makes one bounded wait.
 */
public final class SourceFanOut {

    private static final Logger LOG = LoggerFactory.getLogger(SourceFanOut.class);

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
    private volatile Consumer<Instant> tickObserver = now -> { };
    /**
     * Running calls per configured source name, process-wide for this fan-out (one per deployment); an entry
     * is removed when it reaches zero. Task 272's shared pool may replace it.
     */
    private final ConcurrentHashMap<String, Load> load = new ConcurrentHashMap<>();

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
     * Test hook: told the clock reading of each pass of the waiting loop, after that pass has
     * applied the per-source timeouts, so a test knows the waiter has seen a moved clock
     * and has not timed anything out, without sleeping.
     */
    void tickObserver(Consumer<Instant> observer) {
        this.tickObserver = Objects.requireNonNull(observer, "observer");
    }

    /**
     * What is running for one source: all its calls, including leftovers that ignored their interrupt and
     * outlived their request, and how many of those hold a half-open trial.
     */
    private record Load(int running, int trials) {
    }

    /** Test hook: calls running for a source, leftovers included. */
    int running(String sourceName) {
        Load load = this.load.get(sourceName);
        return load == null ? 0 : load.running();
    }

    /** Test hook: how many sources have anything running; an entry is removed when its count reaches zero. */
    int trackedSources() {
        return load.size();
    }

    private void began(String sourceName, boolean trial) {
        load.merge(sourceName, new Load(1, trial ? 1 : 0),
                (a, b) -> new Load(a.running() + b.running(), a.trials() + b.trials()));
    }

    private void ended(String sourceName, boolean trial) {
        load.computeIfPresent(sourceName, (k, l) -> {
            Load next = new Load(l.running() - 1, l.trials() - (trial ? 1 : 0));
            return next.running() <= 0 ? null : next;
        });
    }

    /**
     * Whether the source cannot take a new call: it already has {@code maxConcurrency} calls running, or the
     * trial call of its breaker is still running. Both include calls that outlived their request.
     */
    private boolean busy(String sourceName, RequestLimits limits) {
        Load load = this.load.get(sourceName);
        return load != null && (load.running() >= limits.maxConcurrency() || load.trials() > 0);
    }

    /**
     * The sources one request will call, decided but not yet called.
     *
     * @param callable    the adapters to call, in the order given
     * @param notCalled   the sources reported without being called: their breaker is open, or they are still
     *                    busy with earlier calls
     * @param requests    the request to put to each source, keyed by source name
     * @param trials      the half-open trials this plan took, by source name. Each is ended by a
     *                    recorded outcome, or handed back if the source is not called
     */
    public record Plan(List<DataSourceAdapter<?>> callable, List<Fetched> notCalled,
                       Map<String, DataRequest> requests, Map<String, SourceCircuitBreaker.Admission> trials) {
        public Plan {
            callable = List.copyOf(callable);
            notCalled = List.copyOf(notCalled);
            requests = Map.copyOf(requests);
            trials = Map.copyOf(trials);
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
     * Thrown by {@link #execute} when it failed for a reason of its own. By then every call is
     * cancelled and every trial handed back; {@link #notStarted} says how many sources were never
     * called, so their reads can be given back, and the cause is what the caller should see.
     */
    public static final class ExecutionFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final int notStarted;

        ExecutionFailure(RuntimeException cause, int notStarted) {
            super(cause.getClass().getSimpleName(), cause);
            this.notStarted = notStarted;
        }

        public int notStarted() {
            return notStarted;
        }

        /** The failure that ended the execution. */
        public RuntimeException failure() {
            return (RuntimeException) getCause();
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
        List<Fetched> notCalled = new ArrayList<>();
        Map<String, SourceCircuitBreaker.Admission> trials = new HashMap<>();
        for (DataSourceAdapter<?> adapter : adapters) {
            String name = adapter.sourceName();
            if (!requests.containsKey(name)) {
                continue;
            }
            if (busy(name, limits)) {
                // Before the breaker, so no trial is admitted for a call that will not be made; and before the
                // budget, which only ever sees the sources that will be called.
                notCalled.add(new Fetched(new SourceOutcome(name, SourceOutcome.Status.TIMED_OUT, Duration.ZERO,
                        "source still busy with earlier calls"), null));
                continue;
            }
            SourceCircuitBreaker.Admission admission = breaker.admit(name);
            if (admission.holdsTrial()) {
                trials.put(name, admission);
            }
            if (!admission.allowed()) {
                notCalled.add(new Fetched(new SourceOutcome(name,
                        SourceOutcome.Status.CIRCUIT_OPEN, Duration.ZERO,
                        "breaker open after repeated failures"), null));
                continue;
            }
            callable.add(adapter);
        }
        Plan plan = new Plan(callable, notCalled, requests, trials);
        if (callable.size() > limits.maxSources()) {
            abandon(plan);
            throw new PrivacyRefusedException("SOURCE_SELECTION_TOO_LARGE", "$",
                    callable.size() + " sources selected, at most " + limits.maxSources() + " allowed");
        }
        return plan;
    }

    /**
     * Gives up a plan that will not be executed (its reads were refused). A source that was
     * never called says nothing about its health, so nothing is recorded for it; a half-open
     * trial this plan took is handed back, because only a recorded outcome or a release ends it.
     */
    public void abandon(Plan plan) {
        plan.trials().forEach(breaker::releaseTrial);
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
        /**
         * Set, before the state moves to CANCELLED, when the call's own thread failed before it could call the
         * source. A CANCELLED call the waiter has not itself resolved can only have come from here.
         */
        volatile Throwable startError;
        /** Whether the call took the breaker's half-open trial. */
        final boolean holdsTrial;
        /** Whether the call is counted as running; its thread uncounts it when it actually ends. */
        volatile boolean counted;
        volatile Thread thread;

        Call(DataSourceAdapter<?> adapter, DataRequest request, boolean holdsTrial) {
            this.adapter = adapter;
            this.request = request;
            this.holdsTrial = holdsTrial;
        }
    }

    /**
     * What became of one call, and what each consumer is to be told.
     *
     * @param breaker      whether the outcome is recorded in the breaker; if not, a trial is handed back
     * @param errorMetric  whether a failure is counted in {@code SOURCE_ERRORS}
     * @param latency      whether the call ran, so that its latency means something
     * @param neverCalled  whether the source was never called, so that its read goes back
     */
    private record Resolution(Fetched fetched, boolean breaker, boolean errorMetric, boolean latency,
                              boolean neverCalled) {
    }

    private enum Cut { PER_SOURCE, DEADLINE, INTERRUPTED }

    /**
     * Calls every source in the plan, and returns when each has an outcome or the deadline has passed.
     *
     * @throws ExecutionFailure if the execution itself failed, after cancelling everything it started
     */
    public Execution execute(Plan plan, RequestLimits limits) {
        List<Call> calls = new ArrayList<>();
        AtomicBoolean launched = new AtomicBoolean();
        try {
            return run(plan, limits, calls, launched);
        } catch (RuntimeException failure) {
            throw new ExecutionFailure(failure, abort(plan, calls, launched.get()));
        } catch (Error failure) {
            abort(plan, calls, launched.get());
            throw failure;
        }
    }

    private Execution run(Plan plan, RequestLimits limits, List<Call> calls, AtomicBoolean launched) {
        Instant started = Instant.now(clock);
        Instant deadline = started.plus(limits.requestTimeout());
        // Fair, so permits go in the order the calls asked for them.
        Semaphore bulkhead = new Semaphore(limits.maxConcurrency(), true);
        bulkheadObserver.accept(bulkhead);
        LinkedBlockingQueue<Call> finished = new LinkedBlockingQueue<>();
        for (DataSourceAdapter<?> adapter : plan.callable()) {
            Call call = new Call(adapter, plan.requests().get(adapter.sourceName()),
                    plan.trials().containsKey(adapter.sourceName()));
            call.thread = Thread.ofVirtual().unstarted(() -> callOne(call, bulkhead, deadline, finished));
            calls.add(call);
        }
        launched.set(true);
        calls.forEach(call -> call.thread.start());

        Map<Call, Resolution> resolved = new IdentityHashMap<>();
        boolean interrupted = false;
        try {
            while (resolved.size() < calls.size()) {
                Instant now = Instant.now(clock);
                if (!now.isBefore(deadline)) {
                    expire(calls, resolved, started, now, limits);
                    break;
                }
                for (Call call : calls) {
                    if (!resolved.containsKey(call) && call.state.get() == STARTED && call.startedAt != null
                            && !now.isBefore(call.startedAt.plus(limits.perSourceTimeout()))) {
                        resolveRunning(call, resolved, Cut.PER_SOURCE, now, limits);
                    }
                }
                tickObserver.accept(now);
                Call done = finished.poll(TICK_MILLIS, TimeUnit.MILLISECONDS);
                while (done != null) {
                    if (!resolved.containsKey(done)) {
                        if (done.state.get() == STARTED && done.finishedAt != null) {
                            resolved.put(done, completed(done));
                        } else if (done.state.get() == CANCELLED && done.startError != null) {
                            resolved.put(done, failedBeforeStart(done, started));
                        }
                    }
                    done = finished.poll();
                }
            }
        } catch (InterruptedException e) {
            interrupted = true;
            Instant now = Instant.now(clock);
            for (Call call : calls) {
                if (resolved.containsKey(call)) {
                    continue;
                }
                if (call.state.compareAndSet(NEW, CANCELLED)) {
                    call.thread.interrupt();
                    resolved.put(call, neverCalled(call, SourceOutcome.Status.FAILED, "interrupted", started));
                } else if (call.state.get() == CANCELLED && call.startError != null) {
                    resolved.put(call, failedBeforeStart(call, started));
                } else {
                    resolveRunning(call, resolved, Cut.INTERRUPTED, now, limits);
                }
            }
        }
        awaitUnwind(calls);
        if (interrupted) {
            Thread.currentThread().interrupt();
        }

        List<Fetched> results = new ArrayList<>(plan.notCalled());
        int notStarted = 0;
        for (Call call : calls) {
            Resolution resolution = resolved.get(call);
            results.add(resolution.fetched());
            if (resolution.neverCalled()) {
                notStarted++;
            }
        }
        // The breaker first, and on its own: a metrics implementation that throws must not leave
        // the remaining sources' outcomes (and any half-open trial they hold) unrecorded.
        for (Call call : calls) {
            Resolution resolution = resolved.get(call);
            String name = call.adapter.sourceName();
            if (resolution.breaker()) {
                breaker.record(resolution.fetched().outcome());
            } else {
                SourceCircuitBreaker.Admission trial = plan.trials().get(name);
                if (trial != null) {
                    breaker.releaseTrial(name, trial);
                }
            }
        }
        for (Call call : calls) {
            Resolution resolution = resolved.get(call);
            recordMetrics(resolution.fetched().outcome(), resolution.latency(), resolution.errorMetric());
        }
        return new Execution(results, notStarted);
    }

    private void recordMetrics(SourceOutcome outcome, boolean latency, boolean error) {
        try {
            if (latency) {
                metrics.record(Metric.SOURCE_LATENCY, outcome.sourceName(), outcome.took());
            }
            if (error && outcome.failure()) {
                metrics.increment(Metric.SOURCE_ERRORS, outcome.sourceName());
            }
        } catch (RuntimeException e) {
            // Metrics are not worth a request; the class only, as a message can carry a value.
            LOG.warn("source metrics failed: {}", e.getClass().getSimpleName());
        }
    }

    /** One source's thread: wait for a permit within the deadline, then call, then give the permit back. */
    private void callOne(Call call, Semaphore bulkhead, Instant deadline, LinkedBlockingQueue<Call> finished) {
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
                    began(call.adapter.sourceName(), call.holdsTrial);
                    call.counted = true;
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
        } catch (Throwable t) {
            // Failed before the source was called (opening the MDC, reading the clock): the call is
            // cancelled with its error, so the waiter need not wait for the deadline to find out.
            call.startError = t;
            call.state.compareAndSet(NEW, CANCELLED);
        } finally {
            if (call.counted) {
                // The thread has really ended: only now is the source free of this call.
                ended(call.adapter.sourceName(), call.holdsTrial);
            }
            call.ended.countDown();
            finished.add(call);
        }
    }

    /**
     * One bounded wait for a permit: no longer than the time left before the deadline, and cut short
     * by the waiter interrupting a call it has cancelled.
     */
    private boolean acquire(Call call, Semaphore bulkhead, Instant deadline) {
        try {
            long left = Duration.between(Instant.now(clock), deadline).toMillis();
            if (left <= 0 || call.state.get() != NEW) {
                return false;
            }
            if (!bulkhead.tryAcquire(left, TimeUnit.MILLISECONDS)) {
                return false;
            }
            if (call.state.get() == NEW && Instant.now(clock).isBefore(deadline)) {
                return true;
            }
            bulkhead.release();
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** Past the overall deadline: nothing not yet called is called, and everything running is interrupted. */
    private void expire(List<Call> calls, Map<Call, Resolution> resolved, Instant started, Instant now,
                        RequestLimits limits) {
        // Every call not yet started is cancelled before any running one is
        // interrupted, so an interrupted call's permit cannot start a queued one.
        for (Call call : calls) {
            if (!resolved.containsKey(call) && call.state.compareAndSet(NEW, CANCELLED)) {
                call.thread.interrupt();
                resolved.put(call, neverCalled(call, SourceOutcome.Status.TIMED_OUT,
                        "not started before the request deadline", started));
            }
        }
        for (Call call : calls) {
            if (resolved.containsKey(call)) {
                continue;
            }
            if (call.state.get() == CANCELLED && call.startError != null) {
                resolved.put(call, failedBeforeStart(call, started));
            } else {
                resolveRunning(call, resolved, Cut.DEADLINE, now, limits);
            }
        }
    }

    /** A started call that has not answered in time: interrupt it, unless it has just finished. */
    private void resolveRunning(Call call, Map<Call, Resolution> resolved, Cut cut, Instant now,
                                RequestLimits limits) {
        if (call.finishedAt != null) {
            // finishedAt is written after the result, so seeing it means the call finished, whether or not
            // its thread has yet counted down `ended`.
            resolved.put(call, completed(call));
            return;
        }
        Instant from = call.startedAt == null ? now : call.startedAt;
        Duration took = Duration.between(from, now);
        // Interrupt the virtual thread so a slow source stops occupying its bulkhead permit.
        call.thread.interrupt();
        String name = call.adapter.sourceName();
        boolean ownTimeout = cut == Cut.PER_SOURCE || (cut == Cut.DEADLINE && took.compareTo(limits.perSourceTimeout()) >= 0);
        if (ownTimeout) {
            String detail = cut == Cut.PER_SOURCE
                    ? "no answer within " + limits.perSourceTimeout().toMillis() + "ms"
                    : "no answer within " + limits.perSourceTimeout().toMillis() + "ms before the request deadline";
            resolved.put(call, new Resolution(new Fetched(new SourceOutcome(name, SourceOutcome.Status.TIMED_OUT,
                    took, detail), null), true, true, true, false));
        } else if (cut == Cut.DEADLINE) {
            // Cut by the deadline before its own timeout: slow for this request, not shown to be a failing source.
            resolved.put(call, new Resolution(new Fetched(new SourceOutcome(name, SourceOutcome.Status.TIMED_OUT,
                    took, "no answer before the request deadline"), null), false, false, true, false));
        } else {
            resolved.put(call, new Resolution(new Fetched(new SourceOutcome(name, SourceOutcome.Status.FAILED,
                    took, "interrupted"), null), false, false, true, false));
        }
    }

    private Resolution neverCalled(Call call, SourceOutcome.Status status, String detail, Instant started) {
        return new Resolution(new Fetched(new SourceOutcome(call.adapter.sourceName(), status,
                Duration.between(started, Instant.now(clock)), detail), null), false, false, false, true);
    }

    /** The call's thread failed before the source was called: its error is counted, the source is not blamed. */
    private Resolution failedBeforeStart(Call call, Instant started) {
        return new Resolution(new Fetched(new SourceOutcome(call.adapter.sourceName(), SourceOutcome.Status.FAILED,
                Duration.between(started, Instant.now(clock)), call.startError.getClass().getSimpleName()), null),
                false, true, false, true);
    }

    private Resolution completed(Call call) {
        String name = call.adapter.sourceName();
        Duration took = Duration.between(call.startedAt, call.finishedAt);
        Fetched fetched;
        if (call.succeeded) {
            fetched = call.record == null
                    ? new Fetched(SourceOutcome.noData(name, took), null)
                    : new Fetched(SourceOutcome.answered(name, took), call.record);
        } else {
            // The cause's message can carry a fragment of the record that caused
            // it, so only the exception type is recorded.
            fetched = new Fetched(new SourceOutcome(name, SourceOutcome.Status.FAILED, took,
                    call.failure.getClass().getSimpleName()), null);
        }
        return new Resolution(fetched, true, true, true, false);
    }

    /**
     * An execution that failed for its own reasons: cancel every call, give every trial back, and say
     * how many sources were never called.
     */
    private int abort(Plan plan, List<Call> calls, boolean launched) {
        int notStarted = 0;
        if (!launched) {
            notStarted = plan.callable().size();
        } else {
            for (Call call : calls) {
                if (call.state.compareAndSet(NEW, CANCELLED) || call.state.get() == CANCELLED) {
                    notStarted++;
                }
                call.thread.interrupt();
            }
            awaitUnwind(calls);
        }
        plan.trials().forEach(breaker::releaseTrial);
        return notStarted;
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

    /** One source's outcome, and its record where it answered. */
    public record Fetched(SourceOutcome outcome, Object record) {
    }
}

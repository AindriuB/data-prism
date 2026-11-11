package io.github.aindriub.dataprism.loadtest;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import java.util.function.LongConsumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Drives {@code get_entity_context} through the MCP Java SDK client over streamable HTTP, the protocol path
 * agents use. Each worker holds its own session. Every call asks for a different subject, so the per-subject
 * read budget is not what a run measures.
 *
 * <p>When a rate is set, call {@code i} is due at {@code start + i / rate} and its latency is measured from
 * that due time, not from when a worker got round to it: a stalled server therefore shows as latency, instead
 * of silently thinning the calls that would have exposed it (coordinated omission).
 */
final class LoadDriver {

    /**
     * @param elapsed   the wall time of the measured phase, in-flight drain included
     * @param serverExitCode the server's exit code if it died during the run (which was then stopped), otherwise null
     * @param measured  the window throughput is divided by: {@code elapsed}, but never more than the scenario's
     *                  duration, so the calls still in flight at the deadline do not stretch it
     */
    record Run(ResultAggregator.Summary summary, Duration elapsed, long notDispatched, Duration measured,
               long tokenReissues, long scopeRotations, Integer serverExitCode,
               List<ResultAggregator.IntervalRow> intervals) {

        /** A run with no interval rows. */
        Run(ResultAggregator.Summary summary, Duration elapsed, long notDispatched, Duration measured,
            long tokenReissues, long scopeRotations, Integer serverExitCode) {
            this(summary, elapsed, notDispatched, measured, tokenReissues, scopeRotations, serverExitCode, List.of());
        }
    }

    /** The longest the client waits to open a connection. */
    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    /** The longest the closing of every session may take, in total. */
    static final Duration CLOSE_BOUND = Duration.ofSeconds(30);

    /** How often the server child is checked while a run drives it. */
    static final Duration SERVER_POLL = Duration.ofSeconds(1);

    /** A failure that took at least this long is timed even though it was not a timeout. */
    static final Duration SLOW_FAILURE = Duration.ofSeconds(1);

    private final String baseUrl;
    private final CredentialRotation credentials;
    private final Scenario scenario;

    private final Supplier<Integer> serverExit;
    private final Supplier<ServerJvmProbe.Reading> probe;
    private volatile ServerWatch watch;

    LoadDriver(String baseUrl, CredentialRotation credentials, Scenario scenario) {
        this(baseUrl, credentials, scenario, () -> null);
    }

    /** @param serverExit the server's exit code, or null while it is alive; polled to stop a run whose server died */
    LoadDriver(String baseUrl, CredentialRotation credentials, Scenario scenario, Supplier<Integer> serverExit) {
        this(baseUrl, credentials, scenario, serverExit, () -> ServerJvmProbe.Reading.NONE);
    }

    /** @param probe reads the harness server's JVM for each interval row; a target has none to read */
    LoadDriver(String baseUrl, CredentialRotation credentials, Scenario scenario, Supplier<Integer> serverExit,
               Supplier<ServerJvmProbe.Reading> probe) {
        this.baseUrl = baseUrl;
        this.credentials = credentials;
        this.scenario = scenario;
        this.serverExit = serverExit;
        this.probe = probe;
    }

    static HttpClient.Builder boundConnect(HttpClient.Builder builder) {
        return builder.connectTimeout(CONNECT_TIMEOUT);
    }

    /** The subject call {@code index} asks for: a new one each time, or one of {@code pool} when a pool is set. */
    static String subjectFor(String prefix, long index, int pool) {
        return String.format("%s-%08d", prefix, pool > 0 ? index % pool : index);
    }

    Run run() throws Exception {
        Scenario.Load load = scenario.load();
        List<McpSyncClient> clients = new ArrayList<>();
        try (ServerWatch serverWatch = ServerWatch.start(serverExit, SERVER_POLL)) {
            watch = serverWatch;
            for (int i = 0; i < load.concurrency(); i++) {
                clients.add(newClient(load));
            }
            if (load.warmupCalls() > 0) {
                phase(clients, load.warmupCalls(), 0, 0, null, "warmup");
            }
            ResultAggregator aggregator = new ResultAggregator();
            long started = System.nanoTime();
            java.util.concurrent.ScheduledExecutorService sampler = java.util.concurrent.Executors
                    .newSingleThreadScheduledExecutor(task -> {
                        Thread thread = new Thread(task, "interval-sampler");
                        thread.setDaemon(true);
                        return thread;
                    });
            sampler.scheduleAtFixedRate(() -> closeInterval(aggregator, started), load.intervalSeconds(),
                    load.intervalSeconds(), java.util.concurrent.TimeUnit.SECONDS);
            long notDispatched;
            try {
                notDispatched = phase(clients, load.calls(), load.durationSeconds(), load.ratePerSecond(),
                        aggregator, "m");
            } finally {
                sampler.shutdownNow();
            }
            if (aggregator.intervalHasCalls()) {
                closeInterval(aggregator, started);
            }
            Duration elapsed = Duration.ofNanos(System.nanoTime() - started);
            Duration measured = load.durationSeconds() > 0
                    ? elapsed.compareTo(Duration.ofSeconds(load.durationSeconds())) > 0
                            ? Duration.ofSeconds(load.durationSeconds()) : elapsed
                    : elapsed;
            return new Run(aggregator.summary(measured), elapsed, notDispatched, measured,
                    credentials.tokenReissues(), credentials.scopeRotations(), serverWatch.exitCode(),
                    aggregator.intervals());
        } finally {
            // A session that will not close must not hold the run (or the result file) up.
            Bounded.run(() -> clients.forEach(client -> {
                try {
                    client.closeGracefully();
                } catch (RuntimeException ignored) {
                    // Closing a session the run is finished with; nothing to report.
                }
            }), CLOSE_BOUND);
        }
    }

    private void closeInterval(ResultAggregator aggregator, long startedNanos) {
        ServerJvmProbe.Reading reading;
        try {
            reading = probe.get();
        } catch (RuntimeException unreadable) {
            reading = ServerJvmProbe.Reading.NONE;
        }
        aggregator.closeInterval((System.nanoTime() - startedNanos) / 1_000_000_000L, reading);
    }

    private McpSyncClient newClient(Scenario.Load load) {
        return openClient(baseUrl, credentials::token, Duration.ofSeconds(load.callTimeoutSeconds()));
    }

    /**
     * An initialised MCP session. The token is read on every request, so one replaced during a long run is the
     * one that is sent. A session whose initialisation fails is closed before the failure is rethrown.
     */
    static McpSyncClient openClient(String baseUrl, java.util.function.Supplier<String> token, Duration timeout) {
        HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport.builder(baseUrl)
                .endpoint("/mcp")
                .customizeClient(LoadDriver::boundConnect)
                .httpRequestCustomizer((request, method, uri, body, context) ->
                        request.setHeader("Authorization", "Bearer " + token.get()))
                .build();
        McpSyncClient client = McpClient.sync(transport)
                .clientInfo(new McpSchema.Implementation("data-prism-load-test", "1.0.0"))
                .requestTimeout(timeout)
                .build();
        try {
            client.initialize();
        } catch (RuntimeException failed) {
            Bounded.run(() -> {
                try {
                    client.close();
                } catch (RuntimeException ignored) {
                    // The initialisation failure is the one to report.
                }
            }, CLOSE_BOUND);
            throw failed;
        }
        return client;
    }

    /** {@code recordTo} is null for warmup, whose calls are made and discarded. Returns the calls not dispatched. */
    private long phase(List<McpSyncClient> clients, int calls, int durationSeconds, int ratePerSecond,
                       ResultAggregator recordTo, String subjectPrefix) throws InterruptedException {
        AtomicLong next = new AtomicLong();
        AtomicLong dispatched = new AtomicLong();
        long start = System.nanoTime();
        long end = durationSeconds > 0 ? start + Duration.ofSeconds(durationSeconds).toNanos() : Long.MAX_VALUE;
        int subjectPool = scenario.load().subjectPool();
        List<Thread> workers = new ArrayList<>();
        for (McpSyncClient client : clients) {
            MixedCalls.State state = new MixedCalls.State();
            Thread worker = new Thread(() -> workerLoop(next, dispatched, calls, start, ratePerSecond,
                    end, System::nanoTime, LockSupport::parkNanos, (index, due) -> {
                // The scope is read before the call: a rotation landing mid-call can mislabel that one call.
                int scope = credentials.scopeNumber();
                Called called = mixedCall(client::callTool, state, scenario, subjectFor(subjectPrefix, index, subjectPool),
                        index, due, System::nanoTime);
                if (recordTo != null) {
                    boolean afterDeadline = System.nanoTime() >= end;
                    if (called.operation() == null) {
                        recordTo.record(called.outcome(), afterDeadline, scope);
                    } else {
                        recordTo.record(called.operation(), called.outcome(), afterDeadline, scope);
                    }
                }
            }, () -> watch != null && watch.halted()), "load-worker");
            workers.add(worker);
            worker.start();
        }
        for (Thread worker : workers) {
            worker.join();
        }
        return notDispatched(ratePerSecond, durationSeconds, calls, dispatched.get());
    }

    /** One dispatched call: its index and the time (on the clock's scale) its latency is measured from. */
    @FunctionalInterface
    interface Dispatch {
        void accept(long index, long due);
    }

    /**
     * One worker's loop. A call's {@code due} time is the latency origin, but the deadline is the real clock: a
     * worker that has fallen behind schedule stops dispatching when {@code end} passes instead of working
     * through the backlog.
     */
    static void workerLoop(AtomicLong next, AtomicLong dispatched, int calls, long start, int ratePerSecond,
                           long end, LongSupplier clock, LongConsumer park, Dispatch dispatch) {
        workerLoop(next, dispatched, calls, start, ratePerSecond, end, clock, park, dispatch, () -> false);
    }

    /** As above, and a worker stops at once, before its next call, when {@code halted} turns true. */
    static void workerLoop(AtomicLong next, AtomicLong dispatched, int calls, long start, int ratePerSecond,
                           long end, LongSupplier clock, LongConsumer park, Dispatch dispatch,
                           BooleanSupplier halted) {
        while (!halted.getAsBoolean()) {
            long index = next.getAndIncrement();
            if (calls > 0 && index >= calls) {
                return;
            }
            long due = ratePerSecond > 0 ? start + index * 1_000_000_000L / ratePerSecond : clock.getAsLong();
            if (due >= end) {
                return;
            }
            // parkNanos may return early; wait until the schedule says go. A set interrupt flag makes it
            // return at once every time, so an interrupted worker stops instead of spinning.
            long wait;
            while ((wait = due - clock.getAsLong()) > 0) {
                if (Thread.currentThread().isInterrupted() || halted.getAsBoolean()) {
                    return;
                }
                park.accept(wait);
            }
            if (clock.getAsLong() >= end) {
                return;
            }
            dispatched.incrementAndGet();
            dispatch.accept(index, due);
        }
    }

    /**
     * Paced calls that were scheduled inside the duration but never dispatched because the workers could not
     * keep up. Zero for an unpaced or calls-only run, which has no schedule to fall behind.
     */
    static long notDispatched(int ratePerSecond, int durationSeconds, int calls, long dispatched) {
        if (ratePerSecond <= 0 || durationSeconds <= 0) {
            return 0;
        }
        long scheduled = (long) ratePerSecond * durationSeconds;
        if (calls > 0) {
            scheduled = Math.min(scheduled, calls);
        }
        return Math.max(0, scheduled - dispatched);
    }

    /** A finished call: the operation it made (null for a scenario with no mix) and what it came to. */
    record Called(String operation, CallOutcome outcome) { }

    /** Sends one request; the seam around the MCP client's {@code callTool}. */
    @FunctionalInterface
    interface Sender {
        McpSchema.CallToolResult send(McpSchema.CallToolRequest request);
    }

    /** The subject a call asks for: one of a target's listed subjects (cycled), else the generated one. */
    static String subjectOf(Scenario scenario, String generatedSubject, long index) {
        Scenario.Target target = scenario.target();
        return target != null && !target.subjects().isEmpty()
                ? target.subjects().get((int) (index % target.subjects().size())) : generatedSubject;
    }

    /**
     * One call of the scenario: planned from the mix (or a plain {@code get_entity_context} with no mix), sent,
     * classified by the classifier its tool needs, and then what it returned (or, when it threw, nothing) is
     * absorbed into the worker's state.
     */
    static Called mixedCall(Sender send, MixedCalls.State state, Scenario scenario, String generatedSubject,
                            long index, long due, LongSupplier clock) {
        Duration timeout = Duration.ofSeconds(scenario.load().callTimeoutSeconds());
        Scenario.Target target = scenario.target();
        String entityType = target != null ? target.entityType() : SourceGenerator.ENTITY_TYPE;
        String subject = subjectOf(scenario, generatedSubject, index);
        if (scenario.mix() == null) {
            return new Called(null, measuredCall(id -> send.send(new McpSchema.CallToolRequest(
                    "get_entity_context", Map.of("entityType", entityType, "subjectId", id))),
                    clock, subject, due, timeout));
        }
        MixedCalls.Planned planned = MixedCalls.plan(MixedCalls.choose(scenario.mix(), index), state, entityType,
                subject, index);
        java.util.concurrent.atomic.AtomicReference<McpSchema.CallToolResult> received =
                new java.util.concurrent.atomic.AtomicReference<>();
        // The context envelope: get_entity_context, and a followed link, which answers with the same envelope.
        boolean context = planned.tool().equals("get_entity_context") || planned.tool().equals("follow")
                && planned.op() != MixedCalls.Op.INVALID_LINK;
        CallOutcome outcome = measuredCall(id -> {
            McpSchema.CallToolResult result = send.send(planned.request());
            received.set(result);
            return result;
        }, context ? ResponseClassifier::classify : ResponseClassifier::classifyOther, clock, subject, due, timeout);
        MixedCalls.absorb(planned, received.get(), state);
        return new Called(planned.op().key(), outcome);
    }

    /** The seam around {@code callTool}. */
    @FunctionalInterface
    interface ToolCall {
        McpSchema.CallToolResult invoke(String subject);
    }

    /**
     * Makes one call and times it from {@code measuredFrom} (its scheduled time). A failure is timed only if it
     * was a timeout (a timeout cause in the chain, or a wait of at least {@code callTimeout}) or slow (a wait of at
     * least {@link #SLOW_FAILURE}); a quick failure is counted but not timed. Every failure carries its cause.
     */
    static CallOutcome measuredCall(ToolCall toolCall, LongSupplier clock, String subject, long measuredFrom,
                                    Duration callTimeout) {
        return measuredCall(toolCall, ResponseClassifier::classify, clock, subject, measuredFrom, callTimeout);
    }

    /** As above, with the classifier that turns a result into an outcome. */
    static CallOutcome measuredCall(ToolCall toolCall,
                                    java.util.function.BiFunction<McpSchema.CallToolResult, Long, CallOutcome> classifier,
                                    LongSupplier clock, String subject, long measuredFrom, Duration callTimeout) {
        long sent = clock.getAsLong();
        try {
            McpSchema.CallToolResult result = toolCall.invoke(subject);
            return classifier.apply(result, micros(clock, measuredFrom));
        } catch (RuntimeException failure) {
            String kind = failure.getClass().getSimpleName();
            long waited = clock.getAsLong() - sent;
            FailureCause cause = FailureCause.of(failure);
            boolean timedOut = cause.isTimeout() || waited >= callTimeout.toNanos();
            if (timedOut && cause == FailureCause.OTHER) {
                cause = FailureCause.READ_TIMEOUT;
            }
            if (timedOut) {
                return CallOutcome.timeout(kind, cause, micros(clock, measuredFrom));
            }
            return waited >= SLOW_FAILURE.toNanos()
                    ? CallOutcome.slowFailure(kind, cause, micros(clock, measuredFrom))
                    : CallOutcome.transportFailure(kind, cause);
        }
    }

    private static long micros(LongSupplier clock, long measuredFrom) {
        long now = clock.getAsLong();
        return (now - Math.min(measuredFrom, now)) / 1_000;
    }
}

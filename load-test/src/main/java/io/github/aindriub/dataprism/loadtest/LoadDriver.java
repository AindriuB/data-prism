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
import java.util.function.LongConsumer;
import java.util.function.LongSupplier;

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
     * @param measured  the window throughput is divided by: {@code elapsed}, but never more than the scenario's
     *                  duration, so the calls still in flight at the deadline do not stretch it
     */
    record Run(ResultAggregator.Summary summary, Duration elapsed, long notDispatched, Duration measured,
               long tokenReissues, long scopeRotations) { }

    /** The longest the client waits to open a connection. */
    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    /** The longest the closing of every session may take, in total. */
    static final Duration CLOSE_BOUND = Duration.ofSeconds(30);

    /** A failure that took at least this long is timed even though it was not a timeout. */
    static final Duration SLOW_FAILURE = Duration.ofSeconds(1);

    private final String baseUrl;
    private final CredentialRotation credentials;
    private final Scenario scenario;

    LoadDriver(String baseUrl, CredentialRotation credentials, Scenario scenario) {
        this.baseUrl = baseUrl;
        this.credentials = credentials;
        this.scenario = scenario;
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
        try {
            for (int i = 0; i < load.concurrency(); i++) {
                clients.add(newClient(load));
            }
            if (load.warmupCalls() > 0) {
                phase(clients, load.warmupCalls(), 0, 0, null, "warmup");
            }
            ResultAggregator aggregator = new ResultAggregator();
            long started = System.nanoTime();
            long notDispatched = phase(clients, load.calls(), load.durationSeconds(), load.ratePerSecond(),
                    aggregator, "m");
            Duration elapsed = Duration.ofNanos(System.nanoTime() - started);
            Duration measured = load.durationSeconds() > 0
                    ? elapsed.compareTo(Duration.ofSeconds(load.durationSeconds())) > 0
                            ? Duration.ofSeconds(load.durationSeconds()) : elapsed
                    : elapsed;
            return new Run(aggregator.summary(measured), elapsed, notDispatched, measured,
                    credentials.tokenReissues(), credentials.scopeRotations());
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
            Thread worker = new Thread(() -> workerLoop(next, dispatched, calls, start, ratePerSecond,
                    end, System::nanoTime, LockSupport::parkNanos, (index, due) -> {
                // The scope is read before the call: a rotation landing mid-call can mislabel that one call.
                int scope = credentials.scopeNumber();
                CallOutcome outcome = call(client, subjectFor(subjectPrefix, index, subjectPool), due);
                if (recordTo != null) {
                    recordTo.record(outcome, System.nanoTime() >= end, scope);
                }
            }), "load-worker");
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
        while (true) {
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
                if (Thread.currentThread().isInterrupted()) {
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

    private CallOutcome call(McpSyncClient client, String subject, long measuredFrom) {
        return measuredCall(id -> client.callTool(new McpSchema.CallToolRequest("get_entity_context",
                Map.of("entityType", SourceGenerator.ENTITY_TYPE, "subjectId", id))),
                System::nanoTime, subject, measuredFrom, Duration.ofSeconds(scenario.load().callTimeoutSeconds()));
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
        long sent = clock.getAsLong();
        try {
            McpSchema.CallToolResult result = toolCall.invoke(subject);
            return ResponseClassifier.classify(result, micros(clock, measuredFrom));
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

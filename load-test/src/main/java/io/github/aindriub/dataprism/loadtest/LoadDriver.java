package io.github.aindriub.dataprism.loadtest;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;

import java.net.http.HttpRequest;
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

    record Run(ResultAggregator.Summary summary, Duration elapsed, long notDispatched) { }

    private final String baseUrl;
    private final String token;
    private final Scenario scenario;

    LoadDriver(String baseUrl, String token, Scenario scenario) {
        this.baseUrl = baseUrl;
        this.token = token;
        this.scenario = scenario;
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
            return new Run(aggregator.summary(elapsed), elapsed, notDispatched);
        } finally {
            clients.forEach(client -> {
                try {
                    client.closeGracefully();
                } catch (RuntimeException ignored) {
                    // Closing a session the run is finished with; nothing to report.
                }
            });
        }
    }

    private McpSyncClient newClient(Scenario.Load load) {
        HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport.builder(baseUrl)
                .endpoint("/mcp")
                .requestBuilder(HttpRequest.newBuilder().header("Authorization", "Bearer " + token))
                .build();
        McpSyncClient client = McpClient.sync(transport)
                .clientInfo(new McpSchema.Implementation("data-prism-load-test", "1.0.0"))
                .requestTimeout(Duration.ofSeconds(load.callTimeoutSeconds()))
                .build();
        client.initialize();
        return client;
    }

    /** {@code recordTo} is null for warmup, whose calls are made and discarded. Returns the calls not dispatched. */
    private long phase(List<McpSyncClient> clients, int calls, int durationSeconds, int ratePerSecond,
                       ResultAggregator recordTo, String subjectPrefix) throws InterruptedException {
        AtomicLong next = new AtomicLong();
        AtomicLong dispatched = new AtomicLong();
        long start = System.nanoTime();
        long end = durationSeconds > 0 ? start + Duration.ofSeconds(durationSeconds).toNanos() : Long.MAX_VALUE;
        List<Thread> workers = new ArrayList<>();
        for (McpSyncClient client : clients) {
            Thread worker = new Thread(() -> workerLoop(next, dispatched, calls, start, ratePerSecond,
                    end, System::nanoTime, LockSupport::parkNanos, (index, due) -> {
                CallOutcome outcome = call(client, String.format("%s-%08d", subjectPrefix, index), due);
                if (recordTo != null) {
                    recordTo.record(outcome);
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
                System::nanoTime, subject, measuredFrom);
    }

    /** The seam around {@code callTool}. */
    @FunctionalInterface
    interface ToolCall {
        McpSchema.CallToolResult invoke(String subject);
    }

    /**
     * Makes one call and times it from {@code measuredFrom} (its scheduled time), whether it completes or not.
     */
    static CallOutcome measuredCall(ToolCall toolCall, LongSupplier clock, String subject, long measuredFrom) {
        try {
            McpSchema.CallToolResult result = toolCall.invoke(subject);
            return ResponseClassifier.classify(result, micros(clock, measuredFrom));
        } catch (RuntimeException failure) {
            return CallOutcome.transportFailure(failure.getClass().getSimpleName(), micros(clock, measuredFrom));
        }
    }

    private static long micros(LongSupplier clock, long measuredFrom) {
        long now = clock.getAsLong();
        return (now - Math.min(measuredFrom, now)) / 1_000;
    }
}

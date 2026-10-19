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

    record Run(ResultAggregator.Summary summary, Duration elapsed) { }

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
            phase(clients, load.calls(), load.durationSeconds(), load.ratePerSecond(), aggregator, "m");
            Duration elapsed = Duration.ofNanos(System.nanoTime() - started);
            return new Run(aggregator.summary(elapsed), elapsed);
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

    /** {@code recordTo} is null for warmup, whose calls are made and discarded. */
    private void phase(List<McpSyncClient> clients, int calls, int durationSeconds, int ratePerSecond,
                       ResultAggregator recordTo, String subjectPrefix) throws InterruptedException {
        AtomicLong next = new AtomicLong();
        long start = System.nanoTime();
        long end = durationSeconds > 0 ? start + Duration.ofSeconds(durationSeconds).toNanos() : Long.MAX_VALUE;
        List<Thread> workers = new ArrayList<>();
        for (McpSyncClient client : clients) {
            Thread worker = new Thread(() -> {
                while (true) {
                    long index = next.getAndIncrement();
                    if (calls > 0 && index >= calls) {
                        return;
                    }
                    long due = ratePerSecond > 0 ? start + index * 1_000_000_000L / ratePerSecond : System.nanoTime();
                    if (due >= end) {
                        return;
                    }
                    long wait = due - System.nanoTime();
                    if (wait > 0) {
                        LockSupport.parkNanos(wait);
                    }
                    CallOutcome outcome = call(client, String.format("%s-%08d", subjectPrefix, index), due);
                    if (recordTo != null) {
                        recordTo.record(outcome);
                    }
                }
            }, "load-worker");
            workers.add(worker);
            worker.start();
        }
        for (Thread worker : workers) {
            worker.join();
        }
    }

    private CallOutcome call(McpSyncClient client, String subject, long measuredFrom) {
        try {
            McpSchema.CallToolResult result = client.callTool(new McpSchema.CallToolRequest("get_entity_context",
                    Map.of("entityType", SourceGenerator.ENTITY_TYPE, "subjectId", subject)));
            long micros = (System.nanoTime() - Math.min(measuredFrom, System.nanoTime())) / 1_000;
            return ResponseClassifier.classify(result, micros);
        } catch (RuntimeException failure) {
            return CallOutcome.transportFailure(failure.getClass().getSimpleName());
        }
    }
}

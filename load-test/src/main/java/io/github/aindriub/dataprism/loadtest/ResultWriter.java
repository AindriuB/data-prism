package io.github.aindriub.dataprism.loadtest;

import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/** Writes {@code results/<scenario>-<timestamp>.json}. */
final class ResultWriter {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
            .withZone(ZoneOffset.UTC);

    private ResultWriter() { }

    static Path write(Path resultsDir, Scenario scenario, Instant startedAt, String commit, boolean dirty,
                      LoadDriver.Run run) throws IOException {
        Files.createDirectories(resultsDir);
        Path file = resultsDir.resolve(scenario.name() + "-" + STAMP.format(startedAt) + ".json");
        JsonMapper mapper = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();
        Files.writeString(file, mapper.writeValueAsString(document(scenario, startedAt, commit, dirty, run)) + "\n");
        return file;
    }

    static Map<String, Object> document(Scenario scenario, Instant startedAt, String commit, boolean dirty,
                                        LoadDriver.Run run) {
        ResultAggregator.Summary s = run.summary();
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("scenario", scenario.name());
        doc.put("startedAt", startedAt.toString());
        doc.put("commit", commit);
        doc.put("workingTreeDirty", dirty);
        doc.put("jdk", System.getProperty("java.vendor") + " " + System.getProperty("java.version"));
        doc.put("host", host());
        doc.put("parameters", scenario);
        doc.put("effectiveLimits", scenario.limits().asProperties());
        Map<String, Object> calls = new LinkedHashMap<>();
        calls.put("attempted", s.attempted());
        calls.put("completed", s.completed());
        calls.put("transportFailures", s.transportFailures());
        calls.put("transportFailureKinds", s.transportFailureKinds());
        doc.put("calls", calls);
        Map<String, Object> latency = new LinkedHashMap<>();
        latency.put("p50", s.p50Ms());
        latency.put("p95", s.p95Ms());
        latency.put("p99", s.p99Ms());
        latency.put("max", s.maxMs());
        latency.put("mean", s.meanMs());
        doc.put("latencyMs", latency);
        doc.put("elapsedSeconds", run.elapsed().toNanos() / 1e9);
        doc.put("throughputPerSecond", s.throughputPerSecond());
        doc.put("refusalCodes", s.refusalCodes());
        doc.put("sourceStatusTotals", s.totalStatus());
        doc.put("perSourceStatus", s.perSourceStatus());
        return doc;
    }

    static String host() {
        Runtime runtime = Runtime.getRuntime();
        return System.getProperty("os.name") + " " + System.getProperty("os.version") + " "
                + System.getProperty("os.arch") + ", " + runtime.availableProcessors() + " processors, "
                + runtime.maxMemory() / (1024 * 1024) + " MiB max heap of the driver";
    }
}

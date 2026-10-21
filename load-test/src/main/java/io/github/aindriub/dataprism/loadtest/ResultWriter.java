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
        long causes = s.failureCauses().values().stream().mapToLong(Long::longValue).sum();
        if (causes != s.transportFailures()) {
            throw new IllegalStateException("failure causes (" + causes + ") do not add up to the failed calls ("
                    + s.transportFailures() + "); the result would be wrong");
        }
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
        calls.put("failureCauses", s.failureCauses());
        calls.put("completedAfterDeadline", s.completedAfterDeadline());
        calls.put("notDispatched", run.notDispatched());
        doc.put("calls", calls);
        Map<String, Object> latency = new LinkedHashMap<>();
        latency.put("p50", s.p50Ms());
        latency.put("p95", s.p95Ms());
        latency.put("p99", s.p99Ms());
        latency.put("max", s.maxMs());
        latency.put("mean", s.meanMs());
        latency.put("clamped", s.clamped());
        doc.put("latencyMs", latency);
        doc.put("elapsedSeconds", run.elapsed().toNanos() / 1e9);
        doc.put("measuredSeconds", run.measured().toNanos() / 1e9);
        Map<String, Object> credentials = new LinkedHashMap<>();
        credentials.put("tokenReissues", run.tokenReissues());
        credentials.put("scopeRotations", run.scopeRotations());
        doc.put("credentials", credentials);
        Map<String, Object> server = new LinkedHashMap<>();
        server.put("diedDuringRun", run.serverExitCode() != null);
        if (run.serverExitCode() != null) {
            server.put("exitCode", run.serverExitCode());
        }
        doc.put("server", server);
        doc.put("throughputPerSecond", s.throughputPerSecond());
        doc.put("refusalCodes", s.refusalCodes());
        doc.put("sourceStatusTotals", s.totalStatus());
        doc.put("sourcesNotAnswered", s.sourcesNotAnswered());
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

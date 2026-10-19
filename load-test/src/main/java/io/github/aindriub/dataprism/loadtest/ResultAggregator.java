package io.github.aindriub.dataprism.loadtest;

import org.HdrHistogram.Histogram;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Accumulates call outcomes from many threads and summarises them.
 *
 * <p>Latency is held in microseconds with five significant digits, which is exact for any value up to
 * 262143 microseconds (about 262 ms) and within 0.002 % above that. Percentiles are reported in
 * milliseconds.
 */
final class ResultAggregator {

    private static final long HIGHEST_TRACKABLE_MICROS = Duration.ofMinutes(10).toNanos() / 1_000;

    private final Histogram latency = new Histogram(HIGHEST_TRACKABLE_MICROS, 5);
    private final Map<String, Long> refusalCodes = new TreeMap<>();
    private final Map<String, Long> transportFailureKinds = new TreeMap<>();
    private final Map<String, Map<String, Long>> perSource = new TreeMap<>();
    private long attempted;
    private long completed;
    private long transportFailures;

    synchronized void record(CallOutcome outcome) {
        attempted++;
        if (!outcome.completed()) {
            transportFailures++;
            transportFailureKinds.merge(outcome.transportFailure(), 1L, Long::sum);
            return;
        }
        completed++;
        latency.recordValue(Math.min(Math.max(outcome.latencyMicros(), 0), HIGHEST_TRACKABLE_MICROS));
        if (outcome.refusalCode() != null) {
            refusalCodes.merge(outcome.refusalCode(), 1L, Long::sum);
        }
        outcome.sourceStatuses().forEach((source, status) -> {
            Map<String, Long> tally = perSource.computeIfAbsent(source, ignored -> zeroed());
            tally.merge(status, 1L, Long::sum);
        });
    }

    synchronized Summary summary(Duration elapsed) {
        Map<String, Long> total = zeroed();
        Map<String, Map<String, Long>> sources = new TreeMap<>();
        perSource.forEach((source, tally) -> {
            sources.put(source, new LinkedHashMap<>(tally));
            tally.forEach((status, count) -> total.merge(status, count, Long::sum));
        });
        double seconds = elapsed.toNanos() / 1e9;
        boolean empty = latency.getTotalCount() == 0;
        return new Summary(attempted, completed, transportFailures, new TreeMap<>(transportFailureKinds),
                empty ? 0 : millis(latency.getValueAtPercentile(50)),
                empty ? 0 : millis(latency.getValueAtPercentile(95)),
                empty ? 0 : millis(latency.getValueAtPercentile(99)),
                empty ? 0 : millis(latency.getMaxValue()),
                empty ? 0 : latency.getMean() / 1000.0,
                seconds > 0 ? completed / seconds : 0, new TreeMap<>(refusalCodes), total, sources);
    }

    private static double millis(long micros) {
        return micros / 1000.0;
    }

    /** All seven known codes at zero first, so a code that never occurred reads as 0 rather than absent. */
    private static Map<String, Long> zeroed() {
        Map<String, Long> tally = new LinkedHashMap<>();
        SourceStatuses.ALL.forEach(status -> tally.put(status, 0L));
        return tally;
    }

    record Summary(long attempted, long completed, long transportFailures, Map<String, Long> transportFailureKinds,
                   double p50Ms, double p95Ms, double p99Ms, double maxMs, double meanMs,
                   double throughputPerSecond, Map<String, Long> refusalCodes, Map<String, Long> totalStatus,
                   Map<String, Map<String, Long>> perSourceStatus) { }
}

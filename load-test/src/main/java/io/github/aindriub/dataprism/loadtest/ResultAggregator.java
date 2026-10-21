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
 * milliseconds. Timed-out calls are included, at their time to the timeout, and so is any other failure that
 * took long (see {@code LoadDriver.SLOW_FAILURE}); quick transport failures are only counted. A value above six hours is recorded as
 * six hours and counted in {@code clamped}.
 */
final class ResultAggregator {

    static final long HIGHEST_TRACKABLE_MICROS = Duration.ofHours(6).toNanos() / 1_000;

    private final Histogram latency = new Histogram(HIGHEST_TRACKABLE_MICROS, 5);
    private final Map<String, Long> refusalCodes = new TreeMap<>();
    private final Map<String, Long> transportFailureKinds = new TreeMap<>();
    private final Map<String, Long> failureCauses = zeroedCauses();
    private final Map<String, Map<String, Long>> perSource = new TreeMap<>();
    private long attempted;
    private long completed;
    private long transportFailures;
    private long clamped;
    private long completedAfterDeadline;

    void record(CallOutcome outcome) {
        record(outcome, false);
    }

    /**
     * @param afterDeadline whether the call finished after the run's deadline (the in-flight drain). It is still
     *                      counted and timed, but is kept out of throughput, which is calls finished in the window.
     */
    void record(CallOutcome outcome, boolean afterDeadline) {
        record(outcome, afterDeadline, 1);
    }

    /**
     * @param scope the number of the privacy scope the call ran in. A source's name in a response is an alias that
     *              is stable only within one scope, so the per-source tally is keyed {@code scope-<n>/<name>}.
     */
    synchronized void record(CallOutcome outcome, boolean afterDeadline, int scope) {
        attempted++;
        if (!outcome.completed()) {
            // The failed total and each cause are counted separately, from the outcome's own fields, so that a
            // test can show they agree rather than one being derived from the other.
            transportFailures++;
            transportFailureKinds.merge(outcome.transportFailure(), 1L, Long::sum);
            failureCauses.merge(outcome.failureCause().name(), 1L, Long::sum);
            // Only a timed failure enters the latency figures: a stall must raise the tail, never drop out of
            // it. A quick failure (a dead server refusing connections) is counted but kept out, or it would
            // pull p50 down.
            if (outcome.timed()) {
                recordLatency(outcome.latencyMicros());
            }
            return;
        }
        completed++;
        if (afterDeadline) {
            completedAfterDeadline++;
        }
        recordLatency(outcome.latencyMicros());
        if (outcome.refusalCode() != null) {
            refusalCodes.merge(outcome.refusalCode(), 1L, Long::sum);
        }
        outcome.sourceStatuses().forEach((source, status) -> {
            Map<String, Long> tally = perSource.computeIfAbsent("scope-" + scope + "/" + source, ignored -> zeroed());
            tally.merge(status, 1L, Long::sum);
        });
    }

    private void recordLatency(long micros) {
        if (micros > HIGHEST_TRACKABLE_MICROS) {
            clamped++;
        }
        latency.recordValue(Math.min(Math.max(micros, 0), HIGHEST_TRACKABLE_MICROS));
    }

    synchronized Summary summary(Duration elapsed) {
        Map<String, Long> total = zeroed();
        Map<String, Map<String, Long>> sources = new TreeMap<>();
        perSource.forEach((source, tally) -> {
            sources.put(source, new LinkedHashMap<>(tally));
            tally.forEach((status, count) -> total.merge(status, count, Long::sum));
        });
        long notAnswered = 0;
        for (Map.Entry<String, Long> status : total.entrySet()) {
            if (!SourceStatuses.isAnswered(status.getKey())) {
                notAnswered += status.getValue();
            }
        }
        double seconds = elapsed.toNanos() / 1e9;
        boolean empty = latency.getTotalCount() == 0;
        return new Summary(attempted, completed, transportFailures, new TreeMap<>(transportFailureKinds),
                empty ? 0 : millis(latency.getValueAtPercentile(50)),
                empty ? 0 : millis(latency.getValueAtPercentile(95)),
                empty ? 0 : millis(latency.getValueAtPercentile(99)),
                empty ? 0 : millis(latency.getMaxValue()),
                empty ? 0 : latency.getMean() / 1000.0,
                seconds > 0 ? (completed - completedAfterDeadline) / seconds : 0, new TreeMap<>(refusalCodes), total,
                sources, clamped, new LinkedHashMap<>(failureCauses), notAnswered, completedAfterDeadline);
    }

    private static double millis(long micros) {
        return micros / 1000.0;
    }

    private static Map<String, Long> zeroedCauses() {
        Map<String, Long> causes = new LinkedHashMap<>();
        for (FailureCause cause : FailureCause.values()) {
            causes.put(cause.name(), 0L);
        }
        return causes;
    }

    /** All known codes at zero first, so a code that never occurred reads as 0 rather than absent. */
    private static Map<String, Long> zeroed() {
        Map<String, Long> tally = new LinkedHashMap<>();
        SourceStatuses.ALL.forEach(status -> tally.put(status, 0L));
        return tally;
    }

    record Summary(long attempted, long completed, long transportFailures, Map<String, Long> transportFailureKinds,
                   double p50Ms, double p95Ms, double p99Ms, double maxMs, double meanMs,
                   double throughputPerSecond, Map<String, Long> refusalCodes, Map<String, Long> totalStatus,
                   Map<String, Map<String, Long>> perSourceStatus, long clamped,
                   Map<String, Long> failureCauses, long sourcesNotAnswered, long completedAfterDeadline) { }
}

package io.github.aindriub.dataprism.core.metrics;

import java.time.Duration;
import java.util.function.DoubleSupplier;

/**
 * Counts and times what happens, without ever taking a value to label it with.
 *
 * <p>There is no method that accepts a free-form tag or a metric value: the
 * shape of the API is what makes a PII metric label unrepresentable, rather than
 * merely forbidden by convention. See docs/pack.md §89 for the banned label list
 * this exists to make impossible to reproduce by accident.
 */
public interface PrivacyMetrics {

    void increment(Metric metric);

    /**
     * @param sourceName a configured source name, and nothing else — never a
     *                   value read from a source payload or a caller argument.
     */
    void increment(Metric metric, String sourceName);

    /**
     * @param sourceName see {@link #increment(Metric, String)}
     */
    void record(Metric metric, String sourceName, Duration duration);

    /**
     * Registers a gauge whose value the registry reads at scrape time. The owner of the value calls this once.
     * A gauge has no tag, like every other signal. The default does nothing, so an implementation written
     * before this method existed compiles unchanged and simply exports no gauges.
     *
     * @param supplier must be cheap, thread-safe and must not throw; it is called from the scrape thread
     */
    default void gauge(Metric metric, DoubleSupplier supplier) {}

    static PrivacyMetrics none() {
        return new PrivacyMetrics() {
            @Override
            public void increment(Metric metric) {}

            @Override
            public void increment(Metric metric, String sourceName) {}

            @Override
            public void record(Metric metric, String sourceName, Duration duration) {}
        };
    }
}

package io.github.aindriub.dataprism.core;

import java.time.Duration;

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

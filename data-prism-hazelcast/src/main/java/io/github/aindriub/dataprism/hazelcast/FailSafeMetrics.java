package io.github.aindriub.dataprism.hazelcast;

import io.github.aindriub.dataprism.core.Metric;
import io.github.aindriub.dataprism.core.PrivacyMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Objects;

/**
 * A {@link PrivacyMetrics} that cannot fail its caller, no matter what the
 * delegate does.
 *
 * <p>{@link CachingSyntheticValueSource} promises that publishing metrics never
 * changes the outcome of a lookup. That promise cannot rest on every call site
 * remembering to guard itself — a call added later would simply be unguarded
 * again, the same mistake repeated. So the guard lives here instead, once,
 * wrapped around the delegate at construction: every method swallows whatever
 * the delegate throws, logs it, and returns as if nothing happened. Callers in
 * this module hold only a {@code PrivacyMetrics} that already behaves this
 * way, so there is nothing left for them to remember.
 */
final class FailSafeMetrics implements PrivacyMetrics {

    private static final Logger LOG = LoggerFactory.getLogger(FailSafeMetrics.class);

    private final PrivacyMetrics delegate;

    private FailSafeMetrics(PrivacyMetrics delegate) {
        this.delegate = delegate;
    }

    static PrivacyMetrics wrap(PrivacyMetrics metrics) {
        Objects.requireNonNull(metrics, "metrics");
        return metrics instanceof FailSafeMetrics ? metrics : new FailSafeMetrics(metrics);
    }

    @Override
    public void increment(Metric metric) {
        guard(() -> delegate.increment(metric));
    }

    @Override
    public void increment(Metric metric, String sourceName) {
        guard(() -> delegate.increment(metric, sourceName));
    }

    @Override
    public void record(Metric metric, String sourceName, Duration duration) {
        guard(() -> delegate.record(metric, sourceName, duration));
    }

    private static void guard(Runnable call) {
        try {
            call.run();
        } catch (RuntimeException metricsFailure) {
            LOG.warn("metrics reporting failed, ignoring: {}", metricsFailure.getClass().getSimpleName());
        }
    }
}

package io.github.aindriub.dataprism.example.http;

import io.github.aindriub.dataprism.core.Metric;
import io.github.aindriub.dataprism.core.PrivacyMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Objects;

/**
 * {@link PrivacyMetrics} over a Micrometer {@link MeterRegistry}.
 *
 * <p>The only tag this class ever attaches is {@code source}, and only with the
 * value {@link PrivacyMetrics#increment(Metric, String)} itself documents: a
 * configured source name, never one read from a payload or a caller argument.
 * Nothing here can turn a caller-supplied or source-supplied value into a
 * metric label.
 *
 * <p>Micrometer throws when a meter's registration conflicts with one already
 * registered under the same name — a hazard {@code CachingSyntheticValueSource}
 * in data-prism-hazelcast already guards against by wrapping its delegate in a
 * {@code FailSafeMetrics} at construction. That type is package-private to its
 * own module, so this class cannot reuse it, but the promise it exists to keep
 * — an emit can never fail the call it is reporting on — is the same one made
 * here: every Micrometer call is guarded, a failure is logged and swallowed,
 * and the caller never sees it.
 */
public final class MicrometerPrivacyMetrics implements PrivacyMetrics {

    private static final Logger LOG = LoggerFactory.getLogger(MicrometerPrivacyMetrics.class);
    private static final String SOURCE_TAG = "source";

    private final MeterRegistry registry;

    public MicrometerPrivacyMetrics(MeterRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    @Override
    public void increment(Metric metric) {
        guard(() -> registry.counter(metric.metricName()).increment());
    }

    @Override
    public void increment(Metric metric, String sourceName) {
        guard(() -> registry.counter(metric.metricName(), SOURCE_TAG, sourceName).increment());
    }

    @Override
    public void record(Metric metric, String sourceName, Duration duration) {
        guard(() -> Timer.builder(metric.metricName())
                .tag(SOURCE_TAG, sourceName)
                .register(registry)
                .record(duration));
    }

    private static void guard(Runnable call) {
        try {
            call.run();
        } catch (RuntimeException metricsFailure) {
            LOG.warn("metrics reporting failed, ignoring: {}", metricsFailure.getClass().getSimpleName());
        }
    }
}

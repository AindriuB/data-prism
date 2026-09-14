package io.github.aindriub.dataprism.server;

import io.github.aindriub.dataprism.core.Metric;
import io.github.aindriub.dataprism.core.PrivacyMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;

/** Fail-safe Micrometer binding; source is the only permitted metric tag. */
final class ServerPrivacyMetrics implements PrivacyMetrics {
    private static final Logger LOG = LoggerFactory.getLogger(ServerPrivacyMetrics.class);
    private final MeterRegistry registry;

    ServerPrivacyMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void increment(Metric metric) {
        safely(() -> registry.counter(metric.metricName()).increment());
    }

    @Override
    public void increment(Metric metric, String source) {
        safely(() -> registry.counter(metric.metricName(), "source", source).increment());
    }

    @Override
    public void record(Metric metric, String source, Duration duration) {
        safely(() -> Timer.builder(metric.metricName()).tag("source", source)
                .register(registry).record(duration));
    }

    private static void safely(Runnable emission) {
        try {
            emission.run();
        } catch (RuntimeException failure) {
            LOG.warn("privacy metric emission failed: {}", failure.getClass().getSimpleName());
        }
    }
}

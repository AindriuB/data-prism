package io.github.aindriub.dataprism.hazelcast.seal;

import io.github.aindriub.dataprism.core.metrics.Metric;
import io.github.aindriub.dataprism.core.metrics.PrivacyMetrics;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** Counts every increment so a test can assert exactly which counter moved. */
final class SealerTestSupport {

    private SealerTestSupport() {}

    static final class CountingMetrics implements PrivacyMetrics {
        private final Map<Metric, AtomicInteger> counts = new EnumMap<>(Metric.class);

        @Override
        public synchronized void increment(Metric metric) {
            counts.computeIfAbsent(metric, m -> new AtomicInteger()).incrementAndGet();
        }

        @Override
        public void increment(Metric metric, String sourceName) {
            throw new AssertionError("the seal never tags a metric");
        }

        @Override
        public void record(Metric metric, String sourceName, Duration duration) {
            throw new AssertionError("the seal never times a metric");
        }

        synchronized int count(Metric metric) {
            AtomicInteger c = counts.get(metric);
            return c == null ? 0 : c.get();
        }

        synchronized Map<Metric, AtomicInteger> all() {
            return new EnumMap<>(counts);
        }
    }
}

package io.github.aindriub.dataprism.hazelcast.seal;

import io.github.aindriub.dataprism.core.metrics.Metric;
import io.github.aindriub.dataprism.core.metrics.PrivacyMetrics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.DoubleSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code FailSafeMetrics} is package-private to the module root, so it is reached by reflection from here: this
 * task may add tests only under {@code seal}.
 */
class FailSafeGaugeForwardingTest {

    private static PrivacyMetrics wrap(PrivacyMetrics delegate) throws Exception {
        Class<?> type = Class.forName("io.github.aindriub.dataprism.hazelcast.FailSafeMetrics");
        Method wrap = type.getDeclaredMethod("wrap", PrivacyMetrics.class);
        wrap.setAccessible(true);
        return (PrivacyMetrics) wrap.invoke(null, delegate);
    }

    @Test
    @DisplayName("FailSafeMetrics forwards a gauge registration to the delegate")
    void forwards() throws Exception {
        List<Metric> registered = new ArrayList<>();
        List<DoubleSupplier> suppliers = new ArrayList<>();
        PrivacyMetrics delegate = new PrivacyMetrics() {
            @Override public void increment(Metric metric) {}
            @Override public void increment(Metric metric, String sourceName) {}
            @Override public void record(Metric metric, String sourceName, Duration duration) {}
            @Override public void gauge(Metric metric, DoubleSupplier supplier) {
                registered.add(metric);
                suppliers.add(supplier);
            }
        };

        wrap(delegate).gauge(Metric.CLUSTER_PAUSE_FRESH, () -> 4.0);

        assertThat(registered).containsExactly(Metric.CLUSTER_PAUSE_FRESH);
        assertThat(suppliers.get(0).getAsDouble()).isEqualTo(4.0);
    }

    @Test
    @DisplayName("a delegate that throws on a gauge cannot fail the caller")
    void containsDelegateFailure() throws Exception {
        PrivacyMetrics delegate = new PrivacyMetrics() {
            @Override public void increment(Metric metric) {}
            @Override public void increment(Metric metric, String sourceName) {}
            @Override public void record(Metric metric, String sourceName, Duration duration) {}
            @Override public void gauge(Metric metric, DoubleSupplier supplier) {
                throw new IllegalStateException("registry down");
            }
        };
        wrap(delegate).gauge(Metric.CLUSTER_PAUSE_FRESH, () -> 1.0);
    }
}

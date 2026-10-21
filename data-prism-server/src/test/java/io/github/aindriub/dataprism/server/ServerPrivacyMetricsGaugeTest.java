package io.github.aindriub.dataprism.server;

import io.github.aindriub.dataprism.core.metrics.Metric;
import io.github.aindriub.dataprism.core.metrics.PrivacyMetrics;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class ServerPrivacyMetricsGaugeTest {

    @Test
    @DisplayName("a gauge reads its supplier at scrape time and carries no tag")
    void gaugeReadsTheSupplierBack() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        PrivacyMetrics metrics = new ServerPrivacyMetrics(registry);
        double[] value = {2.0};

        metrics.gauge(Metric.CLUSTER_ROTATION_BEHIND, () -> value[0]);
        value[0] = 1.0;

        Gauge gauge = registry.get("dataprism.cluster.rotation.behind").gauge();
        assertThat(gauge.value()).isEqualTo(1.0);
        assertThat(gauge.getId().getTags()).isEmpty();
    }

    @Test
    @DisplayName("a bad registration is contained, not thrown at the caller")
    void badRegistrationIsContained() {
        PrivacyMetrics metrics = new ServerPrivacyMetrics(new SimpleMeterRegistry());
        assertThatCode(() -> metrics.gauge(Metric.CLUSTER_PAUSE_FRESH, null)).doesNotThrowAnyException();
    }
}

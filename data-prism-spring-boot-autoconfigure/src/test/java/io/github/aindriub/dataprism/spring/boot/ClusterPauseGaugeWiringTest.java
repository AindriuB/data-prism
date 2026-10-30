package io.github.aindriub.dataprism.spring.boot;

import com.hazelcast.core.Hazelcast;
import io.github.aindriub.dataprism.core.metrics.Metric;
import io.github.aindriub.dataprism.core.metrics.PrivacyMetrics;
import io.github.aindriub.dataprism.hazelcast.HazelcastOversightState;
import io.github.aindriub.dataprism.oversight.OversightState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.DoubleSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 228, amendment 7: the cluster-backed oversight state is built with the application's {@code PrivacyMetrics},
 * so {@code pause.fresh} and {@code pause.age_seconds} are registered with it, once each, and the pause document
 * counts into it. No bean is added: the existing {@code OversightState} bean method takes the metrics.
 */
class ClusterPauseGaugeWiringTest {

    @AfterEach
    void shutDown() {
        Hazelcast.shutdownAll();
    }

    static final Map<Metric, AtomicInteger> REGISTRATIONS = new ConcurrentHashMap<>();
    static final Map<Metric, DoubleSupplier> GAUGES = new ConcurrentHashMap<>();

    @Configuration(proxyBeanMethods = false)
    static class RecordingMetrics extends ClusterKeyConfigurationTest.KeyIntegrations {
        @Bean PrivacyMetrics metrics() {
            return new PrivacyMetrics() {
                @Override public void record(Metric metric, String tag, Duration elapsed) { }
                @Override public void increment(Metric metric, String tag) { }
                @Override public void increment(Metric metric) { }
                @Override public void gauge(Metric metric, DoubleSupplier supplier) {
                    REGISTRATIONS.computeIfAbsent(metric, m -> new AtomicInteger()).incrementAndGet();
                    GAUGES.put(metric, supplier);
                }
            };
        }
    }

    private static WebApplicationContextRunner runner() {
        Map<String, String> p = ClusterKeyConfigurationTest.valid("none");
        p.put("dataprism.hazelcast.member.port", String.valueOf(ClusterConfigurationTest.freePort()));
        List<String> all = new ArrayList<>();
        p.forEach((k, v) -> all.add(k + "=" + v));
        return new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(DataPrismAutoConfiguration.class))
                .withUserConfiguration(RecordingMetrics.class)
                .withPropertyValues(OversightConfigurationTest.base("embedded"))
                .withPropertyValues(all.toArray(String[]::new));
    }

    @Test
    void thePauseGaugesAreRegisteredOnceEachWithTheApplicationsMetrics() {
        REGISTRATIONS.clear();
        GAUGES.clear();
        runner().run(result -> {
            assertThat(result).hasNotFailed();
            assertThat(result.getBean(OversightState.class)).isInstanceOf(HazelcastOversightState.class);
            assertThat(REGISTRATIONS.get(Metric.CLUSTER_PAUSE_FRESH)).isNotNull().hasValue(1);
            assertThat(REGISTRATIONS.get(Metric.CLUSTER_PAUSE_AGE_SECONDS)).isNotNull().hasValue(1);
            // Before any pause the document is absent: not fresh, and no age.
            assertThat(GAUGES.get(Metric.CLUSTER_PAUSE_FRESH).getAsDouble()).isEqualTo(0.0);
            assertThat(GAUGES.get(Metric.CLUSTER_PAUSE_AGE_SECONDS).getAsDouble()).isNaN();
            result.getBean(OversightState.class).pauseAll();
            assertThat(GAUGES.get(Metric.CLUSTER_PAUSE_FRESH).getAsDouble()).isEqualTo(1.0);
        });
    }
}

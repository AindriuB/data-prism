package io.github.aindriub.dataprism.spring.boot;

import com.hazelcast.core.Hazelcast;
import io.github.aindriub.dataprism.core.metrics.Metric;
import io.github.aindriub.dataprism.core.metrics.PrivacyMetrics;
import io.github.aindriub.dataprism.hazelcast.PrivacyCluster;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.DoubleSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/** Review of task 222: the legacy-entries gauge is registered on the cluster bean, and a failure to register stops the member. */
class ClusterLegacyGaugeTest {

    @AfterEach
    void shutDown() {
        Hazelcast.shutdownAll();
    }

    static final Map<Metric, DoubleSupplier> GAUGES = new ConcurrentHashMap<>();

    @Configuration(proxyBeanMethods = false)
    static class RecordingMetrics extends ClusterKeyConfigurationTest.KeyIntegrations {
        @Bean PrivacyMetrics metrics() {
            return new PrivacyMetrics() {
                @Override public void record(Metric metric, String tag, java.time.Duration elapsed) { }
                @Override public void increment(Metric metric, String tag) { }
                @Override public void increment(Metric metric) { }
                @Override public void gauge(Metric metric, DoubleSupplier supplier) {
                    GAUGES.put(metric, supplier);
                }
            };
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class ThrowingMetrics extends ClusterKeyConfigurationTest.KeyIntegrations {
        @Bean PrivacyMetrics metrics() {
            return new PrivacyMetrics() {
                @Override public void record(Metric metric, String tag, java.time.Duration elapsed) { }
                @Override public void increment(Metric metric, String tag) { }
                @Override public void increment(Metric metric) { }
                @Override public void gauge(Metric metric, DoubleSupplier supplier) {
                    throw new IllegalStateException("the registry refused the gauge");
                }
            };
        }
    }

    private static WebApplicationContextRunner runner(Class<?> integrations) {
        java.util.List<String> all = new java.util.ArrayList<>();
        Map<String, String> p = ClusterKeyConfigurationTest.valid("none");
        p.put("dataprism.hazelcast.member.port", String.valueOf(ClusterConfigurationTest.freePort()));
        p.forEach((k, v) -> all.add(k + "=" + v));
        return new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(DataPrismAutoConfiguration.class))
                .withUserConfiguration(integrations)
                .withPropertyValues(OversightConfigurationTest.base("embedded"))
                .withPropertyValues(all.toArray(String[]::new));
    }

    @Test
    void theLegacyEntriesGaugeIsRegisteredAndReadsTheClusterCount() {
        GAUGES.clear();
        runner(RecordingMetrics.class).run(result -> {
            assertThat(result).hasNotFailed();
            DoubleSupplier gauge = GAUGES.get(Metric.CLUSTER_LEGACY_ENTRIES);
            assertThat(gauge).isNotNull();
            assertThat(gauge.getAsDouble()).isEqualTo(result.getBean(PrivacyCluster.class).legacyEntries());
        });
    }

    @Test
    void aGaugeThatWillNotRegisterLeavesNoMemberRunning() {
        runner(ThrowingMetrics.class).run(result -> {
            assertThat(result).hasFailed();
            assertThat(Hazelcast.getAllHazelcastInstances()).isEmpty();
        });
    }

    @Test
    void aCloseThatFailsIsAddedAsSuppressedAndTheOriginalFailureSurvives() {
        IllegalStateException original = new IllegalStateException("gauge");
        IllegalStateException closeFailure = new IllegalStateException("close");
        ClusterBackedState.closeSuppressing(() -> {
            throw closeFailure;
        }, original);
        assertThat(original.getSuppressed()).containsExactly(closeFailure);
    }

    @Test
    void aCloseThatWorksLeavesTheFailureAlone() {
        IllegalStateException original = new IllegalStateException("gauge");
        ClusterBackedState.closeSuppressing(() -> { }, original);
        assertThat(original.getSuppressed()).isEmpty();
    }

    @Configuration(proxyBeanMethods = false)
    static class CachingKeys extends DataPrismAutoConfigurationTest.ReviewedHttpIntegrations {
        static final Map<String, byte[]> CACHE = new ConcurrentHashMap<>();

        @Override @Bean HmacKeyReferenceResolver keys() {
            return (id, reference) -> CACHE.computeIfAbsent(reference, r -> {
                byte[] out = new byte[32];
                java.util.Arrays.fill(out, (byte) r.hashCode());
                return out;
            });
        }
    }

    @Test
    void theHmacKeyCheckLeavesACachingResolversArrayIntact() {
        CachingKeys.CACHE.clear();
        runner(CachingKeys.class).run(result -> {
            assertThat(result).hasNotFailed();
            assertThat(CachingKeys.CACHE).isNotEmpty();
            CachingKeys.CACHE.forEach((reference, bytes) -> {
                byte[] expected = new byte[32];
                java.util.Arrays.fill(expected, (byte) reference.hashCode());
                assertThat(bytes).as(reference).isEqualTo(expected);
            });
        });
    }
}

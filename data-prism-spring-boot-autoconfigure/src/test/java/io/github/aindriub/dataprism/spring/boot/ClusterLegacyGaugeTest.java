package io.github.aindriub.dataprism.spring.boot;

import com.hazelcast.core.Hazelcast;
import io.github.aindriub.dataprism.core.metrics.Metric;
import io.github.aindriub.dataprism.core.metrics.PrivacyMetrics;
import io.github.aindriub.dataprism.hazelcast.ClusterMembership;
import io.github.aindriub.dataprism.hazelcast.PrivacyCluster;
import io.github.aindriub.dataprism.hazelcast.seal.ClusterKeys;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.DoubleSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Review of task 222: the legacy-entries gauge is registered on the cluster bean, and a failure to register stops
 * the member.
 */
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
                @Override public void record(Metric metric, String tag, Duration elapsed) { }
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
                @Override public void record(Metric metric, String tag, Duration elapsed) { }
                @Override public void increment(Metric metric, String tag) { }
                @Override public void increment(Metric metric) { }
                @Override public void gauge(Metric metric, DoubleSupplier supplier) {
                    throw new IllegalStateException("the registry refused the gauge");
                }
            };
        }
    }

    private static WebApplicationContextRunner runner(Class<?> integrations) {
        Map<String, String> p = ClusterKeyConfigurationTest.valid("none");
        p.put("dataprism.hazelcast.member.port", String.valueOf(ClusterConfigurationTest.freePort()));
        return runner(integrations, p);
    }

    private static WebApplicationContextRunner runner(Class<?> integrations, Map<String, String> p) {
        List<String> all = new ArrayList<>();
        p.forEach((k, v) -> all.add(k + "=" + v));
        return new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(DataPrismAutoConfiguration.class))
                .withUserConfiguration(integrations)
                .withPropertyValues(OversightConfigurationTest.base("embedded"))
                .withPropertyValues(all.toArray(String[]::new));
    }

    @Test
    void theLegacyEntriesGaugeIsRegisteredOnTheClusterBean() {
        GAUGES.clear();
        runner(RecordingMetrics.class).run(result -> {
            assertThat(result).hasNotFailed();
            DoubleSupplier gauge = GAUGES.get(Metric.CLUSTER_LEGACY_ENTRIES);
            assertThat(gauge).isNotNull();
            assertThat(gauge.getAsDouble()).isEqualTo(result.getBean(PrivacyCluster.class).legacyEntries());
        });
    }

    /**
     * A legacy-format entry is written to the member's 0.7 identity map and a third member joins, which makes the
     * member count its legacy entries; the gauge must then read that count. The joining member refuses to start
     * (it finds the legacy state) and is not what is asserted on.
     */
    @Test
    void theGaugeReadsLegacyEntriesTheMembershipCheckCounted() throws Exception {
        GAUGES.clear();
        int first = ClusterConfigurationTest.freePort();
        int second = ClusterConfigurationTest.freePort();
        int third = ClusterConfigurationTest.freePort();
        Map<String, String> p = ClusterKeyConfigurationTest.valid("tcp-ip");
        String name = p.get("dataprism.hazelcast.cluster-name");
        p.put("dataprism.hazelcast.join.members[0]", "127.0.0.1:" + first);
        p.put("dataprism.hazelcast.join.members[1]", "127.0.0.1:" + second);
        p.put("dataprism.hazelcast.join.members[2]", "127.0.0.1:" + third);
        p.put("dataprism.hazelcast.member.port", String.valueOf(first));
        List<String> members = List.of("127.0.0.1:" + first, "127.0.0.1:" + second, "127.0.0.1:" + third);
        ClusterKeys keys = new ClusterKeys("cluster-1",
                ClusterKeyConfigurationTest.material("cluster-1", "DATAPRISM_CLUSTER_KEY_REF"), null, null, null,
                null, "name-1", ClusterKeyConfigurationTest.material("name-1", "DATAPRISM_NAME_KEY_REF"));
        runner(RecordingMetrics.class, p).run(result -> {
            assertThat(result).hasNotFailed();
            PrivacyCluster cluster = result.getBean(PrivacyCluster.class);
            try (PrivacyCluster peer = PrivacyCluster.embedded(
                    ClusterMembership.tcpIp(name, members).withPort(second).withInterface("127.0.0.1"), false,
                    OptionalInt.of(1), keys)) {
                DoubleSupplier gauge = GAUGES.get(Metric.CLUSTER_LEGACY_ENTRIES);
                assertThat(gauge.getAsDouble()).isZero();
                cluster.instance().getMap("dataprism.identity").put("legacy-key", "legacy-value");
                try (PrivacyCluster refused = PrivacyCluster.embedded(
                        ClusterMembership.tcpIp(name, members).withPort(third).withInterface("127.0.0.1"), false,
                        OptionalInt.of(1), keys)) {
                    // Normally refused for the legacy state; if it starts, its join still ran the check.
                } catch (RuntimeException expected) {
                    // The legacy state is found at its start.
                }
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
                while (gauge.getAsDouble() == 0 && System.nanoTime() < deadline) {
                    Thread.sleep(100);
                }
                assertThat(gauge.getAsDouble()).isEqualTo(1.0);
                assertThat(cluster.legacyEntries()).isEqualTo(1);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
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
                Arrays.fill(out, (byte) r.hashCode());
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
                Arrays.fill(expected, (byte) reference.hashCode());
                assertThat(bytes).as(reference).isEqualTo(expected);
            });
        });
    }

    private static WebApplicationContextRunner suppliedRunner(Class<?> integrations) {
        return new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(DataPrismAutoConfiguration.class))
                .withUserConfiguration(integrations, ClusterConfigurationTest.SuppliedCluster.class)
                .withPropertyValues(OversightConfigurationTest.base("embedded"));
    }

    @Test
    void anApplicationSuppliedClusterGetsTheGaugeToo() {
        GAUGES.clear();
        suppliedRunner(RecordingMetrics.class).run(result -> {
            assertThat(result).hasNotFailed();
            DoubleSupplier gauge = GAUGES.get(Metric.CLUSTER_LEGACY_ENTRIES);
            assertThat(gauge).isNotNull();
            assertThat(gauge.getAsDouble()).isEqualTo(result.getBean(PrivacyCluster.class).legacyEntries());
        });
    }

    @Test
    void aGaugeThatWillNotRegisterOnASuppliedClusterDoesNotStopItsStartup() {
        suppliedRunner(ThrowingMetrics.class).run(result -> {
            assertThat(result).hasNotFailed();
            assertThat(result.getBean(PrivacyCluster.class).instance().getLifecycleService().isRunning()).isTrue();
        });
    }
}

package io.github.aindriub.dataprism.spring.boot;

import io.github.aindriub.dataprism.core.link.LinkKeys;
import io.github.aindriub.dataprism.core.metrics.Metric;
import io.github.aindriub.dataprism.core.metrics.PrivacyMetrics;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.DoubleSupplier;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The {@code linkKeys} health contributor and the loaded-keys gauge (OD-08-10 (a)): the ids a member holds so that
 * rotation phase 1 can be confirmed, and the count as a gauge. No key material and no reference appears in either.
 */
class LinkKeysHealthTest {

    static final Map<Metric, DoubleSupplier> GAUGES = new ConcurrentHashMap<>();

    @Configuration(proxyBeanMethods = false)
    static class GaugeCapturing extends LinkKeyConfigurationTest.ReviewedIntegrations {
        @Override @Bean PrivacyMetrics metrics() {
            return new PrivacyMetrics() {
                @Override public void increment(Metric metric) { }
                @Override public void increment(Metric metric, String sourceName) { }
                @Override public void record(Metric metric, String tag, java.time.Duration value) { }
                @Override public void gauge(Metric metric, DoubleSupplier supplier) { GAUGES.put(metric, supplier); }
            };
        }
    }

    private final WebApplicationContextRunner context = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(DataPrismAutoConfiguration.class))
            .withUserConfiguration(GaugeCapturing.class)
            .withPropertyValues(OversightConfigurationTest.base("single-node"));

    @Test
    void there_is_no_contributor_and_no_gauge_without_a_link_key() {
        GAUGES.clear();
        context.run(result -> {
            assertThat(result).hasNotFailed().doesNotHaveBean("linkKeysHealthIndicator");
            assertThat(GAUGES).doesNotContainKey(Metric.LINK_KEYS_LOADED);
        });
    }

    @Test
    void the_contributor_lists_every_held_id_and_nothing_else_and_the_gauge_counts_them() {
        GAUGES.clear();
        context.withPropertyValues("dataprism.links.key-id=lk2", "dataprism.links.keys.lk2.environment-variable=LINK_REF",
                "dataprism.links.keys.lk1.environment-variable=OLD_LINK_REF").run(result -> {
                    assertThat(result).hasNotFailed();
                    Health health = result.getBean("linkKeysHealthIndicator", HealthIndicator.class).health();
                    assertThat(health.getStatus()).isEqualTo(Status.UP);
                    assertThat(health.getDetails()).containsEntry("current", "lk2")
                            .containsEntry("held", List.of("lk1", "lk2")).containsEntry("configured", 2)
                            .containsEntry("unresolved", 0L);
                    assertThat(health.getDetails().toString()).doesNotContain("LINK_REF");
                    assertThat(GAUGES.get(Metric.LINK_KEYS_LOADED).getAsDouble()).isEqualTo(2.0);
                });
    }

    @Test
    void a_key_the_member_can_no_longer_resolve_is_reported_down_and_by_count() {
        Set<String> configured = Set.of("lk1", "lk2");
        HealthIndicator indicator = LinkKeysHealth.indicator(() -> null, "lk2", configured);
        Health health = indicator.health();
        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry("held", List.of()).containsEntry("unresolved", 2L);
    }

    /** Real keys, built while every id resolves; then the named ids stop resolving, as after a bad rotation. */
    private static LinkKeys keysThatLose(Set<String> lost) {
        java.util.Set<String> gone = java.util.concurrent.ConcurrentHashMap.newKeySet();
        LinkKeys keys = new LinkKeys(id -> {
            if (gone.contains(id)) {
                throw new IllegalStateException("gone");
            }
            return (id + "-material-0123456789-0123456789-0123").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        }, "lk2", "pk", () -> Set.of("lk1", "lk2"));
        gone.addAll(lost);
        return keys;
    }

    @Test
    void a_partly_held_set_is_reported_down_with_the_held_ids_and_the_unresolved_count() {
        HealthIndicator indicator = LinkKeysHealth.indicator(() -> keysThatLose(Set.of("lk1")), "lk2",
                Set.of("lk1", "lk2"));
        Health health = indicator.health();
        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry("held", List.of("lk2")).containsEntry("unresolved", 1L);
    }

    @Test
    void a_held_set_that_lacks_the_current_id_is_reported_down_though_the_others_resolve() {
        HealthIndicator indicator = LinkKeysHealth.indicator(() -> keysThatLose(Set.of("lk2")), "lk2",
                Set.of("lk1", "lk2"));
        Health health = indicator.health();
        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry("held", List.of("lk1")).containsEntry("unresolved", 1L);
    }

    @Test
    void a_keys_object_that_throws_is_reported_down_not_thrown() {
        HealthIndicator indicator = LinkKeysHealth.indicator(() -> {
            throw new IllegalStateException("secret-detail");
        }, "lk1", Set.of("lk1"));
        Health health = indicator.health();
        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails().toString()).doesNotContain("secret-detail");
    }

    @Test
    void the_catalog_counts_are_published_as_gauges() {
        GAUGES.clear();
        context.run(result -> {
            assertThat(result).hasNotFailed();
            assertThat(GAUGES).containsKeys(Metric.CATALOG_DESCRIPTIONS_GENERATED,
                    Metric.CATALOG_SOURCE_NAMES_NONCONFORMING);
            assertThat(GAUGES.get(Metric.CATALOG_DESCRIPTIONS_GENERATED).getAsDouble()).isEqualTo(1.0);
        });
    }
}

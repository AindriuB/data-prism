package io.github.aindriub.dataprism.spring.boot;

import com.hazelcast.core.Hazelcast;
import io.github.aindriub.dataprism.annotations.PrivacyNamespace;
import io.github.aindriub.dataprism.core.JsonTreeScrubbingEngine;
import io.github.aindriub.dataprism.core.Metric;
import io.github.aindriub.dataprism.core.PrivacyMetrics;
import io.github.aindriub.dataprism.core.PrivacyContext;
import io.github.aindriub.dataprism.core.PrivacyScopeType;
import io.github.aindriub.dataprism.core.PseudonymisationVersion;
import io.github.aindriub.dataprism.core.SyntheticValueSource;
import io.github.aindriub.dataprism.hazelcast.CachingSyntheticValueSource;
import io.github.aindriub.dataprism.hazelcast.PrivacyCluster;
import io.github.aindriub.dataprism.reidentification.ReidentificationOutcome;
import io.github.aindriub.dataprism.reidentification.ReidentificationRequest;
import io.github.aindriub.dataprism.reidentification.ReidentificationService;
import io.github.aindriub.dataprism.security.AuthenticatedCaller;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.assertj.AssertableWebApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.Set;

import static io.github.aindriub.dataprism.spring.boot.OversightConfigurationTest.concat;
import static io.github.aindriub.dataprism.spring.boot.OversightConfigurationTest.runner;
import static org.assertj.core.api.Assertions.assertThat;

/** The pseudonymisation path feeds the re-identification index when, and only when, it is switched on. */
class ReidentificationIndexWiringTest {

    private static final String[] OPERATOR = {
            "dataprism.operator.enabled=true", "dataprism.operator.port=9443",
            "dataprism.operator.required-audience=operator", "dataprism.operator.required-scope=dataprism.operate"};
    private static final String[] INDEX = {
            "dataprism.hazelcast.reidentification-enabled=true",
            "dataprism.hazelcast.reidentification-controls-reference=REVIEWED_REIDENTIFICATION_CONTROLS"};
    private static final String[] ENABLED = {
            "dataprism.reidentification.enabled=true", "dataprism.reidentification.purposes[0]=fraud-review",
            "dataprism.reidentification.roles.requester[0]=REQUEST",
            "dataprism.reidentification.roles.approver[0]=APPROVE"};
    private static final String SUBJECT = "FIXTURE-SUBJECT-1";

    @AfterEach
    void shutDown() {
        Hazelcast.shutdownAll();
    }

    private static String[] all(String[]... groups) {
        String[] out = new String[0];
        for (String[] group : groups) out = concat(out, group);
        return out;
    }

    private static String produce(AssertableWebApplicationContext context) {
        PrivacyContext scope = new PrivacyContext("case:C1", PrivacyScopeType.INVESTIGATION, "DEFAULT",
                "investigation", Instant.parse("2100-01-01T00:00:00Z"), context.getBean(PseudonymisationVersion.class));
        return context.getBean(SyntheticValueSource.class).syntheticValue(SUBJECT, PrivacyNamespace.PERSON_NAME, scope);
    }

    @Test void enabled_wraps_the_source_in_every_engine_and_a_produced_value_resolves() {
        runner("embedded", all(INDEX, ENABLED, OPERATOR)).run(result -> {
            assertThat(result).hasNotFailed();
            PrivacyCluster cluster = result.getBean(PrivacyCluster.class);
            Object engineSource = ReflectionTestUtils.getField(result.getBean(JsonTreeScrubbingEngine.class), "synthetics");
            assertThat(engineSource).isInstanceOf(CachingSyntheticValueSource.class);
            assertThat(result.getBean(SyntheticValueSource.class)).isInstanceOf(CachingSyntheticValueSource.class);
            assertThat(ReflectionTestUtils.getField(engineSource, "cluster")).isSameAs(cluster);

            String synthetic = produce(result);
            ReidentificationService service = result.getBean(ReidentificationService.class);
            AuthenticatedCaller requester = new AuthenticatedCaller("req", "client-req", Set.of("requester"),
                    "fraud-review", "C1", null);
            AuthenticatedCaller approver = new AuthenticatedCaller("app", "client-app", Set.of("approver"),
                    "fraud-review", "C1", null);
            ReidentificationOutcome pending = service.request(requester, new ReidentificationRequest(
                    "case:C1", PrivacyNamespace.PERSON_NAME, synthetic, "fraud-review", "C1"));
            String id = ((ReidentificationOutcome.PendingApproval) pending).approvalId();
            assertThat(service.approve(approver, id)).isInstanceOf(ReidentificationOutcome.Approved.class);
            assertThat(service.collect(requester, id)).isEqualTo(new ReidentificationOutcome.Resolved(SUBJECT));
        });
    }

    @Test void disabled_does_not_wrap_and_writes_nothing_to_the_reverse_map() {
        runner("embedded", all(INDEX, OPERATOR)).run(result -> {
            assertThat(result).hasNotFailed();
            assertThat(result.getBean(SyntheticValueSource.class)).isNotInstanceOf(CachingSyntheticValueSource.class);
            produce(result);
            assertThat(result.getBean(PrivacyCluster.class).instance()
                    .getMap(PrivacyCluster.REIDENTIFICATION_MAP).size()).isZero();
        });
    }

    @Test void single_node_loads_no_wiring() {
        runner("single-node").run(result -> {
            assertThat(result).hasNotFailed();
            assertThat(result.getBean(SyntheticValueSource.class)).isNotInstanceOf(CachingSyntheticValueSource.class);
            assertThat(result).doesNotHaveBean("dataPrismReidentificationIndexFeed");
        });
    }

    @Test void a_source_that_already_caches_is_not_wrapped_twice() {
        runner("embedded", all(INDEX, ENABLED, OPERATOR)).withUserConfiguration(AlreadyCaching.class).run(result -> {
            assertThat(result).hasNotFailed();
            assertThat(result.getBean(SyntheticValueSource.class)).isSameAs(AlreadyCaching.SOURCE.get());
        });
    }

    @Test void an_application_supplied_source_is_wrapped_like_the_default() {
        runner("embedded", all(INDEX, ENABLED, OPERATOR)).withUserConfiguration(Supplied.class).run(result -> {
            assertThat(result).hasNotFailed();
            assertThat(result.getBean(SyntheticValueSource.class)).isInstanceOf(CachingSyntheticValueSource.class);
            assertThat(produce(result)).isEqualTo("supplied-value");
        });
    }

    @Test void the_wrapper_reports_to_the_applications_metrics_bean() {
        runner("embedded", all(INDEX, ENABLED, OPERATOR)).withUserConfiguration(RecordingMetrics.class).run(result -> {
            assertThat(result).hasNotFailed();
            RecordingMetrics.EVENTS.clear();
            String first = produce(result);
            assertThat(RecordingMetrics.EVENTS).contains(Metric.IDENTITY_CACHE_MISS);
            assertThat(produce(result)).isEqualTo(first);
            assertThat(RecordingMetrics.EVENTS).contains(Metric.IDENTITY_CACHE_HIT);
        });
    }

    @Test void a_cluster_failure_is_visible_as_a_metric_and_the_value_is_unchanged() {
        runner("embedded", all(INDEX, ENABLED, OPERATOR)).withUserConfiguration(RecordingMetrics.class).run(result -> {
            assertThat(result).hasNotFailed();
            String healthy = produce(result);
            result.getBean(PrivacyCluster.class).instance().shutdown();
            RecordingMetrics.EVENTS.clear();
            assertThat(produce(result)).isEqualTo(healthy);
            assertThat(RecordingMetrics.EVENTS).contains(Metric.IDENTITY_CACHE_MISS);
        });
    }

    @Configuration(proxyBeanMethods = false)
    static class RecordingMetrics {
        static final java.util.List<Metric> EVENTS = new java.util.concurrent.CopyOnWriteArrayList<>();
        @Bean static org.springframework.beans.factory.config.BeanPostProcessor recordingMetrics() {
            return new org.springframework.beans.factory.config.BeanPostProcessor() {
                @Override public Object postProcessBeforeInitialization(Object bean, String name) {
                    if (!(bean instanceof PrivacyMetrics)) return bean;
                    return new PrivacyMetrics() {
                        @Override public void increment(Metric metric) { EVENTS.add(metric); }
                        @Override public void increment(Metric metric, String source) { EVENTS.add(metric); }
                        @Override public void record(Metric metric, String source, java.time.Duration d) { EVENTS.add(metric); }
                    };
                }
            };
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class AlreadyCaching {
        static final java.util.concurrent.atomic.AtomicReference<SyntheticValueSource> SOURCE =
                new java.util.concurrent.atomic.AtomicReference<>();
        @Bean SyntheticValueSource appSource(org.springframework.beans.factory.ObjectProvider<PrivacyCluster> cluster) {
            SyntheticValueSource source = new CachingSyntheticValueSource((s, n, c) -> "x", cluster.getObject());
            SOURCE.set(source);
            return source;
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class Supplied {
        @Bean SyntheticValueSource appSource() { return (s, n, c) -> "supplied-value"; }
    }
}

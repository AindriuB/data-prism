package io.github.aindriub.dataprism.spring.boot;

import com.hazelcast.core.Hazelcast;
import io.github.aindriub.dataprism.hazelcast.ClusterSecurityStatus;
import io.github.aindriub.dataprism.hazelcast.ClusterSecurityStatus.Reason;
import io.github.aindriub.dataprism.hazelcast.HazelcastOversightState;
import io.github.aindriub.dataprism.hazelcast.PrivacyCluster;
import io.github.aindriub.dataprism.hazelcast.StalePauseFixture;
import io.github.aindriub.dataprism.oversight.OversightState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.actuate.endpoint.SecurityContext;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.availability.ApplicationAvailabilityAutoConfiguration;
import org.springframework.boot.health.actuate.endpoint.AdditionalHealthEndpointPath;
import org.springframework.boot.health.actuate.endpoint.HealthEndpointGroup;
import org.springframework.boot.health.actuate.endpoint.HealthEndpointGroups;
import org.springframework.boot.health.actuate.endpoint.HttpCodeStatusMapper;
import org.springframework.boot.health.actuate.endpoint.StatusAggregator;
import org.springframework.boot.health.autoconfigure.actuate.endpoint.AvailabilityProbesAutoConfiguration;
import org.springframework.boot.health.autoconfigure.actuate.endpoint.HealthEndpointAutoConfiguration;
import org.springframework.boot.health.autoconfigure.application.AvailabilityHealthContributorAutoConfiguration;
import org.springframework.boot.health.autoconfigure.contributor.HealthContributorAutoConfiguration;
import org.springframework.boot.health.autoconfigure.registry.HealthContributorRegistryAutoConfiguration;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 286: the {@code clusterSecurity} contributor is {@code DOWN} while this instance is not serving, sits in the
 * readiness group and not the liveness group, exists with a {@link PrivacyCluster} bean, and shows no address.
 */
class ClusterSecurityHealthTest {
    private static final String BEAN = "clusterSecurityHealthIndicator";
    private static final String[] KEYED_MEMBER = {
            "dataprism.operator.enabled=true", "dataprism.operator.port=9443",
            "dataprism.operator.required-audience=operator", "dataprism.operator.required-scope=dataprism.operate"};

    @AfterEach
    void shutDown() {
        Hazelcast.shutdownAll();
    }

    private static Health healthOf(ClusterSecurityStatus status) {
        return new ClusterSecurityHealth.Contributor(() -> status).health();
    }

    // ---- the status ------------------------------------------------------------------------------

    @Test void a_serving_member_is_up() {
        Health health = healthOf(new ClusterSecurityStatus(true, Reason.NONE, 3, 0));
        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("reason", "NONE").containsEntry("members", 3)
                .containsEntry("unverified", 0);
    }

    @Test void an_unverified_member_under_refuse_is_down_alone() {
        assertDown(new ClusterSecurityStatus(false, Reason.UNVERIFIED_MEMBER, 3, 1), "UNVERIFIED_MEMBER", 3, 1);
    }

    @Test void a_sentinel_mismatch_is_down_alone() {
        assertDown(new ClusterSecurityStatus(false, Reason.SENTINEL_MISMATCH, 2, 0), "SENTINEL_MISMATCH", 2, 0);
    }

    @Test void a_key_check_value_mismatch_is_down_alone() {
        assertDown(new ClusterSecurityStatus(false, Reason.KEY_CHECK_MISMATCH, 2, 0), "KEY_CHECK_MISMATCH", 2, 0);
    }

    @Test void a_pause_document_that_is_not_fresh_is_down_alone() {
        assertDown(new ClusterSecurityStatus(true, Reason.NONE, 2, 0).withPause(false, 90), "PAUSE_NOT_FRESH", 2, 0);
    }

    @Test void unreadable_cards_are_down() {
        assertDown(new ClusterSecurityStatus(false, Reason.CARDS_UNAVAILABLE, 1, 0), "CARDS_UNAVAILABLE", 1, 0);
    }

    private static void assertDown(ClusterSecurityStatus status, String reason, int members, int unverified) {
        Health health = healthOf(status);
        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsOnlyKeys("reason", "members", "unverified")
                .containsEntry("reason", reason).containsEntry("members", members)
                .containsEntry("unverified", unverified);
    }

    @Test void the_details_hold_no_address_uuid_key_or_pause_content() {
        for (Reason reason : Reason.values()) {
            ClusterSecurityStatus status = reason == Reason.NONE ? new ClusterSecurityStatus(true, reason, 4, 1)
                    : new ClusterSecurityStatus(false, reason, 4, 1, false, 120);
            String text = healthOf(status).getDetails().toString();
            assertThat(text).doesNotContainPattern("\\d+\\.\\d+\\.\\d+\\.\\d+")
                    .doesNotContainPattern("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
                    .doesNotContain("cluster-1", "name-1", "pause", "key", ":");
        }
    }

    // ---- the readiness group ---------------------------------------------------------------------

    private record Group(Predicate<String> members) implements HealthEndpointGroup {
        @Override public boolean isMember(String name) { return members.test(name); }
        @Override public boolean showComponents(SecurityContext context) { return false; }
        @Override public boolean showDetails(SecurityContext context) { return false; }
        @Override public StatusAggregator getStatusAggregator() { return StatusAggregator.getDefault(); }
        @Override public HttpCodeStatusMapper getHttpCodeStatusMapper() { return HttpCodeStatusMapper.DEFAULT; }
        @Override public AdditionalHealthEndpointPath getAdditionalPath() { return null; }
    }

    @Test void the_contributor_is_in_the_readiness_group_and_not_the_liveness_group() {
        HealthEndpointGroup primary = new Group(name -> true);
        HealthEndpointGroups groups = HealthEndpointGroups.of(primary, Map.of(
                "readiness", new Group("readinessState"::equals), "liveness", new Group("livenessState"::equals)));
        HealthEndpointGroups processed = new ClusterSecurityHealth.Contributor(
                () -> new ClusterSecurityStatus(true, Reason.NONE, 1, 0)).postProcessHealthEndpointGroups(groups);
        assertThat(processed.get("readiness").isMember("clusterSecurity")).isTrue();
        assertThat(processed.get("readiness").isMember("readinessState")).isTrue();
        assertThat(processed.get("readiness").isMember("db")).isFalse();
        assertThat(processed.get("liveness").isMember("clusterSecurity")).isFalse();
        assertThat(processed.get("liveness").isMember("livenessState")).isTrue();
        assertThat(processed.getPrimary()).isSameAs(primary);
    }

    /**
     * Boot adds the readiness group in a post-processor of its own that runs last, so DataPrismAutoConfiguration must
     * be ordered after AvailabilityProbesAutoConfiguration: this is Boot's real HealthEndpointGroups, with default
     * probes, in a context whose only DataPrism part is the real auto-configuration.
     */
    @Test void boots_real_readiness_group_has_the_contributor_and_the_liveness_group_does_not() {
        embedded().withConfiguration(AutoConfigurations.of(ApplicationAvailabilityAutoConfiguration.class,
                        HealthContributorRegistryAutoConfiguration.class, HealthContributorAutoConfiguration.class,
                        HealthEndpointAutoConfiguration.class, AvailabilityHealthContributorAutoConfiguration.class,
                        AvailabilityProbesAutoConfiguration.class))
                .run(result -> {
                    assertThat(result).hasNotFailed();
                    HealthEndpointGroups groups = result.getBean(HealthEndpointGroups.class);
                    assertThat(groups.getNames()).contains("readiness", "liveness");
                    assertThat(groups.get("readiness").isMember("clusterSecurity")).isTrue();
                    assertThat(groups.get("readiness").isMember("readinessState")).isTrue();
                    assertThat(groups.get("liveness").isMember("clusterSecurity")).isFalse();
                    assertThat(groups.get("liveness").isMember("livenessState")).isTrue();
                });
    }

    @Test void no_readiness_group_is_created_when_there_is_none() {
        HealthEndpointGroups groups = HealthEndpointGroups.of(new Group(name -> true), Map.of());
        assertThat(new ClusterSecurityHealth.Contributor(() -> new ClusterSecurityStatus(true, Reason.NONE, 1, 0))
                .postProcessHealthEndpointGroups(groups).getNames()).isEmpty();
    }

    // ---- wiring ----------------------------------------------------------------------------------

    private static WebApplicationContextRunner embedded(String... extra) {
        return OversightConfigurationTest.runner("embedded", KEYED_MEMBER)
                .withPropertyValues(ClusterConfigurationTest.KEYS).withPropertyValues(extra);
    }

    @Test void a_starter_built_member_on_join_mode_none_has_the_contributor_and_is_up() {
        embedded().run(result -> {
            assertThat(result).hasNotFailed().hasBean(BEAN);
            assertThat(result.getBean(BEAN, HealthIndicator.class).health().getStatus()).isEqualTo(Status.UP);
        });
    }

    @Test void a_starter_built_member_on_tcp_ip_has_the_contributor() {
        int port = ClusterConfigurationTest.freePort();
        embedded("dataprism.hazelcast.join.mode=tcp-ip", "dataprism.hazelcast.join.members[0]=127.0.0.1:" + port,
                "dataprism.hazelcast.member.port=" + port, "dataprism.hazelcast.member.interface=127.0.0.1",
                "dataprism.hazelcast.trusted-network=true")
                .run(result -> assertThat(result).hasNotFailed().hasBean(BEAN));
    }

    @Test void an_application_supplied_cluster_has_the_contributor() {
        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(DataPrismAutoConfiguration.class))
                .withUserConfiguration(ClusterLegacyGaugeTest.RecordingMetrics.class,
                        ClusterConfigurationTest.SuppliedCluster.class)
                .withPropertyValues(OversightConfigurationTest.base("embedded"))
                .run(result -> {
                    assertThat(result).hasNotFailed().hasBean(BEAN);
                    assertThat(result.getBean(BEAN, HealthIndicator.class).health().getStatus())
                            .isEqualTo(Status.UP);
                });
    }

    @Test void single_node_has_no_contributor() {
        OversightConfigurationTest.runner("single-node")
                .run(result -> assertThat(result).hasNotFailed().doesNotHaveBean(BEAN));
    }

    /**
     * The serving gate stays open while the pause document is stale, so recovery can happen; the contributor reads
     * the freshness from the oversight state, which the cluster's own status does not carry.
     */
    @Test void the_pause_freshness_is_read_from_the_hazelcast_oversight_state() {
        // A pause lease is at least five seconds and no test here sleeps, so a stale document is covered by the
        // withPause(false, ..) status test above; this one checks which status is read.
        embedded().run(result -> {
            assertThat(result).hasNotFailed();
            PrivacyCluster cluster = result.getBean(PrivacyCluster.class);
            OversightState oversight = result.getBean(OversightState.class);
            assertThat(oversight).isInstanceOf(HazelcastOversightState.class);
            assertThat(cluster.securityStatus().serving()).isTrue();
            ClusterSecurityStatus read = ((HazelcastOversightState) oversight).securityStatus();
            assertThat(ClusterSecurityHealth.statusOf(cluster, oversight).reason()).isEqualTo(read.reason());
            assertThat(result.getBean(BEAN, HealthIndicator.class).health().getDetails())
                    .containsEntry("reason", read.reason().name());
        });
    }

    /**
     * The cluster's own gate stays open while the pause document is stale; only the oversight state knows. A keyless
     * member on a fake clock lets the lease lapse with no sleep: the cluster still serves, the contributor is DOWN.
     */
    @Test void a_stale_pause_document_is_down_while_the_cluster_itself_still_serves() {
        try (StalePauseFixture fixture = new StalePauseFixture(ClusterConfigurationTest.uniqueName())) {
            // Through the production bean method, so a bean that ignored the oversight state would fail here.
            org.springframework.beans.factory.support.StaticListableBeanFactory beans =
                    new org.springframework.beans.factory.support.StaticListableBeanFactory();
            beans.addBean("cluster", fixture.cluster());
            beans.addBean("oversight", fixture.state());
            ClusterSecurityHealth.Contributor contributor = new ClusterSecurityHealth()
                    .clusterSecurityHealthIndicator(beans.getBeanProvider(PrivacyCluster.class),
                            beans.getBeanProvider(OversightState.class));
            assertThat(contributor.health().getStatus()).as("before the lease lapses").isEqualTo(Status.UP);
            fixture.advancePastLease();
            assertThat(fixture.cluster().securityStatus().serving()).isTrue();
            Health health = contributor.health();
            assertThat(health.getStatus()).isEqualTo(Status.DOWN);
            assertThat(health.getDetails()).containsEntry("reason", "PAUSE_NOT_FRESH");
        }
    }

    @Test void another_oversight_state_falls_back_to_the_cluster_status() {
        embedded().run(result -> {
            PrivacyCluster cluster = result.getBean(PrivacyCluster.class);
            OversightState other = org.mockito.Mockito.mock(OversightState.class);
            assertThat(ClusterSecurityHealth.statusOf(cluster, other)).isEqualTo(cluster.securityStatus());
            assertThat(ClusterSecurityHealth.statusOf(cluster, null)).isEqualTo(cluster.securityStatus());
        });
    }

    @Test void no_cluster_and_no_hazelcast_oversight_state_is_not_serving() {
        ClusterSecurityStatus status = ClusterSecurityHealth.statusOf(null, null);
        assertThat(status.serving()).isFalse();
        assertThat(status.reason()).isEqualTo(Reason.CARDS_UNAVAILABLE);
    }

    // ---- one rule for "the starter's cluster" ----------------------------------------------------

    @Test void both_callers_agree_on_a_starter_cluster_an_application_cluster_under_its_name_and_another() {
        embedded().run(result -> {
            assertThat(result).hasNotFailed();
            assertAgree(result.getSourceApplicationContext().getAutowireCapableBeanFactory(), "dataPrismPrivacyCluster",
                    true);
        });
        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(DataPrismAutoConfiguration.class))
                .withUserConfiguration(ClusterLegacyGaugeTest.RecordingMetrics.class,
                        ClusterConfigurationTest.DefaultNamedCluster.class)
                .withPropertyValues(OversightConfigurationTest.base("embedded"))
                .run(result -> {
                    assertThat(result).hasNotFailed();
                    assertAgree(result.getSourceApplicationContext().getAutowireCapableBeanFactory(),
                            "dataPrismPrivacyCluster", false);
                });
        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(DataPrismAutoConfiguration.class))
                .withUserConfiguration(ClusterLegacyGaugeTest.RecordingMetrics.class,
                        ClusterConfigurationTest.SuppliedCluster.class)
                .withPropertyValues(OversightConfigurationTest.base("embedded"))
                .run(result -> {
                    assertThat(result).hasNotFailed();
                    assertAgree(result.getSourceApplicationContext().getAutowireCapableBeanFactory(),
                            "suppliedCluster", false);
                });
    }

    private static void assertAgree(Object factory, String name, boolean expected) {
        ConfigurableListableBeanFactory beans = (ConfigurableListableBeanFactory) factory;
        assertThat(ClusterBackedState.builtHere(beans, name)).as("builtHere " + name).isEqualTo(expected);
        assertThat(PropertiesValidation.isFrameworkCluster(beans, name)).as("isFrameworkCluster " + name)
                .isEqualTo(expected);
        assertThat(Set.of(beans.getBeanNamesForType(PrivacyCluster.class))).contains(name);
    }
}

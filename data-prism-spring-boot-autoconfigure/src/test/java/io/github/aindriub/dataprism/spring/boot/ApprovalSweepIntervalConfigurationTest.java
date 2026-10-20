package io.github.aindriub.dataprism.spring.boot;

import com.hazelcast.core.Hazelcast;
import io.github.aindriub.dataprism.hazelcast.PrivacyCluster;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The interval at which a member sweeps pending approvals is configuration: bound, validated, frozen and
 * delivered to the cluster that sweeps. It is fixed when the cluster is built, so setting it where this
 * application builds no cluster is refused rather than ignored.
 */
class ApprovalSweepIntervalConfigurationTest {

    private static final String KEY = "dataprism.hazelcast.approval-sweep-interval";

    @AfterEach
    void shutDown() {
        Hazelcast.shutdownAll();
    }

    private static String rootMessage(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage();
    }

    private static void refuses(String code, String topology, String... properties) {
        OversightConfigurationTest.runner(topology, properties).run(result -> {
            assertThat(result).hasFailed();
            assertThat(rootMessage(result.getStartupFailure())).startsWith(code + ":");
        });
    }

    @Test void unset_the_cluster_sweeps_every_five_minutes() {
        OversightConfigurationTest.runner("embedded").run(result -> {
            assertThat(result).hasNotFailed();
            assertThat(result.getBean(DataPrismProperties.class).getHazelcast().getApprovalSweepInterval())
                    .isEqualTo(Duration.ofMinutes(5));
            assertThat(result.getBean(PrivacyCluster.class).approvalSweepInterval()).isEqualTo(Duration.ofMinutes(5));
        });
    }

    @Test void a_bound_interval_reaches_the_cluster() {
        OversightConfigurationTest.runner("embedded", KEY + "=PT30S").run(result -> {
            assertThat(result).hasNotFailed();
            assertThat(result.getBean(PrivacyCluster.class).approvalSweepInterval()).isEqualTo(Duration.ofSeconds(30));
        });
    }

    @Test void the_limits_of_the_range_are_accepted() {
        for (String edge : new String[] {"PT1S", "P1D"}) {
            OversightConfigurationTest.runner("embedded", KEY + "=" + edge).run(result -> {
                assertThat(result).hasNotFailed();
                assertThat(result.getBean(PrivacyCluster.class).approvalSweepInterval())
                        .isEqualTo(Duration.parse(edge));
            });
            Hazelcast.shutdownAll();
        }
    }

    @Test void a_zero_negative_or_out_of_range_interval_is_refused() {
        for (String bad : new String[] {"PT0S", "-PT1S", "PT0.5S", "P1DT1S"}) {
            refuses("INVALID_APPROVAL_SWEEP_INTERVAL", "embedded", KEY + "=" + bad);
        }
    }

    @Test void a_value_that_is_not_a_duration_fails_binding() {
        OversightConfigurationTest.runner("embedded", KEY + "=soon").run(result -> assertThat(result).hasFailed());
    }

    @Test void single_node_refuses_the_interval() {
        refuses("CLUSTER_SETTINGS_IGNORED", "single-node", KEY + "=PT30S");
    }

    @Test void an_application_supplied_cluster_refuses_the_interval() {
        // No cluster-name, join or member settings, so only the interval can be what is refused.
        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(DataPrismAutoConfiguration.class))
                .withUserConfiguration(DataPrismAutoConfigurationTest.ReviewedHttpIntegrations.class,
                        ClusterConfigurationTest.SuppliedCluster.class)
                .withPropertyValues(OversightConfigurationTest.base("embedded"))
                .withPropertyValues(KEY + "=PT30S")
                .run(result -> {
                    assertThat(result).hasFailed();
                    assertThat(rootMessage(result.getStartupFailure())).startsWith("CLUSTER_SETTINGS_IGNORED:");
                });
    }
}

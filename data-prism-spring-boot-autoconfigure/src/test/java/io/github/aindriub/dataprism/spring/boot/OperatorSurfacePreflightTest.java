package io.github.aindriub.dataprism.spring.boot;

import com.hazelcast.core.Hazelcast;
import io.github.aindriub.dataprism.reidentification.ReidentificationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.FilteredClassLoader;

import static io.github.aindriub.dataprism.spring.boot.OversightConfigurationTest.runner;
import static org.assertj.core.api.Assertions.assertThat;

/** Task 105: the two refusals the operator surface depends on. */
class OperatorSurfacePreflightTest {

    @AfterEach
    void shutDownEveryClusterMemberThisTestStarted() {
        Hazelcast.shutdownAll();
    }

    private static String rootMessage(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage();
    }

    @Test void refuses_an_operator_port_equal_to_the_management_port() {
        runner("single-node", "server.port=9001", "management.server.port=9443",
                "dataprism.operator.enabled=true", "dataprism.operator.port=9443",
                "dataprism.operator.required-audience=operator", "dataprism.operator.required-scope=s")
                .run(result -> {
                    assertThat(result).hasFailed();
                    assertThat(rootMessage(result.getStartupFailure())).startsWith("OPERATOR_PORT_SHARED:");
                });
    }

    @Test void accepts_an_operator_port_distinct_from_server_and_management_ports() {
        runner("single-node", "server.port=9001", "management.server.port=9002",
                "dataprism.operator.enabled=true", "dataprism.operator.port=9443",
                "dataprism.operator.required-audience=operator", "dataprism.operator.required-scope=s")
                .run(result -> assertThat(result).hasNotFailed());
    }

    @Test void refuses_enabled_reidentification_when_the_module_is_absent_from_the_classpath() {
        runner("embedded", "dataprism.reidentification.enabled=true",
                "dataprism.reidentification.purposes[0]=fraud-review",
                "dataprism.reidentification.roles.approver[0]=APPROVE",
                "dataprism.hazelcast.reidentification-enabled=true",
                "dataprism.hazelcast.reidentification-controls-reference=REVIEWED_REIDENTIFICATION_CONTROLS",
                "dataprism.operator.enabled=true", "dataprism.operator.port=9443",
                "dataprism.operator.required-audience=operator", "dataprism.operator.required-scope=s")
                .withClassLoader(new FilteredClassLoader(ReidentificationService.class))
                .run(result -> {
                    assertThat(result).hasFailed();
                    assertThat(rootMessage(result.getStartupFailure()))
                            .startsWith("REIDENTIFICATION_MODULE_MISSING:");
                });
    }

    @Test void a_deployment_without_reidentification_does_not_need_the_module() {
        runner("single-node")
                .withClassLoader(new FilteredClassLoader(ReidentificationService.class))
                .run(result -> assertThat(result).hasNotFailed());
    }

    @Test void refuses_an_operator_audience_equal_to_the_mcp_audience() {
        runner("single-node", "server.port=9001", "dataprism.security.jwt.audience=shared-audience",
                "dataprism.operator.enabled=true", "dataprism.operator.port=9443",
                "dataprism.operator.required-audience=shared-audience", "dataprism.operator.required-scope=s")
                .run(result -> {
                    assertThat(result).hasFailed();
                    assertThat(rootMessage(result.getStartupFailure())).startsWith("OPERATOR_AUDIENCE_SHARED:");
                });
    }

    @Test void accepts_an_operator_audience_distinct_from_the_mcp_audience() {
        runner("single-node", "server.port=9001", "dataprism.security.jwt.audience=mcp-audience",
                "dataprism.operator.enabled=true", "dataprism.operator.port=9443",
                "dataprism.operator.required-audience=operator-audience", "dataprism.operator.required-scope=s")
                .run(result -> assertThat(result).hasNotFailed());
    }
}

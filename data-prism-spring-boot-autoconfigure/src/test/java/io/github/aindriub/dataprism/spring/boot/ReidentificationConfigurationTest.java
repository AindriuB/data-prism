package io.github.aindriub.dataprism.spring.boot;

import com.hazelcast.core.Hazelcast;
import io.github.aindriub.dataprism.reidentification.ReidentificationPolicy;
import io.github.aindriub.dataprism.reidentification.ReidentificationService;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static io.github.aindriub.dataprism.spring.boot.OversightConfigurationTest.concat;
import static io.github.aindriub.dataprism.spring.boot.OversightConfigurationTest.runner;
import static org.assertj.core.api.Assertions.assertThat;

/** {@code dataprism.reidentification.*}: bound, validated, and built only when switched on. */
class ReidentificationConfigurationTest {

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

    @AfterEach
    void shutDownEveryClusterMemberThisTestStarted() {
        Hazelcast.shutdownAll();
    }

    private static String rootMessage(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage();
    }

    private static void refuses(String code, String... properties) {
        runner("embedded", properties).run(result -> {
            assertThat(result).hasFailed();
            assertThat(rootMessage(result.getStartupFailure())).startsWith(code + ":");
        });
    }

    private static String[] all(String[]... groups) {
        String[] out = new String[0];
        for (String[] group : groups) out = concat(out, group);
        return out;
    }

    // ---- refusal codes -------------------------------------------------------------------------

    @Test void refuses_reidentification_when_the_index_is_disabled() {
        refuses("REIDENTIFICATION_INDEX_DISABLED", all(ENABLED, OPERATOR));
    }
    @Test void refuses_reidentification_with_no_purposes() {
        refuses("EMPTY_REIDENTIFICATION_PURPOSES", all(INDEX, OPERATOR,
                new String[] {"dataprism.reidentification.enabled=true",
                        "dataprism.reidentification.roles.approver[0]=APPROVE"}));
    }
    @Test void refuses_four_eyes_reidentification_with_no_approver_role() {
        refuses("NO_REIDENTIFICATION_APPROVER", all(INDEX, OPERATOR,
                new String[] {"dataprism.reidentification.enabled=true",
                        "dataprism.reidentification.purposes[0]=fraud-review",
                        "dataprism.reidentification.roles.requester[0]=REQUEST"}));
    }
    @Test void refuses_reidentification_without_the_operator_surface() {
        refuses("REIDENTIFICATION_REQUIRES_OPERATOR_SURFACE", all(INDEX, ENABLED));
    }
    @Test void refuses_reidentification_without_an_embedded_cluster() {
        runner("single-node", all(INDEX, ENABLED, OPERATOR)).run(result -> {
            assertThat(result).hasFailed();
            assertThat(rootMessage(result.getStartupFailure())).startsWith("REIDENTIFICATION_REQUIRES_CLUSTER:");
        });
    }
    @Test void refuses_a_non_positive_reidentification_approval_ttl() {
        refuses("INVALID_OVERSIGHT_LIMIT", all(INDEX, ENABLED, OPERATOR,
                new String[] {"dataprism.reidentification.approval-ttl=PT0S"}));
    }
    @Test void refuses_a_non_positive_reidentification_max_pending_per_requester() {
        refuses("INVALID_OVERSIGHT_LIMIT", all(INDEX, ENABLED, OPERATOR,
                new String[] {"dataprism.reidentification.max-pending-per-requester=0"}));
    }

    // ---- defaults and wiring -------------------------------------------------------------------

    @Test void four_eyes_defaults_to_true_and_the_documented_defaults_hold() {
        runner("single-node").run(result -> {
            assertThat(result).hasNotFailed();
            ReidentificationProperties r = result.getBean(DataPrismProperties.class).getReidentification();
            assertThat(r.isEnabled()).isFalse();
            assertThat(r.isFourEyes()).isTrue();
            assertThat(r.getApprovalTtl()).isEqualTo(java.time.Duration.ofMinutes(15));
            assertThat(r.getMaxPendingPerRequester()).isEqualTo(5);
        });
    }

    @Test void a_four_eyes_policy_is_bound_when_enabled() {
        runner("embedded", all(INDEX, ENABLED, OPERATOR)).run(result -> {
            assertThat(result).hasNotFailed();
            ReidentificationPolicy policy = result.getBean(ReidentificationPolicy.class);
            assertThat(policy.fourEyes()).isTrue();
            assertThat(policy.maxPendingPerRequester()).isEqualTo(5);
            assertThat(policy.purposes()).containsExactly("fraud-review");
        });
    }

    @Test void no_service_bean_exists_unless_reidentification_is_enabled() {
        runner("embedded").run(result -> {
            assertThat(result).hasNotFailed();
            assertThat(result).doesNotHaveBean(ReidentificationService.class);
        });
    }

    @Test void the_service_exists_when_enabled_and_the_tool_list_is_unchanged() {
        runner("embedded", all(INDEX, ENABLED, OPERATOR)).run(result -> {
            assertThat(result).hasNotFailed();
            assertThat(result).hasSingleBean(ReidentificationService.class);
            assertThat(result.getBean(McpSyncServer.class).listTools())
                    .extracting(McpSchema.Tool::name)
                    .containsExactlyInAnyOrder("get_entity_context", "compare_entity_sources");
        });
    }
}

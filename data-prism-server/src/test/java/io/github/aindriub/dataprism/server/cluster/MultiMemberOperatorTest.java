package io.github.aindriub.dataprism.server.cluster;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.aindriub.dataprism.hazelcast.PrivacyCluster;
import io.github.aindriub.dataprism.server.operator.OperatorHarness;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.http.HttpResponse;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 134: the state 0.4.0 described as cluster-wide really is shared once members have joined one
 * embedded cluster. Every member is the real server on real ports; a token is only valid on the
 * member that minted it (each has its own signing key), so each call uses that member's own token
 * for the same principal and case. Synthetic subjects only.
 */
class MultiMemberOperatorTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PRINCIPAL = "analyst-cluster";
    private static final String CASE = "CASE-CLUSTER";
    private static final String SUBJECT = "subject-4711";
    private static final String TOOL_ARGUMENT = "--dataprism.oversight.approval-required-tools[0]=get_entity_context";

    @TempDir
    Path tempDir;

    private static McpSyncClient client(OperatorHarness member) throws Exception {
        return member.mcpClient(member.mcpToken(PRINCIPAL, CASE), java.time.Duration.ofSeconds(180));
    }

    private static void post(OperatorHarness member, String path, String body) throws Exception {
        HttpResponse<String> response = member.operator("POST", path, member.operatorToken("operator-pause"), body);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    }

    @Test
    void aPauseOnOneMemberRefusesOnTheOtherAndAResumeOnTheOtherLiftsIt() throws Exception {
        try (ClusterMembers cluster = ClusterMembers.start(tempDir, 2)) {
            OperatorHarness a = cluster.member(0);
            OperatorHarness b = cluster.member(1);
            try (McpSyncClient onA = client(a); McpSyncClient onB = client(b)) {
                assertThat(a.getEntityContext(onA, SUBJECT).isError()).isNotEqualTo(Boolean.TRUE);

                post(a, "/operator/pause", "{\"target\":\"ALL\"}");
                McpSchema.CallToolResult paused = b.getEntityContext(onB, SUBJECT);
                assertThat(paused.isError()).isTrue();
                assertThat(OperatorHarness.text(paused)).isEqualTo("DATAPRISM_PAUSED");

                post(b, "/operator/resume", "{\"target\":\"ALL\"}");
                assertThat(a.getEntityContext(onA, SUBJECT).isError()).isNotEqualTo(Boolean.TRUE);

                post(b, "/operator/pause", "{\"target\":\"TOOL\",\"name\":\"get_entity_context\"}");
                McpSchema.CallToolResult toolPaused = a.getEntityContext(onA, SUBJECT);
                assertThat(toolPaused.isError()).isTrue();
                assertThat(OperatorHarness.text(toolPaused)).isEqualTo("TOOL_PAUSED");
            }
        }
    }

    @Test
    void anApprovalGivenOnOneMemberAdmitsExactlyOneRetryOnAnotherAndIsConsumedClusterWide() throws Exception {
        try (ClusterMembers cluster = ClusterMembers.start(tempDir, 2, TOOL_ARGUMENT)) {
            OperatorHarness a = cluster.member(0);
            OperatorHarness b = cluster.member(1);
            try (McpSyncClient onA = client(a); McpSyncClient onB = client(b)) {
                McpSchema.CallToolResult first = a.getEntityContext(onA, SUBJECT);
                assertThat(first.isError()).isTrue();
                String refusal = OperatorHarness.text(first);
                assertThat(refusal).startsWith("APPROVAL_REQUIRED approvalId=");
                String approvalId = refusal.substring(refusal.indexOf('=') + 1).trim();

                assertThat(b.operator("POST", "/operator/approvals/" + approvalId + "/approve",
                        b.operatorToken("operator-two"), null).statusCode()).isEqualTo(200);

                McpSchema.CallToolResult retryOnB = b.getEntityContext(onB, SUBJECT);
                assertThat(retryOnB.isError()).isNotEqualTo(Boolean.TRUE);

                McpSchema.CallToolResult repeatOnA = a.getEntityContext(onA, SUBJECT);
                assertThat(repeatOnA.isError()).isTrue();
                assertThat(OperatorHarness.text(repeatOnA)).startsWith("APPROVAL_REQUIRED approvalId=")
                        .doesNotContain(approvalId);
            }
        }
    }

    /**
     * The per-subject read budget is not a property in 0.4.1: it is {@code RequestLimits.DEFAULT},
     * 100 reads of one subject within a scope. The budget is therefore exercised at its real size.
     */
    @Test
    void theReadBudgetIsSharedSoTheHundredAndFirstReadIsRefusedOnEitherMember() throws Exception {
        try (ClusterMembers cluster = ClusterMembers.start(tempDir, 2)) {
            OperatorHarness a = cluster.member(0);
            OperatorHarness b = cluster.member(1);
            try (McpSyncClient onA = client(a); McpSyncClient onB = client(b)) {
                for (int i = 0; i < 60; i++) {
                    assertThat(a.getEntityContext(onA, SUBJECT).isError()).as("read %d on A", i)
                            .isNotEqualTo(Boolean.TRUE);
                }
                for (int i = 0; i < 40; i++) {
                    assertThat(b.getEntityContext(onB, SUBJECT).isError()).as("read %d on B", 60 + i)
                            .isNotEqualTo(Boolean.TRUE);
                }
                McpSchema.CallToolResult onBRefused = b.getEntityContext(onB, SUBJECT);
                assertThat(onBRefused.isError()).isTrue();
                assertThat(OperatorHarness.text(onBRefused)).contains("SCOPE_READ_BUDGET");
                McpSchema.CallToolResult onARefused = a.getEntityContext(onA, SUBJECT);
                assertThat(onARefused.isError()).isTrue();
                assertThat(OperatorHarness.text(onARefused)).contains("SCOPE_READ_BUDGET");
            }
        }
    }

    @Test
    void theCallerRateLimitIsSharedSoTheThirdCallIsRefusedOnEitherMember() throws Exception {
        try (ClusterMembers cluster = ClusterMembers.start(tempDir, 2,
                "--dataprism.oversight.caller-rate-limit.requests=2",
                "--dataprism.oversight.caller-rate-limit.window=PT1H")) {
            OperatorHarness a = cluster.member(0);
            OperatorHarness b = cluster.member(1);
            try (McpSyncClient onA = client(a); McpSyncClient onB = client(b)) {
                assertThat(a.getEntityContext(onA, SUBJECT).isError()).isNotEqualTo(Boolean.TRUE);
                assertThat(b.getEntityContext(onB, SUBJECT).isError()).isNotEqualTo(Boolean.TRUE);

                McpSchema.CallToolResult thirdOnA = a.getEntityContext(onA, SUBJECT);
                assertThat(thirdOnA.isError()).isTrue();
                assertThat(OperatorHarness.text(thirdOnA)).isEqualTo("CALLER_RATE_LIMITED");
                McpSchema.CallToolResult thirdOnB = b.getEntityContext(onB, SUBJECT);
                assertThat(thirdOnB.isError()).isTrue();
                assertThat(OperatorHarness.text(thirdOnB)).isEqualTo("CALLER_RATE_LIMITED");
            }
        }
    }

    @Test
    void aPseudonymFromOneMemberIsReidentifiedViaAnotherAndIsTheSameOnBoth() throws Exception {
        try (ClusterMembers cluster = ClusterMembers.start(tempDir, 2)) {
            OperatorHarness a = cluster.member(0);
            OperatorHarness b = cluster.member(1);
            try (McpSyncClient onA = client(a); McpSyncClient onB = client(b)) {
                String synthetic = customerName(a, onA);
                assertThat(synthetic).isNotBlank().isNotEqualTo("Fixture Person");

                HttpResponse<String> requested = b.operator("POST", "/operator/reidentifications",
                        b.operatorToken("requester-cluster", "requester"),
                        "{\"scopeId\":\"case:" + CASE + "\",\"namespace\":\"PERSON_NAME\",\"syntheticValue\":\""
                                + synthetic + "\",\"purpose\":\"fraud-review\",\"caseId\":\"" + CASE + "\"}");
                assertThat(requested.statusCode()).as(requested.body()).isEqualTo(202);
                String approvalId = JSON.readTree(requested.body()).path("approvalId").asText();

                assertThat(a.operator("POST", "/operator/reidentifications/" + approvalId + "/approve",
                        a.operatorToken("approver-cluster", "approver"), null).statusCode()).isEqualTo(200);

                HttpResponse<String> collected = b.operator("GET", "/operator/reidentifications/" + approvalId,
                        b.operatorToken("requester-cluster", "requester"), null);
                assertThat(collected.statusCode()).as(collected.body()).isEqualTo(200);
                JsonNode body = JSON.readTree(collected.body());
                assertThat(body.path("status").asText()).isEqualTo("RESOLVED");
                assertThat(body.path("subjectId").asText()).isEqualTo(SUBJECT);

                assertThat(customerName(b, onB)).isEqualTo(synthetic);
            }
        }
    }

    /**
     * Pins the map backup count of 1: the member that owns the pause key is killed without a
     * graceful shutdown, so no partition migrates and only the backup copy can keep the flag. Losing
     * the owner and the backup together loses the flag and reopens a paused path; task 135
     * documents that.
     */
    @Test
    void aPauseSurvivesTheUngracefulLossOfItsOwnerAmongThreeMembers() throws Exception {
        try (ClusterMembers cluster = ClusterMembers.start(tempDir, 3)) {
            int owner = cluster.ownerOf(PrivacyCluster.OVERSIGHT_MAP, "all");
            int first = (owner + 1) % 3;
            int second = (owner + 2) % 3;
            OperatorHarness survivorOne = cluster.member(first);
            OperatorHarness survivorTwo = cluster.member(second);
            try (McpSyncClient onOne = client(survivorOne); McpSyncClient onTwo = client(survivorTwo)) {
                post(survivorOne, "/operator/pause", "{\"target\":\"ALL\"}");
                assertThat(OperatorHarness.text(survivorTwo.getEntityContext(onTwo, SUBJECT)))
                        .isEqualTo("DATAPRISM_PAUSED");

                assertThat(cluster.ownerOf(PrivacyCluster.OVERSIGHT_MAP, "all"))
                        .as("pause-key owner moved before the terminate").isEqualTo(owner);
                cluster.terminate(owner);
                cluster.awaitMembers(2);

                McpSchema.CallToolResult oneResult = survivorOne.getEntityContext(onOne, SUBJECT);
                assertThat(oneResult.isError()).isTrue();
                assertThat(OperatorHarness.text(oneResult)).isEqualTo("DATAPRISM_PAUSED");
                McpSchema.CallToolResult twoResult = survivorTwo.getEntityContext(onTwo, SUBJECT);
                assertThat(twoResult.isError()).isTrue();
                assertThat(OperatorHarness.text(twoResult)).isEqualTo("DATAPRISM_PAUSED");
            }
        }
    }

    private static String customerName(OperatorHarness member, McpSyncClient client) throws Exception {
        McpSchema.CallToolResult result = member.getEntityContext(client, SUBJECT);
        assertThat(result.isError()).as(OperatorHarness.text(result)).isNotEqualTo(Boolean.TRUE);
        return JSON.readTree(OperatorHarness.text(result)).path("entity").path("customerName").asText();
    }
}

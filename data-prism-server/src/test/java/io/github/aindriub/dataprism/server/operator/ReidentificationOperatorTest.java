package io.github.aindriub.dataprism.server.operator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.aindriub.dataprism.audit.AuditEvent;
import io.github.aindriub.dataprism.annotations.PrivacyNamespace;
import io.github.aindriub.dataprism.core.PrivacyContext;
import io.github.aindriub.dataprism.core.PrivacyScopeType;
import io.github.aindriub.dataprism.core.PseudonymisationVersion;
import io.github.aindriub.dataprism.core.SyntheticValueSource;
import io.github.aindriub.dataprism.hazelcast.CachingSyntheticValueSource;
import io.github.aindriub.dataprism.hazelcast.PrivacyCluster;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** Task 105: re-identification over the operator port, against a real embedded cluster. */
class ReidentificationOperatorTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SUBJECT = "subject-4711";

    @TempDir
    static Path tempDir;
    static OperatorHarness app;

    @BeforeAll
    static void start() throws Exception {
        app = OperatorHarness.start(tempDir, true,
                "--dataprism.reidentification.max-pending-per-requester=2");
    }

    @AfterAll
    static void stop() {
        if (app != null) app.close();
    }

    private static JsonNode body(HttpResponse<String> response) throws Exception {
        return JSON.readTree(response.body());
    }

    /**
     * A real pseudonym for {@code SUBJECT} in {@code case:<caseId>}, entered in the re-identification
     * index the way an index-feeding deployment would: the application's own generator, wrapped by
     * {@code CachingSyntheticValueSource} over the application's own cluster.
     */
    private static String synthetic(String caseId) {
        CachingSyntheticValueSource indexing = new CachingSyntheticValueSource(
                app.context.getBean(SyntheticValueSource.class), app.context.getBean(PrivacyCluster.class));
        PrivacyContext scope = new PrivacyContext("case:" + caseId, PrivacyScopeType.INVESTIGATION, "DEFAULT",
                "investigation", Instant.parse("2100-01-01T00:00:00Z"), app.context.getBean(PseudonymisationVersion.class));
        return indexing.syntheticValue(SUBJECT, PrivacyNamespace.PERSON_NAME, scope);
    }

    private static String requestBody(String caseId, String synthetic, String purpose) {
        return "{\"scopeId\":\"case:" + caseId + "\",\"namespace\":\"PERSON_NAME\",\"syntheticValue\":\""
                + synthetic + "\",\"purpose\":\"" + purpose + "\",\"caseId\":\"" + caseId + "\"}";
    }

    @Test
    void requestApproveCollectResolvesOnceAndOnlyTheCollectCarriesTheSubjectId() throws Exception {
        String synthetic = synthetic("CASE-R1");
        assertThat(synthetic).isNotBlank().doesNotContain(SUBJECT);
        String requester = app.operatorToken("requester-1", "requester");
        String approver = app.operatorToken("approver-1", "approver");
        app.audit.clear();

        HttpResponse<String> requested = app.operator("POST", "/operator/reidentifications", requester,
                requestBody("CASE-R1", synthetic, "fraud-review"));
        assertThat(requested.statusCode()).isEqualTo(202);
        assertThat(requested.body()).doesNotContain(SUBJECT);
        String approvalId = body(requested).path("approvalId").asText();
        assertThat(approvalId).isNotBlank();

        HttpResponse<String> early = app.operator("GET", "/operator/reidentifications/" + approvalId, requester,
                null);
        assertThat(early.statusCode()).isEqualTo(409);
        assertThat(early.body()).isEqualTo("{\"code\":\"APPROVAL_NOT_APPROVED\"}");

        HttpResponse<String> approved = app.operator("POST", "/operator/reidentifications/" + approvalId
                + "/approve", approver, null);
        assertThat(approved.statusCode()).isEqualTo(200);
        assertThat(approved.body()).doesNotContain(SUBJECT);

        HttpResponse<String> stranger = app.operator("GET", "/operator/reidentifications/" + approvalId,
                approver, null);
        assertThat(stranger.statusCode()).isEqualTo(403);
        assertThat(stranger.body()).isEqualTo("{\"code\":\"NOT_REQUESTER\"}");

        HttpResponse<String> collected = app.operator("GET", "/operator/reidentifications/" + approvalId,
                requester, null);
        assertThat(collected.statusCode()).isEqualTo(200);
        assertThat(body(collected).path("status").asText()).isEqualTo("RESOLVED");
        assertThat(body(collected).path("subjectId").asText()).isEqualTo(SUBJECT);

        HttpResponse<String> again = app.operator("GET", "/operator/reidentifications/" + approvalId, requester,
                null);
        assertThat(again.statusCode()).isEqualTo(409);
        assertThat(again.body()).isEqualTo("{\"code\":\"APPROVAL_NOT_APPROVED\"}");

        // Every operator action wrote its own event naming the operator, and none carries the subject.
        assertThat(app.auditFor("operator:reidentify-request")).singleElement().satisfies(e -> {
            assertThat(e.principalId()).isEqualTo("requester-1");
            assertThat(e.clientId()).isEqualTo("operator-console");
            assertThat(e.scopeId()).isEqualTo("case:CASE-R1");
            assertThat(e.entityType()).isEqualTo("PERSON_NAME");
        });
        assertThat(app.auditFor("operator:reidentify-approve")).singleElement().satisfies(e -> {
            assertThat(e.principalId()).isEqualTo("approver-1");
            assertThat(e.approvalId()).isEqualTo(approvalId);
        });
        assertThat(app.auditFor("operator:reidentify-collect")).hasSize(4);
        assertThat(app.audit).noneSatisfy(e -> assertThat(e.toString()).contains(SUBJECT));
        assertThat(app.auditFor("reidentify")).anySatisfy(e -> assertThat(e.policyDecision())
                .isEqualTo("ALLOW:RESOLVED"));
    }

    @Test
    void approvingYourOwnRequestIsRefusedWithSelfApproval() throws Exception {
        String synthetic = synthetic("CASE-R2");
        String both = app.operatorToken("requester-2", "requester", "approver");
        String approvalId = body(app.operator("POST", "/operator/reidentifications", both,
                requestBody("CASE-R2", synthetic, "fraud-review"))).path("approvalId").asText();

        HttpResponse<String> response = app.operator("POST", "/operator/reidentifications/" + approvalId
                + "/approve", both, null);

        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(response.body()).isEqualTo("{\"code\":\"SELF_APPROVAL\"}");
    }

    @Test
    void everyRefusalReturnsItsCodeAndNeverEchoesTheSyntheticValue() throws Exception {
        String synthetic = synthetic("CASE-R3");
        String requester = app.operatorToken("requester-3", "requester");
        String unprivileged = app.operatorToken("nobody-3");

        HttpResponse<String> notPermitted = app.operator("POST", "/operator/reidentifications", unprivileged,
                requestBody("CASE-R3", synthetic, "fraud-review"));
        assertThat(notPermitted.statusCode()).isEqualTo(403);
        assertThat(notPermitted.body()).isEqualTo("{\"code\":\"REIDENTIFICATION_NOT_PERMITTED\"}");

        HttpResponse<String> purpose = app.operator("POST", "/operator/reidentifications", requester,
                requestBody("CASE-R3", synthetic, "curiosity"));
        assertThat(purpose.statusCode()).isEqualTo(400);
        assertThat(purpose.body()).isEqualTo("{\"code\":\"PURPOSE_NOT_ALLOWED\"}");

        HttpResponse<String> blank = app.operator("POST", "/operator/reidentifications", requester,
                requestBody("CASE-R3", synthetic, ""));
        assertThat(blank.statusCode()).isEqualTo(400);
        assertThat(blank.body()).isEqualTo("{\"code\":\"PURPOSE_REQUIRED\"}");

        HttpResponse<String> unknownApproval = app.operator("POST",
                "/operator/reidentifications/no-such-approval/approve", requester, null);
        assertThat(unknownApproval.statusCode()).isIn(403, 404);
        assertThat(unknownApproval.body()).doesNotContain(synthetic).doesNotContain(SUBJECT);

        HttpResponse<String> unknownCollect = app.operator("GET", "/operator/reidentifications/no-such-approval",
                requester, null);
        assertThat(unknownCollect.statusCode()).isEqualTo(404);
        assertThat(unknownCollect.body()).isEqualTo("{\"code\":\"APPROVAL_NOT_FOUND\"}");

        // A pseudonym nobody holds: the entry is not found, and the code says only that.
        String approver = app.operatorToken("approver-3", "approver");
        String unheld = "Nobody Atall (ZZZZZZZZ)";
        String approvalId = body(app.operator("POST", "/operator/reidentifications", requester,
                requestBody("CASE-R3", unheld, "fraud-review"))).path("approvalId").asText();
        assertThat(app.operator("POST", "/operator/reidentifications/" + approvalId + "/approve", approver, null)
                .statusCode()).isEqualTo(200);
        HttpResponse<String> notFound = app.operator("GET", "/operator/reidentifications/" + approvalId,
                requester, null);
        assertThat(notFound.statusCode()).isEqualTo(404);
        assertThat(notFound.body()).isEqualTo("{\"code\":\"REIDENTIFICATION_NOT_FOUND\"}");

        // Malformed input is refused without echoing a thing from it.
        String secret = "Echo-Me-Not-12345";
        HttpResponse<String> badNamespace = app.operator("POST", "/operator/reidentifications", requester,
                "{\"scopeId\":\"case:X\",\"namespace\":\"" + secret + "\",\"syntheticValue\":\"" + secret
                        + "\",\"purpose\":\"fraud-review\"}");
        assertThat(badNamespace.statusCode()).isEqualTo(400);
        assertThat(badNamespace.body()).isEqualTo("{\"code\":\"INVALID_REQUEST\"}");
        HttpResponse<String> malformed = app.operator("POST", "/operator/reidentifications", requester,
                "{\"syntheticValue\":\"" + secret + "\"");
        assertThat(malformed.body()).isEqualTo("{\"code\":\"INVALID_REQUEST\"}");
    }

    @Test
    void theThirdPendingRequestIsRefusedWith429AndAuditedOnce() throws Exception {
        String synthetic = synthetic("CASE-R4");
        String requester = app.operatorToken("requester-4", "requester");
        String payload = requestBody("CASE-R4", synthetic, "fraud-review");
        assertThat(app.operator("POST", "/operator/reidentifications", requester, payload).statusCode())
                .isEqualTo(202);
        assertThat(app.operator("POST", "/operator/reidentifications", requester, payload).statusCode())
                .isEqualTo(202);
        app.audit.clear();

        HttpResponse<String> flooded = app.operator("POST", "/operator/reidentifications", requester, payload);

        assertThat(flooded.statusCode()).isEqualTo(429);
        assertThat(flooded.body()).isEqualTo("{\"code\":\"TOO_MANY_PENDING\"}");
        assertThat(app.audit.stream().map(AuditEvent::policyDecision).filter("DENY:TOO_MANY_PENDING"::equals))
                .hasSize(1);
        assertThat(app.auditFor("reidentify")).singleElement().satisfies(e ->
                assertThat(e.policyDecision()).isEqualTo("DENY:TOO_MANY_PENDING"));
        assertThat(app.auditFor("operator:reidentify-request")).singleElement().satisfies(e ->
                assertThat(e.policyDecision()).isEqualTo("ALLOW:FORWARDED"));
    }
}

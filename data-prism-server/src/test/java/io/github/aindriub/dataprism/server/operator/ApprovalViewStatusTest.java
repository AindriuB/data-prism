package io.github.aindriub.dataprism.server.operator;

import io.github.aindriub.dataprism.audit.AuditRecorder;
import io.github.aindriub.dataprism.core.metrics.PrivacyMetrics;
import io.github.aindriub.dataprism.oversight.ApprovalRequest;
import io.github.aindriub.dataprism.oversight.ApprovalRequest.Kind;
import io.github.aindriub.dataprism.oversight.ApprovalRequest.Status;
import io.github.aindriub.dataprism.oversight.InMemoryApprovalStore;
import io.github.aindriub.dataprism.oversight.InMemoryOversightState;
import io.github.aindriub.dataprism.security.AuthenticatedCaller;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Task 236: the operator approval view carries the approval's effective status. */
class ApprovalViewStatusTest {

    private static final Instant NOW = Instant.parse("2030-01-01T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final AuthenticatedCaller OPERATOR = new AuthenticatedCaller("op-1", "operator-console",
            Set.of("approver"), "operations", "case-1", null);

    private final InMemoryApprovalStore store = new InMemoryApprovalStore();
    private final OperatorAudit audit = new OperatorAudit(new AuditRecorder(event -> { }, CLOCK, "operator-test"));

    private OversightOperatorController controller() {
        var beans = new DefaultListableBeanFactory();
        beans.registerSingleton("metrics", PrivacyMetrics.none());
        return new OversightOperatorController(() -> Optional.of(OPERATOR), audit, new InMemoryOversightState(),
                store, CLOCK, beans.getBeanProvider(PrivacyMetrics.class));
    }

    private ApprovalRequest pending(String id, Instant expiresAt) {
        return store.create(new ApprovalRequest(id, Kind.TOOL_CALL, "req-1", "agent-client", "scope-a", "search",
                "fp-" + id, null, null, "review", "case-1", NOW.minus(Duration.ofMinutes(5)), expiresAt,
                Status.PENDING, null, null));
    }

    private String statusOf(String id) {
        return controller().approval(id).status();
    }

    @Test
    void aPendingApprovalShowsPending() {
        pending("a-pending", NOW.plusSeconds(60));
        assertThat(statusOf("a-pending")).isEqualTo("PENDING");
    }

    @Test
    void anApprovedApprovalShowsApproved() {
        pending("a-approved", NOW.plusSeconds(60));
        store.approve("a-approved", "op-2", NOW);
        assertThat(statusOf("a-approved")).isEqualTo("APPROVED");
    }

    @Test
    void aRejectedApprovalShowsRejected() {
        pending("a-rejected", NOW.plusSeconds(60));
        store.reject("a-rejected", "op-2", NOW);
        assertThat(statusOf("a-rejected")).isEqualTo("REJECTED");
    }

    @Test
    void aConsumedApprovalShowsConsumed() {
        pending("a-consumed", NOW.plusSeconds(60));
        store.approve("a-consumed", "op-2", NOW);
        store.consumeApproved(Kind.TOOL_CALL, "req-1", "scope-a", "search", "fp-a-consumed", NOW);
        assertThat(statusOf("a-consumed")).isEqualTo("CONSUMED");
    }

    @Test
    void aStoredPendingPastItsExpiryShowsExpired() {
        pending("a-expired", NOW.minusSeconds(1));
        assertThat(store.find("a-expired")).get().extracting(ApprovalRequest::status).isEqualTo(Status.PENDING);
        assertThat(statusOf("a-expired")).isEqualTo("EXPIRED");
    }

    @Test
    void aStoredPendingExactlyAtItsExpiryShowsExpired() {
        pending("a-boundary", NOW);
        assertThat(statusOf("a-boundary")).isEqualTo("EXPIRED");
    }

    @Test
    void aStoredExpiredStatusShowsExpired() {
        store.create(new ApprovalRequest("a-marked", Kind.TOOL_CALL, "req-1", "agent-client", "scope-a", "search",
                "fp-m", null, null, "review", "case-1", NOW.minusSeconds(600), NOW.minusSeconds(300),
                Status.PENDING, null, null));
        assertThat(ApprovalViewHolder.of(store.find("a-marked").orElseThrow().withStatus(Status.EXPIRED)))
                .isEqualTo("EXPIRED");
    }

    @Test
    void theListReturnsOnlyLivePendingApprovalsEachShowingPending() {
        pending("l-live", NOW.plusSeconds(60));
        pending("l-expired", NOW.minusSeconds(1));
        pending("l-approved", NOW.plusSeconds(60));
        store.approve("l-approved", "op-2", NOW);

        @SuppressWarnings("unchecked")
        List<OversightOperatorController.ApprovalView> listed =
                (List<OversightOperatorController.ApprovalView>) controller().approvals().get("approvals");

        assertThat(listed).extracting(OversightOperatorController.ApprovalView::approvalId)
                .containsExactly("l-live");
        assertThat(listed).extracting(OversightOperatorController.ApprovalView::status)
                .containsExactly("PENDING");
    }

    /** Maps a request the way the view does, for a status the in-memory store never leaves in place. */
    private static final class ApprovalViewHolder {
        static String of(ApprovalRequest request) {
            return OversightOperatorController.ApprovalView.of(request, NOW).status();
        }
    }
}

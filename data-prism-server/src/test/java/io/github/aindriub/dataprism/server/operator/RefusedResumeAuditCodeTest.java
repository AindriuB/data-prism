package io.github.aindriub.dataprism.server.operator;

import io.github.aindriub.dataprism.audit.AuditEvent;
import io.github.aindriub.dataprism.audit.AuditRecorder;
import io.github.aindriub.dataprism.core.metrics.PrivacyMetrics;
import io.github.aindriub.dataprism.core.refusal.ClusterOperationRefusedException;
import io.github.aindriub.dataprism.oversight.InMemoryApprovalStore;
import io.github.aindriub.dataprism.oversight.InMemoryOversightState;
import io.github.aindriub.dataprism.oversight.OversightSnapshot;
import io.github.aindriub.dataprism.oversight.OversightState;
import io.github.aindriub.dataprism.security.AuthenticatedCaller;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * A resume the cluster refuses is audited as 245 audits a resume whose apply fails: {@code ALLOW:RESUMED} first,
 * then a {@code DENY} under the same correlation id carrying the code. Each of the two codes of task 228 is pinned:
 * {@code CLUSTER_OPERATION_REFUSED} (the member is not serving, or a member is unverified) and
 * {@code OVERSIGHT_UNAVAILABLE} (the pause document is absent, unauthentic or not fresh). The state is a stub; the
 * Hazelcast side that raises each code is pinned in the hazelcast module.
 */
class RefusedResumeAuditCodeTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2030-01-01T00:00:00Z"), ZoneOffset.UTC);
    private static final AuthenticatedCaller OPERATOR = new AuthenticatedCaller("op-1", "operator-console",
            Set.of("operator"), "operations", "case-1", null);
    private static final String DETAIL = "detail-that-must-not-leak-/var/pause/doc";

    private final List<AuditEvent> events = new ArrayList<>();
    private final InMemoryOversightState real = new InMemoryOversightState();
    private RuntimeException refusal;

    private final OversightState state = new OversightState() {
        @Override public boolean allPaused() { return real.allPaused(); }
        @Override public boolean toolPaused(String tool) { return real.toolPaused(tool); }
        @Override public boolean scopePaused(String scopeId) { return real.scopePaused(scopeId); }
        @Override public void pauseAll() { real.pauseAll(); }
        @Override public void pauseTool(String tool) { real.pauseTool(tool); }
        @Override public void pauseScope(String scopeId) { real.pauseScope(scopeId); }
        @Override public void resumeAll() { throw refusal; }
        @Override public void resumeTool(String tool) { throw refusal; }
        @Override public void resumeScope(String scopeId) { throw refusal; }
        @Override public OversightSnapshot snapshot() { return real.snapshot(); }
    };

    private final OversightOperatorController controller = new OversightOperatorController(
            () -> Optional.of(OPERATOR), new OperatorAudit(new AuditRecorder(events::add, CLOCK, "operator-test")),
            state, new InMemoryApprovalStore(), CLOCK,
            new DefaultListableBeanFactory().getBeanProvider(PrivacyMetrics.class));

    private void assertRefusedWith(RuntimeException failure, String code) {
        refusal = failure;
        real.pauseTool("search");
        events.clear();

        OperatorException thrown = catchThrowableOfType(OperatorException.class,
                () -> controller.resume(new OversightOperatorController.PauseRequest("TOOL", "search")));

        assertThat(thrown).isNotNull();
        var response = new OperatorErrorAdvice().refused(thrown);
        assertThat(response.getStatusCode().value()).isEqualTo(503);
        assertThat(response.getBody()).containsEntry("code", code);
        assertThat(events).extracting(AuditEvent::policyDecision)
                .containsExactly("ALLOW:RESUMED", "DENY:" + code);
        assertThat(events.get(1).correlationId()).isEqualTo(events.get(0).correlationId());
        assertThat(thrown.toString() + events).doesNotContain(DETAIL);
        assertThat(real.toolPaused("search")).as("the refused resume changed nothing").isTrue();
    }

    @Test
    void aResumeRefusedBecauseTheMemberIsNotServingIsAllowedThenDeniedWithClusterOperationRefused() {
        assertRefusedWith(new ClusterOperationRefusedException(new IllegalStateException(DETAIL)),
                "CLUSTER_OPERATION_REFUSED");
    }

    @Test
    void aResumeRefusedBecauseTheDocumentIsNotFreshIsAllowedThenDeniedWithOversightUnavailable() {
        assertRefusedWith(new IllegalStateException(DETAIL), "OVERSIGHT_UNAVAILABLE");
    }
}

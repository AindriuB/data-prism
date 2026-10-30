package io.github.aindriub.dataprism.server.operator;

import io.github.aindriub.dataprism.audit.AuditEvent;
import io.github.aindriub.dataprism.audit.AuditRecorder;
import io.github.aindriub.dataprism.core.metrics.PrivacyMetrics;
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
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The resume response says whether the resumed target is still paused (amendment 9 of task 228). It is read from
 * {@code snapshot()} after the resume has applied, for the resumed target only, and fails closed: a global pause keeps
 * a tool or scope target paused, and a snapshot that cannot be read says paused.
 *
 * <p>The state under the controller here is a stub that can land a pause between the resume and the snapshot, which
 * is what a pause the resume did not observe looks like to the controller. The race inside the Hazelcast document
 * itself (a pause committed between the resume's read and its commit) is pinned in {@code PauseDocumentTest}, where
 * the package-private {@code beforeCommit} seam is reachable.
 */
class ResumeEffectTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2030-01-01T00:00:00Z"), ZoneOffset.UTC);
    private static final AuthenticatedCaller OPERATOR = new AuthenticatedCaller("op-1", "operator-console",
            Set.of("operator"), "operations", "case-1", null);
    private static final String TOOL = "get_entity_context";
    private static final String SCOPE = "scope-a";

    private final List<AuditEvent> events = new ArrayList<>();
    private final InMemoryOversightState real = new InMemoryOversightState();
    /** Runs after the resume has applied, before the controller reads the snapshot. */
    private Runnable afterResume = () -> { };
    private boolean snapshotThrows;

    private final OversightState state = new OversightState() {
        @Override public boolean allPaused() { return real.allPaused(); }
        @Override public boolean toolPaused(String tool) { return real.toolPaused(tool); }
        @Override public boolean scopePaused(String scopeId) { return real.scopePaused(scopeId); }
        @Override public void pauseAll() { real.pauseAll(); }
        @Override public void pauseTool(String tool) { real.pauseTool(tool); }
        @Override public void pauseScope(String scopeId) { real.pauseScope(scopeId); }
        @Override public void resumeAll() { real.resumeAll(); afterResume.run(); }
        @Override public void resumeTool(String tool) { real.resumeTool(tool); afterResume.run(); }
        @Override public void resumeScope(String scopeId) { real.resumeScope(scopeId); afterResume.run(); }

        @Override
        public OversightSnapshot snapshot() {
            if (snapshotThrows) {
                throw new IllegalStateException("the pause document is not fresh");
            }
            return real.snapshot();
        }
    };

    private final DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
    private final OversightOperatorController controller = new OversightOperatorController(
            () -> Optional.of(OPERATOR), new OperatorAudit(new AuditRecorder(events::add, CLOCK, "operator-test")),
            state, new InMemoryApprovalStore(), CLOCK, beans.getBeanProvider(PrivacyMetrics.class));

    private static OversightOperatorController.PauseRequest all() {
        return new OversightOperatorController.PauseRequest("ALL", null);
    }

    private static OversightOperatorController.PauseRequest tool() {
        return new OversightOperatorController.PauseRequest("TOOL", TOOL);
    }

    private static OversightOperatorController.PauseRequest scope() {
        return new OversightOperatorController.PauseRequest("SCOPE", SCOPE);
    }

    @Test
    void aCleanResumeReportsStillPausedFalseForEveryTarget() {
        real.pauseTool(TOOL);
        assertThat(controller.resume(tool()))
                .isEqualTo(Map.of("status", "RESUMED", "target", "TOOL", "stillPaused", false));
        real.pauseScope(SCOPE);
        assertThat(controller.resume(scope()))
                .isEqualTo(Map.of("status", "RESUMED", "target", "SCOPE", "stillPaused", false));
        real.pauseAll();
        assertThat(controller.resume(all()))
                .isEqualTo(Map.of("status", "RESUMED", "target", "ALL", "stillPaused", false));
    }

    @Test
    void aToolTargetResumedWhilePauseAllIsActiveIsStillPaused() {
        real.pauseAll();
        real.pauseTool(TOOL);

        assertThat(controller.resume(tool())).containsEntry("stillPaused", true);
        assertThat(real.toolPaused(TOOL)).isFalse();
        assertThat(real.allPaused()).isTrue();
    }

    @Test
    void aScopeTargetResumedWhilePauseAllIsActiveIsStillPaused() {
        real.pauseAll();
        real.pauseScope(SCOPE);

        assertThat(controller.resume(scope())).containsEntry("stillPaused", true);
    }

    @Test
    void aPauseTheResumeDidNotObserveKeepsTheTargetPausedAndTheResponseSaysSo() {
        real.pauseTool(TOOL);
        afterResume = () -> real.pauseTool(TOOL);

        assertThat(controller.resume(tool())).containsEntry("stillPaused", true);

        afterResume = () -> { };
        assertThat(controller.resume(tool())).containsEntry("stillPaused", false);
        assertThat(real.toolPaused(TOOL)).isFalse();
    }

    @Test
    void aConcurrentPauseOfAnotherTargetDoesNotChangeTheAnswer() {
        real.pauseTool(TOOL);
        afterResume = () -> real.pauseTool("another_tool");

        assertThat(controller.resume(tool())).containsEntry("stillPaused", false);
    }

    @Test
    void aSnapshotThatThrowsAfterTheResumeReportsStillPausedAndWritesNoFurtherAudit() {
        real.pauseTool(TOOL);
        afterResume = () -> snapshotThrows = true;

        Map<String, Object> response = controller.resume(tool());

        assertThat(response).isEqualTo(Map.of("status", "RESUMED", "target", "TOOL", "stillPaused", true));
        assertThat(events).extracting(AuditEvent::policyDecision).containsExactly("ALLOW:RESUMED");
    }
}

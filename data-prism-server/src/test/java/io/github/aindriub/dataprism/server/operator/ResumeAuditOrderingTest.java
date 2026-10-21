package io.github.aindriub.dataprism.server.operator;

import io.github.aindriub.dataprism.audit.AuditEvent;
import io.github.aindriub.dataprism.audit.AuditRecorder;
import io.github.aindriub.dataprism.core.metrics.Metric;
import io.github.aindriub.dataprism.core.metrics.PrivacyMetrics;
import io.github.aindriub.dataprism.oversight.InMemoryApprovalStore;
import io.github.aindriub.dataprism.oversight.InMemoryOversightState;
import io.github.aindriub.dataprism.oversight.OversightSnapshot;
import io.github.aindriub.dataprism.oversight.OversightState;
import io.github.aindriub.dataprism.security.AuthenticatedCaller;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * Resume is audit-first (OD-08-14 a): the ALLOW is written, then the resume is applied. When applying it fails,
 * the ALLOW is followed by a linked DENY under the same correlation id and the state is unchanged.
 */
class ResumeAuditOrderingTest {

    private static final String SENTINEL = "state-secret-detail-/var/pause/doc";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2030-01-01T00:00:00Z"), ZoneOffset.UTC);
    private static final AuthenticatedCaller OPERATOR = new AuthenticatedCaller("op-1", "operator-console",
            Set.of("operator"), "operations", "case-1", null);

    private final List<AuditEvent> events = new ArrayList<>();
    private final Map<Metric, Integer> counts = new EnumMap<>(Metric.class);
    private boolean denyWriteDown;
    private boolean allWritesDown;
    private boolean resumeThrows = true;
    private final InMemoryOversightState real = new InMemoryOversightState();

    private final OversightState state = new OversightState() {
        @Override public boolean allPaused() { return real.allPaused(); }
        @Override public boolean toolPaused(String tool) { return real.toolPaused(tool); }
        @Override public boolean scopePaused(String scopeId) { return real.scopePaused(scopeId); }
        @Override public void pauseAll() { real.pauseAll(); }
        @Override public void pauseTool(String tool) { real.pauseTool(tool); }
        @Override public void pauseScope(String scopeId) { real.pauseScope(scopeId); }
        @Override public void resumeAll() { failOr(real::resumeAll); }
        @Override public void resumeTool(String tool) { failOr(() -> real.resumeTool(tool)); }
        @Override public void resumeScope(String scopeId) { failOr(() -> real.resumeScope(scopeId)); }
        @Override public OversightSnapshot snapshot() { return real.snapshot(); }

        private void failOr(Runnable apply) {
            if (resumeThrows) {
                throw new IllegalStateException(SENTINEL);
            }
            apply.run();
        }
    };

    private final DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
    private final OperatorAudit audit;
    private final OversightOperatorController controller;

    ResumeAuditOrderingTest() {
        beans.registerSingleton("metrics", new PrivacyMetrics() {
            @Override public void increment(Metric metric) { counts.merge(metric, 1, Integer::sum); }
            @Override public void increment(Metric metric, String sourceName) { increment(metric); }
            @Override public void record(Metric metric, String sourceName, Duration duration) { }
        });
        audit = new OperatorAudit(new AuditRecorder(event -> {
            if (allWritesDown || (denyWriteDown && event.policyDecision().startsWith("DENY"))) {
                throw new IllegalStateException(SENTINEL);
            }
            events.add(event);
        }, CLOCK, "operator-test"), beans.getBeanProvider(PrivacyMetrics.class));
        controller = new OversightOperatorController(() -> Optional.of(OPERATOR), audit, state,
                new InMemoryApprovalStore(), CLOCK, beans.getBeanProvider(PrivacyMetrics.class));
    }

    private static final List<OversightOperatorController.PauseRequest> TARGETS = List.of(
            new OversightOperatorController.PauseRequest("ALL", null),
            new OversightOperatorController.PauseRequest("TOOL", "get_entity_context"),
            new OversightOperatorController.PauseRequest("SCOPE", "scope-a"));

    private void pauseEverything() {
        real.pauseAll();
        real.pauseTool("get_entity_context");
        real.pauseScope("scope-a");
    }

    private void assertStateUnchanged() {
        assertThat(real.allPaused()).isTrue();
        assertThat(real.toolPaused("get_entity_context")).isTrue();
        assertThat(real.scopePaused("scope-a")).isTrue();
    }

    private static String code(OperatorException e) {
        return new OperatorErrorAdvice().refused(e).getBody().get("code");
    }

    private static int status(OperatorException e) {
        return new OperatorErrorAdvice().refused(e).getStatusCode().value();
    }

    @Test
    void aResumeThatFailsToApplyWritesALinkedDenyAndLeavesTheStateUnchanged() {
        pauseEverything();
        for (var target : TARGETS) {
            events.clear();
            OperatorException thrown = catchThrowableOfType(OperatorException.class,
                    () -> controller.resume(target));
            assertThat(thrown).as(target.target()).isNotNull();
            assertThat(status(thrown)).isEqualTo(503);
            assertThat(code(thrown)).isEqualTo("OVERSIGHT_UNAVAILABLE");
            assertThat(events).extracting(AuditEvent::policyDecision)
                    .containsExactly("ALLOW:RESUMED", "DENY:OVERSIGHT_UNAVAILABLE");
            assertThat(events.get(1).correlationId()).isEqualTo(events.get(0).correlationId());
            assertThat(events).extracting(AuditEvent::tool).containsOnly("operator:resume");
            assertThat(thrown.toString()).doesNotContain(SENTINEL);
        }
        assertStateUnchanged();
        assertThat(counts).doesNotContainKey(Metric.DENIAL_AUDIT_UNAVAILABLE);
    }

    @Test
    void ifTheLinkedDenyCannotBeWrittenTheAnswerIsAuditUnavailable() {
        pauseEverything();
        denyWriteDown = true;
        for (var target : TARGETS) {
            events.clear();
            counts.clear();
            OperatorException thrown = catchThrowableOfType(OperatorException.class,
                    () -> controller.resume(target));
            assertThat(thrown).as(target.target()).isNotNull();
            assertThat(status(thrown)).isEqualTo(503);
            assertThat(code(thrown)).isEqualTo("AUDIT_UNAVAILABLE");
            assertThat(thrown.toString()).doesNotContain(SENTINEL);
            assertThat(events).extracting(AuditEvent::policyDecision).containsExactly("ALLOW:RESUMED");
            assertThat(counts).containsEntry(Metric.DENIAL_AUDIT_UNAVAILABLE, 1);
        }
        assertStateUnchanged();
    }

    @Test
    void aResumeThatAppliesWritesOnlyTheAllow() {
        pauseEverything();
        resumeThrows = false;
        assertThat(controller.resume(TARGETS.get(0))).isEqualTo(Map.of("status", "RESUMED", "target", "ALL"));
        assertThat(events).extracting(AuditEvent::policyDecision).containsExactly("ALLOW:RESUMED");
        assertThat(real.allPaused()).isFalse();
    }

    @Test
    void ifTheAllowCannotBeWrittenNothingIsAppliedAndNoDenyIsAttempted() {
        pauseEverything();
        resumeThrows = false;
        allWritesDown = true;
        OperatorException thrown = catchThrowableOfType(OperatorException.class,
                () -> controller.resume(TARGETS.get(0)));
        assertThat(code(thrown)).isEqualTo("AUDIT_UNAVAILABLE");
        assertStateUnchanged();
        assertThat(events).isEmpty();
    }
}

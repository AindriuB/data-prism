package io.github.aindriub.dataprism.server.operator;

import io.github.aindriub.dataprism.audit.AuditEvent;
import io.github.aindriub.dataprism.audit.AuditRecorder;
import io.github.aindriub.dataprism.core.metrics.Metric;
import io.github.aindriub.dataprism.core.metrics.PrivacyMetrics;
import io.github.aindriub.dataprism.oversight.ApprovalRefusedException;
import io.github.aindriub.dataprism.oversight.ApprovalRequest;
import io.github.aindriub.dataprism.oversight.ApprovalRequest.Kind;
import io.github.aindriub.dataprism.oversight.ApprovalRequest.Status;
import io.github.aindriub.dataprism.oversight.ApprovalStore;
import io.github.aindriub.dataprism.oversight.InMemoryApprovalStore;
import io.github.aindriub.dataprism.oversight.InMemoryCallerRateLimiter;
import io.github.aindriub.dataprism.oversight.InMemoryOversightState;
import io.github.aindriub.dataprism.security.AdmissionDecision;
import io.github.aindriub.dataprism.security.AuthenticatedCaller;
import io.github.aindriub.dataprism.security.OversightPolicy;
import io.github.aindriub.dataprism.security.ToolAdmission;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.http.ResponseEntity;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * A tool-call approval can take effect only after its {@code ALLOW:APPROVED} event is written. The store
 * wrappers here make the old undo (a {@code consumeApproved} after a failed audit write) fail, and make the
 * commit fail or lose its reply, so each test proves safety from the order of the two calls alone.
 */
class ApprovalDecisionAuditTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2030-01-01T00:00:00Z"), ZoneOffset.UTC);
    private static final AuthenticatedCaller APPROVER = new AuthenticatedCaller("op-1", "operator-console",
            Set.of("approver"), "operations", "case-1", null);
    private static final AuthenticatedCaller REQUESTER = new AuthenticatedCaller("req-1", "agent-client",
            Set.of("requester"), "fraud-review", "case-1", null);
    private static final String TOOL = "search";
    private static final String SCOPE = "scope-a";
    private static final String FINGERPRINT = "fp-1";

    private interface Delegate {
        Object call() throws Throwable;
    }

    private interface Intercept {
        Object call(String method, Delegate delegate) throws Throwable;
    }

    private final List<AuditEvent> events = new ArrayList<>();
    private final Map<Metric, Integer> counts = new EnumMap<>(Metric.class);
    private final InMemoryApprovalStore real = new InMemoryApprovalStore();
    private boolean auditDown;
    private Consumer<AuditEvent> onEvent;
    private final OperatorAudit audit = new OperatorAudit(new AuditRecorder(event -> {
        if (auditDown) {
            throw new IllegalStateException("audit down");
        }
        if (onEvent != null) {
            onEvent.accept(event);
        }
        events.add(event);
    }, CLOCK, "operator-test"));
    private final PrivacyMetrics metrics = new PrivacyMetrics() {
        @Override public void increment(Metric metric) { counts.merge(metric, 1, Integer::sum); }
        @Override public void increment(Metric metric, String sourceName) { increment(metric); }
        @Override public void record(Metric metric, String sourceName, Duration duration) { }
    };

    private ApprovalStore intercepting(Intercept intercept) {
        return (ApprovalStore) Proxy.newProxyInstance(ApprovalStore.class.getClassLoader(),
                new Class<?>[] {ApprovalStore.class}, (p, m, args) -> intercept.call(m.getName(), () -> {
                    try {
                        return m.invoke(real, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                }));
    }

    private OversightOperatorController controller(ApprovalStore store) {
        var beans = new DefaultListableBeanFactory();
        beans.registerSingleton("metrics", metrics);
        return new OversightOperatorController(() -> Optional.of(APPROVER), audit, new InMemoryOversightState(),
                store, CLOCK, beans.getBeanProvider(PrivacyMetrics.class));
    }

    private static ToolAdmission admission(ApprovalStore store) {
        return new ToolAdmission(new InMemoryOversightState(), store, new InMemoryCallerRateLimiter(),
                new OversightPolicy(Set.of(TOOL), OptionalInt.empty(), Duration.ofMinutes(1), Duration.ofHours(1)),
                CLOCK);
    }

    private static AdmissionDecision admit(ToolAdmission admission) {
        return admission.admit(REQUESTER, TOOL, SCOPE, FINGERPRINT);
    }

    /** The tool call's own admission creates the approval, as in production. */
    private String requested(ToolAdmission admission) {
        AdmissionDecision first = admit(admission);
        assertThat(first.code()).isEqualTo("APPROVAL_REQUIRED");
        return first.approvalId();
    }

    private List<AuditEvent> decisionEvents(String id) {
        return events.stream().filter(e -> id.equals(e.approvalId())).toList();
    }

    private static String bodyCode(OperatorException e) {
        return new OperatorErrorAdvice().refused(e).getBody().get("code");
    }

    private static int statusOf(OperatorException e) {
        ResponseEntity<Map<String, String>> response = new OperatorErrorAdvice().refused(e);
        return response.getStatusCode().value();
    }

    @Test
    void approveWithAuditDownLeavesTheApprovalPendingEvenWhenAnUndoWouldFail() {
        // The first consumeApproved after an approve reaches the store is the old undo; it fails.
        AtomicBoolean approveCalled = new AtomicBoolean();
        AtomicBoolean undoFailed = new AtomicBoolean();
        ApprovalStore failingUndo = intercepting((method, delegate) -> {
            if (method.equals("approve")) {
                approveCalled.set(true);
            }
            if (method.equals("consumeApproved") && approveCalled.get() && undoFailed.compareAndSet(false, true)) {
                throw new IllegalStateException("undo failed");
            }
            return delegate.call();
        });
        ToolAdmission admission = admission(failingUndo);
        String id = requested(admission);
        auditDown = true;
        OperatorException thrown = catchThrowableOfType(OperatorException.class,
                () -> controller(failingUndo).approve(id));
        auditDown = false;
        assertThat(thrown).isNotNull();
        assertThat(statusOf(thrown)).isEqualTo(503);
        assertThat(bodyCode(thrown)).isEqualTo("AUDIT_UNAVAILABLE");
        AdmissionDecision again = admit(admission);
        assertThat(again.admitted()).isFalse();
        assertThat(again.code()).isEqualTo("APPROVAL_PENDING");
        assertThat(real.find(id)).get().extracting(ApprovalRequest::status).isEqualTo(Status.PENDING);
        assertThat(counts).containsEntry(Metric.APPROVAL_DECISION_AUDIT_UNAVAILABLE, 1)
                .doesNotContainKey(Metric.APPROVAL_DECISION_UNCONFIRMED);
    }

    @Test
    void theApprovalIsStillPendingWhenItsAllowEventIsWritten() {
        ToolAdmission admission = admission(real);
        String id = requested(admission);
        List<Status> seen = new ArrayList<>();
        onEvent = e -> {
            if (e.policyDecision().equals("ALLOW:APPROVED")) {
                seen.add(real.find(id).orElseThrow().status());
            }
        };
        controller(real).approve(id);
        assertThat(seen).containsExactly(Status.PENDING);
    }

    @Test
    void anApproveThatSucceedsStillAnswers200AndTheIdenticalCallIsAdmittedOnce() {
        ToolAdmission admission = admission(real);
        String id = requested(admission);
        assertThat(controller(real).approve(id)).isEqualTo(Map.of("status", "APPROVED", "approvalId", id));
        assertThat(events).extracting(AuditEvent::policyDecision).containsExactly("ALLOW:APPROVED");
        assertThat(events.get(0).tool()).isEqualTo("operator:approve");
        assertThat(admit(admission).admitted()).isTrue();
        assertThat(admit(admission).admitted()).isFalse();
    }

    @Test
    void anApproveThatLosesARaceIsAuditedAsAllowThenALinkedDeny() {
        ApprovalStore raced = intercepting((method, delegate) -> {
            if (method.equals("approve")) {
                throw new ApprovalRefusedException(ApprovalRefusedException.Code.NOT_PENDING);
            }
            return delegate.call();
        });
        String id = requested(admission(raced));
        OperatorException thrown = catchThrowableOfType(OperatorException.class, () -> controller(raced).approve(id));
        assertThat(statusOf(thrown)).isEqualTo(409);
        assertThat(bodyCode(thrown)).isEqualTo("APPROVAL_NOT_PENDING");
        List<AuditEvent> pair = decisionEvents(id);
        assertThat(pair).extracting(AuditEvent::policyDecision).containsExactly("ALLOW:APPROVED",
                "DENY:APPROVAL_NOT_PENDING");
        assertThat(pair.get(1).correlationId()).isEqualTo(pair.get(0).correlationId());
        assertThat(counts).doesNotContainKey(Metric.APPROVAL_DECISION_UNCONFIRMED);
    }

    @Test
    void anApproveWhoseReplyIsLostAfterItAppliedIsSuccessWithoutADeny() {
        ApprovalStore lostReply = intercepting((method, delegate) -> {
            Object result = delegate.call();
            if (method.equals("approve")) {
                throw new IllegalStateException("reply lost");
            }
            return result;
        });
        String id = requested(admission(lostReply));
        assertThat(controller(lostReply).approve(id)).isEqualTo(Map.of("status", "APPROVED", "approvalId", id));
        assertThat(decisionEvents(id)).extracting(AuditEvent::policyDecision).containsExactly("ALLOW:APPROVED");
        assertThat(counts).isEmpty();
    }

    @Test
    void anApproveThatCannotBeConfirmedIsRefusedWithALinkedDenyAndCounted() {
        ApprovalStore neverApplied = intercepting((method, delegate) -> {
            if (method.equals("approve")) {
                throw new IllegalStateException("store down");
            }
            return delegate.call();
        });
        String id = requested(admission(neverApplied));
        OperatorException thrown = catchThrowableOfType(OperatorException.class,
                () -> controller(neverApplied).approve(id));
        assertThat(statusOf(thrown)).isEqualTo(503);
        assertThat(bodyCode(thrown)).isEqualTo("OVERSIGHT_UNAVAILABLE");
        List<AuditEvent> pair = decisionEvents(id);
        assertThat(pair).extracting(AuditEvent::policyDecision).containsExactly("ALLOW:APPROVED",
                "DENY:OVERSIGHT_UNAVAILABLE");
        assertThat(pair.get(1).correlationId()).isEqualTo(pair.get(0).correlationId());
        assertThat(counts).containsEntry(Metric.APPROVAL_DECISION_UNCONFIRMED, 1);
    }

    @Test
    void aLinkedDenyThatCannotBeWrittenKeepsTheDecisionsCodeAndIsCounted() {
        ApprovalStore raced = intercepting((method, delegate) -> {
            if (method.equals("approve")) {
                throw new ApprovalRefusedException(ApprovalRefusedException.Code.NOT_PENDING);
            }
            return delegate.call();
        });
        String id = requested(admission(raced));
        onEvent = e -> {
            if (e.policyDecision().startsWith("DENY")) {
                throw new IllegalStateException("sink down");
            }
        };
        OperatorException thrown = catchThrowableOfType(OperatorException.class, () -> controller(raced).approve(id));
        assertThat(statusOf(thrown)).isEqualTo(409);
        assertThat(bodyCode(thrown)).isEqualTo("APPROVAL_NOT_PENDING");
        assertThat(counts).containsEntry(Metric.APPROVAL_DECISION_UNCONFIRMED, 1)
                .doesNotContainKey(Metric.APPROVAL_DECISION_AUDIT_UNAVAILABLE);
    }

    @Test
    void anApproveTheStoreWouldRefuseIsDeniedOnceWithoutAnAllowAndNeverReachesTheStore() {
        AtomicInteger approves = new AtomicInteger();
        ApprovalStore counting = intercepting((method, delegate) -> {
            if (method.equals("approve")) {
                approves.incrementAndGet();
            }
            return delegate.call();
        });
        ToolAdmission admission = admission(counting);
        String decided = requested(admission);
        controller(counting).approve(decided);
        events.clear();
        approves.set(0);
        counts.clear();
        // Self-approval: the approver is the requester.
        real.create(new ApprovalRequest("self-1", Kind.TOOL_CALL, APPROVER.principalId(), "agent-client", SCOPE,
                TOOL, "fp-self", null, null, "operations", "case-1", CLOCK.instant(),
                CLOCK.instant().plus(Duration.ofHours(1)), Status.PENDING, null, null));
        real.create(new ApprovalRequest("old-1", Kind.TOOL_CALL, "req-2", "agent-client", SCOPE, TOOL, "fp-old",
                null, null, "operations", "case-1", CLOCK.instant().minus(Duration.ofHours(2)),
                CLOCK.instant().minus(Duration.ofHours(1)), Status.PENDING, null, null));
        List<String> codes = new ArrayList<>();
        for (String id : List.of("self-1", decided, "old-1")) {
            OperatorException thrown = catchThrowableOfType(OperatorException.class,
                    () -> controller(counting).approve(id));
            codes.add(bodyCode(thrown));
        }
        assertThat(codes).containsExactly("SELF_APPROVAL", "APPROVAL_NOT_PENDING", "APPROVAL_EXPIRED");
        assertThat(events).extracting(AuditEvent::policyDecision)
                .containsExactly("DENY:SELF_APPROVAL", "DENY:APPROVAL_NOT_PENDING", "DENY:APPROVAL_EXPIRED");
        assertThat(approves.get()).isZero();
        assertThat(counts).isEmpty();
    }
}

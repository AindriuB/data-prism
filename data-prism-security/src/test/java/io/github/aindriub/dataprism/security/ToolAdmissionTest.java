package io.github.aindriub.dataprism.security;

import io.github.aindriub.dataprism.oversight.ApprovalRefusedException;
import io.github.aindriub.dataprism.oversight.ApprovalStore;
import io.github.aindriub.dataprism.oversight.CallerRateLimiter;
import io.github.aindriub.dataprism.oversight.InMemoryApprovalStore;
import io.github.aindriub.dataprism.oversight.InMemoryCallerRateLimiter;
import io.github.aindriub.dataprism.oversight.InMemoryOversightState;
import io.github.aindriub.dataprism.oversight.OversightState;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.OptionalInt;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolAdmissionTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2030-01-01T00:00:00Z"), ZoneOffset.UTC);
    private static final AuthenticatedCaller CALLER = new AuthenticatedCaller(
            "alice", "client", Set.of(), "purpose", "case-1", null);

    private final OversightState state = new InMemoryOversightState();
    private final ApprovalStore approvals = new InMemoryApprovalStore();
    private final CallerRateLimiter limiter = new InMemoryCallerRateLimiter();

    private ToolAdmission admission(OversightPolicy policy) {
        return new ToolAdmission(state, approvals, limiter, policy, CLOCK);
    }

    private static OversightPolicy limited(int limit) {
        return new OversightPolicy(Set.of(), OptionalInt.of(limit), Duration.ofMinutes(1), Duration.ofHours(1));
    }

    private static OversightPolicy approval() {
        return new OversightPolicy(Set.of("risky"), OptionalInt.empty(), Duration.ofMinutes(1),
                Duration.ofHours(1));
    }

    @Test
    void admitsWhenNothingApplies() {
        AdmissionDecision d = admission(OversightPolicy.none()).admit(CALLER, "t", "s", "f");
        assertTrue(d.admitted());
        assertNull(d.code());
    }

    @Test
    void globalPause() {
        state.pauseAll();
        assertEquals("DATAPRISM_PAUSED", admission(OversightPolicy.none()).admit(CALLER, "t", "s", "f").code());
    }

    @Test
    void toolPause() {
        state.pauseTool("t");
        assertEquals("TOOL_PAUSED", admission(OversightPolicy.none()).admit(CALLER, "t", "s", "f").code());
    }

    @Test
    void scopePause() {
        state.pauseScope("s");
        assertEquals("SCOPE_PAUSED", admission(OversightPolicy.none()).admit(CALLER, "t", "s", "f").code());
    }

    @Test
    void callerRateLimit() {
        ToolAdmission a = admission(limited(1));
        assertTrue(a.admit(CALLER, "t", "s", "f").admitted());
        assertEquals("CALLER_RATE_LIMITED", a.admit(CALLER, "t", "s", "f").code());
    }

    @Test
    void pauseWinsOverRateLimitAndDoesNotAdvanceCounter() {
        ToolAdmission a = admission(limited(1));
        state.pauseTool("t");
        assertEquals("TOOL_PAUSED", a.admit(CALLER, "t", "s", "f").code());
        assertEquals("TOOL_PAUSED", a.admit(CALLER, "t", "s", "f").code());
        state.resumeTool("t");
        assertTrue(a.admit(CALLER, "t", "s", "f").admitted());
    }

    @Test
    void approvalRequiredThenPendingWithoutDuplicate() {
        ToolAdmission a = admission(approval());
        AdmissionDecision first = a.admit(CALLER, "risky", "s", "f");
        assertEquals("APPROVAL_REQUIRED", first.code());
        assertFalse(first.admitted());
        AdmissionDecision second = a.admit(CALLER, "risky", "s", "f");
        assertEquals("APPROVAL_PENDING", second.code());
        assertEquals(first.approvalId(), second.approvalId());
        assertEquals(1, approvals.pending(CLOCK.instant()).size());
    }

    private static OversightPolicy capped(int max) {
        return new OversightPolicy(Set.of("risky"), OptionalInt.empty(), Duration.ofMinutes(1),
                Duration.ofHours(1), max);
    }

    @Test
    void aRequesterOverTheCapIsRefusedTooManyPendingAndNothingIsCreated() {
        ToolAdmission a = admission(capped(2));
        assertEquals("APPROVAL_REQUIRED", a.admit(CALLER, "risky", "s", "f1").code());
        assertEquals("APPROVAL_REQUIRED", a.admit(CALLER, "risky", "s", "f2").code());

        AdmissionDecision over = a.admit(CALLER, "risky", "s", "f3");

        assertFalse(over.admitted());
        assertEquals("TOO_MANY_PENDING", over.code());
        assertNull(over.approvalId());
        assertEquals(2, approvals.pending(CLOCK.instant()).size());
    }

    @Test
    void aMatchingRetryAtTheCapIsStillApprovalPending() {
        ToolAdmission a = admission(capped(1));
        String id = a.admit(CALLER, "risky", "s", "f1").approvalId();

        AdmissionDecision retry = a.admit(CALLER, "risky", "s", "f1");

        assertEquals("APPROVAL_PENDING", retry.code());
        assertEquals(id, retry.approvalId());
    }

    @Test
    void anotherCallerIsNotAffectedByTheCap() {
        ToolAdmission a = admission(capped(1));
        a.admit(CALLER, "risky", "s", "f1");
        AuthenticatedCaller bob = new AuthenticatedCaller("bob", "client", Set.of(), "purpose", "case-1", null);

        assertEquals("APPROVAL_REQUIRED", a.admit(bob, "risky", "s", "f1").code());
    }

    @Test
    void anyOtherStoreFailureOnCreateIsStillOversightUnavailable() {
        ApprovalStore failingCreate = (ApprovalStore) Proxy.newProxyInstance(
                ApprovalStore.class.getClassLoader(), new Class<?>[] {ApprovalStore.class}, (p, m, args) -> {
                    if (m.getName().equals("create")) {
                        throw new ApprovalRefusedException(ApprovalRefusedException.Code.NOT_PENDING);
                    }
                    try {
                        return m.invoke(approvals, args);
                    } catch (java.lang.reflect.InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
        ToolAdmission a = new ToolAdmission(state, failingCreate, limiter, approval(), CLOCK);

        assertEquals("OVERSIGHT_UNAVAILABLE", a.admit(CALLER, "risky", "s", "f").code());
    }

    @Test
    void approvedCallIsAdmittedOnceAndBoundToFingerprint() {
        ToolAdmission a = admission(approval());
        String id = a.admit(CALLER, "risky", "s", "f").approvalId();
        approvals.approve(id, "bob", CLOCK.instant());

        AdmissionDecision other = a.admit(CALLER, "risky", "s", "other");
        assertFalse(other.admitted());
        assertNotEquals(id, other.approvalId());

        AdmissionDecision ok = a.admit(CALLER, "risky", "s", "f");
        assertTrue(ok.admitted());
        assertEquals(id, ok.approvalId());
        assertEquals("bob", ok.approverPrincipalId());
        assertEquals("APPROVAL_REQUIRED", a.admit(CALLER, "risky", "s", "f").code());
    }

    @Test
    void stateFailureRefuses() {
        OversightState broken = proxy(OversightState.class);
        ToolAdmission a = new ToolAdmission(broken, approvals, limiter, OversightPolicy.none(), CLOCK);
        assertEquals("OVERSIGHT_UNAVAILABLE", a.admit(CALLER, "t", "s", "f").code());
    }

    @Test
    void approvalStoreFailureRefuses() {
        ApprovalStore broken = proxy(ApprovalStore.class);
        ToolAdmission a = new ToolAdmission(state, broken, limiter, approval(), CLOCK);
        AdmissionDecision d = a.admit(CALLER, "risky", "s", "f");
        assertFalse(d.admitted());
        assertEquals("OVERSIGHT_UNAVAILABLE", d.code());
    }

    @Test
    void rateLimiterFailureRefuses() {
        CallerRateLimiter broken = proxy(CallerRateLimiter.class);
        ToolAdmission a = new ToolAdmission(state, approvals, broken, limited(5), CLOCK);
        assertEquals("OVERSIGHT_UNAVAILABLE", a.admit(CALLER, "t", "s", "f").code());
    }

    @Test
    void noneAdmitsEverything() {
        assertTrue(ToolAdmission.none().admit(CALLER, "t", "s", "f").admitted());
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (p, m, args) -> {
            throw new IllegalStateException("down");
        });
    }
}

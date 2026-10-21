package io.github.aindriub.dataprism.reidentification;

import io.github.aindriub.dataprism.annotations.PrivacyNamespace;
import io.github.aindriub.dataprism.audit.AuditEvent;
import io.github.aindriub.dataprism.audit.AuditRecorder;
import io.github.aindriub.dataprism.core.metrics.Metric;
import io.github.aindriub.dataprism.core.metrics.PrivacyMetrics;
import io.github.aindriub.dataprism.oversight.ApprovalRefusedException;
import io.github.aindriub.dataprism.oversight.ApprovalRequest.Status;
import io.github.aindriub.dataprism.core.refusal.ClusterLockTimeoutException;
import io.github.aindriub.dataprism.core.refusal.ClusterOperationRefusedException;
import io.github.aindriub.dataprism.oversight.ApprovalStore;
import io.github.aindriub.dataprism.oversight.InMemoryApprovalStore;
import io.github.aindriub.dataprism.reidentification.ReidentificationOutcome.Approved;
import io.github.aindriub.dataprism.reidentification.ReidentificationOutcome.PendingApproval;
import io.github.aindriub.dataprism.reidentification.ReidentificationOutcome.Refused;
import io.github.aindriub.dataprism.reidentification.ReidentificationOutcome.Resolved;
import io.github.aindriub.dataprism.security.AuthenticatedCaller;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class ReidentificationServiceTest {

    private static final String SUBJECT = "SUBJ-9f3a-secret-id";
    private static final String SYNTH = "Alex Murphy 7QF2";

    private final List<AuditEvent> events = new ArrayList<>();
    private boolean auditDown;
    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2030-01-01T00:00:00Z"));
    private final Clock clock = new Clock() {
        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now.get(); }
    };
    private int lookups;
    private Optional<String> found = Optional.of(SUBJECT);

    private final InMemoryApprovalStore store = new InMemoryApprovalStore();

    private java.util.function.Consumer<AuditEvent> onEvent;
    private final AuditRecorder recorder = new AuditRecorder(e -> {
        if (auditDown) {
            throw new IllegalStateException("sink down");
        }
        if (onEvent != null) {
            onEvent.accept(e);
        }
        events.add(e);
    }, clock, "test");
    private final Map<Metric, Integer> counts = new java.util.EnumMap<>(Metric.class);
    private final PrivacyMetrics metrics = new PrivacyMetrics() {
        @Override public void increment(Metric metric) { counts.merge(metric, 1, Integer::sum); }
        @Override public void increment(Metric metric, String sourceName) { increment(metric); }
        @Override public void record(Metric metric, String sourceName, Duration duration) { }
    };

    private static final Map<String, Set<Permission>> ROLES = Map.of(
            "investigator", Set.of(Permission.REQUEST),
            "supervisor", Set.of(Permission.REQUEST, Permission.APPROVE),
            "viewer", Set.of());

    private ReidentificationService service(boolean fourEyes) {
        return service(fourEyes, ReidentificationPolicy.DEFAULT_MAX_PENDING_PER_REQUESTER);
    }

    private ReidentificationService service(boolean fourEyes, int maxPending) {
        return service(fourEyes, maxPending, store);
    }

    private ReidentificationService service(boolean fourEyes, int maxPending, ApprovalStore approvals) {
        ReidentificationPolicy policy = new ReidentificationPolicy(Set.of("fraud-investigation", "audit-review"), ROLES,
                fourEyes, Duration.ofMinutes(10), maxPending);
        return new ReidentificationService((scope, ns, synth) -> {
            lookups++;
            return found;
        }, approvals, recorder, policy, clock, metrics);
    }

    private static AuthenticatedCaller caller(String principal, String role) {
        return new AuthenticatedCaller(principal, "client-" + principal, Set.of(role), "fraud-investigation",
                "CASE-1", null);
    }

    private static ReidentificationRequest req(String purpose) {
        return new ReidentificationRequest("scope-1", PrivacyNamespace.PERSON_NAME, SYNTH, purpose, "CASE-1");
    }

    private void assertDenied(ReidentificationOutcome outcome, String code) {
        assertThat(outcome).isEqualTo(new Refused(code));
        assertThat(events).last().extracting(AuditEvent::policyDecision).isEqualTo("DENY:" + code);
    }

    @Test
    void withoutFourEyesRequestResolvesImmediately() {
        ReidentificationOutcome outcome = service(false).request(caller("alice", "investigator"),
                req("fraud-investigation"));
        assertThat(outcome).isEqualTo(new Resolved(SUBJECT));
        AuditEvent e = events.get(0);
        assertThat(e.tool()).isEqualTo("reidentify");
        assertThat(e.subjectPseudonym()).isEqualTo(SYNTH);
        assertThat(e.entityType()).isEqualTo("PERSON_NAME");
        assertThat(e.purpose()).isEqualTo("fraud-investigation");
        assertThat(e.caseId()).isEqualTo("CASE-1");
        assertThat(e.scopeId()).isEqualTo("scope-1");
        assertThat(e.principalId()).isEqualTo("alice");
        assertThat(e.clientId()).isEqualTo("client-alice");
    }

    @Test
    void notPermitted() {
        assertDenied(service(false).request(caller("v", "viewer"), req("fraud-investigation")),
                "REIDENTIFICATION_NOT_PERMITTED");
        assertThat(lookups).isZero();
    }

    @Test
    void purposeRequired() {
        assertDenied(service(false).request(caller("alice", "investigator"), req(" ")), "PURPOSE_REQUIRED");
    }

    @Test
    void purposeNotAllowed() {
        assertDenied(service(false).request(caller("alice", "investigator"), req("marketing")),
                "PURPOSE_NOT_ALLOWED");
        assertThat(lookups).isZero();
    }

    @Test
    void notFound() {
        found = Optional.empty();
        assertDenied(service(false).request(caller("alice", "investigator"), req("fraud-investigation")),
                "REIDENTIFICATION_NOT_FOUND");
    }

    @Test
    void fourEyesFlowResolvesOnlyAfterDistinctApproverAndOnlyOnce() {
        ReidentificationService s = service(true);
        AuthenticatedCaller alice = caller("alice", "supervisor");
        AuthenticatedCaller bob = caller("bob", "supervisor");
        ReidentificationOutcome pending = s.request(alice, req("fraud-investigation"));
        assertThat(pending).isInstanceOf(PendingApproval.class);
        assertThat(lookups).isZero();
        String id = ((PendingApproval) pending).approvalId();

        assertDenied(s.collect(alice, id), "APPROVAL_NOT_APPROVED");
        assertDenied(s.approve(alice, id), "SELF_APPROVAL");
        assertThat(s.approve(bob, id)).isEqualTo(new Approved());
        assertDenied(s.collect(bob, id), "NOT_REQUESTER");
        assertThat(lookups).isZero();

        assertThat(s.collect(alice, id)).isEqualTo(new Resolved(SUBJECT));
        AuditEvent resolved = events.get(events.size() - 1);
        assertThat(resolved.approvalId()).isEqualTo(id);
        assertThat(resolved.approverId()).isEqualTo("bob");
        assertThat(resolved.principalId()).isEqualTo("alice");
        assertThat(s.collect(alice, id)).isInstanceOf(Refused.class);
        assertThat(lookups).isEqualTo(1);
    }

    @Test
    void approverNeedsApprovePermission() {
        ReidentificationService s = service(true);
        String id = ((PendingApproval) s.request(caller("alice", "investigator"), req("fraud-investigation")))
                .approvalId();
        assertDenied(s.approve(caller("carol", "investigator"), id), "REIDENTIFICATION_NOT_PERMITTED");
    }

    @Test
    void unknownApproval() {
        ReidentificationService s = service(true);
        assertDenied(s.approve(caller("bob", "supervisor"), "nope"), "APPROVAL_NOT_FOUND");
        assertDenied(s.collect(caller("bob", "supervisor"), "nope"), "APPROVAL_NOT_FOUND");
    }

    @Test
    void expiredApproval() {
        ReidentificationService s = service(true);
        AuthenticatedCaller alice = caller("alice", "supervisor");
        String id = ((PendingApproval) s.request(alice, req("fraud-investigation"))).approvalId();
        s.approve(caller("bob", "supervisor"), id);
        String other = ((PendingApproval) s.request(alice, new ReidentificationRequest("scope-1",
                PrivacyNamespace.PERSON_NAME, "Other 1234", "fraud-investigation", "CASE-1"))).approvalId();
        now.set(now.get().plus(Duration.ofMinutes(11)));
        assertDenied(s.collect(alice, id), "APPROVAL_EXPIRED");
        assertDenied(s.approve(caller("bob", "supervisor"), other), "APPROVAL_EXPIRED");
        assertThat(lookups).isZero();
    }

    @Test
    void auditFailureReturnsNoSubject() {
        auditDown = true;
        ReidentificationOutcome outcome = service(false).request(caller("alice", "investigator"),
                req("fraud-investigation"));
        assertThat(outcome).isEqualTo(new Refused("AUDIT_UNAVAILABLE"));
        assertThat(outcome.toString()).doesNotContain(SUBJECT);
    }

    @Test
    void subjectIdAppearsInNoAuditField() {
        ReidentificationService s = service(true);
        AuthenticatedCaller alice = caller("alice", "supervisor");
        String id = ((PendingApproval) s.request(alice, req("fraud-investigation"))).approvalId();
        s.approve(caller("bob", "supervisor"), id);
        s.collect(alice, id);
        s.collect(alice, id);
        assertThat(events).isNotEmpty();
        for (AuditEvent e : events) {
            assertThat(e.toString()).doesNotContain(SUBJECT);
        }
    }

    private static ReidentificationRequest req(String purpose, String caseId) {
        return new ReidentificationRequest("scope-1", PrivacyNamespace.PERSON_NAME, SYNTH, purpose, caseId);
    }

    @Test
    void collectConsumesExactlyTheNamedApproval() {
        ReidentificationService s = service(true);
        AuthenticatedCaller alice = caller("alice", "supervisor");
        String a = ((PendingApproval) s.request(alice, req("fraud-investigation", "C1"))).approvalId();
        s.approve(caller("bob", "supervisor"), a);
        String b = ((PendingApproval) s.request(alice, req("fraud-investigation", "C2"))).approvalId();
        assertThat(b).isNotEqualTo(a);
        s.approve(caller("carol", "supervisor"), b);

        assertThat(s.collect(alice, b)).isEqualTo(new Resolved(SUBJECT));
        AuditEvent resolvedB = events.get(events.size() - 1);
        assertThat(resolvedB.approvalId()).isEqualTo(b);
        assertThat(resolvedB.approverId()).isEqualTo("carol");
        assertThat(resolvedB.caseId()).isEqualTo("C2");

        assertThat(s.collect(alice, b)).isInstanceOf(Refused.class);
        assertThat(lookups).isEqualTo(1);

        assertThat(s.collect(alice, a)).isEqualTo(new Resolved(SUBJECT));
        AuditEvent resolvedA = events.get(events.size() - 1);
        assertThat(resolvedA.approvalId()).isEqualTo(a);
        assertThat(resolvedA.approverId()).isEqualTo("bob");
        assertThat(resolvedA.caseId()).isEqualTo("C1");
        assertThat(lookups).isEqualTo(2);
    }

    @Test
    void differentPurposeOrCaseNeverReusesAnApproval() {
        ReidentificationService s = service(true);
        AuthenticatedCaller alice = caller("alice", "supervisor");
        String a = ((PendingApproval) s.request(alice, req("fraud-investigation", "C1"))).approvalId();
        String otherPurpose = ((PendingApproval) s.request(alice, req("audit-review", "C1"))).approvalId();
        String otherCase = ((PendingApproval) s.request(alice, req("fraud-investigation", "C2"))).approvalId();
        assertThat(Set.of(a, otherPurpose, otherCase)).hasSize(3);
        AuditEvent last = events.get(events.size() - 1);
        assertThat(last.caseId()).isEqualTo("C2");
        assertThat(last.approvalId()).isEqualTo(otherCase);
    }

    @Test
    void approveAuditFailureLeavesNothingUsable() {
        ReidentificationService s = service(true);
        AuthenticatedCaller alice = caller("alice", "supervisor");
        String id = ((PendingApproval) s.request(alice, req("fraud-investigation"))).approvalId();
        auditDown = true;
        assertThat(s.approve(caller("bob", "supervisor"), id)).isEqualTo(new Refused("AUDIT_UNAVAILABLE"));
        auditDown = false;
        assertThat(s.collect(alice, id)).isInstanceOf(Refused.class);
        assertThat(lookups).isZero();
    }

    /** A store that delegates to the in-memory one, except where {@code intercept} answers for a method. */
    private interface Intercept {
        Object call(String method, Object[] args, Delegate delegate) throws Throwable;
    }

    private interface Delegate {
        Object call() throws Throwable;
    }

    private ApprovalStore intercepting(Intercept intercept) {
        return (ApprovalStore) Proxy.newProxyInstance(ApprovalStore.class.getClassLoader(),
                new Class<?>[] {ApprovalStore.class}, (p, m, args) -> intercept.call(m.getName(), args, (Delegate) () -> {
                    try {
                        return m.invoke(store, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                }));
    }

    private String requested(ReidentificationService s) {
        return ((PendingApproval) s.request(caller("alice", "supervisor"), req("fraud-investigation"))).approvalId();
    }

    private List<AuditEvent> decisionEvents(String id) {
        return events.stream().filter(e -> id.equals(e.approvalId()) && e.policyDecision().contains("APPROVED")
                || id.equals(e.approvalId()) && e.policyDecision().startsWith("DENY")).toList();
    }

    @Test
    void approveWithAuditDownLeavesTheApprovalPendingEvenWhenAnUndoWouldFail() {
        java.util.concurrent.atomic.AtomicInteger consumes = new java.util.concurrent.atomic.AtomicInteger();
        ApprovalStore failingUndo = intercepting((method, args, delegate) -> {
            if (method.equals("consumeApproved") && consumes.getAndIncrement() == 0) {
                throw new IllegalStateException("undo failed");
            }
            return delegate.call();
        });
        ReidentificationService s = service(true, 5, failingUndo);
        String id = requested(s);
        auditDown = true;
        assertThat(s.approve(caller("bob", "supervisor"), id)).isEqualTo(new Refused("AUDIT_UNAVAILABLE"));
        auditDown = false;
        assertThat(s.collect(caller("alice", "supervisor"), id)).isEqualTo(new Refused("APPROVAL_NOT_APPROVED"));
        assertThat(lookups).isZero();
        assertThat(store.find(id)).get().extracting(a -> a.status()).isEqualTo(Status.PENDING);
        assertThat(counts).containsEntry(Metric.APPROVAL_DECISION_AUDIT_UNAVAILABLE, 1)
                .doesNotContainKey(Metric.APPROVAL_DECISION_UNCONFIRMED);
    }

    @Test
    void theApprovalIsStillPendingWhenItsAllowEventIsWritten() {
        ReidentificationService s = service(true);
        String id = requested(s);
        List<Status> seen = new ArrayList<>();
        onEvent = e -> {
            if (e.policyDecision().equals("ALLOW:APPROVED")) {
                seen.add(store.find(id).orElseThrow().status());
            }
        };
        assertThat(s.approve(caller("bob", "supervisor"), id)).isInstanceOf(Approved.class);
        assertThat(seen).containsExactly(Status.PENDING);
    }

    @Test
    void anApproveThatLosesARaceIsAuditedAsAllowThenALinkedDeny() {
        ApprovalStore raced = intercepting((method, args, delegate) -> {
            if (method.equals("approve")) {
                throw new ApprovalRefusedException(ApprovalRefusedException.Code.NOT_PENDING);
            }
            return delegate.call();
        });
        ReidentificationService s = service(true, 5, raced);
        String id = requested(s);
        assertThat(s.approve(caller("bob", "supervisor"), id)).isEqualTo(new Refused("APPROVAL_NOT_PENDING"));
        List<AuditEvent> pair = decisionEvents(id);
        assertThat(pair).extracting(AuditEvent::policyDecision).containsExactly("ALLOW:APPROVED",
                "DENY:APPROVAL_NOT_PENDING");
        assertThat(pair.get(1).correlationId()).isEqualTo(pair.get(0).correlationId());
        assertThat(counts).doesNotContainKey(Metric.APPROVAL_DECISION_UNCONFIRMED);
    }

    @Test
    void anApproveWhoseReplyIsLostAfterItAppliedIsSuccessWithoutADeny() {
        ApprovalStore lostReply = intercepting((method, args, delegate) -> {
            Object result = delegate.call();
            if (method.equals("approve")) {
                throw new IllegalStateException("reply lost");
            }
            return result;
        });
        ReidentificationService s = service(true, 5, lostReply);
        String id = requested(s);
        assertThat(s.approve(caller("bob", "supervisor"), id)).isInstanceOf(Approved.class);
        assertThat(decisionEvents(id)).extracting(AuditEvent::policyDecision).containsExactly("ALLOW:APPROVED");
        assertThat(counts).isEmpty();
    }

    @Test
    void anApproveThatCannotBeConfirmedIsRefusedWithALinkedDenyAndCounted() {
        ApprovalStore neverApplied = intercepting((method, args, delegate) -> {
            if (method.equals("approve")) {
                throw new IllegalStateException("store down");
            }
            return delegate.call();
        });
        ReidentificationService s = service(true, 5, neverApplied);
        String id = requested(s);
        assertThat(s.approve(caller("bob", "supervisor"), id)).isEqualTo(new Refused("REIDENTIFICATION_UNAVAILABLE"));
        List<AuditEvent> pair = decisionEvents(id);
        assertThat(pair).extracting(AuditEvent::policyDecision).containsExactly("ALLOW:APPROVED",
                "DENY:REIDENTIFICATION_UNAVAILABLE");
        assertThat(pair.get(1).correlationId()).isEqualTo(pair.get(0).correlationId());
        assertThat(counts).containsEntry(Metric.APPROVAL_DECISION_UNCONFIRMED, 1);
    }

    @Test
    void anotherApproversDecisionIsNotThisApproversSuccess() {
        ApprovalStore other = intercepting((method, args, delegate) -> {
            if (method.equals("approve")) {
                store.approve((String) args[0], "carol", now.get());
                throw new IllegalStateException("reply lost");
            }
            return delegate.call();
        });
        ReidentificationService s = service(true, 5, other);
        String id = requested(s);
        assertThat(s.approve(caller("bob", "supervisor"), id)).isEqualTo(new Refused("REIDENTIFICATION_UNAVAILABLE"));
        assertThat(decisionEvents(id)).extracting(AuditEvent::policyDecision).containsExactly("ALLOW:APPROVED",
                "DENY:REIDENTIFICATION_UNAVAILABLE");
    }

    @Test
    void aDecidedStatusThatCarriesThisApproverIsNotSuccess() {
        ApprovalStore rejecting = intercepting((method, args, delegate) -> {
            if (method.equals("approve")) {
                store.reject((String) args[0], "bob", now.get());
                throw new IllegalStateException("reply lost");
            }
            return delegate.call();
        });
        ReidentificationService s = service(true, 5, rejecting);
        String id = requested(s);
        assertThat(s.approve(caller("bob", "supervisor"), id)).isEqualTo(new Refused("REIDENTIFICATION_UNAVAILABLE"));
    }

    @Test
    void aClusterRefusalBeforeTheCommitIsAllowThenALinkedDeny() {
        for (RuntimeException failure : List.<RuntimeException>of(new ClusterLockTimeoutException(),
                new ClusterOperationRefusedException(new IllegalArgumentException("x")))) {
            events.clear();
            ApprovalStore refusing = intercepting((method, args, delegate) -> {
                if (method.equals("approve")) {
                    throw failure;
                }
                return delegate.call();
            });
            ReidentificationService s = service(true, 5, refusing);
            String id = requested(s);
            String code = failure instanceof ClusterLockTimeoutException
                    ? ClusterLockTimeoutException.CODE : ClusterOperationRefusedException.CODE;
            assertThat(s.approve(caller("bob", "supervisor"), id)).isEqualTo(new Refused(code));
            List<AuditEvent> pair = decisionEvents(id);
            assertThat(pair).extracting(AuditEvent::policyDecision).containsExactly("ALLOW:APPROVED", "DENY:" + code);
            assertThat(pair.get(1).correlationId()).isEqualTo(pair.get(0).correlationId());
            assertThat(pair.get(1).approvalId()).isEqualTo(pair.get(0).approvalId()).isEqualTo(id);
        }
    }

    @Test
    void aLinkedDenyThatCannotBeWrittenKeepsTheDecisionsCodeAndIsCounted() {
        ApprovalStore raced = intercepting((method, args, delegate) -> {
            if (method.equals("approve")) {
                throw new ApprovalRefusedException(ApprovalRefusedException.Code.NOT_PENDING);
            }
            return delegate.call();
        });
        ReidentificationService s = service(true, 5, raced);
        String id = requested(s);
        onEvent = e -> {
            if (e.policyDecision().startsWith("DENY")) {
                throw new IllegalStateException("sink down");
            }
        };
        assertThat(s.approve(caller("bob", "supervisor"), id)).isEqualTo(new Refused("APPROVAL_NOT_PENDING"));
        assertThat(counts).containsEntry(Metric.APPROVAL_DECISION_UNCONFIRMED, 1)
                .doesNotContainKey(Metric.APPROVAL_DECISION_AUDIT_UNAVAILABLE);
    }

    @Test
    void anApproveTheStoreWouldRefuseIsDeniedOnceWithoutAnAllowAndNeverReachesTheStore() {
        int[] approves = {0};
        ApprovalStore counting = intercepting((method, args, delegate) -> {
            if (method.equals("approve")) {
                approves[0]++;
            }
            return delegate.call();
        });
        ReidentificationService s = service(true, 5, counting);
        String self = requested(s);
        String expired = requested(s);
        String decided = requested(s);
        assertThat(s.approve(caller("bob", "supervisor"), decided)).isInstanceOf(Approved.class);
        events.clear();
        approves[0] = 0;
        counts.clear();
        assertThat(s.approve(caller("alice", "supervisor"), self)).isEqualTo(new Refused("SELF_APPROVAL"));
        assertThat(s.approve(caller("bob", "supervisor"), decided)).isEqualTo(new Refused("APPROVAL_NOT_PENDING"));
        now.set(now.get().plus(Duration.ofHours(1)));
        assertThat(s.approve(caller("bob", "supervisor"), expired)).isEqualTo(new Refused("APPROVAL_EXPIRED"));
        assertThat(events).extracting(AuditEvent::policyDecision)
                .containsExactly("DENY:SELF_APPROVAL", "DENY:APPROVAL_NOT_PENDING", "DENY:APPROVAL_EXPIRED");
        assertThat(approves[0]).isZero();
        assertThat(counts).isEmpty();
    }

    @Test
    void requestAuditFailureLeavesNoPendingApproval() {
        ReidentificationService s = service(true);
        auditDown = true;
        assertThat(s.request(caller("alice", "supervisor"), req("fraud-investigation")))
                .isEqualTo(new Refused("AUDIT_UNAVAILABLE"));
        assertThat(store.pending(now.get())).isEmpty();
    }

    @Test
    void collectRechecksRequestPermission() {
        ReidentificationService s = service(true);
        String id = ((PendingApproval) s.request(caller("alice", "supervisor"), req("fraud-investigation")))
                .approvalId();
        s.approve(caller("bob", "supervisor"), id);
        assertDenied(s.collect(caller("alice", "viewer"), id), "REIDENTIFICATION_NOT_PERMITTED");
        assertThat(lookups).isZero();
    }

    @Test
    void maxPendingDefaultsToFiveAndMustBePositive() {
        assertThat(new ReidentificationPolicy(Set.of(), ROLES, true, Duration.ofMinutes(1))
                .maxPendingPerRequester()).isEqualTo(5);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new ReidentificationPolicy(Set.of(), ROLES, true, Duration.ofMinutes(1), 0));
    }

    @Test
    void aRequesterOverTheCapIsRefusedAndAuditedWithoutAnApprovalId() {
        ReidentificationService s = service(true, 2);
        AuthenticatedCaller alice = caller("alice", "supervisor");
        assertThat(s.request(alice, req("fraud-investigation"))).isInstanceOf(PendingApproval.class);
        assertThat(s.request(alice, req("fraud-investigation"))).isInstanceOf(PendingApproval.class);
        int before = events.size();

        assertDenied(s.request(alice, req("fraud-investigation")), "TOO_MANY_PENDING");

        assertThat(events).hasSize(before + 1);
        assertThat(events.get(before).approvalId()).isNullOrEmpty();
        assertThat(store.pending(now.get())).hasSize(2);
        assertThat(s.request(caller("bob", "supervisor"), req("fraud-investigation")))
                .isInstanceOf(PendingApproval.class);
    }

    @Test
    void anAuditFailureOnTheCapRefusalIsAuditUnavailableAndNothingIsStored() {
        ReidentificationService s = service(true, 1);
        AuthenticatedCaller alice = caller("alice", "supervisor");
        s.request(alice, req("fraud-investigation"));
        auditDown = true;

        assertThat(s.request(alice, req("fraud-investigation"))).isEqualTo(new Refused("AUDIT_UNAVAILABLE"));

        assertThat(store.pending(now.get())).hasSize(1);
    }

    // ----- a cluster refusal reaches the caller as its own code

    private static final String SENTINEL = "sentinel-key-x-1";

    private static List<RuntimeException> clusterFailures() {
        return List.of(new ClusterOperationRefusedException(new IllegalArgumentException(SENTINEL)),
                new ClusterLockTimeoutException());
    }

    /** The shared in-memory store, except that the one named method throws {@code failure}. */
    private ApprovalStore storeThrowingOn(String method, RuntimeException failure) {
        return (ApprovalStore) Proxy.newProxyInstance(ApprovalStore.class.getClassLoader(),
                new Class<?>[] {ApprovalStore.class}, (p, m, args) -> {
                    if (m.getName().equals(method)) {
                        throw failure;
                    }
                    try {
                        return m.invoke(store, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    private ReidentificationService serviceOver(ApprovalStore approvals) {
        ReidentificationPolicy policy = new ReidentificationPolicy(Set.of("fraud-investigation", "audit-review"),
                ROLES, true, Duration.ofMinutes(10), ReidentificationPolicy.DEFAULT_MAX_PENDING_PER_REQUESTER);
        return new ReidentificationService((scope, ns, synth) -> {
            lookups++;
            return found;
        }, approvals, recorder, policy, clock);
    }

    private String approvedId() {
        ReidentificationService real = service(true);
        String id = ((PendingApproval) real.request(caller("alice", "supervisor"), req("fraud-investigation")))
                .approvalId();
        real.approve(caller("bob", "supervisor"), id);
        events.clear();
        return id;
    }

    @Test
    void aClusterRefusalWhenOpeningARequestIsRefusedWithItsOwnCode() {
        for (RuntimeException failure : clusterFailures()) {
            events.clear();
            String code = ((io.github.aindriub.dataprism.core.refusal.PrivacyRefusedException) failure).code();
            assertDenied(serviceOver(storeThrowingOn("create", failure))
                    .request(caller("alice", "supervisor"), req("fraud-investigation")), code);
            assertThat(lookups).isZero();
        }
    }

    @Test
    void aClusterRefusalWhenApprovingIsRefusedWithItsOwnCodeOnBothCalls() {
        for (RuntimeException failure : clusterFailures()) {
            String code = ((io.github.aindriub.dataprism.core.refusal.PrivacyRefusedException) failure).code();
            for (String method : new String[] {"find", "approve"}) {
                String id = ((PendingApproval) service(true).request(caller("alice", "supervisor"),
                        req("fraud-investigation"))).approvalId();
                events.clear();
                assertDenied(serviceOver(storeThrowingOn(method, failure)).approve(caller("bob", "supervisor"), id),
                        code);
            }
        }
    }

    @Test
    void aClusterRefusalWhenCollectingIsRefusedWithItsOwnCodeAndResolvesNothing() {
        for (RuntimeException failure : clusterFailures()) {
            String code = ((io.github.aindriub.dataprism.core.refusal.PrivacyRefusedException) failure).code();
            for (String method : new String[] {"find", "consumeApproved"}) {
                String id = approvedId();
                assertDenied(serviceOver(storeThrowingOn(method, failure)).collect(caller("alice", "supervisor"), id),
                        code);
            }
        }
        assertThat(lookups).isZero();
    }

    @Test
    void aClusterRefusalInTheLookupWithoutFourEyesIsRefusedWithItsOwnCode() {
        for (RuntimeException failure : clusterFailures()) {
            String code = ((io.github.aindriub.dataprism.core.refusal.PrivacyRefusedException) failure).code();
            events.clear();
            ReidentificationPolicy policy = new ReidentificationPolicy(Set.of("fraud-investigation"), ROLES, false,
                    Duration.ofMinutes(10), ReidentificationPolicy.DEFAULT_MAX_PENDING_PER_REQUESTER);
            var s = new ReidentificationService((scope, ns, synth) -> {
                throw failure;
            }, store, recorder, policy, clock);
            assertDenied(s.request(caller("alice", "investigator"), req("fraud-investigation")), code);
        }
    }

    @Test
    void aClusterRefusalWithTheAuditSinkDownStillRefusesAndResolvesNothing() {
        // The denial's audit write fails (task 245): the answer is AUDIT_UNAVAILABLE, never the refusal's own code
        // as if it had been recorded, and never a subject.
        for (RuntimeException failure : clusterFailures()) {
            auditDown = true;
            events.clear();
            assertThat(serviceOver(storeThrowingOn("create", failure))
                    .request(caller("alice", "supervisor"), req("fraud-investigation")))
                    .isEqualTo(new Refused("AUDIT_UNAVAILABLE"));
            assertThat(events).isEmpty();
        }
        assertThat(lookups).isZero();
    }

    @Test
    void anyOtherFailureIsStillReidentificationUnavailableOnEveryCall() {
        RuntimeException other = new IllegalStateException(SENTINEL);
        assertDenied(serviceOver(storeThrowingOn("create", other))
                .request(caller("alice", "supervisor"), req("fraud-investigation")), "REIDENTIFICATION_UNAVAILABLE");
        String pendingId = ((PendingApproval) service(true).request(caller("alice", "supervisor"),
                req("fraud-investigation"))).approvalId();
        assertDenied(serviceOver(storeThrowingOn("find", other)).approve(caller("bob", "supervisor"), pendingId),
                "REIDENTIFICATION_UNAVAILABLE");
        assertDenied(serviceOver(storeThrowingOn("approve", other)).approve(caller("bob", "supervisor"), pendingId),
                "REIDENTIFICATION_UNAVAILABLE");
        String approved = approvedId();
        assertDenied(serviceOver(storeThrowingOn("find", other)).collect(caller("alice", "supervisor"), approved),
                "REIDENTIFICATION_UNAVAILABLE");
        assertDenied(serviceOver(storeThrowingOn("consumeApproved", other))
                .collect(caller("alice", "supervisor"), approved), "REIDENTIFICATION_UNAVAILABLE");
    }
}

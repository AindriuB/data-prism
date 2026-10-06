package io.github.aindriub.dataprism.reidentification;

import io.github.aindriub.dataprism.annotations.PrivacyNamespace;
import io.github.aindriub.dataprism.audit.AuditEvent;
import io.github.aindriub.dataprism.audit.AuditRecorder;
import io.github.aindriub.dataprism.oversight.InMemoryApprovalStore;
import io.github.aindriub.dataprism.reidentification.ReidentificationOutcome.Approved;
import io.github.aindriub.dataprism.reidentification.ReidentificationOutcome.PendingApproval;
import io.github.aindriub.dataprism.reidentification.ReidentificationOutcome.Refused;
import io.github.aindriub.dataprism.reidentification.ReidentificationOutcome.Resolved;
import io.github.aindriub.dataprism.security.AuthenticatedCaller;
import org.junit.jupiter.api.Test;

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

    private final AuditRecorder recorder = new AuditRecorder(e -> {
        if (auditDown) {
            throw new IllegalStateException("sink down");
        }
        events.add(e);
    }, clock, "test");

    private static final Map<String, Set<Permission>> ROLES = Map.of(
            "investigator", Set.of(Permission.REQUEST),
            "supervisor", Set.of(Permission.REQUEST, Permission.APPROVE),
            "viewer", Set.of());

    private ReidentificationService service(boolean fourEyes) {
        ReidentificationPolicy policy = new ReidentificationPolicy(Set.of("fraud-investigation"), ROLES,
                fourEyes, Duration.ofMinutes(10));
        return new ReidentificationService((scope, ns, synth) -> {
            lookups++;
            return found;
        }, new InMemoryApprovalStore(), recorder, policy, clock);
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
}

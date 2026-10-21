package io.github.aindriub.dataprism.reidentification;

import io.github.aindriub.dataprism.annotations.PrivacyNamespace;
import io.github.aindriub.dataprism.audit.AuditEvent;
import io.github.aindriub.dataprism.audit.AuditRecorder;
import io.github.aindriub.dataprism.core.metrics.Metric;
import io.github.aindriub.dataprism.core.metrics.PrivacyMetrics;
import io.github.aindriub.dataprism.oversight.ApprovalStore;
import io.github.aindriub.dataprism.oversight.InMemoryApprovalStore;
import io.github.aindriub.dataprism.reidentification.ReidentificationOutcome.PendingApproval;
import io.github.aindriub.dataprism.reidentification.ReidentificationOutcome.Refused;
import io.github.aindriub.dataprism.security.AuthenticatedCaller;
import org.junit.jupiter.api.Test;

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
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A refusal whose DENY event cannot be written is answered {@code AUDIT_UNAVAILABLE} and counted, never
 * answered with its own code as if it had been recorded. A refusal whose event is written keeps its code.
 */
class DenialAuditUnavailableServiceTest {

    private static final String SENTINEL = "sink-secret-path-/var/audit/unwritable";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2030-01-01T00:00:00Z"), ZoneOffset.UTC);
    private static final Map<String, Set<Permission>> ROLES = Map.of(
            "investigator", Set.of(Permission.REQUEST),
            "supervisor", Set.of(Permission.REQUEST, Permission.APPROVE),
            "viewer", Set.of());

    private final List<AuditEvent> events = new ArrayList<>();
    private final Map<Metric, Integer> counts = new EnumMap<>(Metric.class);
    private final InMemoryApprovalStore store = new InMemoryApprovalStore();
    private boolean auditDown;
    private boolean storeDown;
    private Optional<String> found = Optional.of("SUBJ-1");

    private final AuditRecorder recorder = new AuditRecorder(event -> {
        if (auditDown) {
            throw new IllegalStateException(SENTINEL);
        }
        events.add(event);
    }, CLOCK, "test");
    private final PrivacyMetrics metrics = new PrivacyMetrics() {
        @Override public void increment(Metric metric) { counts.merge(metric, 1, Integer::sum); }
        @Override public void increment(Metric metric, String sourceName) { increment(metric); }
        @Override public void record(Metric metric, String sourceName, Duration duration) { }
    };

    private ApprovalStore flaky() {
        return (ApprovalStore) Proxy.newProxyInstance(ApprovalStore.class.getClassLoader(),
                new Class<?>[] {ApprovalStore.class}, (p, m, args) -> {
                    if (storeDown && m.getName().equals("find")) {
                        throw new IllegalStateException(SENTINEL);
                    }
                    try {
                        return m.invoke(store, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    private ReidentificationService service(boolean fourEyes, int maxPending) {
        ReidentificationPolicy policy = new ReidentificationPolicy(Set.of("fraud-investigation"), ROLES, fourEyes,
                Duration.ofMinutes(10), maxPending);
        return new ReidentificationService((scope, ns, synth) -> found, flaky(), recorder, policy, CLOCK, metrics);
    }

    private ReidentificationService service(boolean fourEyes) {
        return service(fourEyes, ReidentificationPolicy.DEFAULT_MAX_PENDING_PER_REQUESTER);
    }

    private static AuthenticatedCaller caller(String principal, String role) {
        return new AuthenticatedCaller(principal, "client-" + principal, Set.of(role), "fraud-investigation",
                "CASE-1", null);
    }

    private static ReidentificationRequest req(String purpose) {
        return new ReidentificationRequest("scope-1", PrivacyNamespace.PERSON_NAME, "Alex Murphy 7QF2", purpose,
                "CASE-1");
    }

    private String pending(ReidentificationService service) {
        ReidentificationOutcome outcome = service.request(caller("alice", "investigator"),
                req("fraud-investigation"));
        return ((PendingApproval) outcome).approvalId();
    }

    private void assertUnavailableAndCounted(ReidentificationOutcome outcome) {
        assertThat(outcome).isEqualTo(new Refused("AUDIT_UNAVAILABLE"));
        assertThat(outcome.toString()).doesNotContain(SENTINEL);
        assertThat(counts).containsEntry(Metric.DENIAL_AUDIT_UNAVAILABLE, 1);
    }

    private void assertOwnCode(ReidentificationOutcome outcome, String code) {
        assertThat(outcome).isEqualTo(new Refused(code));
        assertThat(events).last().extracting(AuditEvent::policyDecision).isEqualTo("DENY:" + code);
        assertThat(counts).doesNotContainKey(Metric.DENIAL_AUDIT_UNAVAILABLE);
    }

    // ---- request -------------------------------------------------------------------------------

    @Test
    void aRequestRefusedAsNotPermittedIsAnsweredAuditUnavailableWhenItsDenyCannotBeWritten() {
        auditDown = true;
        assertUnavailableAndCounted(service(false).request(caller("v", "viewer"), req("fraud-investigation")));
    }

    @Test
    void aRequestRefusedForNoPurposeIsAnsweredAuditUnavailableWhenItsDenyCannotBeWritten() {
        auditDown = true;
        assertUnavailableAndCounted(service(false).request(caller("a", "investigator"), req("")));
    }

    @Test
    void aRequestRefusedForADisallowedPurposeIsAnsweredAuditUnavailableWhenItsDenyCannotBeWritten() {
        auditDown = true;
        assertUnavailableAndCounted(service(false).request(caller("a", "investigator"), req("marketing")));
    }

    @Test
    void aRequestForAnUnknownSyntheticValueIsAnsweredAuditUnavailableWhenItsDenyCannotBeWritten() {
        found = Optional.empty();
        auditDown = true;
        assertUnavailableAndCounted(service(false).request(caller("a", "investigator"),
                req("fraud-investigation")));
    }

    @Test
    void aRequestWhoseLookupFailsIsAnsweredAuditUnavailableWhenItsDenyCannotBeWritten() {
        ReidentificationPolicy policy = new ReidentificationPolicy(Set.of("fraud-investigation"), ROLES, false,
                Duration.ofMinutes(10), 5);
        var service = new ReidentificationService((scope, ns, synth) -> {
            throw new IllegalStateException(SENTINEL);
        }, flaky(), recorder, policy, CLOCK, metrics);
        auditDown = true;
        assertUnavailableAndCounted(service.request(caller("a", "investigator"), req("fraud-investigation")));
    }

    @Test
    void aRequestRefusedForTooManyPendingIsAnsweredAuditUnavailableAndCountedWhenItsDenyCannotBeWritten() {
        ReidentificationService service = service(true, 1);
        pending(service);
        auditDown = true;
        assertUnavailableAndCounted(service.request(caller("alice", "investigator"), req("fraud-investigation")));
    }

    // ---- approve -------------------------------------------------------------------------------

    @Test
    void anApproveRefusedAsNotPermittedIsAnsweredAuditUnavailableWhenItsDenyCannotBeWritten() {
        ReidentificationService service = service(true);
        String id = pending(service);
        auditDown = true;
        assertUnavailableAndCounted(service.approve(caller("v", "viewer"), id));
    }

    @Test
    void anApproveOfAnUnknownApprovalIsAnsweredAuditUnavailableWhenItsDenyCannotBeWritten() {
        auditDown = true;
        assertUnavailableAndCounted(service(true).approve(caller("bob", "supervisor"), "no-such-approval"));
    }

    @Test
    void aSelfApprovalIsAnsweredAuditUnavailableWhenItsDenyCannotBeWritten() {
        ReidentificationService service = service(true);
        String id = pending(service);
        auditDown = true;
        assertUnavailableAndCounted(service.approve(caller("alice", "supervisor"), id));
    }

    @Test
    void anApproveWhoseStoreIsUnreadableIsAnsweredAuditUnavailableWhenItsDenyCannotBeWritten() {
        ReidentificationService service = service(true);
        String id = pending(service);
        storeDown = true;
        auditDown = true;
        assertUnavailableAndCounted(service.approve(caller("bob", "supervisor"), id));
    }

    // ---- collect -------------------------------------------------------------------------------

    @Test
    void aCollectOfAnUnknownApprovalIsAnsweredAuditUnavailableWhenItsDenyCannotBeWritten() {
        auditDown = true;
        assertUnavailableAndCounted(service(true).collect(caller("alice", "investigator"), "no-such-approval"));
    }

    @Test
    void aCollectByAnotherPrincipalIsAnsweredAuditUnavailableWhenItsDenyCannotBeWritten() {
        ReidentificationService service = service(true);
        String id = pending(service);
        auditDown = true;
        assertUnavailableAndCounted(service.collect(caller("mallory", "investigator"), id));
    }

    @Test
    void aCollectOfAnUnapprovedApprovalIsAnsweredAuditUnavailableWhenItsDenyCannotBeWritten() {
        ReidentificationService service = service(true);
        String id = pending(service);
        auditDown = true;
        assertUnavailableAndCounted(service.collect(caller("alice", "investigator"), id));
    }

    @Test
    void aCollectWhoseStoreIsUnreadableIsAnsweredAuditUnavailableWhenItsDenyCannotBeWritten() {
        ReidentificationService service = service(true);
        String id = pending(service);
        storeDown = true;
        auditDown = true;
        assertUnavailableAndCounted(service.collect(caller("alice", "investigator"), id));
    }

    // ---- audit up: own code, no metric ----------------------------------------------------------

    @Test
    void aRefusalWhoseAuditIsWrittenKeepsItsOwnCode() {
        assertOwnCode(service(false).request(caller("v", "viewer"), req("fraud-investigation")),
                "REIDENTIFICATION_NOT_PERMITTED");
        assertOwnCode(service(false).request(caller("a", "investigator"), req("")), "PURPOSE_REQUIRED");
        assertOwnCode(service(true).approve(caller("bob", "supervisor"), "no-such-approval"), "APPROVAL_NOT_FOUND");
        assertOwnCode(service(true).collect(caller("alice", "investigator"), "no-such-approval"),
                "APPROVAL_NOT_FOUND");
    }
}

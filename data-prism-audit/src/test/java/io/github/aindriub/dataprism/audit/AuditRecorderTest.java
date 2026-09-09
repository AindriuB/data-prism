package io.github.aindriub.dataprism.audit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The chain is only tamper-evident if every field that matters is actually in
 * the hash. A field added to {@link AuditEvent} and forgotten in the body is a
 * gap nothing here would ever notice, so each new field this task adds gets its
 * own mutation.
 */
class AuditRecorderTest {

    private static final Clock FIXED = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);

    /** A fresh recorder per call, so the only difference between two events is the one under test. */
    private static AuditRecorder recorder() {
        List<AuditEvent> sink = new ArrayList<>();
        return new AuditRecorder(sink::add, FIXED, "instance-1");
    }

    private static AuditEvent baseline(AuditRecorder recorder, String principalId, String clientId,
                                       String purpose, String caseId, Set<String> rejectedArguments) {
        return recorder.record(principalId, clientId, "get_entity_context", "CUSTOMER", "SUBJ-1",
                "fp-1", "DEFAULT", "CASE-1", purpose, caseId, "ALLOW",
                Set.of("customer-api:ANSWERED"), rejectedArguments, "corr-1");
    }

    @Test
    @DisplayName("changing the principal alone changes the event hash")
    void principalIdAffectsHash() {
        AuditEvent a = baseline(recorder(), "investigator-1", "client-1", "investigation", "CASE-1", Set.of());
        AuditEvent b = baseline(recorder(), "investigator-2", "client-1", "investigation", "CASE-1", Set.of());

        assertThat(b.eventHash()).isNotEqualTo(a.eventHash());
    }

    @Test
    @DisplayName("changing the client alone changes the event hash")
    void clientIdAffectsHash() {
        AuditEvent a = baseline(recorder(), "investigator-1", "client-1", "investigation", "CASE-1", Set.of());
        AuditEvent b = baseline(recorder(), "investigator-1", "client-2", "investigation", "CASE-1", Set.of());

        assertThat(b.eventHash()).isNotEqualTo(a.eventHash());
    }

    @Test
    @DisplayName("changing the purpose alone changes the event hash")
    void purposeAffectsHash() {
        AuditEvent a = baseline(recorder(), "investigator-1", "client-1", "investigation", "CASE-1", Set.of());
        AuditEvent b = baseline(recorder(), "investigator-1", "client-1", "audit-review", "CASE-1", Set.of());

        assertThat(b.eventHash()).isNotEqualTo(a.eventHash());
    }

    @Test
    @DisplayName("changing the case id alone changes the event hash")
    void caseIdAffectsHash() {
        AuditEvent a = baseline(recorder(), "investigator-1", "client-1", "investigation", "CASE-1", Set.of());
        AuditEvent b = baseline(recorder(), "investigator-1", "client-1", "investigation", "CASE-2", Set.of());

        assertThat(b.eventHash()).isNotEqualTo(a.eventHash());
    }

    @Test
    @DisplayName("changing the rejected arguments alone changes the event hash")
    void rejectedArgumentsAffectHash() {
        AuditEvent a = baseline(recorder(), "investigator-1", "client-1", "investigation", "CASE-1", Set.of());
        AuditEvent b = baseline(recorder(), "investigator-1", "client-1", "investigation", "CASE-1",
                Set.of("scopeId", "purpose"));

        assertThat(b.eventHash()).isNotEqualTo(a.eventHash());
    }
}

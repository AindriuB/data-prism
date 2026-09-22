package io.github.aindriub.dataprism.audit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    /**
     * Pins {@link AuditEventHash}'s join format to a checked-in literal, computed
     * independently (not by running this codebase) before and kept unchanged by
     * this task's refactor. {@link AuditRecorder} mints its own random
     * {@code eventId}, so this bypasses it and calls {@link AuditEventHash}
     * directly with every component fixed, including the genesis
     * {@code previousHash} — the one thing that actually exercises the exact
     * body format the recorder itself builds.
     */
    @Test
    @DisplayName("a fully-specified event's hash matches a checked-in literal")
    void hashMatchesPinnedLiteral() {
        String genesis = "0".repeat(64);

        String hash = AuditEventHash.compute(
                "11111111-1111-1111-1111-111111111111", "instance-1", 1L, "investigator-1", "client-1",
                "get_entity_context", "CUSTOMER", "SUBJ-1", "fp-1", "DEFAULT", "CASE-1", "investigation",
                "CASE-1", "ALLOW", "corr-1", Set.of(), genesis);

        assertThat(hash).isEqualTo("63b5da378e10d4fab0c9cde452fd47115cf3e4b76e6aa12ad8aee3b480115489");
    }

    /**
     * The ordering guarantee this task adds: a sink that throws on its second
     * call must not move the chain head past the record it never actually
     * wrote. The third call's {@code previousHash} still equals the first
     * event's {@code eventHash} — the second, failed event never entered the
     * chain at all — and the thrown exception propagates out of {@code record}
     * rather than being swallowed.
     */
    @Test
    @DisplayName("a sink that throws leaves the chain head where it was before the call")
    void throwingSinkDoesNotAdvanceTheChain() {
        List<AuditEvent> written = new ArrayList<>();
        AtomicLong calls = new AtomicLong();
        RuntimeException failure = new RuntimeException("sink unavailable");
        AuditRecorder recorder = new AuditRecorder(event -> {
            if (calls.incrementAndGet() == 2) {
                throw failure;
            }
            written.add(event);
        }, FIXED, "instance-1");

        AuditEvent first = baseline(recorder, "investigator-1", "client-1", "investigation", "CASE-1", Set.of());

        assertThatThrownBy(() -> baseline(recorder, "investigator-2", "client-1", "investigation", "CASE-1",
                Set.of()))
                .isSameAs(failure);

        AuditEvent third = baseline(recorder, "investigator-3", "client-1", "investigation", "CASE-1", Set.of());

        assertThat(third.previousHash()).isEqualTo(first.eventHash());
        assertThat(written).containsExactly(first, third);
    }

    /**
     * The design this task settles on: a failed write does not consume its
     * sequence number. The retried, successful third call reuses the sequence
     * the failed second call was handed, rather than leaving a gap — so task
     * 66's verifier never needs to tolerate one; a failed write leaves no trace
     * anywhere, including in the sequence counter.
     */
    @Test
    @DisplayName("a sink that throws does not consume the sequence number")
    void throwingSinkDoesNotConsumeTheSequenceNumber() {
        AtomicLong calls = new AtomicLong();
        RuntimeException failure = new RuntimeException("sink unavailable");
        AuditRecorder recorder = new AuditRecorder(event -> {
            if (calls.incrementAndGet() == 2) {
                throw failure;
            }
        }, FIXED, "instance-1");

        AuditEvent first = baseline(recorder, "investigator-1", "client-1", "investigation", "CASE-1", Set.of());

        assertThatThrownBy(() -> baseline(recorder, "investigator-2", "client-1", "investigation", "CASE-1",
                Set.of()))
                .isSameAs(failure);

        AuditEvent third = baseline(recorder, "investigator-3", "client-1", "investigation", "CASE-1", Set.of());

        assertThat(first.sequence()).isEqualTo(1L);
        assertThat(third.sequence())
                .as("the second, failed call's sequence number is reused rather than left as a gap")
                .isEqualTo(2L);
    }
}

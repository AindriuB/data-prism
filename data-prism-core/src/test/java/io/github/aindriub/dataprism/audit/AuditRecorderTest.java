package io.github.aindriub.dataprism.audit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
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
     * The gap this task closes: {@code timestamp} used to be excluded from the
     * joined body, so a backdated record verified clean. Two otherwise-identical
     * events recorded from clocks reporting different instants must now hash
     * differently.
     */
    @Test
    @DisplayName("changing the timestamp alone changes the event hash")
    void timestampAffectsHash() {
        List<AuditEvent> sinkA = new ArrayList<>();
        AuditRecorder recorderA = new AuditRecorder(sinkA::add,
                Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC), "instance-1");
        AuditEvent a = baseline(recorderA, "investigator-1", "client-1", "investigation", "CASE-1", Set.of());

        List<AuditEvent> sinkB = new ArrayList<>();
        AuditRecorder recorderB = new AuditRecorder(sinkB::add,
                Clock.fixed(Instant.parse("2020-06-15T00:00:00Z"), ZoneOffset.UTC), "instance-1");
        AuditEvent b = baseline(recorderB, "investigator-1", "client-1", "investigation", "CASE-1", Set.of());

        assertThat(b.eventHash()).isNotEqualTo(a.eventHash());
    }

    /**
     * The other gap this task closes: {@code sourceSystems} used to be excluded
     * from the joined body, so a rewritten source list verified clean.
     */
    @Test
    @DisplayName("changing the source systems alone changes the event hash")
    void sourceSystemsAffectHash() {
        AuditEvent a = recorder().record("investigator-1", "client-1", "get_entity_context", "CUSTOMER", "SUBJ-1",
                "fp-1", "DEFAULT", "CASE-1", "investigation", "CASE-1", "ALLOW",
                Set.of("customer-api:ANSWERED"), Set.of(), "corr-1");
        AuditEvent b = recorder().record("investigator-1", "client-1", "get_entity_context", "CUSTOMER", "SUBJ-1",
                "fp-1", "DEFAULT", "CASE-1", "investigation", "CASE-1", "ALLOW",
                Set.of("other-system:ANSWERED"), Set.of(), "corr-1");

        assertThat(b.eventHash()).isNotEqualTo(a.eventHash());
    }

    /**
     * {@code sourceSystems} joins sorted, exactly as {@code rejectedArguments}
     * already does, so the hash cannot depend on the iteration order of
     * whatever {@code Set} the caller happened to pass in. Calls {@link
     * AuditEventHash#compute} directly with every other component fixed —
     * going through {@link AuditRecorder} would mint a different random
     * {@code eventId} per call, which would change the hash on its own and
     * mask what this test is checking.
     */
    @Test
    @DisplayName("source systems in a different insertion order hash identically")
    void sourceSystemsOrderDoesNotAffectHash() {
        String genesis = "0".repeat(64);
        Instant timestamp = Instant.parse("2026-01-01T00:00:00Z");
        Set<String> forwardOrder =
                new java.util.LinkedHashSet<>(List.of("customer-api:ANSWERED", "billing-api:ANSWERED"));
        Set<String> reverseOrder =
                new java.util.LinkedHashSet<>(List.of("billing-api:ANSWERED", "customer-api:ANSWERED"));

        String a = AuditEventHash.compute("11111111-1111-1111-1111-111111111111", timestamp, "instance-1", 1L,
                "investigator-1", "client-1", "get_entity_context", "CUSTOMER", "SUBJ-1", "fp-1", "DEFAULT",
                "CASE-1", "investigation", "CASE-1", "ALLOW", "corr-1", forwardOrder, Set.of(), genesis);
        String b = AuditEventHash.compute("11111111-1111-1111-1111-111111111111", timestamp, "instance-1", 1L,
                "investigator-1", "client-1", "get_entity_context", "CUSTOMER", "SUBJ-1", "fp-1", "DEFAULT",
                "CASE-1", "investigation", "CASE-1", "ALLOW", "corr-1", reverseOrder, Set.of(), genesis);

        assertThat(b).isEqualTo(a);
    }

    /**
     * {@code timestamp} joins as {@code Instant.toString()} — the same ISO-8601
     * rendering {@code AuditRecordFormat} already persists and parses. Two
     * equal {@code Instant}s reached by different routes (parsed text versus
     * an epoch-seconds-plus-zero-nanos construction) must render, and so hash,
     * identically: the rendered form is stable, not incidental. Calls {@link
     * AuditEventHash#compute} directly for the same reason as the test above:
     * a random {@code eventId} per {@link AuditRecorder} call would mask the
     * comparison.
     */
    @Test
    @DisplayName("equal instants reached by different routes hash identically")
    void equalInstantsHashIdenticallyRegardlessOfConstructionRoute() {
        Instant parsed = Instant.parse("2026-01-01T00:00:00Z");
        Instant fromEpoch = Instant.ofEpochSecond(parsed.getEpochSecond(), 0);
        assertThat(fromEpoch).isEqualTo(parsed);

        String genesis = "0".repeat(64);
        String a = AuditEventHash.compute("11111111-1111-1111-1111-111111111111", parsed, "instance-1", 1L,
                "investigator-1", "client-1", "get_entity_context", "CUSTOMER", "SUBJ-1", "fp-1", "DEFAULT",
                "CASE-1", "investigation", "CASE-1", "ALLOW", "corr-1", Set.of("customer-api:ANSWERED"), Set.of(),
                genesis);
        String b = AuditEventHash.compute("11111111-1111-1111-1111-111111111111", fromEpoch, "instance-1", 1L,
                "investigator-1", "client-1", "get_entity_context", "CUSTOMER", "SUBJ-1", "fp-1", "DEFAULT",
                "CASE-1", "investigation", "CASE-1", "ALLOW", "corr-1", Set.of("customer-api:ANSWERED"), Set.of(),
                genesis);

        assertThat(b).isEqualTo(a);
    }

    /**
     * The trap this task's own task file calls out: the obvious implementation
     * reads {@code clock.instant()} twice — once to compute the hash, again to
     * build the event — which would leave a real clock's two reads disagreeing
     * and every record's stored hash disagreeing with its own stored
     * timestamp. Driven by a stepping clock that returns a different instant
     * on every call, so a double-read would fail this assertion even though a
     * {@code Clock.fixed} test never could.
     */
    @Test
    @DisplayName("the clock is read exactly once, so the stored hash matches the stored timestamp")
    void clockIsReadExactlyOnce() {
        Clock stepping = new Clock() {
            private final AtomicLong callCount = new AtomicLong();

            @Override
            public ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return Instant.ofEpochSecond(1_700_000_000L + callCount.incrementAndGet());
            }
        };

        List<AuditEvent> sink = new ArrayList<>();
        AuditRecorder recorder = new AuditRecorder(sink::add, stepping, "instance-1");
        AuditEvent event = recorder.record("investigator-1", "client-1", "get_entity_context", "CUSTOMER",
                "SUBJ-1", "fp-1", "DEFAULT", "CASE-1", "investigation", "CASE-1", "ALLOW",
                Set.of("customer-api:ANSWERED"), Set.of(), "corr-1");

        assertThat(AuditEventHash.compute(event)).isEqualTo(event.eventHash());
    }

    /**
     * Pins {@link AuditEventHash}'s join format to a checked-in literal, computed
     * independently (not by running this codebase). This task widened the
     * joined body to include {@code timestamp} and {@code sourceSystems}, so
     * the literal changed from the one this test pinned before that widening —
     * recomputed here, not merely relaxed, for the new nineteen-field join.
     * {@link AuditRecorder} mints its own random {@code eventId}, so this
     * bypasses it and calls {@link AuditEventHash} directly with every
     * component fixed, including the genesis {@code previousHash} — the one
     * thing that actually exercises the exact body format the recorder itself
     * builds.
     */
    @Test
    @DisplayName("a fully-specified event's hash matches a checked-in literal")
    void hashMatchesPinnedLiteral() {
        String genesis = "0".repeat(64);

        String hash = AuditEventHash.compute(
                "11111111-1111-1111-1111-111111111111", Instant.parse("2026-01-01T00:00:00Z"), "instance-1", 1L,
                "investigator-1", "client-1", "get_entity_context", "CUSTOMER", "SUBJ-1", "fp-1", "DEFAULT",
                "CASE-1", "investigation", "CASE-1", "ALLOW", "corr-1", Set.of("customer-api:ANSWERED"), Set.of(),
                genesis);

        assertThat(hash).isEqualTo("323972fe88232f919008b2f608d7923cc5b6cc4948c0a38fac734a33881b25ce");
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

    /**
     * A restart configured with the same writer-id must not collide with the
     * previous boot's chain. Two recorders built with the identical
     * writer-id mint distinct instance ids, each prefixed by that writer-id,
     * so {@link AuditChainVerifier} sees them as independent writers rather
     * than one writer whose sequence went backwards.
     */
    @Test
    @DisplayName("two recorders built with the same writer-id produce distinct instance ids, both prefixed by it")
    void sameWriterIdProducesDistinctInstanceIdsSharingThePrefix() {
        List<AuditEvent> sinkA = new ArrayList<>();
        AuditRecorder recorderA = new AuditRecorder(sinkA::add, FIXED, "shared-writer");
        List<AuditEvent> sinkB = new ArrayList<>();
        AuditRecorder recorderB = new AuditRecorder(sinkB::add, FIXED, "shared-writer");

        assertThat(recorderA.instanceId()).isNotEqualTo(recorderB.instanceId());
        assertThat(recorderA.instanceId()).startsWith("shared-writer/");
        assertThat(recorderB.instanceId()).startsWith("shared-writer/");
    }

    /**
     * Every event a single recorder ever produces carries that one recorder's
     * instance id, and the first event still starts the chain exactly as
     * before: GENESIS previousHash, sequence 1.
     */
    @Test
    @DisplayName("every event from one recorder carries its instanceId, and the first starts at GENESIS/1")
    void allEventsFromOneRecorderShareItsInstanceIdAndStartAtGenesis() {
        AuditRecorder recorder = recorder();
        String instanceId = recorder.instanceId();

        AuditEvent first = baseline(recorder, "investigator-1", "client-1", "investigation", "CASE-1", Set.of());
        AuditEvent second = baseline(recorder, "investigator-2", "client-1", "investigation", "CASE-1", Set.of());

        assertThat(first.instanceId()).isEqualTo(instanceId);
        assertThat(second.instanceId()).isEqualTo(instanceId);
        assertThat(first.previousHash()).isEqualTo("0".repeat(64));
        assertThat(first.sequence()).isEqualTo(1L);
    }

    @Test
    @DisplayName("a blank writer-id is refused")
    void blankWriterIdIsRefused() {
        List<AuditEvent> sink = new ArrayList<>();
        assertThatThrownBy(() -> new AuditRecorder(sink::add, FIXED, "   "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a writer-id containing the instanceId separator is refused")
    void writerIdContainingSeparatorIsRefused() {
        List<AuditEvent> sink = new ArrayList<>();
        assertThatThrownBy(() -> new AuditRecorder(sink::add, FIXED, "host/1"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

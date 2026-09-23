package io.github.aindriub.dataprism.audit;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Builds and chains audit events for one writer.
 *
 * <p>The chain is per instance. Each event hashes its own content together with
 * the previous event's hash, so a deletion or edit breaks the link from that
 * point on. Ordering across instances is the sink's problem, not this class's.
 *
 * <p>{@code previousHash} only advances, and the sequence counter only stays
 * advanced, once {@code sink.record(event)} has returned without throwing. A
 * throwing sink leaves this recorder's state byte-identical to what it was
 * before the call — the sequence number handed out for the failed event is
 * rolled back rather than left consumed, so the next successful write reuses
 * it and chains against the same {@code previousHash} the failed attempt did.
 * Task 66's verifier therefore never has to tolerate a gap: a failed write
 * leaves no trace in either this recorder's state or the sink's own output.
 */
public final class AuditRecorder {

    private static final String GENESIS = "0".repeat(64);

    private final AuditSink sink;
    private final Clock clock;
    private final String instanceId;
    private final AtomicLong sequence = new AtomicLong();

    private volatile String previousHash = GENESIS;

    public AuditRecorder(AuditSink sink, Clock clock, String instanceId) {
        this.sink = Objects.requireNonNull(sink, "sink");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.instanceId = Objects.requireNonNull(instanceId, "instanceId");
    }

    public synchronized AuditEvent record(String principalId, String clientId, String tool,
                                          String entityType, String subjectPseudonym,
                                          String parameterFingerprint, String privacyProfile,
                                          String scopeId, String purpose, String caseId,
                                          String policyDecision, Set<String> sourceSystems,
                                          Set<String> rejectedArguments, String correlationId) {
        long seq = sequence.incrementAndGet();
        String id = UUID.randomUUID().toString();
        String prior = previousHash;
        // Read the clock exactly once: the hash and the constructed event must carry
        // the identical Instant, or a real clock's two reads would leave every
        // record's stored hash disagreeing with its own stored timestamp and the
        // chain breaking on its very first record.
        Instant timestamp = clock.instant();
        String hash = AuditEventHash.compute(id, timestamp, instanceId, seq, principalId, clientId, tool,
                entityType, subjectPseudonym, parameterFingerprint, privacyProfile, scopeId, purpose, caseId,
                policyDecision, correlationId, sourceSystems, rejectedArguments, prior);

        AuditEvent event = new AuditEvent(id, timestamp, principalId, clientId, tool, entityType,
                subjectPseudonym, parameterFingerprint, privacyProfile, scopeId, purpose, caseId,
                policyDecision, sourceSystems, rejectedArguments, correlationId, instanceId, seq, prior,
                hash);

        try {
            sink.record(event);
        } catch (RuntimeException e) {
            // Roll back exactly what was tentatively advanced above: the
            // sequence counter and, since previousHash has not moved yet, no
            // further undo is needed for the chain head. The next call sees
            // the same seq and the same previousHash a retried write of this
            // very event would need.
            sequence.decrementAndGet();
            throw e;
        }
        previousHash = hash;
        return event;
    }
}

package io.github.aindriub.dataprism.audit;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
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
        // Sorted so the hash does not depend on the iteration order of whatever
        // Set implementation the caller happened to pass in.
        String rejected = String.join(",", rejectedArguments.stream().sorted().toList());
        String body = String.join("|", id, instanceId, Long.toString(seq), principalId, clientId, tool,
                entityType, subjectPseudonym, parameterFingerprint, privacyProfile, scopeId, purpose,
                caseId, policyDecision, correlationId, rejected, prior);
        String hash = sha256(body);

        AuditEvent event = new AuditEvent(id, clock.instant(), principalId, clientId, tool, entityType,
                subjectPseudonym, parameterFingerprint, privacyProfile, scopeId, purpose, caseId,
                policyDecision, sourceSystems, rejectedArguments, correlationId, instanceId, seq, prior,
                hash);

        previousHash = hash;
        sink.record(event);
        return event;
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}

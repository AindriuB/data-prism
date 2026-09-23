package io.github.aindriub.dataprism.audit;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Set;

/**
 * The one place that builds the joined body a chained audit event's
 * {@code eventHash} is computed over.
 *
 * <p>{@link AuditRecorder} calls this when it mints a new event, and task 66's
 * offline verifier calls it again to recompute a recorded event's hash for
 * comparison. Neither keeps its own copy of the join format, so the two can
 * never drift apart from each other.
 */
public final class AuditEventHash {

    private AuditEventHash() {
    }

    /**
     * @return the hex-encoded SHA-256 {@code eventHash} for an event built from
     *         these components, joined in the same order {@link AuditRecorder}
     *         always uses.
     */
    public static String compute(String eventId, Instant timestamp, String instanceId, long sequence,
                                  String principalId, String clientId, String tool, String entityType,
                                  String subjectPseudonym, String parameterFingerprint, String privacyProfile,
                                  String scopeId, String purpose, String caseId, String policyDecision,
                                  String correlationId, Set<String> sourceSystems, Set<String> rejectedArguments,
                                  String previousHash) {
        // Sorted so the hash does not depend on the iteration order of whatever
        // Set implementation the caller happened to pass in.
        String sources = String.join(",", sourceSystems.stream().sorted().toList());
        String rejected = String.join(",", rejectedArguments.stream().sorted().toList());
        // Instant.toString() is the same ISO-8601 rendering AuditRecordFormat already
        // persists and parses: locale- and JVM-independent, and equal instants always
        // render equal, so it is stable to join rather than incidental.
        String renderedTimestamp = timestamp == null ? "null" : timestamp.toString();
        String body = String.join("|", eventId, renderedTimestamp, instanceId, Long.toString(sequence),
                principalId, clientId, tool, entityType, subjectPseudonym, parameterFingerprint, privacyProfile,
                scopeId, purpose, caseId, policyDecision, correlationId, sources, rejected, previousHash);
        return sha256(body);
    }

    /** @return the hex-encoded SHA-256 {@code eventHash} an already-built {@link AuditEvent} should carry. */
    public static String compute(AuditEvent event) {
        return compute(event.eventId(), event.timestamp(), event.instanceId(), event.sequence(),
                event.principalId(), event.clientId(), event.tool(), event.entityType(), event.subjectPseudonym(),
                event.parameterFingerprint(), event.privacyProfile(), event.scopeId(), event.purpose(),
                event.caseId(), event.policyDecision(), event.correlationId(), event.sourceSystems(),
                event.rejectedArguments(), event.previousHash());
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

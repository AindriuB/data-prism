package io.github.aindriub.dataprism.audit;

import io.github.aindriub.dataprism.annotations.PrivacyAction;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * One record of an access decision.
 *
 * <p>Records who, what, when and what was decided — never the data. Note
 * {@code subjectPseudonym} rather than the real identifier: the specification
 * itself calls the internal id sensitive operational metadata, and an audit log
 * is one of the most widely-read stores in an organisation. Mapping a pseudonym
 * back to a subject is the re-identification path's job, under its own
 * authorisation. See docs/design-review.md §E.
 *
 * <p>{@code principalId}, {@code clientId} and {@code caseId} come from the
 * caller's {@code InvestigationContext} — never a constant, and never a value
 * the caller supplied as a tool argument. {@code rejectedArguments} is the other
 * half of that: the *names* of any reserved argument the caller attempted to
 * supply instead of letting the session decide. Both are what let this event
 * answer "who actually asked, and did they try to say otherwise".
 *
 * <p>The chain fields are per writer, not global: instances are stateless and
 * horizontally scaled, so a single chain would fork under concurrency into
 * something indistinguishable from tampering (§A6). S0 writes them; verifying
 * and sequencing them is S9.
 *
 * <p>{@code fieldDispositions} maps a field path ({@code <source>:<json-pointer>},
 * array indices collapsed to {@code *}) to the action taken on it: a {@link
 * PrivacyAction} name or {@code REFUSED}. It names paths and actions, never
 * values. {@code approvalId} and {@code approverId} identify a four-eyes approval
 * and the second principal, or are empty. {@code recordVersion} is {@code 1} for
 * records written before these fields existed and {@code 2} after; version 1
 * records verify over exactly the original nineteen hashed fields.
 */
public record AuditEvent(
        String eventId,
        Instant timestamp,
        String principalId,
        String clientId,
        String tool,
        String entityType,
        String subjectPseudonym,
        String parameterFingerprint,
        String privacyProfile,
        String scopeId,
        String purpose,
        String caseId,
        String policyDecision,
        Set<String> sourceSystems,
        Set<String> rejectedArguments,
        String correlationId,
        String instanceId,
        long sequence,
        String previousHash,
        String eventHash,
        int recordVersion,
        Map<String, String> fieldDispositions,
        String approvalId,
        String approverId) {

    /** The record version written by this code. */
    public static final int CURRENT_VERSION = 2;

    public AuditEvent {
        sourceSystems = Set.copyOf(sourceSystems);
        rejectedArguments = Set.copyOf(rejectedArguments);
        fieldDispositions = java.util.Collections.unmodifiableMap(new TreeMap<>(fieldDispositions));
        fieldDispositions.forEach((path, action) -> {
            if (!isValidAction(action)) {
                throw new IllegalArgumentException(
                        "field disposition for '" + path + "' is not a PrivacyAction name or REFUSED");
            }
        });
        approvalId = approvalId == null ? "" : approvalId;
        approverId = approverId == null ? "" : approverId;
    }

    /** The pre-version-2 shape: yields {@code recordVersion == 1}, no dispositions and no approval. */
    public AuditEvent(String eventId, Instant timestamp, String principalId, String clientId, String tool,
                      String entityType, String subjectPseudonym, String parameterFingerprint,
                      String privacyProfile, String scopeId, String purpose, String caseId,
                      String policyDecision, Set<String> sourceSystems, Set<String> rejectedArguments,
                      String correlationId, String instanceId, long sequence, String previousHash,
                      String eventHash) {
        this(eventId, timestamp, principalId, clientId, tool, entityType, subjectPseudonym,
                parameterFingerprint, privacyProfile, scopeId, purpose, caseId, policyDecision, sourceSystems,
                rejectedArguments, correlationId, instanceId, sequence, previousHash, eventHash, 1, Map.of(),
                "", "");
    }

    private static boolean isValidAction(String action) {
        if ("REFUSED".equals(action)) {
            return true;
        }
        if (action == null) {
            return false;
        }
        try {
            PrivacyAction.valueOf(action);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}

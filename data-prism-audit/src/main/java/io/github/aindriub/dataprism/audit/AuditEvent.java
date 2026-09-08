package io.github.aindriub.dataprism.audit;

import java.time.Instant;
import java.util.Set;

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
 * <p>The chain fields are per writer, not global: instances are stateless and
 * horizontally scaled, so a single chain would fork under concurrency into
 * something indistinguishable from tampering (§A6). S0 writes them; verifying
 * and sequencing them is S9.
 */
public record AuditEvent(
        String eventId,
        Instant timestamp,
        String principalId,
        String tool,
        String entityType,
        String subjectPseudonym,
        String parameterFingerprint,
        String privacyProfile,
        String scopeId,
        String policyDecision,
        Set<String> sourceSystems,
        String correlationId,
        String instanceId,
        long sequence,
        String previousHash,
        String eventHash) {

    public AuditEvent {
        sourceSystems = Set.copyOf(sourceSystems);
    }
}

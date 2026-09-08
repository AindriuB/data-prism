package io.github.aindriub.dataprism.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Writes audit events to a dedicated logger.
 *
 * <p>Every field emitted here is either a decision, an identifier of the actor,
 * or a pseudonym. Nothing read from a source payload reaches this line, which is
 * why it can be shipped to ordinary log infrastructure.
 */
public final class Slf4jAuditSink implements AuditSink {

    private static final Logger AUDIT = LoggerFactory.getLogger("dataprism.audit");

    @Override
    public void record(AuditEvent e) {
        AUDIT.info("event={} seq={}/{} ts={} principal={} tool={} entityType={} subject={} "
                        + "params={} profile={} scope={} decision={} sources={} correlation={} hash={} prev={}",
                e.eventId(), e.instanceId(), e.sequence(), e.timestamp(), e.principalId(), e.tool(),
                e.entityType(), e.subjectPseudonym(), e.parameterFingerprint(), e.privacyProfile(),
                e.scopeId(), e.policyDecision(), e.sourceSystems(), e.correlationId(),
                e.eventHash(), e.previousHash());
    }
}

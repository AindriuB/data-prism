package io.github.aindriub.dataprism.audit.sink;

import io.github.aindriub.dataprism.audit.AuditEvent;
import io.github.aindriub.dataprism.audit.AuditSink;
import io.github.aindriub.dataprism.audit.format.AuditFieldMapping;
import io.github.aindriub.dataprism.audit.format.AuditJsonRenderer;
import io.github.aindriub.dataprism.audit.format.AuditRouting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.spi.LoggingEventBuilder;

import java.util.Map;

/**
 * Writes audit events to a dedicated logger.
 *
 * <p>Every field emitted here is either a decision, an identifier of the actor,
 * or a pseudonym. Nothing read from a source payload reaches this line, which is
 * why it can be shipped to ordinary log infrastructure.
 *
 * <p>The message text is unchanged apart from a trailing {@code extCorrelation={}}. Built with an
 * {@link AuditFieldMapping} and {@link AuditRouting}, the sink also attaches every mapped field,
 * the routing constants and the derived {@code event.outcome} as SLF4J key-value pairs under the
 * mapped names, for a JSON log encoder such as Spring Boot's structured logging or
 * logstash-logback-encoder. Those values are the same ones {@link AuditJsonRenderer} renders.
 */
public final class Slf4jAuditSink implements AuditSink {

    private static final Logger AUDIT = LoggerFactory.getLogger("dataprism.audit");

    private final Logger logger;
    private final AuditFieldMapping mapping;
    private final AuditRouting routing;

    /** The message only, with no key-value pairs. */
    public Slf4jAuditSink() {
        this.logger = AUDIT;
        this.mapping = null;
        this.routing = null;
    }

    /**
     * @throws IllegalArgumentException {@code AUDIT_FIELD_MAPPING_CONFLICT} if a routing path
     *                                  collides with a mapped path
     */
    public Slf4jAuditSink(AuditFieldMapping mapping, AuditRouting routing) {
        this(mapping, routing, AUDIT);
    }

    /** Visible for testing: lets a test capture the logging event. {@code null} mapping means no pairs. */
    Slf4jAuditSink(AuditFieldMapping mapping, AuditRouting routing, Logger logger) {
        this.logger = logger;
        this.mapping = mapping;
        this.routing = mapping == null ? null : java.util.Objects.requireNonNull(routing, "routing");
        if (mapping != null) {
            routing.checkAgainst(mapping);
        }
    }

    @Override
    public void record(AuditEvent e) {
        LoggingEventBuilder builder = logger.atInfo();
        if (mapping != null) {
            for (Map.Entry<String, Object> pair : AuditJsonRenderer.keyValues(e, mapping, routing).entrySet()) {
                builder = builder.addKeyValue(pair.getKey(), pair.getValue());
            }
        }
        builder.log("event={} seq={}/{} ts={} principal={} client={} tool={} entityType={} subject={} "
                        + "params={} profile={} scope={} purpose={} case={} decision={} sources={} "
                        + "rejected={} correlation={} hash={} prev={} extCorrelation={}",
                e.eventId(), e.instanceId(), e.sequence(), e.timestamp(), e.principalId(), e.clientId(),
                e.tool(), e.entityType(), e.subjectPseudonym(), e.parameterFingerprint(),
                e.privacyProfile(), e.scopeId(), e.purpose(), e.caseId(), e.policyDecision(),
                e.sourceSystems(), e.rejectedArguments(), e.correlationId(), e.eventHash(),
                e.previousHash(), e.externalCorrelationId());
    }
}

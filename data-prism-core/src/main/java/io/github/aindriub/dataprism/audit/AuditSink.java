package io.github.aindriub.dataprism.audit;

/**
 * Where audit events go.
 *
 * <p>An SPI with only a logging implementation here. The sink is what actually
 * provides ordering and immutability, so the strength of the tamper-evidence is
 * a deployment property, not a claim this library can make on its own.
 */
public interface AuditSink {

    void record(AuditEvent event);
}

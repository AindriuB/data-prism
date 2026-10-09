package io.github.aindriub.dataprism.audit;

/**
 * Receives each audit event after the configured {@link AuditSink} has accepted it.
 *
 * <p>The contract, in full:
 * <ul>
 *   <li>Called only after the authoritative write succeeded: after {@code sink.record(event)} returned
 *       (with {@code hash-chained}, after the fsync and the optional JSON projection; with {@code slf4j}
 *       the event has only been handed to the logger, which is neither durable nor tamper-evident). A
 *       failed write is never delivered.</li>
 *   <li>Receives the event exactly as it was logged. {@link AuditEvent} is immutable, so a listener
 *       cannot alter the logged record.</li>
 *   <li>Cannot veto. The tool call, the hash chain and the projection do not depend on the listener.</li>
 *   <li>Failures are isolated. Anything a listener throws is caught and logged as {@code
 *       AUDIT_LISTENER_FAILED} with the listener's class and the exception's class name only; other
 *       listeners are still called.</li>
 *   <li>Delivery is best effort, on one shared dispatcher thread behind a bounded queue, in sequence order.
 *       When the queue is full the event is dropped for listeners only (logged as {@code
 *       AUDIT_LISTENER_DROPPED}); it is never missing from the audit log. A slow listener delays the
 *       listeners after it, never the audited call.</li>
 *   <li>Where a listener sends events is the operator's responsibility, including that destination's access
 *       control and retention.</li>
 * </ul>
 */
@FunctionalInterface
public interface AuditEventListener {

    /** Handles one logged event. May throw; the dispatcher isolates it. Should return promptly. */
    void onAuditEvent(AuditEvent event);
}

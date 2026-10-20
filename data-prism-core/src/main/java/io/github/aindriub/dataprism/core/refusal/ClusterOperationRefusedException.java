package io.github.aindriub.dataprism.core.refusal;

/**
 * A cluster operation was rejected or failed for a reason that is neither a held lock nor a business refusal: the
 * cluster refused a lock or its lease (for example a configured maximum lease below the one requested), or an
 * index or entry-processor call failed. The request is refused and nothing is reported as a duplicate.
 *
 * <p>Carries the stable code {@value #CODE}. The message names no key, scope, principal or approval id and does
 * not repeat the cause's message, so the code and message are safe to log and to audit. The cause is chained for
 * diagnosis only: it must never be shown to a caller, and a log line may carry its class name but not its message.
 */
public final class ClusterOperationRefusedException extends PrivacyRefusedException {

    /** The refusal code a client and the audit trail see. */
    public static final String CODE = "CLUSTER_OPERATION_REFUSED";

    /** @param cause the failure that caused the refusal; its message is never copied into this one */
    public ClusterOperationRefusedException(Throwable cause) {
        super(CODE, "$", "a cluster operation was refused", cause);
    }
}

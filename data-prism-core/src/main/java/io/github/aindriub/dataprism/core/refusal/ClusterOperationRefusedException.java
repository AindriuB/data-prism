package io.github.aindriub.dataprism.core.refusal;

/**
 * A cluster operation was rejected or failed for a reason that is neither a held lock nor a business refusal: the
 * cluster refused a lock or its lease (for example a configured maximum lease below the one requested), or an
 * index or entry-processor call failed. The request is refused and nothing is reported as a duplicate.
 *
 * <p>Carries the stable code {@value #CODE}. The message names no key, scope, principal or approval id and does
 * not repeat the cause's message, so the code and message are safe to log and to audit. The cause is chained for
 * diagnosis only: it must never be shown to a caller, and a log line may carry its class name but not its message.
 *
 * <p>A rejected lock or lease changes nothing. Steps of the same call that ran before it are not undone: a
 * rate-limit allowance stays used, and an approval consumed earlier stays consumed. A rejection of a later
 * cluster call while an approval is being created can follow writes that call made, which it undoes where it
 * can, so a pending approval or count may be left until it expires. See {@code docs/multiple-instances.md},
 * "What a cluster refusal leaves behind".
 */
public final class ClusterOperationRefusedException extends PrivacyRefusedException {

    /** The refusal code a client and the audit trail see. */
    public static final String CODE = "CLUSTER_OPERATION_REFUSED";

    /** @param cause the failure that caused the refusal; its message is never copied into this one */
    public ClusterOperationRefusedException(Throwable cause) {
        super(CODE, "$", "a cluster operation was refused", cause);
    }
}

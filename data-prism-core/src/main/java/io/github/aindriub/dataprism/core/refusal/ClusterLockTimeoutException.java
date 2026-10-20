package io.github.aindriub.dataprism.core.refusal;

/**
 * A wait for a cluster lock ran out. The request is refused rather than left hanging
 * on a lock another member, or something on the member port, is holding.
 *
 * <p>Carries the stable code {@value #CODE}. It names no key, scope or subject, so the
 * code and message are safe to log and to audit.
 *
 * <p>Only the step that failed is known to have changed nothing. Steps of the same call that ran before it are
 * not undone: a rate-limit allowance stays used, and an approval consumed earlier stays consumed. See
 * {@code docs/multiple-instances.md}, "What a cluster refusal leaves behind".
 */
public final class ClusterLockTimeoutException extends PrivacyRefusedException {

    /** The refusal code a client and the audit trail see. */
    public static final String CODE = "CLUSTER_LOCK_TIMEOUT";

    public ClusterLockTimeoutException() {
        super(CODE, "$", "a cluster lock was not obtained in time");
    }
}

package io.github.aindriub.dataprism.hazelcast;

import io.github.aindriub.dataprism.core.refusal.PrivacyRefusedException;

/**
 * A cluster operation was rejected or failed for a reason that is neither a held lock nor a business refusal:
 * Hazelcast refused a lock or its lease (for example a configured maximum lease below the one requested), or an
 * index or entry-processor call failed. The request is refused and nothing is reported as a duplicate.
 *
 * <p>Carries the stable code {@value #CODE}. The message names no key, scope, principal or approval id, so the
 * code and message are safe to log and to audit.
 */
public final class ClusterOperationRefusedException extends PrivacyRefusedException {

    /** The refusal code a client and the audit trail see. */
    public static final String CODE = "CLUSTER_OPERATION_REFUSED";

    public ClusterOperationRefusedException() {
        super(CODE, "$", "a cluster operation was refused");
    }
}

package io.github.aindriub.dataprism.hazelcast;

import io.github.aindriub.dataprism.core.refusal.PrivacyRefusedException;

/**
 * The cluster's oversight map holds a scope pause written by 0.7.x, in a key form 0.8 does not read. Starting would
 * let a paused scope resume without anyone asking, so it refuses.
 *
 * <p>Carries the stable code {@value #CODE}. It names no scope, so the code and message are safe to log.
 */
public final class LegacyOversightStateException extends PrivacyRefusedException {

    /** The refusal code a client and the audit trail see. */
    public static final String CODE = "LEGACY_OVERSIGHT_STATE";

    public LegacyOversightStateException() {
        super(CODE, "$", "the cluster holds scope pauses written before 0.8; "
                + "a full cluster restart is required to upgrade, a rolling upgrade is not supported");
    }
}

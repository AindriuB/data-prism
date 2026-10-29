package io.github.aindriub.dataprism.audit;

import java.util.Objects;
import java.util.Optional;

/**
 * Whether a writer's {@code eventHash} chain is the plain SHA-256 form or the opt-in keyed
 * HMAC-SHA256 form. Nothing written to the audit or checkpoint file records which; the verifier is
 * told by its caller.
 */
public final class AuditChainMode {

    private static final AuditChainMode UNKEYED = new AuditChainMode(null);

    private final AuditChainKey key;

    private AuditChainMode(AuditChainKey key) {
        this.key = key;
    }

    public static AuditChainMode unkeyed() {
        return UNKEYED;
    }

    public static AuditChainMode keyed(AuditChainKey key) {
        return new AuditChainMode(Objects.requireNonNull(key, "key"));
    }

    public boolean isKeyed() {
        return key != null;
    }

    public Optional<AuditChainKey> key() {
        return Optional.ofNullable(key);
    }

    /** @return the {@code eventHash} an already-built event should carry under this mode. */
    String hash(AuditEvent event) {
        return key == null ? AuditEventHash.compute(event) : AuditEventHash.compute(event, key);
    }

    @Override
    public String toString() {
        return key == null ? "AuditChainMode[unkeyed]" : "AuditChainMode[keyed, " + key + "]";
    }
}

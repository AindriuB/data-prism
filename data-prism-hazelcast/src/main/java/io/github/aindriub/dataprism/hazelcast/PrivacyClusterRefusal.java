package io.github.aindriub.dataprism.hazelcast;

/**
 * A cluster description or member refused before it could start. The message names the
 * offending field and never its value, so a refusal that reaches a log leaks no address.
 */
public final class PrivacyClusterRefusal extends IllegalStateException {

    public enum Code {
        MISSING_CLUSTER_NAME,
        RESERVED_CLUSTER_NAME,
        INVALID_CLUSTER_MEMBERS,
        INVALID_KUBERNETES_JOIN,
        INVALID_CLUSTER_PORT,
        INVALID_CLUSTER_INTERFACE,
        HAZELCAST_TLS_UNSUPPORTED,
        UNSAFE_HAZELCAST_DISCOVERY,
        INVALID_APPROVAL_QUORUM,
        MISSING_APPROVAL_QUORUM,
        MISSING_MEMBER_INTERFACE,
        UNBOUNDED_MEMBER_INTERFACE,
        UNSAFE_HAZELCAST_SERIALIZATION,
        UNSEALED_CLUSTER_STATE,
        MISSING_CLUSTER_KEY,
        CLUSTER_KEY_MISMATCH,
        /** A wrapper's pause lease, clock skew tolerance or cluster key ids differ from the instance's first (228). */
        CLUSTER_SETTINGS_MISMATCH,
        /** A joining member listens on, or advertises, an address beyond the private and trusted blocks (287). */
        PUBLIC_MEMBER_INTERFACE,
        /** A member that joins others was started without the trusted-network declaration (287). */
        MISSING_TRUSTED_NETWORK,
        /** A declared trusted range is not an IPv4 CIDR block, or is neither private nor at most a /24 (287). */
        INVALID_TRUSTED_RANGE
    }

    private final Code code;

    public PrivacyClusterRefusal(Code code, String message) {
        super(code + ": " + message);
        this.code = code;
    }

    public Code code() {
        return code;
    }
}

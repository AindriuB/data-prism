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
        UNSAFE_HAZELCAST_DISCOVERY
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

package io.github.aindriub.dataprism.spring.boot;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

public class HazelcastProperties {
    /** embedded shares state across members that have joined one cluster; single-node keeps state per process. Required for a protected deployment, never defaulted. */
    private String topology;
    /** Refused if set: member TLS is not available in the open-source distribution. */
    private String tlsKeyReference;
    /** Refused if set: member TLS is not available in the open-source distribution. */
    private String tlsTrustReference;
    /** Reference to the reviewed re-identification controls. Needed to enable the re-identification index. */
    private String reidentificationControlsReference;
    /** Identity cache time to live. Follows the privacy scope lifetime when unset. */
    private Duration identityCacheTtl;
    /** Whether the re-identification index is kept in the cluster. Defaults to false. */
    private boolean reidentificationEnabled;
    /** Persistence of cluster state. Defaults to false; enabling it without a reviewed configuration is refused. */
    private boolean persistenceEnabled;
    /** MapStore backing of cluster state. Defaults to false; enabling it without a reviewed configuration is refused. */
    private boolean mapStoreEnabled;
    /** Cluster name for topology embedded. Required there and never dev. */
    private String clusterName;
    /** Fewest members that must see each other for approvals to be created, decided or consumed. Defaults by join mode; see docs. */
    private Integer approvalMinimumMembers;
    /** How long a call waits for a cluster lock before it is refused with CLUSTER_LOCK_TIMEOUT. Between PT100MS and PT30S. */
    private Duration lockTimeout = Duration.ofSeconds(2);
    private boolean lockTimeoutSet;
    /** How often each member sweeps the pending-approval entries and approval-id claims it owns. Between PT1S and P1D. */
    private Duration approvalSweepInterval = Duration.ofMinutes(5);
    private boolean approvalSweepIntervalSet;
    private final Join join = new Join();
    private final Member member = new Member();
    private final Security security = new Security();

    /**
     * With {@code tcp-ip}, unset means a majority of the distinct {@code join.members}, which must then name every
     * member including this one; with {@code none}, 1 and the key is refused; with {@code kubernetes}, required when
     * {@code dataprism.oversight.approval-required-tools} is not empty. Refused above the member count.
     */
    public Integer getApprovalMinimumMembers() {
        return approvalMinimumMembers;
    }

    public void setApprovalMinimumMembers(Integer v) {
        approvalMinimumMembers = v;
    }

    /**
     * Applies to the approval store, the read budget and the caller rate limiter. A lock is leased for five times this.
     * Refused with {@code single-node}.
     */
    public Duration getLockTimeout() {
        return lockTimeout;
    }

    public void setLockTimeout(Duration v) {
        lockTimeout = v;
        lockTimeoutSet = true;
    }

    /** Whether the lock timeout was given, which {@code single-node} refuses because it has no cluster locks. */
    public boolean lockTimeoutSet() {
        return lockTimeoutSet;
    }

    /**
     * An entry left behind by an approval is removed at most this long after it could last matter. A shorter interval
     * clears them sooner at the cost of more cluster calls. Fixed when the cluster is built: refused with
     * {@code single-node} and when the application supplies its own {@code PrivacyCluster}.
     */
    public Duration getApprovalSweepInterval() {
        return approvalSweepInterval;
    }

    public void setApprovalSweepInterval(Duration v) {
        approvalSweepInterval = v;
        approvalSweepIntervalSet = true;
    }

    /** Whether the sweep interval was given, which a topology that builds no cluster here refuses. */
    public boolean approvalSweepIntervalSet() {
        return approvalSweepIntervalSet;
    }

    /** Required with {@code embedded}; never {@code dev}. Refused with {@code single-node}. */
    public String getClusterName() {
        return clusterName;
    }

    public void setClusterName(String v) {
        clusterName = v;
    }

    public Join getJoin() {
        return join;
    }

    public Member getMember() {
        return member;
    }

    /** The keys every member of the cluster shares. */
    public Security getSecurity() {
        return security;
    }

    /** Whether any of the cluster-only settings is present. */
    private static boolean isBlank(String v) {
        return v == null || v.isBlank();
    }

    public boolean clusterSettingsSet() {
        return !isBlank(clusterName) || approvalMinimumMembers != null || !isBlank(join.mode) || !join.members.isEmpty()
                || !isBlank(join.kubernetes.namespace) || !isBlank(join.kubernetes.serviceName)
                || !isBlank(join.kubernetes.serviceDns) || member.port != null || !isBlank(member.interfaceAddress);
    }

    /**
     * The first {@code dataprism.hazelcast.security.*} property that is set, by name, or {@code null} when none is.
     * Never a value.
     */
    public String firstSecurityPropertySet() {
        return security.firstSet();
    }

    /** Whether any {@code dataprism.hazelcast.security.*} property is set. */
    public boolean securitySet() {
        return security.firstSet() != null;
    }

    /** The settings that secure the cluster: the cluster key (with rotation keys) and the separate name key. */
    public static class Security {
        /** The property prefix of the cluster key, the one place it is written. */
        public static final String CLUSTER_KEY_PROPERTY = "dataprism.hazelcast.security.cluster-key";
        /** The property prefix of the name key. */
        public static final String NAME_KEY_PROPERTY = "dataprism.hazelcast.security.name-key";

        private final ClusterKey clusterKey = new ClusterKey();
        private final NameKey nameKey = new NameKey();

        /** The key that seals what members store; {@code next} and {@code previous} rotate it. */
        public ClusterKey getClusterKey() {
            return clusterKey;
        }

        /** The separate key from which map and lock names are derived. */
        public NameKey getNameKey() {
            return nameKey;
        }

        String firstSet() {
            String found = clusterKey.firstSet(CLUSTER_KEY_PROPERTY);
            return found != null ? found : nameKey.firstSet(NAME_KEY_PROPERTY);
        }
    }

    private static String first(String prefix, String keyId, String environmentVariable, String providerReference) {
        if (!isBlank(keyId)) {
            return prefix + ".key-id";
        }
        if (!isBlank(environmentVariable)) {
            return prefix + ".environment-variable";
        }
        if (!isBlank(providerReference)) {
            return prefix + ".provider-reference";
        }
        return null;
    }

    /** The cluster key by reference, as for {@code dataprism.privacy.hmac-key}: a literal value is refused. */
    public static class ClusterKey {
        /** Identifier of the current cluster key. Required with {@code tcp-ip} and {@code kubernetes}. Contains no dot. */
        private String keyId;
        /** Name of the environment variable holding the current cluster key. Exactly one source is required. */
        private String environmentVariable;
        /** Reference to the current cluster key in the key provider. Exactly one source is required. */
        private String providerReference;
        /** Refused if set: a literal cluster key is never accepted. */
        private String value;
        private final RotationKey next = new RotationKey();
        private final RotationKey previous = new RotationKey();

        public String getKeyId() {
            return keyId;
        }

        public void setKeyId(String v) {
            keyId = v;
        }

        public String getEnvironmentVariable() {
            return environmentVariable;
        }

        public void setEnvironmentVariable(String v) {
            environmentVariable = v;
        }

        public String getProviderReference() {
            return providerReference;
        }

        public void setProviderReference(String v) {
            providerReference = v;
        }

        public String getValue() {
            return value;
        }

        public void setValue(String v) {
            value = v;
        }

        /** The key a rotation introduces first: read by every member, written by none. */
        public RotationKey getNext() {
            return next;
        }

        /** The key a rotation retires: read, and written only while it is being replaced. */
        public RotationKey getPrevious() {
            return previous;
        }

        String firstSet(String prefix) {
            String found = first(prefix, keyId, environmentVariable, providerReference);
            if (found == null && !isBlank(value)) {
                found = prefix + ".value";
            }
            if (found == null) {
                found = next.firstSet(prefix + ".next");
            }
            return found != null ? found : previous.firstSet(prefix + ".previous");
        }
    }

    /** A {@code next} or {@code previous} cluster key, by reference. */
    public static class RotationKey {
        /** Refused if set: a literal rotation key is never accepted. */
        private String value;
        /** Identifier of this rotation key; must differ from every other key id. Contains no dot. */
        private String keyId;
        /** Name of the environment variable holding this rotation key. Exactly one source is required with a key id. */
        private String environmentVariable;
        /** Reference to this rotation key in the key provider. Exactly one source is required with a key id. */
        private String providerReference;

        public String getKeyId() {
            return keyId;
        }

        public void setKeyId(String v) {
            keyId = v;
        }

        public String getEnvironmentVariable() {
            return environmentVariable;
        }

        public void setEnvironmentVariable(String v) {
            environmentVariable = v;
        }

        public String getProviderReference() {
            return providerReference;
        }

        public void setProviderReference(String v) {
            providerReference = v;
        }

        public String getValue() {
            return value;
        }

        public void setValue(String v) {
            value = v;
        }

        String firstSet(String prefix) {
            String found = first(prefix, keyId, environmentVariable, providerReference);
            return found == null && !isBlank(value) ? prefix + ".value" : found;
        }
    }

    /** The name key by reference: a literal value is refused. */
    public static class NameKey {
        /** Identifier of the name key. Required with {@code tcp-ip} and {@code kubernetes}. Contains no dot. */
        private String keyId;
        /** Name of the environment variable holding the name key. Exactly one source is required. */
        private String environmentVariable;
        /** Reference to the name key in the key provider. Exactly one source is required. */
        private String providerReference;
        /** Refused if set: a literal name key is never accepted. */
        private String value;

        public String getKeyId() {
            return keyId;
        }

        public void setKeyId(String v) {
            keyId = v;
        }

        public String getEnvironmentVariable() {
            return environmentVariable;
        }

        public void setEnvironmentVariable(String v) {
            environmentVariable = v;
        }

        public String getProviderReference() {
            return providerReference;
        }

        public void setProviderReference(String v) {
            providerReference = v;
        }

        public String getValue() {
            return value;
        }

        public void setValue(String v) {
            value = v;
        }

        String firstSet(String prefix) {
            String found = first(prefix, keyId, environmentVariable, providerReference);
            return found == null && !isBlank(value) ? prefix + ".value" : found;
        }
    }

    /** How members find each other. {@code mode} is {@code tcp-ip}, {@code kubernetes} or {@code none}. */
    public static class Join {
        /** Join mode for topology embedded: tcp-ip, kubernetes or none. */
        private String mode;
        /** host or host:port entries for join mode tcp-ip. Refused with any other mode. */
        private List<String> members = new ArrayList<>();
        private final Kubernetes kubernetes = new Kubernetes();

        public String getMode() {
            return mode;
        }

        public void setMode(String v) {
            mode = v;
        }

        /** {@code host} or {@code host:port} entries, for {@code tcp-ip} only. */
        public List<String> getMembers() {
            return members;
        }

        public void setMembers(List<String> v) {
            members = v == null ? new ArrayList<>() : new ArrayList<>(v);
        }

        public Kubernetes getKubernetes() {
            return kubernetes;
        }
    }

    /** For {@code kubernetes} only: a namespace and exactly one of service name or service DNS. */
    public static class Kubernetes {
        /** Kubernetes namespace to join in. Required with join mode kubernetes. */
        private String namespace;
        /** Kubernetes service name (API mode). Exactly one of this and service-dns. */
        private String serviceName;
        /** Kubernetes service DNS name (DNS mode). Exactly one of this and service-name. */
        private String serviceDns;

        public String getNamespace() {
            return namespace;
        }

        public void setNamespace(String v) {
            namespace = v;
        }

        public String getServiceName() {
            return serviceName;
        }

        public void setServiceName(String v) {
            serviceName = v;
        }

        public String getServiceDns() {
            return serviceDns;
        }

        public void setServiceDns(String v) {
            serviceDns = v;
        }
    }

    /**
     * This member's own listener. The port defaults to 5701 and is never auto-incremented.
     * With {@code tcp-ip} or {@code kubernetes} and no {@code interface}, the member binds
     * <em>every</em> network interface; set one to restrict it. {@code join.mode: none}
     * binds {@code 127.0.0.1} and refuses an interface.
     */
    public static class Member {
        /** Member port. Unset uses 5701. */
        private Integer port;
        /** Network interface the member binds. Unset binds every interface for tcp-ip and kubernetes. */
        private String interfaceAddress;

        public Integer getPort() {
            return port;
        }

        public void setPort(Integer v) {
            port = v;
        }

        /** Bound as {@code dataprism.hazelcast.member.interface}. */
        public String getInterface() {
            return interfaceAddress;
        }

        public void setInterface(String v) {
            interfaceAddress = v;
        }
    }

    public String getTopology() {
        return topology;
    }

    public void setTopology(String v) {
        topology = v;
    }

    public Duration getIdentityCacheTtl() {
        return identityCacheTtl;
    }

    public void setIdentityCacheTtl(Duration v) {
        identityCacheTtl = v;
    }

    public boolean isReidentificationEnabled() {
        return reidentificationEnabled;
    }

    public void setReidentificationEnabled(boolean v) {
        reidentificationEnabled = v;
    }

    public boolean isPersistenceEnabled() {
        return persistenceEnabled;
    }

    public void setPersistenceEnabled(boolean v) {
        persistenceEnabled = v;
    }

    public boolean isMapStoreEnabled() {
        return mapStoreEnabled;
    }

    public void setMapStoreEnabled(boolean v) {
        mapStoreEnabled = v;
    }

    public String getTlsKeyReference() {
        return tlsKeyReference;
    }

    public void setTlsKeyReference(String v) {
        tlsKeyReference = v;
    }

    public String getTlsTrustReference() {
        return tlsTrustReference;
    }

    public void setTlsTrustReference(String v) {
        tlsTrustReference = v;
    }

    public String getReidentificationControlsReference() {
        return reidentificationControlsReference;
    }

    public void setReidentificationControlsReference(String v) {
        reidentificationControlsReference = v;
    }
}

package io.github.aindriub.dataprism.spring.boot;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
    private final Join join = new Join();
    private final Member member = new Member();

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

    /** Whether any of the cluster-only settings is present. */
    private static boolean isBlank(String v) {
        return v == null || v.isBlank();
    }

    public boolean clusterSettingsSet() {
        return !isBlank(clusterName) || !isBlank(join.mode) || !join.members.isEmpty()
                || !isBlank(join.kubernetes.namespace) || !isBlank(join.kubernetes.serviceName)
                || !isBlank(join.kubernetes.serviceDns) || member.port != null || !isBlank(member.interfaceAddress);
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

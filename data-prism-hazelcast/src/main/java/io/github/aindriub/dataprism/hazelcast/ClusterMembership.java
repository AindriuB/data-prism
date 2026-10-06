package io.github.aindriub.dataprism.hazelcast;

import com.hazelcast.config.Config;
import com.hazelcast.config.JoinConfig;
import com.hazelcast.config.KubernetesConfig;
import io.github.aindriub.dataprism.hazelcast.PrivacyClusterRefusal.Code;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * An explicit, validated description of who may form a cluster. It is translated into a
 * Hazelcast {@link Config} that always has auto-detection, multicast and phone-home off,
 * so a member can only ever join what the deployer named.
 */
public record ClusterMembership(String clusterName, Join join, int port, Optional<String> interfaceAddress) {

    public static final int DEFAULT_PORT = 5701;

    private static final Pattern OCTET = Pattern.compile("\\*|\\d{1,3}(-\\d{1,3})?");

    /** Exactly one way of finding the other members. */
    public sealed interface Join permits TcpIp, Kubernetes, None {
    }

    /** A fixed list of {@code host} or {@code host:port}. */
    public record TcpIp(List<String> members) implements Join {
        public TcpIp {
            members = members == null ? List.of() : List.copyOf(members);
        }
    }

    /** Kubernetes discovery, by service name or by service DNS, never both. */
    public record Kubernetes(String namespace, String serviceName, String serviceDns) implements Join {
    }

    /** A single member that never accepts others. */
    public record None() implements Join {
    }

    public ClusterMembership {
        interfaceAddress = interfaceAddress == null ? Optional.empty() : interfaceAddress;
        if (clusterName == null || clusterName.isBlank()) {
            throw refuse(Code.MISSING_CLUSTER_NAME, "clusterName is required");
        }
        if (isReserved(clusterName)) {
            throw refuse(Code.RESERVED_CLUSTER_NAME, "clusterName is the Hazelcast default and is refused");
        }
        validate(join);
        if (!validPort(port)) {
            throw refuse(Code.INVALID_CLUSTER_PORT, "port must be 1-65535");
        }
        if (interfaceAddress.isPresent() && !validInterface(interfaceAddress.get())) {
            throw refuse(Code.INVALID_CLUSTER_INTERFACE,
                    "interface must be an IPv4 literal or a wildcard pattern such as 10.0.*.*");
        }
    }

    public static ClusterMembership tcpIp(String clusterName, List<String> members) {
        return new ClusterMembership(clusterName, new TcpIp(members), DEFAULT_PORT, Optional.empty());
    }

    public static ClusterMembership kubernetes(String clusterName, String namespace, String serviceName,
                                               String serviceDns) {
        return new ClusterMembership(clusterName, new Kubernetes(namespace, serviceName, serviceDns),
                DEFAULT_PORT, Optional.empty());
    }

    public static ClusterMembership none(String clusterName) {
        return new ClusterMembership(clusterName, new None(), DEFAULT_PORT, Optional.empty());
    }

    public ClusterMembership withPort(int newPort) {
        return new ClusterMembership(clusterName, join, newPort, interfaceAddress);
    }

    public ClusterMembership withInterface(String address) {
        return new ClusterMembership(clusterName, join, port, Optional.ofNullable(address));
    }

    /** Builds a fresh config. Nothing here starts a member. */
    public Config toConfig() {
        Config config = new Config();
        config.setClusterName(clusterName);
        config.getJetConfig().setEnabled(false);
        config.setProperty("hazelcast.phone.home.enabled", "false");
        config.getNetworkConfig().setPort(port).setPortAutoIncrement(false);
        JoinConfig joinConfig = config.getNetworkConfig().getJoin();
        joinConfig.getAutoDetectionConfig().setEnabled(false);
        joinConfig.getMulticastConfig().setEnabled(false);
        joinConfig.getTcpIpConfig().setEnabled(false);
        joinConfig.getKubernetesConfig().setEnabled(false);
        switch (join) {
            case TcpIp tcp -> {
                joinConfig.getTcpIpConfig().setEnabled(true).setMembers(tcp.members());
                interfaceAddress.ifPresent(a -> config.getNetworkConfig().getInterfaces()
                        .setEnabled(true).addInterface(a));
            }
            case Kubernetes k -> {
                KubernetesConfig kube = joinConfig.getKubernetesConfig().setEnabled(true)
                        .setProperty("namespace", k.namespace());
                if (k.serviceName() != null && !k.serviceName().isBlank()) {
                    kube.setProperty("service-name", k.serviceName());
                } else {
                    kube.setProperty("service-dns", k.serviceDns());
                }
                interfaceAddress.ifPresent(a -> config.getNetworkConfig().getInterfaces()
                        .setEnabled(true).addInterface(a));
            }
            case None none -> {
                joinConfig.getTcpIpConfig().setEnabled(true).setMembers(List.of());
                config.getNetworkConfig().getInterfaces().setEnabled(true).addInterface("127.0.0.1");
                config.setProperty("hazelcast.socket.bind.any", "false");
            }
        }
        return config;
    }

    static boolean isReserved(String name) {
        return "dev".equalsIgnoreCase(name.strip());
    }

    static boolean validPort(int p) {
        return p >= 1 && p <= 65535;
    }

    static boolean validInterface(String value) {
        String[] parts = value.split("\\.", -1);
        if (parts.length != 4) {
            return false;
        }
        for (String part : parts) {
            if (!OCTET.matcher(part).matches()) {
                return false;
            }
            for (String n : part.split("-")) {
                if (!n.equals("*") && Integer.parseInt(n) > 255) {
                    return false;
                }
            }
        }
        return true;
    }

    private static void validate(Join join) {
        switch (join) {
            case null -> throw refuse(Code.INVALID_CLUSTER_MEMBERS, "join is required");
            case TcpIp tcp -> {
                if (tcp.members().isEmpty()) {
                    throw refuse(Code.INVALID_CLUSTER_MEMBERS, "members must not be empty");
                }
                tcp.members().forEach(ClusterMembership::validateMember);
            }
            case Kubernetes k -> {
                boolean name = k.serviceName() != null && !k.serviceName().isBlank();
                boolean dns = k.serviceDns() != null && !k.serviceDns().isBlank();
                if (k.namespace() == null || k.namespace().isBlank()) {
                    throw refuse(Code.INVALID_KUBERNETES_JOIN, "namespace is required");
                }
                if (name == dns) {
                    throw refuse(Code.INVALID_KUBERNETES_JOIN,
                            "exactly one of serviceName and serviceDns is required");
                }
            }
            case None none -> {
            }
        }
    }

    private static void validateMember(String member) {
        if (member == null || member.isBlank()) {
            throw refuse(Code.INVALID_CLUSTER_MEMBERS, "a member is blank");
        }
        int colon = member.lastIndexOf(':');
        String host = colon < 0 ? member : member.substring(0, colon);
        if (host.isBlank()) {
            throw refuse(Code.INVALID_CLUSTER_MEMBERS, "a member has no host");
        }
        if (colon >= 0) {
            try {
                if (!validPort(Integer.parseInt(member.substring(colon + 1).strip()))) {
                    throw refuse(Code.INVALID_CLUSTER_MEMBERS, "a member port must be 1-65535");
                }
            } catch (NumberFormatException e) {
                throw refuse(Code.INVALID_CLUSTER_MEMBERS, "a member port must be 1-65535");
            }
        }
    }

    private static PrivacyClusterRefusal refuse(Code code, String message) {
        return new PrivacyClusterRefusal(code, message);
    }
}

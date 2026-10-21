package io.github.aindriub.dataprism.hazelcast;

import com.hazelcast.config.Config;
import com.hazelcast.config.JoinConfig;
import com.hazelcast.config.KubernetesConfig;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.map.IMap;
import io.github.aindriub.dataprism.hazelcast.PrivacyClusterRefusal.Code;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
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

    /** A single member that neither discovers nor accepts other members. */
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
        if (interfaceAddress.isPresent() && join instanceof None) {
            throw refuse(Code.INVALID_CLUSTER_INTERFACE,
                    "interface cannot be set for a single member, which is loopback-only");
        }
        if (interfaceAddress.isPresent() && !validInterface(interfaceAddress.get())) {
            throw refuse(Code.INVALID_CLUSTER_INTERFACE,
                    "interface must be an IPv4 literal or a wildcard pattern such as 10.0.*.*");
        }
        if (interfaceAddress.isPresent() && matchesEveryAddress(interfaceAddress.get())) {
            throw refuse(Code.UNBOUNDED_MEMBER_INTERFACE,
                    "member.interface matches every address; name the network the cluster port may listen on");
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

    /**
     * Builds a fresh config. Nothing here starts a member.
     *
     * @throws PrivacyClusterRefusal {@code MISSING_MEMBER_INTERFACE} for {@code tcp-ip} or {@code kubernetes}
     *                               without an interface: a member that binds every interface listens on networks
     *                               nobody chose. Checked here and not at construction, so the factories can be
     *                               followed by {@link #withInterface}.
     */
    public Config toConfig() {
        if (!(join instanceof None) && interfaceAddress.isEmpty()) {
            throw refuse(Code.MISSING_MEMBER_INTERFACE,
                    "member.interface is required for tcp-ip and kubernetes joins");
        }
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
                bindToInterface(config);
            }
            case Kubernetes k -> {
                KubernetesConfig kube = joinConfig.getKubernetesConfig().setEnabled(true)
                        .setProperty("namespace", k.namespace());
                if (k.serviceName() != null && !k.serviceName().isBlank()) {
                    kube.setProperty("service-name", k.serviceName());
                } else {
                    kube.setProperty("service-dns", k.serviceDns());
                }
                bindToInterface(config);
            }
            case None none -> {
                // Every joiner stays disabled, so the member starts standalone and discovers no one.
                // Hazelcast still answers a join request that carries a matching cluster name, so
                // the effective name also carries an unguessable per-member suffix. It is internal
                // and appears in logs.
                config.setClusterName(clusterName + "-solo-" + UUID.randomUUID());
                config.getNetworkConfig().getInterfaces().setEnabled(true).addInterface("127.0.0.1");
                config.setProperty("hazelcast.socket.bind.any", "false");
            }
        }
        return config;
    }

    /** With an interface, listen only there; without one Hazelcast binds every interface. */
    private void bindToInterface(Config config) {
        interfaceAddress.ifPresent(a -> {
            config.getNetworkConfig().getInterfaces().setEnabled(true).addInterface(a);
            config.setProperty("hazelcast.socket.bind.any", "false");
        });
    }

    static boolean isReserved(String name) {
        return "dev".equalsIgnoreCase(name.strip());
    }

    static boolean validPort(int p) {
        return p >= 1 && p <= 65535;
    }

    /** Every octet is a wildcard or a range that spans 0 to 255, so no address is left out. */
    static boolean matchesEveryAddress(String value) {
        for (String part : value.split("\\.", -1)) {
            if (part.equals("*")) {
                continue;
            }
            String[] bounds = part.split("-", -1);
            if (bounds.length != 2 || Integer.parseInt(bounds[0]) > 0 || Integer.parseInt(bounds[1]) < 255) {
                return false;
            }
        }
        return true;
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

    /**
     * The cutover from 0.7 is non-rolling (cluster-security 4.8): a member that finds an entry in a map of the
     * pre-0.8 generation refuses, so a 0.7 and a 0.8 generation never serve side by side. Only emptiness is asked;
     * the old entries are never copied or logged. The one entry that does not count is the fence, the key
     * {@code all} holding {@code TRUE} in the old oversight map, which a serving 0.8 member keeps there so that a
     * 0.7 member reads the cluster as paused.
     *
     * <p>The caller has not yet written anything of its own, so it cannot be counted as the verified 0.8 member that
     * would excuse the check. Excusing the check when another verified 0.8 member is present needs the member cards
     * of the next task; until then the check always applies.
     *
     * @throws PrivacyClusterRefusal {@code UNSEALED_CLUSTER_STATE}
     */
    static void refuseUnsealedState(HazelcastInstance instance) {
        // State lives only in running members. A member that is alone has nothing to find, and asking would create
        // the old maps, empty and unconfigured, under names 0.8 never uses.
        if (instance.getCluster().getMembers().size() < 2) {
            return;
        }
        for (String name : PrivacyCluster.PRE_0_8_MAPS) {
            if (holdsEntries(instance, name)) {
                throw refuse(Code.UNSEALED_CLUSTER_STATE,
                        "the cluster holds state from before 0.8; stop every 0.7 member, then start the 0.8 members");
            }
        }
    }

    /**
     * How many entries each pre-0.8 map that holds any has, without reading one: sizes only, less the fence. A map
     * that cannot be asked counts as one. Empty if the member is alone.
     */
    static java.util.Map<String, Integer> legacyEntryCounts(HazelcastInstance instance) {
        java.util.Map<String, Integer> found = new java.util.LinkedHashMap<>();
        if (instance.getCluster().getMembers().size() < 2) {
            return found;
        }
        for (String name : PrivacyCluster.PRE_0_8_MAPS) {
            try {
                IMap<Object, Object> map = instance.getMap(name);
                int size = map.size();
                if (PrivacyCluster.OVERSIGHT_FENCE_MAP.equals(name) && Boolean.TRUE.equals(map.get("all"))) {
                    size--;
                }
                if (size > 0) {
                    found.put(name, size);
                }
            } catch (RuntimeException unreadable) {
                found.put(name, 1);
            }
        }
        return found;
    }

    private static boolean holdsEntries(HazelcastInstance instance, String name) {
        try {
            IMap<Object, Object> map = instance.getMap(name);
            if (map.isEmpty()) {
                return false;
            }
            return !(PrivacyCluster.OVERSIGHT_FENCE_MAP.equals(name) && map.size() == 1
                    && Boolean.TRUE.equals(map.get("all")));
        } catch (RuntimeException unreadable) {
            // A map that cannot be asked is not known to be empty.
            return true;
        }
    }

    private static PrivacyClusterRefusal refuse(Code code, String message) {
        return new PrivacyClusterRefusal(code, message);
    }
}

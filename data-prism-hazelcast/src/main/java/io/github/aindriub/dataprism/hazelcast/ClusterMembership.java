package io.github.aindriub.dataprism.hazelcast;

import com.hazelcast.config.Config;
import com.hazelcast.config.JoinConfig;
import com.hazelcast.config.KubernetesConfig;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.map.IMap;
import com.hazelcast.spi.properties.ClusterProperty;
import com.hazelcast.spi.properties.HazelcastProperties;
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
public record ClusterMembership(String clusterName, Join join, int port, String interfaceAddressOrNull) {

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
        if (interfaceAddressOrNull != null && join instanceof None) {
            throw refuse(Code.INVALID_CLUSTER_INTERFACE,
                    "interface cannot be set for a single member, which is loopback-only");
        }
        if (interfaceAddressOrNull != null && !validInterface(interfaceAddressOrNull)) {
            throw refuse(Code.INVALID_CLUSTER_INTERFACE,
                    "interface must be an IPv4 literal or a wildcard pattern such as 10.0.*.*");
        }
        if (interfaceAddressOrNull != null && matchesEveryAddress(interfaceAddressOrNull)) {
            throw refuse(Code.UNBOUNDED_MEMBER_INTERFACE,
                    "member.interface matches every address; name the network the cluster port may listen on");
        }
    }

    /** The interface the member listens on, if one was named. The component itself is nullable. */
    public Optional<String> interfaceAddress() {
        return Optional.ofNullable(interfaceAddressOrNull);
    }

    public static ClusterMembership tcpIp(String clusterName, List<String> members) {
        return new ClusterMembership(clusterName, new TcpIp(members), DEFAULT_PORT, null);
    }

    public static ClusterMembership kubernetes(String clusterName, String namespace, String serviceName,
                                               String serviceDns) {
        return new ClusterMembership(clusterName, new Kubernetes(namespace, serviceName, serviceDns),
                DEFAULT_PORT, null);
    }

    public static ClusterMembership none(String clusterName) {
        return new ClusterMembership(clusterName, new None(), DEFAULT_PORT, null);
    }

    public ClusterMembership withPort(int newPort) {
        return new ClusterMembership(clusterName, join, newPort, interfaceAddressOrNull);
    }

    public ClusterMembership withInterface(String address) {
        return new ClusterMembership(clusterName, join, port, address);
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
        return toConfig(Code.INVALID_CLUSTER_INTERFACE);
    }

    /**
     * As {@link #toConfig()}; {@code isolationFailure} is the code given when a {@code none} membership cannot be
     * made loopback-only: a member that holds keys is not told it lacks them.
     */
    Config toConfig(Code isolationFailure) {
        if (!(join instanceof None) && interfaceAddressOrNull == null) {
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
                isolate(config, isolationFailure);
            }
        }
        return config;
    }

    /**
     * Makes a member solo: every joiner stays disabled, so it starts standalone and discovers no one. Hazelcast still
     * answers a join request that carries a matching cluster name, so the effective name also carries an
     * unguessable per-member suffix (internal, and it appears in logs), and the member listens on loopback only.
     */
    static void isolate(Config config) {
        isolate(config, Code.MISSING_CLUSTER_KEY);
    }

    /** As above; {@code failure} is the code when the configuration still reaches beyond this host. */
    static void isolate(Config config, Code failure) {
        // Modifies the Config it is given. The suffix is always fresh, so nothing in the Config can skip it; a
        // Config passed twice carries two suffixes, which is harmless.
        config.setClusterName(config.getClusterName() + "-solo-" + UUID.randomUUID());
        config.getNetworkConfig().getInterfaces().clear().setEnabled(true).addInterface("127.0.0.1");
        config.setProperty("hazelcast.socket.bind.any", "false");
        config.setProperty("hazelcast.socket.server.bind.any", "false");
        config.getNetworkConfig().setPublicAddress(null);
        config.getNetworkConfig().getMemberAddressProviderConfig().setEnabled(false);
        // What the member will really do is read the same way Hazelcast reads it, so a setting that still wins
        // over the above (a system property, say) is caught here and not assumed away.
        if (!reachableOnlyFromThisHost(config)) {
            throw refuse(failure, failure == Code.MISSING_CLUSTER_KEY
                    ? "a member without cluster keys must listen on loopback only, and its configuration does not"
                    : "a single member listens on loopback only, and an address setting makes it reachable elsewhere");
        }
    }

    /** The host part of {@code host} or {@code host:port}; a bare IPv6 literal has no port to strip. */
    private static String hostOf(String address) {
        int colon = address.lastIndexOf(':');
        return colon > 0 && address.indexOf(':') == colon ? address.substring(0, colon) : address;
    }

    /** Hazelcast reads this as a Config property or a system property, and it overrides the picked address. */
    private static final String LOCAL_ADDRESS = "hazelcast.local.localAddress";

    /** Hazelcast reads this too; it sets the address a member advertises to the others. */
    private static final String PUBLIC_ADDRESS = "hazelcast.local.publicAddress";

    private static final Pattern LOOPBACK_LITERAL = Pattern.compile("127\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})");

    /**
     * An IPv4 literal in 127/8 or {@code ::1}. {@code localhost} and every other name are refused: a name is
     * resolved through the hosts file or DNS and could point anywhere.
     */
    static boolean loopbackLiteral(String value) {
        if (value.equals("::1")) {
            return true;
        }
        var matcher = LOOPBACK_LITERAL.matcher(value);
        if (!matcher.matches()) {
            return false;
        }
        for (int i = 1; i <= 3; i++) {
            if (Integer.parseInt(matcher.group(i)) > 255) {
                return false;
            }
        }
        return true;
    }

    /**
     * Whether the config makes the member reachable from this host only: neither socket setting binds every
     * interface, the interfaces are enabled and all loopback entries, no public address is advertised (field, or the
     * {@code hazelcast.local.publicAddress} property, loopback literals only), no member
     * address provider is enabled, and no non-loopback {@code hazelcast.local.localAddress} (Config or system
     * property) overrides the address picked. Read the way Hazelcast reads its properties. {@code PrivacyCluster.using} asks
     * the same of a supplied instance, so the two cannot disagree.
     */
    static boolean reachableOnlyFromThisHost(Config config) {
        HazelcastProperties properties = new HazelcastProperties(config);
        if (properties.getBoolean(ClusterProperty.SOCKET_BIND_ANY)
                || properties.getBoolean(ClusterProperty.SOCKET_SERVER_BIND_ANY)) {
            return false;
        }
        String localAddress = config.getProperty(LOCAL_ADDRESS);
        if (localAddress == null) {
            localAddress = System.getProperty(LOCAL_ADDRESS);
        }
        if (localAddress != null && !localAddress.isBlank() && !loopbackLiteral(localAddress.strip())) {
            return false;
        }
        String advertised = config.getProperty(PUBLIC_ADDRESS);
        if (advertised == null) {
            advertised = System.getProperty(PUBLIC_ADDRESS);
        }
        if (advertised != null && !advertised.isBlank() && !loopbackLiteral(hostOf(advertised.strip()))) {
            return false;
        }
        var network = config.getNetworkConfig();
        var interfaces = network.getInterfaces();
        if (!interfaces.isEnabled() || interfaces.getInterfaces().isEmpty()
                || !interfaces.getInterfaces().stream().allMatch(entry -> entry != null
                && entry.strip().startsWith("127."))) {
            return false;
        }
        String publicAddress = network.getPublicAddress();
        if (publicAddress != null && !publicAddress.isBlank()) {
            return false;
        }
        return !network.getMemberAddressProviderConfig().isEnabled();
    }

    /**
     * With an interface, listen only there; without one Hazelcast binds every interface. Both bind-any settings are
     * set to false and then read back the way Hazelcast reads them, so a system property that still wins (as
     * {@code -Dhazelcast.socket.server.bind.any=true} would) is refused here and does not override the interface.
     */
    private void bindToInterface(Config config) {
        if (interfaceAddressOrNull != null) {
            String a = interfaceAddressOrNull;
            config.getNetworkConfig().getInterfaces().setEnabled(true).addInterface(a);
            config.setProperty("hazelcast.socket.bind.any", "false");
            config.setProperty("hazelcast.socket.server.bind.any", "false");
            HazelcastProperties effective = new HazelcastProperties(config);
            if (effective.getBoolean(ClusterProperty.SOCKET_BIND_ANY)
                    || effective.getBoolean(ClusterProperty.SOCKET_SERVER_BIND_ANY)) {
                throw refuse(Code.INVALID_CLUSTER_INTERFACE,
                        "a bind-any setting makes the member listen on every interface, overriding member.interface");
            }
        }
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
        refuseUnsealedState(instance, false);
    }

    /**
     * @param supplied whether the instance was started by somebody else and may already have been in use. A member
     *                 this class created is empty, so alone it has nothing to find, and asking would create the old
     *                 maps, empty and unconfigured, under names 0.8 never uses. A supplied instance can hold old
     *                 entries with no other member left: it is asked even when alone, but only about old maps it
     *                 already has, so none is created.
     */
    static void refuseUnsealedState(HazelcastInstance instance, boolean supplied) {
        java.util.Collection<String> names = PrivacyCluster.PRE_0_8_MAPS;
        if (instance.getCluster().getMembers().size() < 2) {
            if (!supplied) {
                return;
            }
            try {
                java.util.Set<String> existing = new java.util.HashSet<>();
                instance.getDistributedObjects().stream().filter(o -> o instanceof IMap)
                        .forEach(o -> existing.add(o.getName()));
                names = PrivacyCluster.PRE_0_8_MAPS.stream().filter(existing::contains).toList();
            } catch (RuntimeException unreadable) {
                // The list cannot be read, so every old name is asked about directly.
            }
        }
        for (String name : names) {
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
        try {
            long raw = instance.<Object, Object>getMap(PrivacyCluster.OVERSIGHT_MAP).keySet().stream()
                    .filter(key -> key instanceof String text && text.indexOf('\0') >= 0).count();
            if (raw > 0) {
                found.put(PrivacyCluster.OVERSIGHT_MAP, (int) raw);
            }
        } catch (RuntimeException unreadable) {
            found.put(PrivacyCluster.OVERSIGHT_MAP, 1);
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

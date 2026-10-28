package io.github.aindriub.dataprism.hazelcast;

import com.hazelcast.config.Config;
import com.hazelcast.config.InterfacesConfig;
import com.hazelcast.spi.properties.ClusterProperty;
import com.hazelcast.spi.properties.HazelcastProperties;

import java.net.InetAddress;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The one copy of the trusted-network rule for a member that joins others. Approvals and counters are unsealed on a
 * multi-member cluster, so a member may start only if everything it listens on and advertises lies in a private
 * block (or in a block the operator named) and it does not bind every interface. This does not make a network
 * trusted: {@code trusted-network: true} is the operator's assertion that only Data Prism members can reach the
 * member port, and this rule only stops the common misconfigurations.
 *
 * <p>It reads the {@link Config} and then system properties the way Hazelcast resolves them, and never throws: each
 * caller maps a {@link Verdict} to its own refusal.
 */
public final class TrustedNetwork {

    /** What the rule decided. */
    public enum Verdict {
        /** Every listened-on and advertised address is private or in a trusted range. */
        TRUSTED,
        /** A listened-on or advertised address, or a bind-any setting, reaches beyond the trusted blocks. */
        PUBLIC_MEMBER_INTERFACE,
        /** A trusted range does not parse, or is neither private nor at most a /24. */
        INVALID_TRUSTED_RANGE
    }

    private static final String LOCAL_ADDRESS = "hazelcast.local.localAddress";
    private static final String PUBLIC_ADDRESS = "hazelcast.local.publicAddress";

    /** The private blocks as {network, prefix length}: 10/8, 172.16/12, 192.168/16 and 127/8. */
    private static final long[][] PRIVATE_BLOCKS = {
            {ip(10, 0, 0, 0), 8}, {ip(172, 16, 0, 0), 12}, {ip(192, 168, 0, 0), 16}, {ip(127, 0, 0, 0), 8}};

    /** A block outside the private ones is accepted only at /24 or longer: at most this many addresses. */
    private static final long MAX_UNPRIVATE_ADDRESSES = 256;

    private static final Pattern DECIMAL = Pattern.compile("\\d{1,3}");
    private static final Pattern IPV4_LITERAL = Pattern.compile("\\d{1,3}(\\.\\d{1,3}){3}");
    private static final Pattern IPV6_LITERAL = Pattern.compile("[0-9a-fA-F:]+");

    private TrustedNetwork() {
    }

    private static long ip(int a, int b, int c, int d) {
        return ((long) a << 24) | ((long) b << 16) | ((long) c << 8) | d;
    }

    /** An inclusive interval of addresses. */
    private record Span(long low, long high) {
        boolean inside(Span outer) {
            return low >= outer.low && high <= outer.high;
        }
    }

    private static Span block(long network, int prefix) {
        long size = 1L << (32 - prefix);
        long low = network & ~(size - 1);
        return new Span(low, low + size - 1);
    }

    private static boolean privateBlock(Span span) {
        for (long[] b : PRIVATE_BLOCKS) {
            if (span.inside(block(b[0], (int) b[1]))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Decides whether a member built from {@code config} may join other members.
     *
     * @param config        the member's Hazelcast config
     * @param trustedRanges IPv4 CIDR blocks the operator trusts beyond the private ones; may be null
     */
    public static Verdict evaluate(Config config, List<String> trustedRanges) {
        List<Span> trusted = new java.util.ArrayList<>();
        for (String entry : trustedRanges == null ? List.<String>of() : trustedRanges) {
            Span span = parseRange(entry);
            if (span == null || !(privateBlock(span) || span.high - span.low + 1 <= MAX_UNPRIVATE_ADDRESSES)) {
                return Verdict.INVALID_TRUSTED_RANGE;
            }
            trusted.add(span);
        }
        try {
            return listensAndAdvertisesTrusted(config, trusted) ? Verdict.TRUSTED : Verdict.PUBLIC_MEMBER_INTERFACE;
        } catch (RuntimeException unreadable) {
            return Verdict.PUBLIC_MEMBER_INTERFACE;
        }
    }

    /** How many of the entries are outside the private blocks. Entries that do not parse are not counted. */
    public static int nonPrivateRangeCount(List<String> trustedRanges) {
        int count = 0;
        for (String entry : trustedRanges == null ? List.<String>of() : trustedRanges) {
            Span span = parseRange(entry);
            if (span != null && !privateBlock(span)) {
                count++;
            }
        }
        return count;
    }

    private static boolean listensAndAdvertisesTrusted(Config config, List<Span> trusted) {
        HazelcastProperties properties = new HazelcastProperties(config);
        // A Config property outranks a system property for Hazelcast, but a system property set to true is refused
        // all the same: it must not be one edit away from taking effect.
        if (properties.getBoolean(ClusterProperty.SOCKET_BIND_ANY)
                || properties.getBoolean(ClusterProperty.SOCKET_SERVER_BIND_ANY)
                || Boolean.getBoolean(ClusterProperty.SOCKET_BIND_ANY.getName())
                || Boolean.getBoolean(ClusterProperty.SOCKET_SERVER_BIND_ANY.getName())) {
            return false;
        }
        InterfacesConfig interfaces = config.getNetworkConfig().getInterfaces();
        if (!interfaces.isEnabled() || interfaces.getInterfaces().isEmpty()) {
            return false;
        }
        for (String entry : interfaces.getInterfaces()) {
            Span span = entry == null ? null : parsePattern(entry.strip());
            if (span == null || !covered(span, trusted)) {
                return false;
            }
        }
        for (String address : new String[] {
                config.getNetworkConfig().getPublicAddress(),
                property(config, LOCAL_ADDRESS), property(config, PUBLIC_ADDRESS)}) {
            if (address != null && !address.isBlank() && !advertisedTrusted(address.strip(), trusted)) {
                return false;
            }
        }
        return true;
    }

    /** The Config property if there is one, else the system property: Hazelcast's own order. */
    private static String property(Config config, String name) {
        String value = config.getProperty(name);
        return value != null ? value : System.getProperty(name);
    }

    private static boolean covered(Span span, List<Span> trusted) {
        return privateBlock(span) || trusted.stream().anyMatch(span::inside);
    }

    private static boolean advertisedTrusted(String address, List<Span> trusted) {
        int colon = address.lastIndexOf(':');
        String host = colon > 0 && address.indexOf(':') == colon ? address.substring(0, colon) : address;
        if (IPV4_LITERAL.matcher(host).matches()) {
            Span span = parsePattern(host);
            return span != null && covered(span, trusted);
        }
        return host.contains(":") && IPV6_LITERAL.matcher(host).matches() && privateIpv6(host);
    }

    /** {@code ::1} or unique-local {@code fc00::/7}; a literal, so nothing is resolved. */
    private static boolean privateIpv6(String host) {
        try {
            byte[] bytes = InetAddress.getByName(host).getAddress();
            if (bytes.length != 16) {
                return false;
            }
            if ((bytes[0] & 0xFE) == 0xFC) {
                return true;
            }
            for (int i = 0; i < 15; i++) {
                if (bytes[i] != 0) {
                    return false;
                }
            }
            return bytes[15] == 1;
        } catch (java.net.UnknownHostException | RuntimeException e) {
            return false;
        }
    }

    /** An interface pattern or literal as the set of addresses it can match, or null if it is not one. */
    private static Span parsePattern(String value) {
        String[] parts = value.split("\\.", -1);
        if (parts.length != 4) {
            return null;
        }
        long low = 0;
        long high = 0;
        for (String part : parts) {
            int from;
            int to;
            if (part.equals("*")) {
                from = 0;
                to = 255;
            } else {
                String[] bounds = part.split("-", -1);
                if (bounds.length > 2) {
                    return null;
                }
                for (String bound : bounds) {
                    if (!DECIMAL.matcher(bound).matches()) {
                        return null;
                    }
                }
                from = Integer.parseInt(bounds[0]);
                to = Integer.parseInt(bounds[bounds.length - 1]);
            }
            if (from > to || to > 255) {
                return null;
            }
            low = (low << 8) | from;
            high = (high << 8) | to;
        }
        return new Span(low, high);
    }

    /** {@code a.b.c.d/n} with n from 0 to 32, as the block it names; null if it is anything else. */
    private static Span parseRange(String entry) {
        if (entry == null) {
            return null;
        }
        int slash = entry.indexOf('/');
        if (slash < 0 || !DECIMAL.matcher(entry.substring(slash + 1)).matches()) {
            return null;
        }
        int prefix = Integer.parseInt(entry.substring(slash + 1));
        String address = entry.substring(0, slash);
        if (prefix > 32 || !IPV4_LITERAL.matcher(address).matches()) {
            return null;
        }
        Span literal = parsePattern(address);
        return literal == null ? null : block(literal.low, prefix);
    }
}

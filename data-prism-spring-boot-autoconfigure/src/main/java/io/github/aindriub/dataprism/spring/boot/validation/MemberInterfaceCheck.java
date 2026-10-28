package io.github.aindriub.dataprism.spring.boot.validation;

import com.hazelcast.config.Config;
import io.github.aindriub.dataprism.hazelcast.ClusterMembership;
import io.github.aindriub.dataprism.hazelcast.PrivacyClusterRefusal;
import io.github.aindriub.dataprism.hazelcast.TrustedNetwork;
import io.github.aindriub.dataprism.spring.boot.DataPrismConfigurationException;

import java.util.List;

/**
 * Checks {@code member.interface} with the member factory's own rules and the trusted-network rule. Kept apart from
 * {@link DataPrismPropertiesValidator} so that class loads without {@code data-prism-hazelcast}: the validator calls
 * this one only inside a block that tolerates the dependency being absent.
 */
final class MemberInterfaceCheck {
    private MemberInterfaceCheck() {
    }

    /**
     * @param trustedNetwork whether the operator declared the network trusted
     * @param trustedRanges  the {@code member.trusted-ranges}, which count only if the network is declared trusted
     */
    static void check(String interfaceAddress, boolean trustedNetwork, List<String> trustedRanges) {
        List<String> counted = trustedNetwork ? trustedRanges : List.of();
        Config config;
        try {
            config = new ClusterMembership("interface-check", new ClusterMembership.TcpIp(List.of("127.0.0.1")),
                    ClusterMembership.DEFAULT_PORT, interfaceAddress).toConfig();
        } catch (PrivacyClusterRefusal refusal) {
            throw translate(refusal);
        }
        switch (TrustedNetwork.evaluate(config, counted)) {
            case TRUSTED -> {
            }
            case INVALID_TRUSTED_RANGE -> throw new DataPrismConfigurationException("INVALID_TRUSTED_RANGE",
                    "dataprism.hazelcast.member.trusted-ranges[" + firstInvalid(config, counted)
                            + "] must be an IPv4 CIDR block, inside 10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16 or "
                            + "127.0.0.0/8, or /24 or longer");
            case PUBLIC_MEMBER_INTERFACE -> throw new DataPrismConfigurationException("PUBLIC_MEMBER_INTERFACE",
                    "dataprism.hazelcast.member.interface, an advertised address "
                            + "(hazelcast.local.localAddress, hazelcast.local.publicAddress) or a bind-any setting "
                            + "(hazelcast.socket.bind.any, hazelcast.socket.server.bind.any) reaches beyond the "
                            + "private blocks 10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16 and 127.0.0.0/8; narrow it "
                            + "or name the block in dataprism.hazelcast.member.trusted-ranges");
        }
        if (!trustedNetwork) {
            throw new DataPrismConfigurationException("MISSING_TRUSTED_NETWORK",
                    "dataprism.hazelcast.trusted-network must be true to join other members. Setting it asserts that "
                            + "only Data Prism members can reach the member port (dataprism.hazelcast.member.port, "
                            + "5701 by default); approvals and counters on that port are unsealed, so anyone else "
                            + "who can reach it can read and forge them. On Kubernetes the shipped NetworkPolicy "
                            + "admits every pod labelled app: data-prism, so run the members in a dedicated "
                            + "namespace; it protects only where the network plugin enforces NetworkPolicy and no "
                            + "pod uses hostNetwork");
        }
    }

    /** How many trusted ranges lie outside the private blocks. */
    static int outsidePrivateBlocks(List<String> trustedRanges) {
        return TrustedNetwork.nonPrivateRangeCount(trustedRanges);
    }

    /** The index of the first entry the rule refuses on its own; the entries themselves are never named. */
    private static int firstInvalid(Config config, List<String> trustedRanges) {
        for (int i = 0; i < trustedRanges.size(); i++) {
            if (TrustedNetwork.evaluate(config, List.of(trustedRanges.get(i)))
                    == TrustedNetwork.Verdict.INVALID_TRUSTED_RANGE) {
                return i;
            }
        }
        return 0;
    }

    /** The configuration refusal for a member refusal: the same code, and a message that names the property. */
    static DataPrismConfigurationException translate(PrivacyClusterRefusal refusal) {
        String code = refusal.code().name();
        return switch (code) {
            case "UNBOUNDED_MEMBER_INTERFACE" -> new DataPrismConfigurationException(code,
                    "dataprism.hazelcast.member.interface matches every address; name the network the cluster "
                            + "port may listen on", refusal);
            case "INVALID_CLUSTER_INTERFACE" -> new DataPrismConfigurationException(code,
                    "dataprism.hazelcast.member.interface must be an IPv4 literal or a wildcard pattern such as "
                            + "10.0.*.*", refusal);
            default -> new DataPrismConfigurationException(code,
                    "dataprism.hazelcast.member.interface was refused by the cluster member", refusal);
        };
    }
}

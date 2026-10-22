package io.github.aindriub.dataprism.spring.boot.validation;

import io.github.aindriub.dataprism.hazelcast.ClusterMembership;
import io.github.aindriub.dataprism.hazelcast.PrivacyClusterRefusal;
import io.github.aindriub.dataprism.spring.boot.DataPrismConfigurationException;

import java.util.List;
import java.util.Optional;

/**
 * Checks {@code member.interface} with the member factory's own rules. Kept apart from
 * {@link DataPrismPropertiesValidator} so that class loads without {@code data-prism-hazelcast}: the validator calls
 * this one only inside a block that tolerates the dependency being absent.
 */
final class MemberInterfaceCheck {
    private MemberInterfaceCheck() {
    }

    static void check(String interfaceAddress) {
        try {
            new ClusterMembership("interface-check", new ClusterMembership.TcpIp(List.of("127.0.0.1")),
                    ClusterMembership.DEFAULT_PORT, Optional.of(interfaceAddress));
        } catch (PrivacyClusterRefusal refusal) {
            String code = refusal.code().name();
            if ("UNBOUNDED_MEMBER_INTERFACE".equals(code)) {
                throw new DataPrismConfigurationException(code, "dataprism.hazelcast.member.interface matches "
                        + "every address; name the network the cluster port may listen on");
            }
            if ("INVALID_CLUSTER_INTERFACE".equals(code)) {
                throw new DataPrismConfigurationException(code, "dataprism.hazelcast.member.interface must be an "
                        + "IPv4 literal or a wildcard pattern such as 10.0.*.*");
            }
        }
    }
}

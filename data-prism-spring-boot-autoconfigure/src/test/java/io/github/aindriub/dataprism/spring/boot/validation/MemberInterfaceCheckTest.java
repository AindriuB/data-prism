package io.github.aindriub.dataprism.spring.boot.validation;

import io.github.aindriub.dataprism.hazelcast.PrivacyClusterRefusal;
import io.github.aindriub.dataprism.hazelcast.PrivacyClusterRefusal.Code;
import io.github.aindriub.dataprism.spring.boot.DataPrismConfigurationException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Review of task 222: the interface check keeps the member factory's code and never swallows a refusal. */
class MemberInterfaceCheckTest {

    @Test
    void anUnboundedInterfaceKeepsItsCode() {
        assertThatThrownBy(() -> MemberInterfaceCheck.check("*.*.*.*", true, List.of()))
                .isInstanceOf(DataPrismConfigurationException.class)
                .hasMessageStartingWith("UNBOUNDED_MEMBER_INTERFACE:");
    }

    @Test
    void anInvalidInterfaceKeepsItsCode() {
        assertThatThrownBy(() -> MemberInterfaceCheck.check("not-an-address", true, List.of()))
                .isInstanceOf(DataPrismConfigurationException.class)
                .hasMessageStartingWith("INVALID_CLUSTER_INTERFACE:");
    }

    @Test
    void aValidInterfacePasses() {
        MemberInterfaceCheck.check("10.0.*.*", true, List.of());
    }

    @Test
    void aPublicInterfaceIsMappedToItsCodeAndNamesThePropertyNotTheValue() {
        assertThatThrownBy(() -> MemberInterfaceCheck.check("203.0.113.*", true, List.of()))
                .isInstanceOf(DataPrismConfigurationException.class)
                .hasMessageStartingWith("PUBLIC_MEMBER_INTERFACE:")
                .hasMessageContaining("dataprism.hazelcast.member.interface")
                .hasMessageNotContaining("203.0.113");
    }

    @Test
    void aTrustedRangeCoveringTheInterfacePasses() {
        MemberInterfaceCheck.check("203.0.113.*", true, List.of("203.0.113.0/24"));
    }

    @Test
    void anInvalidRangeIsMappedToItsCodeAndNamesTheIndexNotTheValue() {
        assertThatThrownBy(() -> MemberInterfaceCheck.check("10.0.*.*", true, List.of("10.1.0.0/16", "198.0.0.0/8")))
                .isInstanceOf(DataPrismConfigurationException.class)
                .hasMessageStartingWith("INVALID_TRUSTED_RANGE:")
                .hasMessageContaining("dataprism.hazelcast.member.trusted-ranges[1]")
                .hasMessageNotContaining("198.0.0.0");
    }

    @Test
    void anUndeclaredNetworkIsMappedToItsCodeAndRangesAreNotReadThen() {
        assertThatThrownBy(() -> MemberInterfaceCheck.check("10.0.*.*", false, List.of("not-a-range")))
                .isInstanceOf(DataPrismConfigurationException.class)
                .hasMessageStartingWith("MISSING_TRUSTED_NETWORK:")
                .hasMessageContaining("dataprism.hazelcast.trusted-network");
    }

    @Test
    void theRangesOutsideThePrivateBlocksAreCounted() {
        assertThat(MemberInterfaceCheck.outsidePrivateBlocks(List.of("10.0.0.0/8", "203.0.113.0/24"))).isEqualTo(1);
    }

    @Test
    void anyOtherRefusalIsPropagatedWithItsOwnCode() {
        DataPrismConfigurationException translated = MemberInterfaceCheck.translate(
                new PrivacyClusterRefusal(Code.INVALID_CLUSTER_PORT, "port must be 1-65535"));
        assertThat(translated.getMessage()).startsWith("INVALID_CLUSTER_PORT:")
                .contains("dataprism.hazelcast.member.interface");
    }
}

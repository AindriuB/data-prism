package io.github.aindriub.dataprism.spring.boot.validation;

import io.github.aindriub.dataprism.hazelcast.PrivacyClusterRefusal;
import io.github.aindriub.dataprism.hazelcast.PrivacyClusterRefusal.Code;
import io.github.aindriub.dataprism.spring.boot.DataPrismConfigurationException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Review of task 222: the interface check keeps the member factory's code and never swallows a refusal. */
class MemberInterfaceCheckTest {

    @Test
    void anUnboundedInterfaceKeepsItsCode() {
        assertThatThrownBy(() -> MemberInterfaceCheck.check("*.*.*.*"))
                .isInstanceOf(DataPrismConfigurationException.class)
                .hasMessageStartingWith("UNBOUNDED_MEMBER_INTERFACE:");
    }

    @Test
    void anInvalidInterfaceKeepsItsCode() {
        assertThatThrownBy(() -> MemberInterfaceCheck.check("not-an-address"))
                .isInstanceOf(DataPrismConfigurationException.class)
                .hasMessageStartingWith("INVALID_CLUSTER_INTERFACE:");
    }

    @Test
    void aValidInterfacePasses() {
        MemberInterfaceCheck.check("10.0.*.*");
    }

    @Test
    void anyOtherRefusalIsPropagatedWithItsOwnCode() {
        DataPrismConfigurationException translated = MemberInterfaceCheck.translate(
                new PrivacyClusterRefusal(Code.INVALID_CLUSTER_PORT, "port must be 1-65535"));
        assertThat(translated.getMessage()).startsWith("INVALID_CLUSTER_PORT:")
                .contains("dataprism.hazelcast.member.interface");
    }
}

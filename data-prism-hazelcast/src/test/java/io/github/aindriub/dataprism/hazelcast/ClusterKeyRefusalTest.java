package io.github.aindriub.dataprism.hazelcast;

import com.hazelcast.config.Config;
import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;
import io.github.aindriub.dataprism.hazelcast.PrivacyClusterRefusal.Code;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.OptionalInt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/** Task 222: a member that joins others needs the cluster and name keys; a solo member does not. */
class ClusterKeyRefusalTest {

    @AfterEach
    void shutDown() {
        Hazelcast.shutdownAll();
    }

    private static void refusedAsMissing(Throwable refused) {
        assertThat(refused).isInstanceOf(PrivacyClusterRefusal.class);
        assertThat(((PrivacyClusterRefusal) refused).code()).isEqualTo(Code.MISSING_CLUSTER_KEY);
        assertThat(refused.getMessage()).startsWith("MISSING_CLUSTER_KEY:");
    }

    @Test
    void aTcpIpMembershipWithoutKeysIsRefusedBeforeAMemberStarts() {
        int port = FreePorts.consecutive(1);
        ClusterMembership membership = ClusterMembership.tcpIp(TestMembers.uniqueName("keys"),
                List.of("127.0.0.1:" + port)).withPort(port).withInterface("127.0.0.1");
        refusedAsMissing(catchThrowable(() -> PrivacyCluster.embedded(membership, false)));
        refusedAsMissing(catchThrowable(() -> PrivacyCluster.embedded(membership, false, OptionalInt.empty(),
                java.time.Duration.ofMinutes(1), null)));
        assertThat(Hazelcast.getAllHazelcastInstances()).isEmpty();
    }

    @Test
    void aKubernetesMembershipWithoutKeysIsRefusedBeforeAMemberStarts() {
        ClusterMembership membership = ClusterMembership.kubernetes(TestMembers.uniqueName("keys"), "ns", "svc",
                null).withInterface("10.0.*.*");
        refusedAsMissing(catchThrowable(() -> PrivacyCluster.embedded(membership, false)));
        assertThat(Hazelcast.getAllHazelcastInstances()).isEmpty();
    }

    @Test
    void keysAreCheckedBeforeTheMemberInterface() {
        ClusterMembership membership = ClusterMembership.tcpIp(TestMembers.uniqueName("keys"),
                List.of("127.0.0.1:5701"));
        refusedAsMissing(catchThrowable(() -> PrivacyCluster.embedded(membership, false)));
    }

    @Test
    void aJoiningConfigWithoutKeysIsRefusedToo() {
        int port = FreePorts.consecutive(1);
        Config config = ClusterMembership.tcpIp(TestMembers.uniqueName("keys"), List.of("127.0.0.1:" + port))
                .withPort(port).withInterface("127.0.0.1").toConfig();
        refusedAsMissing(catchThrowable(() -> PrivacyCluster.embedded(config, false)));
        assertThat(Hazelcast.getAllHazelcastInstances()).isEmpty();
    }

    @Test
    void usingAnInstanceThatJoinsWithoutKeysIsRefusedAndTheInstanceKeepsRunning() {
        int port = FreePorts.consecutive(1);
        Config config = ClusterMembership.tcpIp(TestMembers.uniqueName("keys"), List.of("127.0.0.1:" + port))
                .withPort(port).withInterface("127.0.0.1").toConfig();
        config = PrivacyCluster.configure(config);
        HazelcastInstance instance = Hazelcast.newHazelcastInstance(config);
        refusedAsMissing(catchThrowable(() -> PrivacyCluster.using(instance, false)));
        assertThat(instance.getLifecycleService().isRunning()).isTrue();
        try (PrivacyCluster wrapped = PrivacyCluster.using(instance, false, TestMembers.KEYS)) {
            assertThat(wrapped.keys()).containsSame(TestMembers.KEYS);
        }
    }

    @Test
    void aSoloMemberStartsWithoutKeys() {
        try (PrivacyCluster solo = PrivacyCluster.embedded(ClusterMembership.none(TestMembers.uniqueName("keys"))
                .withPort(FreePorts.consecutive(1)), false)) {
            assertThat(solo.keys()).isEmpty();
        }
    }

    @Test
    void aSoloMemberHoldsTheKeysItIsGiven() {
        try (PrivacyCluster solo = PrivacyCluster.embedded(ClusterMembership.none(TestMembers.uniqueName("keys"))
                .withPort(FreePorts.consecutive(1)), false, OptionalInt.empty(),
                java.time.Duration.ofMinutes(1), TestMembers.KEYS)) {
            assertThat(solo.keys()).containsSame(TestMembers.KEYS);
        }
    }

    @Test
    void aJoiningMemberWithKeysStarts() {
        int port = FreePorts.consecutive(1);
        try (PrivacyCluster member = PrivacyCluster.embedded(ClusterMembership.tcpIp(
                        TestMembers.uniqueName("keys"), List.of("127.0.0.1:" + port)).withPort(port)
                .withInterface("127.0.0.1"), false, OptionalInt.of(1), java.time.Duration.ofMinutes(1),
                TestMembers.KEYS)) {
            assertThat(member.keys()).containsSame(TestMembers.KEYS);
        }
    }
}

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

/** Review of task 222: a member without keys is solo, however its config was written. */
class KeylessMemberIsolationTest {

    @AfterEach
    void shutDown() {
        Hazelcast.shutdownAll();
    }

    /** A config with every joiner disabled that listens where a keyed member will look for it. */
    private static Config keylessConfig(String name, int port) {
        Config config = new Config().setClusterName(name);
        config.setProperty("hazelcast.phone.home.enabled", "false");
        config.getNetworkConfig().setPort(port).setPortAutoIncrement(false);
        config.getNetworkConfig().getInterfaces().setEnabled(true).addInterface("127.0.0.1");
        return config;
    }

    @Test
    void aKeyedMemberCannotFormAClusterWithAKeylessConfigMember() {
        String name = TestMembers.uniqueName("keyless");
        int first = FreePorts.consecutive(2);
        try (PrivacyCluster keyless = PrivacyCluster.embedded(keylessConfig(name, first), false)) {
            // Hazelcast's own joiner check may already turn the keyed member away; it must not join either way.
            PrivacyCluster keyed = null;
            try {
                keyed = TestMembers.member(name, first + 1, List.of("127.0.0.1:" + first), OptionalInt.of(1));
                assertThat(keyed.instance().getCluster().getMembers()).hasSize(1);
            } finally {
                if (keyed != null) {
                    keyed.close();
                }
            }
            assertThat(keyless.instance().getCluster().getMembers()).hasSize(1);
            assertThat(keyed).as("the keyed member starts on its own").isNotNull();
        }
    }

    @Test
    void aKeylessConfigMemberListensOnLoopbackOnly() {
        int port = FreePorts.consecutive(1);
        Config config = keylessConfig(TestMembers.uniqueName("keyless"), port);
        config.getNetworkConfig().getInterfaces().clear().addInterface("10.9.9.9");
        try (PrivacyCluster keyless = PrivacyCluster.embedded(config, false)) {
            assertThat(keyless.instance().getConfig().getNetworkConfig().getInterfaces().getInterfaces())
                    .containsExactly("127.0.0.1");
            assertThat(keyless.instance().getConfig().getClusterName()).contains("-solo-");
        }
    }

    @Test
    void aKeyedConfigMemberKeepsItsClusterNameAndInterface() {
        int port = FreePorts.consecutive(1);
        String name = TestMembers.uniqueName("keyed");
        Config config = keylessConfig(name, port);
        try (PrivacyCluster keyed = PrivacyCluster.embedded(config, false, OptionalInt.empty(), TestMembers.KEYS)) {
            assertThat(keyed.instance().getConfig().getClusterName()).isEqualTo(name);
        }
    }

    /** An instance with every joiner disabled that nonetheless reports {@code size} members. */
    private static HazelcastInstance reportingMembers(Config config, int size) {
        java.util.Set<com.hazelcast.cluster.Member> members = new java.util.LinkedHashSet<>();
        for (int i = 0; i < size; i++) {
            members.add((com.hazelcast.cluster.Member) java.lang.reflect.Proxy.newProxyInstance(
                    KeylessMemberIsolationTest.class.getClassLoader(),
                    new Class<?>[] {com.hazelcast.cluster.Member.class}, (p, m, a) ->
                            m.getName().equals("hashCode") ? System.identityHashCode(p)
                                    : m.getName().equals("equals") ? p == a[0] : null));
        }
        com.hazelcast.cluster.Cluster cluster = (com.hazelcast.cluster.Cluster) java.lang.reflect.Proxy
                .newProxyInstance(KeylessMemberIsolationTest.class.getClassLoader(),
                        new Class<?>[] {com.hazelcast.cluster.Cluster.class}, (p, m, a) -> {
                            if (m.getName().equals("getMembers")) {
                                return members;
                            }
                            throw new AssertionError("unexpected " + m.getName());
                        });
        return (HazelcastInstance) java.lang.reflect.Proxy.newProxyInstance(
                KeylessMemberIsolationTest.class.getClassLoader(), new Class<?>[] {HazelcastInstance.class},
                (p, m, a) -> {
                    if (m.getName().equals("getConfig")) {
                        return config;
                    }
                    if (m.getName().equals("getCluster")) {
                        return cluster;
                    }
                    throw new AssertionError("nothing else may be read before the refusal: " + m.getName());
                });
    }

    @Test
    void usingAnAlreadyClusteredInstanceWithoutKeysIsRefused() {
        Config config = keylessConfig(TestMembers.uniqueName("clustered"), 5701);
        Throwable refused = catchThrowable(() -> PrivacyCluster.using(reportingMembers(config, 2), false));
        assertThat(refused).isInstanceOf(PrivacyClusterRefusal.class);
        assertThat(((PrivacyClusterRefusal) refused).code()).isEqualTo(Code.MISSING_CLUSTER_KEY);
    }

    @Test
    void usingASoloInstanceWithoutKeysStillWorks() {
        HazelcastInstance solo = Hazelcast.newHazelcastInstance(PrivacyCluster.configure(
                keylessConfig(TestMembers.uniqueName("solo"), FreePorts.consecutive(1))));
        try (PrivacyCluster wrapped = PrivacyCluster.using(solo, false)) {
            assertThat(wrapped.keys()).isEmpty();
        }
    }

    @Test
    void aDiscoveryServiceProviderWithNoStrategyIsAJoin() {
        Config config = new Config().setClusterName(TestMembers.uniqueName("keyless"));
        config.getNetworkConfig().getJoin().getDiscoveryConfig().setDiscoveryServiceProvider(
                (com.hazelcast.spi.discovery.integration.DiscoveryServiceProvider) null);
        // A provider that is present, whatever it finds, is a way of finding members.
        config.getNetworkConfig().getJoin().getDiscoveryConfig().setDiscoveryServiceProvider(settings -> null);
        Throwable refused = catchThrowable(() -> PrivacyCluster.embedded(config, false));
        assertThat(refused).isInstanceOf(PrivacyClusterRefusal.class);
        assertThat(((PrivacyClusterRefusal) refused).code()).isEqualTo(Code.MISSING_CLUSTER_KEY);
        assertThat(Hazelcast.getAllHazelcastInstances()).isEmpty();
    }
}

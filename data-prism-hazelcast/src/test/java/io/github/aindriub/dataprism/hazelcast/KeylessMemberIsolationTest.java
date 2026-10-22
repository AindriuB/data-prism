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
    void isolationStopsAKeylessMemberFromBringingDownAKeyedMemberThatPointsAtIt() {
        String name = TestMembers.uniqueName("keyless");
        int first = FreePorts.consecutive(2);
        try (PrivacyCluster keyless = PrivacyCluster.embedded(keylessConfig(name, first), false)) {
            // Pins that isolation keeps the two apart without the keyed member being shut down. The joiner-type
            // check already stopped them clustering; without isolation it shut the keyed member down instead.
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

    @Test
    void keyIdsCarryNullableComponentsAndGiveOptionalsThroughAccessors() {
        PrivacyCluster.KeyIds ids = new PrivacyCluster.KeyIds("c", null, "p", "n");
        assertThat(ids.nextId()).isNull();
        assertThat(ids.next()).isEmpty();
        assertThat(ids.previous()).contains("p");
        try (PrivacyCluster solo = PrivacyCluster.embedded(ClusterMembership.none(TestMembers.uniqueName("ids"))
                .withPort(FreePorts.consecutive(1)), false, TestMembers.KEYS)) {
            PrivacyCluster.KeyIds held = solo.keyIds().orElseThrow();
            assertThat(held.current()).isEqualTo("test-cluster-1");
            assertThat(held.next()).isEmpty();
            assertThat(held.previous()).isEmpty();
            assertThat(held.name()).isEqualTo("test-name-1");
        }
    }

    private static Config discoveryPropertyConfig(String name, int port) {
        Config config = keylessConfig(name, port);
        config.setProperty("hazelcast.discovery.enabled", "true");
        return config;
    }

    @Test
    void theDiscoveryPropertyAloneIsAJoin() {
        Config config = discoveryPropertyConfig(TestMembers.uniqueName("disc"), FreePorts.consecutive(1));
        Throwable refused = catchThrowable(() -> PrivacyCluster.embedded(config, false));
        assertThat(refused).isInstanceOf(PrivacyClusterRefusal.class);
        assertThat(((PrivacyClusterRefusal) refused).code()).isEqualTo(Code.MISSING_CLUSTER_KEY);
        assertThat(Hazelcast.getAllHazelcastInstances()).isEmpty();
        HazelcastInstance running = Hazelcast.newHazelcastInstance(PrivacyCluster.configure(
                discoveryPropertyConfig(TestMembers.uniqueName("disc"), FreePorts.consecutive(1))));
        Throwable usingRefused = catchThrowable(() -> PrivacyCluster.using(running, false));
        assertThat(usingRefused).isInstanceOf(PrivacyClusterRefusal.class);
        assertThat(((PrivacyClusterRefusal) usingRefused).code()).isEqualTo(Code.MISSING_CLUSTER_KEY);
        assertThat(running.getLifecycleService().isRunning()).isTrue();
    }

    @Test
    void aConfigCarryingAMarkerLikePropertyStillGetsAFreshSuffixEachTime() {
        Config config = keylessConfig(TestMembers.uniqueName("again"), FreePorts.consecutive(1));
        config.setProperty("dataprism.cluster.isolated.name", config.getClusterName());
        config.setProperty("dataprism.cluster.isolated", "true");
        String original = config.getClusterName();
        ClusterMembership.isolate(config);
        String first = config.getClusterName();
        assertThat(first).startsWith(original + "-solo-").isNotEqualTo(original);
        ClusterMembership.isolate(config);
        assertThat(config.getClusterName()).isNotEqualTo(first).startsWith(first + "-solo-");
    }
}

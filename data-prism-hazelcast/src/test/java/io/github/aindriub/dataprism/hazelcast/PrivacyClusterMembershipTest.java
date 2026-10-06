package io.github.aindriub.dataprism.hazelcast;

import com.hazelcast.config.Config;
import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;
import io.github.aindriub.dataprism.hazelcast.PrivacyClusterRefusal.Code;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PrivacyClusterMembershipTest {

    private static void refuses(Code code, Supplier<?> action) {
        assertThatThrownBy(action::get)
                .isInstanceOf(PrivacyClusterRefusal.class)
                .isInstanceOf(IllegalStateException.class)
                .satisfies(e -> assertThat(((PrivacyClusterRefusal) e).code()).isEqualTo(code));
    }

    @Test
    void everyJoinDisablesAutoDetectionMulticastAndPhoneHome() {
        for (ClusterMembership m : List.of(
                ClusterMembership.tcpIp("c1", List.of("10.1.2.3")).withPort(6001),
                ClusterMembership.kubernetes("c1", "ns", "svc", null).withPort(6001),
                ClusterMembership.none("c1").withPort(6001))) {
            Config c = m.toConfig();
            assertThat(c.getNetworkConfig().getJoin().getAutoDetectionConfig().isEnabled()).isFalse();
            assertThat(c.getNetworkConfig().getJoin().getMulticastConfig().isEnabled()).isFalse();
            assertThat(c.getProperty("hazelcast.phone.home.enabled")).isEqualTo("false");
            assertThat(c.getNetworkConfig().isPortAutoIncrement()).isFalse();
            assertThat(c.getClusterName()).isEqualTo("c1");
            assertThat(c.getNetworkConfig().getPort()).isEqualTo(6001);
        }
    }

    @Test
    void tcpIpEnablesOnlyTcpIpWithTheGivenMembers() {
        Config c = ClusterMembership.tcpIp("c1", List.of("10.1.2.3", "10.1.2.4:5702")).toConfig();
        var join = c.getNetworkConfig().getJoin();
        assertThat(join.getTcpIpConfig().isEnabled()).isTrue();
        assertThat(join.getTcpIpConfig().getMembers()).containsExactly("10.1.2.3", "10.1.2.4:5702");
        assertThat(join.getKubernetesConfig().isEnabled()).isFalse();
    }

    @Test
    void kubernetesEnablesOnlyKubernetesWithNamespaceAndOneServiceProperty() {
        var byName = ClusterMembership.kubernetes("c1", "ns", "svc", null).toConfig()
                .getNetworkConfig().getJoin();
        assertThat(byName.getKubernetesConfig().isEnabled()).isTrue();
        assertThat(byName.getTcpIpConfig().isEnabled()).isFalse();
        assertThat(byName.getKubernetesConfig().getProperties())
                .containsOnlyKeys("namespace", "service-name")
                .containsEntry("namespace", "ns").containsEntry("service-name", "svc");
        var byDns = ClusterMembership.kubernetes("c1", "ns", null, "svc.ns.svc.cluster.local").toConfig()
                .getNetworkConfig().getJoin();
        assertThat(byDns.getKubernetesConfig().getProperties()).containsOnlyKeys("namespace", "service-dns");
    }

    @Test
    void noneIsASingleLoopbackOnlyMember() {
        Config c = ClusterMembership.none("c1").toConfig();
        var net = c.getNetworkConfig();
        assertThat(net.getJoin().getTcpIpConfig().isEnabled()).isTrue();
        assertThat(net.getJoin().getTcpIpConfig().getMembers()).isEmpty();
        assertThat(net.getJoin().getKubernetesConfig().isEnabled()).isFalse();
        assertThat(net.getInterfaces().isEnabled()).isTrue();
        assertThat(net.getInterfaces().getInterfaces()).containsExactly("127.0.0.1");
        assertThat(c.getProperty("hazelcast.socket.bind.any")).isEqualTo("false");
    }

    @Test
    void interfaceIsAppliedWhenGiven() {
        Config c = ClusterMembership.tcpIp("c1", List.of("10.1.2.3")).withInterface("10.0.*.*").toConfig();
        assertThat(c.getNetworkConfig().getInterfaces().getInterfaces()).containsExactly("10.0.*.*");
        assertThat(c.getProperty("hazelcast.socket.bind.any")).isEqualTo("false");
        Config k = ClusterMembership.kubernetes("c1", "ns", "svc", null).withInterface("10.0.*.*").toConfig();
        assertThat(k.getProperty("hazelcast.socket.bind.any")).isEqualTo("false");
    }

    @Test
    void withoutAnInterfaceTcpIpAndKubernetesBindAny() {
        // Documented choice: no interface means bind-any, which is Hazelcast's default.
        for (ClusterMembership m : List.of(ClusterMembership.tcpIp("c1", List.of("10.1.2.3")),
                ClusterMembership.kubernetes("c1", "ns", "svc", null))) {
            Config c = m.toConfig();
            assertThat(c.getProperty("hazelcast.socket.bind.any")).isNotEqualTo("false");
            assertThat(c.getNetworkConfig().getInterfaces().isEnabled()).isFalse();
        }
    }

    @Test
    void noneRefusesAnInterfaceRatherThanIgnoringIt() {
        refuses(Code.INVALID_CLUSTER_INTERFACE, () -> ClusterMembership.none("c").withInterface("10.0.*.*"));
        refuses(Code.INVALID_CLUSTER_INTERFACE, () -> ClusterMembership.none("c").withInterface("127.0.0.1"));
    }

    @Test
    void embeddedConfigRefusesAnEnabledAdvancedNetwork() {
        Config c = new Config().setClusterName("c-adv");
        c.getAdvancedNetworkConfig().setEnabled(true);
        refuses(Code.UNSAFE_HAZELCAST_DISCOVERY, () -> PrivacyCluster.embedded(c, false));
    }

    @Test
    void usingRefusesAnEnabledAdvancedNetworkAndLeavesItRunning() {
        Config c = quiet("using-adv-" + System.nanoTime());
        c.getAdvancedNetworkConfig().setEnabled(true);
        c.getAdvancedNetworkConfig().setMemberEndpointConfig(new com.hazelcast.config.ServerSocketEndpointConfig()
                .setPort(FreePorts.consecutive(1)).setPortAutoIncrement(false));
        c.getAdvancedNetworkConfig().setJoin(c.getNetworkConfig().getJoin());
        HazelcastInstance instance = Hazelcast.newHazelcastInstance(c);
        try {
            refuses(Code.UNSAFE_HAZELCAST_DISCOVERY, () -> PrivacyCluster.using(instance, false));
            assertThat(instance.getLifecycleService().isRunning()).isTrue();
        } finally {
            instance.shutdown();
        }
    }

    @Test
    void constructionRefusesEachUnsafeDescription() {
        List<String> ok = List.of("10.1.2.3");
        refuses(Code.MISSING_CLUSTER_NAME, () -> ClusterMembership.tcpIp(" ", ok));
        refuses(Code.MISSING_CLUSTER_NAME, () -> ClusterMembership.tcpIp(null, ok));
        refuses(Code.RESERVED_CLUSTER_NAME, () -> ClusterMembership.tcpIp(" DeV ", ok));
        refuses(Code.INVALID_CLUSTER_MEMBERS, () -> ClusterMembership.tcpIp("c", List.of()));
        refuses(Code.INVALID_CLUSTER_MEMBERS, () -> ClusterMembership.tcpIp("c", List.of(" ")));
        refuses(Code.INVALID_CLUSTER_MEMBERS, () -> ClusterMembership.tcpIp("c", List.of("10.1.2.3:0")));
        refuses(Code.INVALID_CLUSTER_MEMBERS, () -> ClusterMembership.tcpIp("c", List.of("10.1.2.3:65536")));
        refuses(Code.INVALID_CLUSTER_MEMBERS, () -> ClusterMembership.tcpIp("c", List.of("10.1.2.3:x")));
        refuses(Code.INVALID_KUBERNETES_JOIN, () -> ClusterMembership.kubernetes("c", " ", "svc", null));
        refuses(Code.INVALID_KUBERNETES_JOIN, () -> ClusterMembership.kubernetes("c", "ns", "svc", "dns"));
        refuses(Code.INVALID_KUBERNETES_JOIN, () -> ClusterMembership.kubernetes("c", "ns", null, null));
        refuses(Code.INVALID_CLUSTER_PORT, () -> ClusterMembership.none("c").withPort(0));
        refuses(Code.INVALID_CLUSTER_PORT, () -> ClusterMembership.none("c").withPort(65536));
        refuses(Code.INVALID_CLUSTER_INTERFACE, () -> ClusterMembership.tcpIp("c", ok).withInterface("example.com"));
        refuses(Code.INVALID_CLUSTER_INTERFACE, () -> ClusterMembership.tcpIp("c", ok).withInterface("10.0.0.256"));
        refuses(Code.INVALID_CLUSTER_INTERFACE, () -> ClusterMembership.tcpIp("c", ok).withInterface("10.0.0"));
        assertThat(ClusterMembership.tcpIp("c", ok).withInterface("10.0.*.*").interfaceAddress())
                .isEqualTo(Optional.of("10.0.*.*"));
    }

    @Test
    void refusalMessagesNeverEchoTheValue() {
        assertThatThrownBy(() -> ClusterMembership.tcpIp("c", List.of("secret-host.internal:99999")))
                .hasMessageNotContaining("secret-host");
        assertThatThrownBy(() -> ClusterMembership.kubernetes("c", " ", "svc", null)
                .toString()).isInstanceOf(PrivacyClusterRefusal.class);
        assertThatThrownBy(() -> ClusterMembership.kubernetes("c", "secret-ns", "svc", "dns"))
                .hasMessageNotContaining("secret-ns");
    }

    @Test
    void embeddedConfigRefusesTheHazelcastDefaultClusterName() {
        refuses(Code.RESERVED_CLUSTER_NAME, () -> PrivacyCluster.embedded(new Config(), false));
        Config blank = new Config();
        blank.setClusterName(" ");
        refuses(Code.MISSING_CLUSTER_NAME, () -> PrivacyCluster.embedded(blank, false));
    }

    @Test
    void embeddedConfigRefusesTlsAndSecurityBeforeStarting() {
        Config tls = new Config().setClusterName("c-tls");
        tls.getNetworkConfig().setSSLConfig(new com.hazelcast.config.SSLConfig().setEnabled(true));
        refuses(Code.HAZELCAST_TLS_UNSUPPORTED, () -> PrivacyCluster.embedded(tls, false));
        Config sec = new Config().setClusterName("c-sec");
        sec.getSecurityConfig().setEnabled(true);
        refuses(Code.HAZELCAST_TLS_UNSUPPORTED, () -> PrivacyCluster.embedded(sec, false));
    }

    @Test
    void embeddedConfigForcesDiscoveryAndPhoneHomeOff() {
        Config c = new Config().setClusterName("c-force");
        c.getNetworkConfig().getJoin().getMulticastConfig().setEnabled(true);
        c.getNetworkConfig().getJoin().getAutoDetectionConfig().setEnabled(true);
        PrivacyCluster.configure(c);
        assertThat(c.getNetworkConfig().getJoin().getMulticastConfig().isEnabled()).isFalse();
        assertThat(c.getNetworkConfig().getJoin().getAutoDetectionConfig().isEnabled()).isFalse();
        assertThat(c.getProperty("hazelcast.phone.home.enabled")).isEqualTo("false");
    }

    @Test
    void usingRefusesUnsafeInstancesAndNeverShutsThemDown() {
        Config multicast = quiet("using-multicast-" + System.nanoTime());
        multicast.getNetworkConfig().getJoin().getMulticastConfig().setEnabled(true);
        Config detect = quiet("using-detect-" + System.nanoTime());
        detect.getNetworkConfig().getJoin().getAutoDetectionConfig().setEnabled(true);
        Config dev = quiet("dev");
        // A default-named instance, kept loopback-only so it cannot reach another cluster.
        dev.getNetworkConfig().getInterfaces().setEnabled(true).addInterface("127.0.0.1");
        Config blank = quiet(" ");
        for (var c : List.of(
                java.util.Map.entry(Code.UNSAFE_HAZELCAST_DISCOVERY, multicast),
                java.util.Map.entry(Code.UNSAFE_HAZELCAST_DISCOVERY, detect),
                java.util.Map.entry(Code.RESERVED_CLUSTER_NAME, dev),
                java.util.Map.entry(Code.MISSING_CLUSTER_NAME, blank))) {
            HazelcastInstance instance = Hazelcast.newHazelcastInstance(c.getValue());
            try {
                refuses(c.getKey(), () -> PrivacyCluster.using(instance, false));
                assertThat(instance.getLifecycleService().isRunning()).isTrue();
            } finally {
                instance.shutdown();
            }
        }
    }

    private static Config quiet(String name) {
        Config c = new Config().setClusterName(name);
        c.getJetConfig().setEnabled(false);
        c.setProperty("hazelcast.phone.home.enabled", "false");
        c.getNetworkConfig().setPort(FreePorts.consecutive(1)).setPortAutoIncrement(false);
        c.getNetworkConfig().getJoin().getMulticastConfig().setEnabled(false);
        c.getNetworkConfig().getJoin().getAutoDetectionConfig().setEnabled(false);
        return c;
    }

    @Test
    void ossHasNoMemberTls() {
        // Pins the Enterprise-only finding the 0.4.1 security decision rests on: OSS 5.7.0
        // ships only the SSLContextFactory interfaces, so a TLS-enabled member cannot start.
        // If this class appears after an upgrade, revisit the HAZELCAST_TLS_UNSUPPORTED refusal.
        assertThatThrownBy(() -> Class.forName("com.hazelcast.nio.ssl.BasicSSLContextFactory"))
                .isInstanceOf(ClassNotFoundException.class);
    }

    @Test
    void twoMembersWithTheSameNameClusterAndADifferentNameDoesNot() throws InterruptedException {
        String name = "dataprism-membership-" + System.nanoTime();
        int base = FreePorts.consecutive(3);
        List<String> members = List.of("127.0.0.1:" + base, "127.0.0.1:" + (base + 1));
        PrivacyCluster a = null;
        PrivacyCluster b = null;
        PrivacyCluster other = null;
        try {
            a = PrivacyCluster.embedded(ClusterMembership.tcpIp(name, members).withPort(base)
                    .withInterface("127.0.0.1"), false);
            b = PrivacyCluster.embedded(ClusterMembership.tcpIp(name, members).withPort(base + 1)
                    .withInterface("127.0.0.1"), false);
            other = PrivacyCluster.embedded(ClusterMembership.tcpIp(name + "-other", members)
                    .withPort(base + 2).withInterface("127.0.0.1"), false);
            var first = a;
            long deadline = System.nanoTime() + java.time.Duration.ofSeconds(30).toNanos();
            while (first.instance().getCluster().getMembers().size() < 2 && System.nanoTime() < deadline) {
                Thread.sleep(200);
            }
            assertThat(first.instance().getCluster().getMembers()).hasSize(2);
            assertThat(other.instance().getCluster().getMembers()).hasSize(1);
        } finally {
            for (PrivacyCluster c : new PrivacyCluster[]{a, b, other}) {
                if (c != null) {
                    c.close();
                }
            }
        }
    }
}

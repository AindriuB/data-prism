package io.github.aindriub.dataprism.hazelcast;

import com.hazelcast.config.Config;
import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.config.EvictionPolicy;
import com.hazelcast.config.IndexType;
import com.hazelcast.config.MapConfig;
import com.hazelcast.config.MaxSizePolicy;
import io.github.aindriub.dataprism.hazelcast.PrivacyClusterRefusal.Code;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.TreeSet;
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
                ClusterMembership.tcpIp("c1", List.of("10.1.2.3")).withPort(6001).withInterface("10.0.*.*"),
                ClusterMembership.kubernetes("c1", "ns", "svc", null).withPort(6001).withInterface("10.0.*.*"),
                ClusterMembership.none("c1").withPort(6001))) {
            Config c = m.toConfig();
            assertThat(c.getNetworkConfig().getJoin().getAutoDetectionConfig().isEnabled()).isFalse();
            assertThat(c.getNetworkConfig().getJoin().getMulticastConfig().isEnabled()).isFalse();
            assertThat(c.getProperty("hazelcast.phone.home.enabled")).isEqualTo("false");
            assertThat(c.getNetworkConfig().isPortAutoIncrement()).isFalse();
            if (c.getClusterName().startsWith("c1-solo-")) {
                assertThat(c.getClusterName()).startsWith("c1-solo-");
            } else {
                assertThat(c.getClusterName()).isEqualTo("c1");
            }
            assertThat(c.getNetworkConfig().getPort()).isEqualTo(6001);
        }
    }

    @Test
    void tcpIpEnablesOnlyTcpIpWithTheGivenMembers() {
        Config c = ClusterMembership.tcpIp("c1", List.of("10.1.2.3", "10.1.2.4:5702")).withInterface("10.0.*.*")
                .toConfig();
        var join = c.getNetworkConfig().getJoin();
        assertThat(join.getTcpIpConfig().isEnabled()).isTrue();
        assertThat(join.getTcpIpConfig().getMembers()).containsExactly("10.1.2.3", "10.1.2.4:5702");
        assertThat(join.getKubernetesConfig().isEnabled()).isFalse();
    }

    @Test
    void kubernetesEnablesOnlyKubernetesWithNamespaceAndOneServiceProperty() {
        var byName = ClusterMembership.kubernetes("c1", "ns", "svc", null).withInterface("10.0.*.*").toConfig()
                .getNetworkConfig().getJoin();
        assertThat(byName.getKubernetesConfig().isEnabled()).isTrue();
        assertThat(byName.getTcpIpConfig().isEnabled()).isFalse();
        assertThat(byName.getKubernetesConfig().getProperties())
                .containsOnlyKeys("namespace", "service-name")
                .containsEntry("namespace", "ns").containsEntry("service-name", "svc");
        var byDns = ClusterMembership.kubernetes("c1", "ns", null, "svc.ns.svc.cluster.local")
                .withInterface("10.0.*.*").toConfig()
                .getNetworkConfig().getJoin();
        assertThat(byDns.getKubernetesConfig().getProperties()).containsOnlyKeys("namespace", "service-dns");
    }

    @Test
    void noneIsASingleLoopbackOnlyMember() {
        Config c = ClusterMembership.none("c1").toConfig();
        var net = c.getNetworkConfig();
        assertThat(net.getJoin().getTcpIpConfig().isEnabled()).isFalse();
        assertThat(net.getJoin().getMulticastConfig().isEnabled()).isFalse();
        assertThat(net.getJoin().getAutoDetectionConfig().isEnabled()).isFalse();
        assertThat(net.getJoin().getKubernetesConfig().isEnabled()).isFalse();
        assertThat(net.getInterfaces().isEnabled()).isTrue();
        assertThat(net.getInterfaces().getInterfaces()).containsExactly("127.0.0.1");
        assertThat(c.getProperty("hazelcast.socket.bind.any")).isEqualTo("false");
    }

    @Test
    void noneMemberRefusesAnIncomingJoinFromTheSameConfiguredClusterName() throws Exception {
        int nonePort = FreePorts.consecutive(1);
        int otherPort = FreePorts.consecutive(1);
        HazelcastInstance solo = Hazelcast.newHazelcastInstance(
                ClusterMembership.none("solo-join").withPort(nonePort).toConfig());
        HazelcastInstance other = null;
        try {
            other = Hazelcast.newHazelcastInstance(
                    ClusterMembership.tcpIp("solo-join", List.of("127.0.0.1:" + nonePort))
                            .withPort(otherPort).withInterface("127.0.0.1").toConfig());
            long deadline = System.nanoTime() + java.time.Duration.ofSeconds(15).toNanos();
            while (System.nanoTime() < deadline) {
                assertThat(solo.getCluster().getMembers()).hasSize(1);
                assertThat(other.getCluster().getMembers()).hasSize(1);
                Thread.sleep(500);
            }
        } finally {
            if (other != null) {
                other.shutdown();
            }
            solo.shutdown();
        }
    }

    @Test
    void noneUsesADistinctInternalClusterNamePerMember() {
        ClusterMembership m = ClusterMembership.none("c1");
        String a = m.toConfig().getClusterName();
        String b = m.toConfig().getClusterName();
        assertThat(a).isNotEqualTo("c1").startsWith("c1-solo-");
        assertThat(b).isNotEqualTo(a);
        assertThat(m.clusterName()).isEqualTo("c1");
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
    void tcpIpAndKubernetesWithoutAnInterfaceAreRefusedWhenTheConfigIsBuilt() {
        refuses(Code.MISSING_MEMBER_INTERFACE, () -> ClusterMembership.tcpIp("c1", List.of("10.1.2.3")).toConfig());
        refuses(Code.MISSING_MEMBER_INTERFACE,
                () -> ClusterMembership.kubernetes("c1", "ns", "svc", null).toConfig());
        refuses(Code.MISSING_MEMBER_INTERFACE, () -> PrivacyCluster.embedded(
                ClusterMembership.tcpIp("c1", List.of("10.1.2.3")), false, TestMembers.KEYS));
    }

    @Test
    void anInterfaceThatMatchesEveryAddressIsRefused() {
        List<String> ok = List.of("10.1.2.3");
        refuses(Code.UNBOUNDED_MEMBER_INTERFACE, () -> ClusterMembership.tcpIp("c", ok).withInterface("*.*.*.*"));
        refuses(Code.UNBOUNDED_MEMBER_INTERFACE,
                () -> ClusterMembership.tcpIp("c", ok).withInterface("0-255.0-255.0-255.0-255"));
        refuses(Code.UNBOUNDED_MEMBER_INTERFACE,
                () -> ClusterMembership.kubernetes("c", "ns", "svc", null).withInterface("*.*.*.*"));
        refuses(Code.UNBOUNDED_MEMBER_INTERFACE, () -> ClusterMembership.tcpIp("c", ok).withInterface("*.*.*.00-255"));
        refuses(Code.UNBOUNDED_MEMBER_INTERFACE, () -> ClusterMembership.tcpIp("c", ok).withInterface("000-255.*.*.*"));
        refuses(Code.UNBOUNDED_MEMBER_INTERFACE,
                () -> ClusterMembership.tcpIp("c", ok).withInterface("*.0-255.00-255.*"));
        // Bounded patterns, wide as they are, stay accepted; PUBLIC_MEMBER_INTERFACE is not added (OD-10 (ii)).
        assertThat(ClusterMembership.tcpIp("c", ok).withInterface("10.*.*.*").interfaceAddress())
                .isEqualTo(Optional.of("10.*.*.*"));
        assertThat(java.util.Arrays.stream(Code.values()).map(Enum::name)).doesNotContain("PUBLIC_MEMBER_INTERFACE");
    }

    @Test
    void noneNeedsNoInterface() {
        assertThat(ClusterMembership.none("c1").toConfig().getNetworkConfig().getInterfaces().getInterfaces())
                .containsExactly("127.0.0.1");
    }

    @Test
    void interfaceRefusalMessagesNameTheSettingAndNeverAValue() {
        assertThatThrownBy(() -> ClusterMembership.tcpIp("c", List.of("secret-host.internal")).toConfig())
                .hasMessageContaining("member.interface").hasMessageNotContaining("secret-host");
        assertThatThrownBy(() -> ClusterMembership.tcpIp("c", List.of("10.1.2.3")).withInterface("*.*.*.*"))
                .hasMessageContaining("member.interface").hasMessageNotContaining("*.*.*.*");
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
            refuses(Code.UNSAFE_HAZELCAST_DISCOVERY, () -> PrivacyCluster.using(instance, false, TestMembers.KEYS));
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
                refuses(c.getKey(), () -> PrivacyCluster.using(instance, false, TestMembers.KEYS));
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
                    .withInterface("127.0.0.1"), false, TestMembers.KEYS);
            b = PrivacyCluster.embedded(ClusterMembership.tcpIp(name, members).withPort(base + 1)
                    .withInterface("127.0.0.1"), false, TestMembers.KEYS);
            other = PrivacyCluster.embedded(ClusterMembership.tcpIp(name + "-other", members)
                    .withPort(base + 2).withInterface("127.0.0.1"), false, TestMembers.KEYS);
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

    // ---- map names (task 218 register, section 8.1) ----

    /** Every Data Prism map-name constant, by constant name. */
    private static final Map<String, String> MAP_NAMES = new LinkedHashMap<>();

    static {
        MAP_NAMES.put("IDENTITY_MAP", PrivacyCluster.IDENTITY_MAP);
        MAP_NAMES.put("REIDENTIFICATION_MAP", PrivacyCluster.REIDENTIFICATION_MAP);
        MAP_NAMES.put("BUDGET_MAP", PrivacyCluster.BUDGET_MAP);
        MAP_NAMES.put("OVERSIGHT_MAP", PrivacyCluster.OVERSIGHT_MAP);
        MAP_NAMES.put("APPROVAL_MAP", PrivacyCluster.APPROVAL_MAP);
        MAP_NAMES.put("APPROVAL_ID_MAP", PrivacyCluster.APPROVAL_ID_MAP);
        MAP_NAMES.put("APPROVAL_PENDING_MAP", PrivacyCluster.APPROVAL_PENDING_MAP);
        MAP_NAMES.put("CALLER_RATE_MAP", PrivacyCluster.CALLER_RATE_MAP);
        MAP_NAMES.put("APPROVAL_CONSUMED_MAP", PrivacyCluster.APPROVAL_CONSUMED_MAP);
        MAP_NAMES.put("MEMBER_CARD_MAP", PrivacyCluster.MEMBER_CARD_MAP);
    }

    @Test
    void everyMapConstantHasTheS1NameOfTheRegister() {
        Set<String> registered = new TreeSet<>();
        Path register = Path.of("..", "docs", "plan", "design", "contract-register.md");
        try {
            for (String line : Files.readAllLines(register)) {
                String[] cells = line.split("\\|");
                // | `name` | map | proposed | 0.8 | ...
                if (cells.length > 4 && cells[2].strip().equals("map") && cells[3].strip().equals("proposed")
                        && cells[4].strip().equals("0.8")) {
                    registered.add(cells[1].strip().replace("`", ""));
                }
            }
        } catch (IOException unreadable) {
            throw new AssertionError("the contract register must be readable from the module directory", unreadable);
        }
        assertThat(registered).as("the 0.8 maps registered in section 8.1").hasSize(10);
        assertThat(new TreeSet<>(MAP_NAMES.values())).isEqualTo(registered);
        assertThat(MAP_NAMES.values()).allSatisfy(name -> assertThat(name).endsWith(".s1"));
    }

    @Test
    void thePreEightMapNamesAreTheEightOfTheCutoverRule() {
        assertThat(PrivacyCluster.PRE_0_8_MAPS).containsExactlyInAnyOrder("dataprism.identity",
                "dataprism.budget", "dataprism.callerrate", "dataprism.oversight", "dataprism.approval",
                "dataprism.approval.id", "dataprism.approval.pending", "dataprism.reidentification");
    }

    // ---- the settings of each map, equal to 0.7 map for map ----

    private record Expected(EvictionPolicy eviction, String mergePolicy, boolean quorum) {
    }

    /** What a map that sets no merge policy has: Hazelcast's default, which is the union of keys. */
    private static final String PUT_IF_ABSENT = new com.hazelcast.config.MergePolicyConfig().getPolicy();
    /** What the claim map and the consumed map set, spelled as 0.7 spelled it. */
    private static final String CLAIM_POLICY = "PutIfAbsentMergePolicy";

    private static final Map<String, Expected> SETTINGS = new LinkedHashMap<>();

    static {
        SETTINGS.put(PrivacyCluster.IDENTITY_MAP, new Expected(EvictionPolicy.LRU, PUT_IF_ABSENT, false));
        SETTINGS.put(PrivacyCluster.REIDENTIFICATION_MAP, new Expected(EvictionPolicy.LRU, PUT_IF_ABSENT, false));
        SETTINGS.put(PrivacyCluster.BUDGET_MAP, new Expected(EvictionPolicy.LRU, PUT_IF_ABSENT, false));
        SETTINGS.put(PrivacyCluster.OVERSIGHT_MAP, new Expected(EvictionPolicy.NONE, PUT_IF_ABSENT, false));
        SETTINGS.put(PrivacyCluster.CALLER_RATE_MAP, new Expected(EvictionPolicy.NONE, PUT_IF_ABSENT, false));
        SETTINGS.put(PrivacyCluster.APPROVAL_MAP,
                new Expected(EvictionPolicy.LRU, ApprovalMergePolicy.class.getName(), true));
        SETTINGS.put(PrivacyCluster.APPROVAL_ID_MAP, new Expected(EvictionPolicy.NONE, CLAIM_POLICY, true));
        SETTINGS.put(PrivacyCluster.APPROVAL_PENDING_MAP,
                new Expected(EvictionPolicy.NONE, PendingMergePolicy.class.getName(), true));
        // New in 0.8: no size-based eviction, a union of keys on heal, and the approval map's quorum.
        SETTINGS.put(PrivacyCluster.APPROVAL_CONSUMED_MAP, new Expected(EvictionPolicy.NONE, CLAIM_POLICY, true));
        SETTINGS.put(PrivacyCluster.MEMBER_CARD_MAP, new Expected(EvictionPolicy.NONE, PUT_IF_ABSENT, false));
    }

    @Test
    void everyMapKeepsItsZeroSevenSettingsUnderTheNewName() {
        Config config = PrivacyCluster.configure(new Config().setClusterName("settings"), OptionalInt.of(2));
        assertThat(config.getMapConfigs().keySet()).containsExactlyInAnyOrderElementsOf(SETTINGS.keySet());
        SETTINGS.forEach((name, expected) -> {
            MapConfig map = config.getMapConfigs().get(name);
            assertThat(map.getEvictionConfig().getEvictionPolicy()).as(name + " eviction")
                    .isEqualTo(expected.eviction());
            assertThat(map.getEvictionConfig().getMaxSizePolicy()).as(name).isEqualTo(MaxSizePolicy.PER_NODE);
            assertThat(map.getEvictionConfig().getSize()).as(name).isEqualTo(100_000);
            assertThat(map.getMergePolicyConfig().getPolicy()).as(name + " merge").isEqualTo(expected.mergePolicy());
            assertThat(map.getMergePolicyConfig().getBatchSize()).as(name).isEqualTo(100);
            assertThat(map.getTimeToLiveSeconds()).as(name + " map-level ttl").isZero();
            assertThat(map.getMapStoreConfig().isEnabled()).as(name + " map store").isFalse();
            assertThat(map.getBackupCount()).as(name).isEqualTo(1);
            assertThat(map.isStatisticsEnabled()).as(name).isTrue();
            assertThat(map.getIndexConfigs()).as(name).hasSize(1);
            assertThat(map.getIndexConfigs().get(0).getType()).isEqualTo(IndexType.HASH);
            assertThat(map.getIndexConfigs().get(0).getAttributes()).containsExactly("__key");
            assertThat(map.getSplitBrainProtectionName()).as(name + " quorum")
                    .isEqualTo(expected.quorum() ? PrivacyCluster.APPROVAL_PROTECTION : null);
        });
        var protection = config.getSplitBrainProtectionConfigs().get(PrivacyCluster.APPROVAL_PROTECTION);
        assertThat(protection.getMinimumClusterSize()).isEqualTo(2);
        assertThat(protection.getProtectOn()).isEqualTo(com.hazelcast.splitbrainprotection.SplitBrainProtectionOn.WRITE);
    }

    @Test
    void withoutAQuorumNoMapNamesAProtection() {
        for (OptionalInt none : List.of(OptionalInt.empty(), OptionalInt.of(1))) {
            Config config = PrivacyCluster.configure(new Config().setClusterName("settings"), none);
            assertThat(config.getSplitBrainProtectionConfigs()).isEmpty();
            config.getMapConfigs().values().forEach(m -> assertThat(m.getSplitBrainProtectionName()).isNull());
        }
    }

    @Test
    void theAreaClassesHoldTheMapNamesTheyConfigure() {
        assertThat(IdentityMaps.NAMES).containsExactlyInAnyOrder(PrivacyCluster.IDENTITY_MAP,
                PrivacyCluster.REIDENTIFICATION_MAP);
        assertThat(CounterMaps.NAMES).containsExactlyInAnyOrder(PrivacyCluster.BUDGET_MAP,
                PrivacyCluster.CALLER_RATE_MAP);
        assertThat(OversightMaps.NAMES).containsExactlyInAnyOrder(PrivacyCluster.OVERSIGHT_MAP,
                PrivacyCluster.MEMBER_CARD_MAP);
        assertThat(ApprovalMaps.NAMES).containsExactlyInAnyOrder(PrivacyCluster.APPROVAL_MAP,
                PrivacyCluster.APPROVAL_ID_MAP, PrivacyCluster.APPROVAL_PENDING_MAP,
                PrivacyCluster.APPROVAL_CONSUMED_MAP);
    }
}

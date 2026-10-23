package io.github.aindriub.dataprism.hazelcast;

import com.hazelcast.cluster.Cluster;
import com.hazelcast.cluster.Member;
import com.hazelcast.cluster.MembershipEvent;
import com.hazelcast.cluster.MembershipListener;
import com.hazelcast.config.Config;
import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;
import io.github.aindriub.dataprism.hazelcast.PrivacyClusterRefusal.Code;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;

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
        config.setProperty("hazelcast.socket.bind.any", "false");
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
            assertThat(keyed.instance().getConfig().getNetworkConfig().getInterfaces().getInterfaces())
                    .containsExactly("127.0.0.1");
        }
    }

    /** An instance with every joiner disabled that nonetheless reports {@code size} members. */
    private static HazelcastInstance reportingMembers(Config config, int size) {
        Set<Member> members = new LinkedHashSet<>();
        for (int i = 0; i < size; i++) {
            members.add((Member) Proxy.newProxyInstance(
                    KeylessMemberIsolationTest.class.getClassLoader(),
                    new Class<?>[] {Member.class}, (p, m, a) ->
                            m.getName().equals("hashCode") ? System.identityHashCode(p)
                                    : m.getName().equals("equals") ? p == a[0] : null));
        }
        Cluster cluster = (Cluster) Proxy
                .newProxyInstance(KeylessMemberIsolationTest.class.getClassLoader(),
                        new Class<?>[] {Cluster.class}, (p, m, a) -> {
                            if (m.getName().equals("getMembers")) {
                                return members;
                            }
                            throw new AssertionError("unexpected " + m.getName());
                        });
        return (HazelcastInstance) Proxy.newProxyInstance(
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

    // ---- a supplied keyless instance --------------------------------------------------------------

    private static final Object NOT_OVERRIDDEN = new Object();

    /** Forwards every call to {@code target} unless {@code override} answers it. */
    @SuppressWarnings("unchecked")
    private static <T> T forwarding(Class<T> type, T target,
                                    BiFunction<Method, Object[], Object> override) {
        return (T) Proxy.newProxyInstance(KeylessMemberIsolationTest.class.getClassLoader(),
                new Class<?>[] {type}, (proxy, method, args) -> {
                    Object answer = override.apply(method, args);
                    if (answer != NOT_OVERRIDDEN) {
                        return answer;
                    }
                    try {
                        return method.invoke(target, args);
                    } catch (InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                });
    }

    /** A running loopback instance whose cluster hands its membership listeners to {@code listeners}. */
    private static HazelcastInstance capturing(HazelcastInstance real,
                                               List<MembershipListener> listeners) {
        Cluster cluster = forwarding(Cluster.class, real.getCluster(),
                (m, a) -> {
                    if (m.getName().equals("addMembershipListener")) {
                        listeners.add((MembershipListener) a[0]);
                    }
                    return NOT_OVERRIDDEN;
                });
        return forwarding(HazelcastInstance.class, real,
                (m, a) -> m.getName().equals("getCluster") ? cluster : NOT_OVERRIDDEN);
    }

    private static void memberJoins(HazelcastInstance real,
                                    List<MembershipListener> listeners) {
        Member local = real.getCluster().getLocalMember();
        var event = new MembershipEvent(real.getCluster(), local,
                MembershipEvent.MEMBER_ADDED, Set.of(local));
        listeners.forEach(l -> l.memberAdded(event));
    }

    private static HazelcastInstance soloInstance() {
        return Hazelcast.newHazelcastInstance(PrivacyCluster.configure(
                keylessConfig(TestMembers.uniqueName("wrapped"), FreePorts.consecutive(1))));
    }

    @Test
    void aKeylessInstanceNotBoundToLoopbackIsRefusedAtWrap() throws Exception {
        HazelcastInstance real = soloInstance();
        InetSocketAddress remote = new InetSocketAddress(
                InetAddress.getByAddress(new byte[] {10, 1, 2, 3}), 5701);
        Member local = forwarding(Member.class,
                real.getCluster().getLocalMember(),
                (m, a) -> m.getName().equals("getSocketAddress") ? remote : NOT_OVERRIDDEN);
        Cluster cluster = forwarding(Cluster.class, real.getCluster(),
                (m, a) -> m.getName().equals("getLocalMember") ? local : NOT_OVERRIDDEN);
        HazelcastInstance exposed = forwarding(HazelcastInstance.class, real,
                (m, a) -> m.getName().equals("getCluster") ? cluster : NOT_OVERRIDDEN);
        Throwable refused = catchThrowable(() -> PrivacyCluster.using(exposed, false));
        assertThat(refused).isInstanceOf(PrivacyClusterRefusal.class);
        assertThat(((PrivacyClusterRefusal) refused).code()).isEqualTo(Code.MISSING_CLUSTER_KEY);
        assertThat(real.getLifecycleService().isRunning()).isTrue();
    }

    /**
     * A real late join cannot be produced here: Hazelcast's own joiner check turns away every member that is not
     * itself joinerless, and a joinerless one never looks. So the join is announced to the wrapper's listeners by
     * hand, through an instance that forwards everything else to a real one.
     */
    @Test
    void aLoopbackKeylessInstanceThatLaterGetsAMemberIsClosedAndRefusesAfterwards() {
        HazelcastInstance real = soloInstance();
        List<MembershipListener> listeners = new ArrayList<>();
        PrivacyCluster wrapped = PrivacyCluster.using(capturing(real, listeners), false);
        assertThat(wrapped.instance()).isNotNull();
        memberJoins(real, listeners);
        Throwable refused = catchThrowable(wrapped::instance);
        assertThat(refused).isInstanceOf(PrivacyClusterRefusal.class);
        assertThat(((PrivacyClusterRefusal) refused).code()).isEqualTo(Code.MISSING_CLUSTER_KEY);
        assertThat(wrapped.isRefused()).isTrue();
        assertThat(real.getLifecycleService().isRunning()).as("not ours to stop").isTrue();
    }

    @Test
    void aKeylessMemberThisClassStartedIsShutDownWhenItGetsAMember() throws Exception {
        HazelcastInstance real = soloInstance();
        List<MembershipListener> listeners = new ArrayList<>();
        PrivacyCluster owned = PrivacyCluster.wrap(capturing(real, listeners), false,
                Duration.ofMinutes(1), null, true);
        memberJoins(real, listeners);
        assertThat(owned.isRefused()).isTrue();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (real.getLifecycleService().isRunning() && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }
        assertThat(real.getLifecycleService().isRunning()).isFalse();
    }

    @Test
    void aKeyedWrapperIsNotClosedWhenItGetsAMember() {
        HazelcastInstance real = soloInstance();
        List<MembershipListener> listeners = new ArrayList<>();
        PrivacyCluster wrapped = PrivacyCluster.using(capturing(real, listeners), false, TestMembers.KEYS);
        memberJoins(real, listeners);
        assertThat(wrapped.isRefused()).isFalse();
        assertThat(wrapped.instance()).isNotNull();
    }

    // ---- what a loopback instance really listens on ------------------------------------------------

    private static void refusedAsMissingKeys(Throwable refused) {
        assertThat(refused).isInstanceOf(PrivacyClusterRefusal.class);
        assertThat(((PrivacyClusterRefusal) refused).code()).isEqualTo(Code.MISSING_CLUSTER_KEY);
    }

    /** The reviewer's probe: the member says 127.0.0.1, but with bind.any left at its default it listens everywhere. */
    @Test
    void aLoopbackInstanceThatBindsEveryInterfaceIsRefused() {
        Config config = keylessConfig(TestMembers.uniqueName("bindany"), FreePorts.consecutive(1));
        config.getProperties().remove("hazelcast.socket.bind.any");
        HazelcastInstance real = Hazelcast.newHazelcastInstance(PrivacyCluster.configure(config));
        assertThat(real.getCluster().getLocalMember().getSocketAddress().getAddress().isLoopbackAddress()).isTrue();
        refusedAsMissingKeys(catchThrowable(() -> PrivacyCluster.using(real, false)));
        assertThat(real.getLifecycleService().isRunning()).isTrue();
    }

    @Test
    void aServerSocketThatBindsEveryInterfaceIsRefusedWhateverTheGeneralSettingSays() {
        Config config = keylessConfig(TestMembers.uniqueName("serverbindany"), FreePorts.consecutive(1));
        config.setProperty("hazelcast.socket.server.bind.any", "true");
        HazelcastInstance real = Hazelcast.newHazelcastInstance(PrivacyCluster.configure(config));
        refusedAsMissingKeys(catchThrowable(() -> PrivacyCluster.using(real, false)));
    }

    @Test
    void aPublicAddressIsRefused() {
        Config config = keylessConfig(TestMembers.uniqueName("public"), FreePorts.consecutive(1));
        config.getNetworkConfig().setPublicAddress("127.0.0.1:" + config.getNetworkConfig().getPort());
        HazelcastInstance real = Hazelcast.newHazelcastInstance(PrivacyCluster.configure(config));
        refusedAsMissingKeys(catchThrowable(() -> PrivacyCluster.using(real, false)));
    }

    @Test
    void interfacesThatAreDisabledOrNotAllLoopbackAreRefused() {
        HazelcastInstance real = soloInstance();
        Config disabled = keylessConfig(TestMembers.uniqueName("disabled"), 5701);
        disabled.getNetworkConfig().getInterfaces().setEnabled(false);
        Config mixed = keylessConfig(TestMembers.uniqueName("mixed"), 5701);
        mixed.getNetworkConfig().getInterfaces().addInterface("10.0.0.*");
        for (Config config : List.of(disabled, mixed)) {
            HazelcastInstance reported = forwarding(HazelcastInstance.class, real,
                    (m, a) -> m.getName().equals("getConfig") ? config : NOT_OVERRIDDEN);
            refusedAsMissingKeys(catchThrowable(() -> PrivacyCluster.using(reported, false)));
        }
        assertThat(real.getLifecycleService().isRunning()).isTrue();
    }

    // ---- a join before the listener is registered ---------------------------------------------------

    @Test
    void aMemberPresentWhenTheListenerIsRegisteredRefusesTheWrapper() {
        HazelcastInstance real = soloInstance();
        Member local = real.getCluster().getLocalMember();
        Member other = forwarding(Member.class, local, (m, a) -> m.getName().equals("equals") ? Boolean.FALSE
                : m.getName().equals("hashCode") ? Integer.valueOf(1) : NOT_OVERRIDDEN);
        Cluster cluster = forwarding(Cluster.class, real.getCluster(), (m, a) -> {
            if (m.getName().equals("addMembershipListener")
                    && a[0] instanceof com.hazelcast.cluster.InitialMembershipListener initial) {
                initial.init(new com.hazelcast.cluster.InitialMembershipEvent(real.getCluster(),
                        new LinkedHashSet<>(List.of(local, other))));
                return java.util.UUID.randomUUID();
            }
            return NOT_OVERRIDDEN;
        });
        HazelcastInstance joined = forwarding(HazelcastInstance.class, real,
                (m, a) -> m.getName().equals("getCluster") ? cluster : NOT_OVERRIDDEN);
        refusedAsMissingKeys(catchThrowable(() -> PrivacyCluster.using(joined, false)));
        assertThat(real.getLifecycleService().isRunning()).isTrue();
    }

    // ---- isolation of a keyless Config -------------------------------------------------------------

    /** The host's first non-loopback IPv4 address, or null when it has none. */
    private static String lanAddress() throws Exception {
        for (var nic : java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces())) {
            for (var address : java.util.Collections.list(nic.getInetAddresses())) {
                if (address instanceof java.net.Inet4Address && !address.isLoopbackAddress() && nic.isUp()) {
                    return address.getHostAddress();
                }
            }
        }
        return null;
    }

    private static void assertListensOnLoopbackOnly(PrivacyCluster keyless, int port) throws Exception {
        Config effective = keyless.instance().getConfig();
        assertThat(effective.getProperty("hazelcast.socket.bind.any")).isEqualTo("false");
        assertThat(effective.getProperty("hazelcast.socket.server.bind.any")).isEqualTo("false");
        assertThat(effective.getNetworkConfig().getPublicAddress()).isNull();
        assertThat(effective.getNetworkConfig().getMemberAddressProviderConfig().isEnabled()).isFalse();
        assertThat(effective.getNetworkConfig().getInterfaces().getInterfaces()).containsExactly("127.0.0.1");
        String lan = lanAddress();
        if (lan != null) {
            try (java.net.Socket socket = new java.net.Socket()) {
                Throwable connect = catchThrowable(() -> socket.connect(new java.net.InetSocketAddress(lan, port), 1500));
                assertThat(connect).as("a LAN connect to " + port).isNotNull();
            }
        }
    }

    @Test
    void aKeylessConfigThatBindsServerSocketsEverywhereIsIsolatedAnyway() throws Exception {
        int port = FreePorts.consecutive(1);
        Config config = keylessConfig(TestMembers.uniqueName("serverany"), port);
        config.setProperty("hazelcast.socket.server.bind.any", "true");
        config.getNetworkConfig().setPublicAddress("203.0.113.9:" + port);
        config.getNetworkConfig().getMemberAddressProviderConfig().setEnabled(true)
                .setImplementation(new com.hazelcast.spi.MemberAddressProvider() {
                    @Override public java.net.InetSocketAddress getBindAddress() {
                        return null;
                    }

                    @Override public java.net.InetSocketAddress getBindAddress(
                            com.hazelcast.instance.EndpointQualifier qualifier) {
                        return null;
                    }

                    @Override public java.net.InetSocketAddress getPublicAddress() {
                        return null;
                    }

                    @Override public java.net.InetSocketAddress getPublicAddress(
                            com.hazelcast.instance.EndpointQualifier qualifier) {
                        return null;
                    }
                });
        try (PrivacyCluster keyless = PrivacyCluster.embedded(config, false)) {
            assertListensOnLoopbackOnly(keyless, port);
        }
    }

    @Test
    void aSystemPropertyForServerBindAnyDoesNotMakeAKeylessMemberListenEverywhere() throws Exception {
        String key = "hazelcast.socket.server.bind.any";
        String before = System.getProperty(key);
        System.setProperty(key, "true");
        try {
            int port = FreePorts.consecutive(1);
            Config config = keylessConfig(TestMembers.uniqueName("serverprop"), port);
            config.getProperties().remove("hazelcast.socket.bind.any");
            try (PrivacyCluster keyless = PrivacyCluster.embedded(config, false)) {
                assertListensOnLoopbackOnly(keyless, port);
            }
        } finally {
            if (before == null) {
                System.clearProperty(key);
            } else {
                System.setProperty(key, before);
            }
        }
    }
}

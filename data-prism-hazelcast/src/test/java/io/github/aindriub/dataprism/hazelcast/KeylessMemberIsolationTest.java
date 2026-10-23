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
                Throwable connect = catchThrowable(
                        () -> socket.connect(new java.net.InetSocketAddress(lan, port), 1500));
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

    // ---- listeners are removed ------------------------------------------------------------------

    /** Records which listener ids were handed out and which were removed, forwarding to the real cluster. */
    private static HazelcastInstance tracking(HazelcastInstance real, List<java.util.UUID> added,
                                              List<java.util.UUID> removed, boolean twoMembersAtInit) {
        Member local = real.getCluster().getLocalMember();
        Member other = forwarding(Member.class, local, (m, a) -> m.getName().equals("equals") ? Boolean.FALSE
                : m.getName().equals("hashCode") ? Integer.valueOf(1) : NOT_OVERRIDDEN);
        Cluster cluster = forwarding(Cluster.class, real.getCluster(), (m, a) -> {
            if (m.getName().equals("addMembershipListener")) {
                java.util.UUID id;
                if (twoMembersAtInit && a[0] instanceof com.hazelcast.cluster.InitialMembershipListener initial) {
                    initial.init(new com.hazelcast.cluster.InitialMembershipEvent(real.getCluster(),
                            new LinkedHashSet<>(List.of(local, other))));
                    id = java.util.UUID.randomUUID();
                } else {
                    id = real.getCluster().addMembershipListener((MembershipListener) a[0]);
                }
                added.add(id);
                return id;
            }
            if (m.getName().equals("removeMembershipListener")) {
                removed.add((java.util.UUID) a[0]);
                return Boolean.TRUE;
            }
            return NOT_OVERRIDDEN;
        });
        return forwarding(HazelcastInstance.class, real,
                (m, a) -> m.getName().equals("getCluster") ? cluster : NOT_OVERRIDDEN);
    }

    @Test
    void aRefusedUsingLeavesNoListenerOnTheCallersInstance() {
        HazelcastInstance real = soloInstance();
        List<java.util.UUID> added = new ArrayList<>();
        List<java.util.UUID> removed = new ArrayList<>();
        refusedAsMissingKeys(catchThrowable(() -> PrivacyCluster.using(tracking(real, added, removed, true), false)));
        assertThat(added).hasSize(2);
        assertThat(removed).containsExactlyInAnyOrderElementsOf(added);
    }

    @Test
    void closingRemovesTheLegacyAndTheJoinerListeners() {
        HazelcastInstance real = soloInstance();
        List<java.util.UUID> added = new ArrayList<>();
        List<java.util.UUID> removed = new ArrayList<>();
        PrivacyCluster wrapped = PrivacyCluster.using(tracking(real, added, removed, false), false);
        assertThat(added).hasSize(2);
        wrapped.close();
        assertThat(removed).containsExactlyInAnyOrderElementsOf(added);
    }

    @Test
    void theRefusalIsLoggedOnceWhenJoinsAreAnnouncedConcurrently() {
        HazelcastInstance real = soloInstance();
        List<MembershipListener> listeners = new ArrayList<>();
        PrivacyCluster wrapped = PrivacyCluster.using(capturing(real, listeners), false);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(16);
        var start = new java.util.concurrent.CountDownLatch(1);
        List<java.util.concurrent.Future<?>> done = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            done.add(pool.submit(() -> {
                start.await();
                memberJoins(real, listeners);
                return null;
            }));
        }
        start.countDown();
        for (var future : done) {
            try {
                future.get(20, TimeUnit.SECONDS);
            } catch (Exception failed) {
                throw new AssertionError(failed);
            }
        }
        pool.shutdownNow();
        assertThat(wrapped.isRefused()).isTrue();
        assertThat(wrapped.refusalsLogged()).isEqualTo(1);
    }

    // ---- the local address override -----------------------------------------------------------------

    @Test
    void aNonLoopbackLocalAddressSystemPropertyRefusesAKeylessMember() {
        String key = "hazelcast.local.localAddress";
        String before = System.getProperty(key);
        System.setProperty(key, "10.1.2.3");
        try {
            Config config = keylessConfig(TestMembers.uniqueName("localaddr"), FreePorts.consecutive(1));
            refusedAsMissingKeys(catchThrowable(() -> PrivacyCluster.embedded(config, false)));
            assertThat(Hazelcast.getAllHazelcastInstances()).isEmpty();
            System.setProperty(key, "127.0.0.1");
            ClusterMembership.isolate(keylessConfig(TestMembers.uniqueName("localaddr"), 5701));
        } finally {
            if (before == null) {
                System.clearProperty(key);
            } else {
                System.setProperty(key, before);
            }
        }
    }

    @Test
    void aNonLoopbackLocalAddressConfigPropertyRefusesAKeylessMember() {
        Config config = keylessConfig(TestMembers.uniqueName("localaddr"), FreePorts.consecutive(1));
        config.setProperty("hazelcast.local.localAddress", "10.1.2.3");
        refusedAsMissingKeys(catchThrowable(() -> PrivacyCluster.embedded(config, false)));
        HazelcastInstance real = soloInstance();
        Config exposed = keylessConfig(TestMembers.uniqueName("localaddr"), 5701);
        exposed.setProperty("hazelcast.local.localAddress", "10.1.2.3");
        HazelcastInstance reported = forwarding(HazelcastInstance.class, real,
                (m, a) -> m.getName().equals("getConfig") ? exposed : NOT_OVERRIDDEN);
        refusedAsMissingKeys(catchThrowable(() -> PrivacyCluster.using(reported, false)));
    }

    @Test
    void onlyLoopbackLiteralsAreAcceptedAsALocalAddress() {
        for (String accepted : List.of("127.0.0.1", "127.1.2.3", " localhost ", "::1")) {
            Config config = keylessConfig(TestMembers.uniqueName("literal"), 5701);
            config.setProperty("hazelcast.local.localAddress", accepted);
            ClusterMembership.isolate(config);
        }
        for (String refused : List.of("127.lan.example.internal", "127.0.0.256", "127.0.0.1.example.com",
                "127.0.0", "10.1.2.3", "example.com")) {
            Config config = keylessConfig(TestMembers.uniqueName("literal"), 5701);
            config.setProperty("hazelcast.local.localAddress", refused);
            refusedAsMissingKeys(catchThrowable(() -> ClusterMembership.isolate(config)));
        }
    }

    // ---- the advertised address override ------------------------------------------------------------

    private static void withSystemProperty(String key, String value, Runnable body) {
        String before = System.getProperty(key);
        System.setProperty(key, value);
        try {
            body.run();
        } finally {
            if (before == null) {
                System.clearProperty(key);
            } else {
                System.setProperty(key, before);
            }
        }
    }

    @Test
    void aNonLoopbackPublicAddressSystemPropertyRefusesAKeylessMember() {
        withSystemProperty("hazelcast.local.publicAddress", "10.1.2.3:5701", () -> {
            Config config = keylessConfig(TestMembers.uniqueName("publicprop"), FreePorts.consecutive(1));
            refusedAsMissingKeys(catchThrowable(() -> PrivacyCluster.embedded(config, false)));
            assertThat(Hazelcast.getAllHazelcastInstances()).isEmpty();
        });
        withSystemProperty("hazelcast.local.publicAddress", "127.0.0.1:5701",
                () -> ClusterMembership.isolate(keylessConfig(TestMembers.uniqueName("publicprop"), 5701)));
    }

    @Test
    void aNonLoopbackPublicAddressConfigPropertyRefusesAKeylessMember() {
        for (String refused : List.of("10.1.2.3", "example.com:5701", "127.lan.example.internal")) {
            Config config = keylessConfig(TestMembers.uniqueName("publicprop"), 5701);
            config.setProperty("hazelcast.local.publicAddress", refused);
            refusedAsMissingKeys(catchThrowable(() -> ClusterMembership.isolate(config)));
        }
        Config loopback = keylessConfig(TestMembers.uniqueName("publicprop"), 5701);
        loopback.setProperty("hazelcast.local.publicAddress", "localhost:5701");
        ClusterMembership.isolate(loopback);
    }

    // ---- the code names the cause ---------------------------------------------------------------------

    @Test
    void aKeyedSoloMemberWithAnOverridingAddressIsNotToldItLacksKeys() {
        withSystemProperty("hazelcast.local.localAddress", "10.1.2.3", () -> {
            ClusterMembership none = ClusterMembership.none(TestMembers.uniqueName("keyedsolo"))
                    .withPort(FreePorts.consecutive(1));
            Throwable keyed = catchThrowable(() -> PrivacyCluster.embedded(none, false, OptionalInt.empty(),
                    Duration.ofMinutes(1), TestMembers.KEYS));
            assertThat(keyed).isInstanceOf(PrivacyClusterRefusal.class);
            assertThat(((PrivacyClusterRefusal) keyed).code()).isEqualTo(Code.INVALID_CLUSTER_INTERFACE);
            Throwable keyless = catchThrowable(() -> PrivacyCluster.embedded(none, false));
            refusedAsMissingKeys(keyless);
            Throwable direct = catchThrowable(none::toConfig);
            assertThat(((PrivacyClusterRefusal) direct).code()).isEqualTo(Code.INVALID_CLUSTER_INTERFACE);
        });
    }
}

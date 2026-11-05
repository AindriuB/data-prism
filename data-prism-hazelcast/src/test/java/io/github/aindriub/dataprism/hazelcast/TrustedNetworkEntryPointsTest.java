package io.github.aindriub.dataprism.hazelcast;

import com.hazelcast.config.Config;
import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;
import io.github.aindriub.dataprism.core.metrics.PrivacyMetrics;
import io.github.aindriub.dataprism.hazelcast.PrivacyClusterRefusal.Code;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Task 287: the trusted-network rule of task 284 applies to every plain-Java entry point that starts or wraps a member
 * that joins others, with the same codes, in the same order. A pass row on an address this machine does not have
 * cannot start a member, so it asserts only that the trusted-network check did not refuse; rows on 127.0.0.1 start.
 */
class TrustedNetworkEntryPointsTest {

    private static final String PUBLIC_NET = "198.51.100.*";
    private static final String PUBLIC_RANGE = "198.51.100.0/24";
    private static final String PUBLIC_ADDRESS = "198.51.100.7";
    private static final String LOCAL_ADDRESS_PROPERTY = "hazelcast.local.localAddress";
    private static final String PUBLIC_ADDRESS_PROPERTY = "hazelcast.local.publicAddress";
    private static final String BIND_ANY = "hazelcast.socket.bind.any";
    private static final String SERVER_BIND_ANY = "hazelcast.socket.server.bind.any";
    private static final List<String> SENTINELS = List.of("198.51.100", "10.0.0.5", "10.0.*", "127.0.0.1");

    private static final ClusterSecuritySettings UNDECLARED = ClusterSecuritySettings.defaults();
    private static final ClusterSecuritySettings DECLARED = UNDECLARED.withTrustedNetwork(true, List.of(), false);
    private static final ClusterSecuritySettings IN_RANGE = UNDECLARED.withTrustedNetwork(true,
            List.of(PUBLIC_RANGE), false);

    private final RecordingLogger log = new RecordingLogger();
    private final PrivacyCluster.Environment environment = new PrivacyCluster.Environment(Clock.systemUTC(),
            PrivacyMetrics.none(), log.logger(), Duration.ofHours(1));

    @AfterEach
    void shutDown() {
        for (String name : List.of(LOCAL_ADDRESS_PROPERTY, PUBLIC_ADDRESS_PROPERTY, BIND_ANY, SERVER_BIND_ANY)) {
            System.clearProperty(name);
        }
        Hazelcast.shutdownAll();
    }

    /** What the member is asked to be: the interfaces it names, and the settings that widen what it reaches. */
    private record Spec(List<String> interfaces, Map<String, String> configProperties, String publicAddress) {
        static Spec on(String... interfaces) {
            return new Spec(List.of(interfaces), Map.of(), null);
        }

        Spec withProperty(String name, String value) {
            return new Spec(interfaces, Map.of(name, value), publicAddress);
        }

        Spec withPublicAddress(String address) {
            return new Spec(interfaces, configProperties, address);
        }
    }

    /** The three entry points. */
    enum Route {
        MEMBERSHIP, CONFIG, USING;

        boolean startsAMember() {
            return this != USING;
        }
    }

    private PrivacyCluster run(Route route, Spec spec, ClusterSecuritySettings settings) {
        int port = FreePorts.consecutive(1);
        String name = TestMembers.uniqueName("trusted");
        List<String> peers = List.of("127.0.0.1:" + port);
        String first = spec.interfaces().isEmpty() ? null : spec.interfaces().get(0);
        ClusterMembership membership = ClusterMembership.tcpIp(name, peers).withPort(port).withInterface(first);
        if (route == Route.MEMBERSHIP) {
            assertThat(spec.configProperties()).isEmpty();
            assertThat(spec.publicAddress()).isNull();
            assertThat(spec.interfaces().size()).isLessThanOrEqualTo(1);
            return PrivacyCluster.embedded(membership, false, OptionalInt.empty(), PendingSweeper.DEFAULT_INTERVAL,
                    TestMembers.KEYS, settings, environment);
        }
        Config config = described(name, peers, port, spec);
        if (route == Route.CONFIG) {
            return PrivacyCluster.embedded(config, false, OptionalInt.empty(), PendingSweeper.DEFAULT_INTERVAL,
                    TestMembers.KEYS, settings, environment);
        }
        // A real member bound to loopback, which reports the config under test: the check judges the reported
        // config and the really bound address.
        Config real = PrivacyCluster.configure(ClusterMembership.tcpIp(name, peers).withPort(port)
                .withInterface("127.0.0.1").toConfig());
        HazelcastInstance instance = startUnderNoSystemProperties(real);
        return PrivacyCluster.using(reporting(instance, PrivacyCluster.configure(config)), false,
                PendingSweeper.DEFAULT_INTERVAL, TestMembers.KEYS, settings, environment);
    }

    /** A real member binds loopback only, and whatever the test sets as a system property applies to the wrap. */
    private static HazelcastInstance startUnderNoSystemProperties(Config real) {
        real.setProperty(BIND_ANY, "false");
        real.setProperty(SERVER_BIND_ANY, "false");
        Map<String, String> held = new java.util.HashMap<>();
        for (String name : List.of(LOCAL_ADDRESS_PROPERTY, PUBLIC_ADDRESS_PROPERTY, BIND_ANY, SERVER_BIND_ANY)) {
            String value = System.clearProperty(name);
            if (value != null) {
                held.put(name, value);
            }
        }
        try {
            return Hazelcast.newHazelcastInstance(real);
        } finally {
            held.forEach(System::setProperty);
        }
    }

    private static Config described(String name, List<String> peers, int port, Spec spec) {
        Config config = ClusterMembership.tcpIp(name, peers).withPort(port).withInterface("127.0.0.1").toConfig();
        var interfaces = config.getNetworkConfig().getInterfaces().clear();
        spec.interfaces().forEach(interfaces::addInterface);
        interfaces.setEnabled(!spec.interfaces().isEmpty());
        spec.configProperties().forEach(config::setProperty);
        config.getNetworkConfig().setPublicAddress(spec.publicAddress());
        return config;
    }

    /** The instance, but for the config it reports. */
    private static HazelcastInstance reporting(HazelcastInstance instance, Config reported) {
        return (HazelcastInstance) Proxy.newProxyInstance(TrustedNetworkEntryPointsTest.class.getClassLoader(),
                new Class<?>[] {HazelcastInstance.class}, (proxy, method, args) -> {
                    if (method.getName().equals("getConfig")) {
                        return reported;
                    }
                    try {
                        return method.invoke(instance, args);
                    } catch (java.lang.reflect.InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    private void refuses(Route route, Spec spec, ClusterSecuritySettings settings, Code expected) {
        int before = Hazelcast.getAllHazelcastInstances().size();
        Throwable refused = catchThrowable(() -> run(route, spec, settings));
        assertThat(refused).as("%s %s", route, expected).isInstanceOf(PrivacyClusterRefusal.class);
        assertThat(((PrivacyClusterRefusal) refused).code()).as("%s", route).isEqualTo(expected);
        noValueIn(refused.getMessage());
        if (route.startsAMember()) {
            assertThat(Hazelcast.getAllHazelcastInstances()).as("no member is left running").isEmpty();
        } else {
            assertThat(Hazelcast.getAllHazelcastInstances()).as("the supplied instance keeps running")
                    .hasSize(before + 1);
            Hazelcast.getAllHazelcastInstances().forEach(i -> assertThat(i.getLifecycleService().isRunning())
                    .isTrue());
        }
    }

    /** The row is accepted: the member starts, or (on an address this host lacks) is not refused as untrusted. */
    private void passes(Route route, Spec spec, ClusterSecuritySettings settings) {
        PrivacyCluster started = null;
        try {
            started = run(route, spec, settings);
        } catch (PrivacyClusterRefusal refused) {
            assertThat(refused.code()).isNotIn(Code.PUBLIC_MEMBER_INTERFACE, Code.MISSING_TRUSTED_NETWORK,
                    Code.INVALID_TRUSTED_RANGE, Code.MISSING_MEMBER_INTERFACE);
            noValueIn(refused.getMessage());
        } catch (RuntimeException hazelcastCouldNotBind) {
            assertThat(route.startsAMember()).as("only a start can fail to bind").isTrue();
        } finally {
            if (started != null) {
                started.close();
            }
        }
        noValueIn(null);
    }

    private void noValueIn(String message) {
        List<String> seen = new java.util.ArrayList<>(log.lines().stream().map(RecordingLogger.Line::message).toList());
        if (message != null) {
            seen.add(message);
        }
        for (String text : seen) {
            for (String sentinel : SENTINELS) {
                assertThat(text).doesNotContain(sentinel);
            }
        }
    }

    // --- the declaration and the three cases of the table ---------------------------------------------------

    @ParameterizedTest
    @EnumSource(Route.class)
    void undeclaredAndJoiningIsRefusedAsMissingTheDeclaration(Route route) {
        refuses(route, Spec.on("127.0.0.1"), UNDECLARED, Code.MISSING_TRUSTED_NETWORK);
    }

    @ParameterizedTest
    @EnumSource(Route.class)
    void declaredWithAPublicInterfaceIsRefused(Route route) {
        refuses(route, Spec.on(PUBLIC_NET), DECLARED, Code.PUBLIC_MEMBER_INTERFACE);
    }

    @ParameterizedTest
    @EnumSource(Route.class)
    void aPublicInterfaceNamedInTheDeclaredRangesPassesTheCheck(Route route) {
        passes(route, Spec.on(PUBLIC_NET), IN_RANGE);
    }

    @ParameterizedTest
    @EnumSource(Route.class)
    void aRangeIsIgnoredUntilTheNetworkIsDeclared(Route route) {
        refuses(route, Spec.on(PUBLIC_NET),
                UNDECLARED.withTrustedNetwork(false, List.of(PUBLIC_RANGE), false), Code.PUBLIC_MEMBER_INTERFACE);
    }

    @ParameterizedTest
    @EnumSource(Route.class)
    void aWidePublicRangeIsRefusedAsInvalid(Route route) {
        refuses(route, Spec.on("127.0.0.1"), UNDECLARED.withTrustedNetwork(true, List.of("198.0.0.0/8"), false),
                Code.INVALID_TRUSTED_RANGE);
    }

    @ParameterizedTest
    @EnumSource(Route.class)
    void aPrivateInterfaceDeclaredPassesTheCheck(Route route) {
        passes(route, Spec.on("10.0.*.*"), DECLARED);
    }

    @ParameterizedTest
    @EnumSource(Route.class)
    void loopbackDeclaredStarts(Route route) {
        PrivacyCluster started = run(route, Spec.on("127.0.0.1"), DECLARED);
        try {
            assertThat(started.instance().getLifecycleService().isRunning()).isTrue();
            long announced = log.lines().stream().filter(l -> l.message().contains("only Data Prism members can reach"))
                    .count();
            assertThat(announced).as("the assertion is logged once").isEqualTo(1);
            assertThat(log.lines().get(0).message()).contains("member port").doesNotContain("127.0.0.1");
        } finally {
            started.close();
        }
    }

    @Test
    void anAnnouncedAssertionIsNotLoggedAgain() {
        PrivacyCluster started = run(Route.CONFIG, Spec.on("127.0.0.1"), DECLARED.withTrustedNetwork(true,
                List.of(), true));
        started.close();
        assertThat(log.lines()).noneMatch(l -> l.message().contains("only Data Prism members"));
    }

    @ParameterizedTest
    @EnumSource(Route.class)
    void aJoiningMemberWithNoEnabledInterfaceIsRefused(Route route) {
        refuses(route, Spec.on(), DECLARED, Code.MISSING_MEMBER_INTERFACE);
    }

    @Test
    void aSoloMemberStartsUndeclared() {
        int port = FreePorts.consecutive(1);
        try (PrivacyCluster solo = PrivacyCluster.embedded(ClusterMembership.none(TestMembers.uniqueName("solo"))
                .withPort(port), false)) {
            assertThat(solo.instance().getLifecycleService().isRunning()).isTrue();
        }
        Config config = new Config().setClusterName(TestMembers.uniqueName("solo"));
        config.getNetworkConfig().setPort(FreePorts.consecutive(1));
        try (PrivacyCluster solo = PrivacyCluster.embedded(config, false)) {
            assertThat(solo.instance().getLifecycleService().isRunning()).isTrue();
        }
        HazelcastInstance loopback = Hazelcast.newHazelcastInstance(PrivacyCluster.configure(
                ClusterMembership.none(TestMembers.uniqueName("solo")).withPort(FreePorts.consecutive(1)).toConfig()));
        try (PrivacyCluster wrapped = PrivacyCluster.using(loopback, false, TestMembers.KEYS)) {
            assertThat(wrapped.instance()).isSameAs(loopback);
        }
    }

    @Test
    void usingRefusesAMemberWhoseBoundAddressIsNotPrivateWhateverItsConfigSays() {
        int port = FreePorts.consecutive(1);
        String name = TestMembers.uniqueName("bound");
        Config real = PrivacyCluster.configure(ClusterMembership.tcpIp(name, List.of("127.0.0.1:" + port))
                .withPort(port).withInterface("127.0.0.1").toConfig());
        // Bound to every interface, though the config reported below names a private one.
        real.getNetworkConfig().getInterfaces().clear().setEnabled(false);
        real.setProperty(BIND_ANY, "true");
        real.setProperty(SERVER_BIND_ANY, "true");
        HazelcastInstance instance = Hazelcast.newHazelcastInstance(real);
        Config reported = PrivacyCluster.configure(described(name, List.of("127.0.0.1:" + port), port,
                Spec.on("10.0.*.*")));
        Throwable refused = catchThrowable(() -> PrivacyCluster.using(reporting(instance, reported), false,
                PendingSweeper.DEFAULT_INTERVAL, TestMembers.KEYS, DECLARED, environment));
        assertThat(refused).isInstanceOf(PrivacyClusterRefusal.class);
        assertThat(((PrivacyClusterRefusal) refused).code()).isEqualTo(Code.PUBLIC_MEMBER_INTERFACE);
        noValueIn(refused.getMessage());
        assertThat(instance.getLifecycleService().isRunning()).isTrue();
    }

    // --- addresses and bind-any settings, on every route -----------------------------------------------------

    @ParameterizedTest
    @EnumSource(Route.class)
    void aPublicAddressSetAsASystemPropertyIsRefused(Route route) {
        for (String property : List.of(PUBLIC_ADDRESS_PROPERTY, LOCAL_ADDRESS_PROPERTY)) {
            System.setProperty(property, PUBLIC_ADDRESS);
            try {
                refuses(route, Spec.on("10.0.*.*"), DECLARED, Code.PUBLIC_MEMBER_INTERFACE);
            } finally {
                System.clearProperty(property);
            }
        }
    }

    @ParameterizedTest
    @EnumSource(Route.class)
    void aPrivateOrRangedAddressSetAsASystemPropertyPassesTheCheck(Route route) {
        for (String property : List.of(PUBLIC_ADDRESS_PROPERTY, LOCAL_ADDRESS_PROPERTY)) {
            System.setProperty(property, "10.0.0.5");
            try {
                passes(route, Spec.on("10.0.*.*"), DECLARED);
            } finally {
                System.clearProperty(property);
            }
            System.setProperty(property, PUBLIC_ADDRESS);
            try {
                passes(route, Spec.on("10.0.*.*"), IN_RANGE);
            } finally {
                System.clearProperty(property);
            }
        }
    }

    @ParameterizedTest
    @EnumSource(Route.class)
    void bindAnySetAsASystemPropertyIsRefused(Route route) {
        for (String property : List.of(BIND_ANY, SERVER_BIND_ANY)) {
            System.setProperty(property, "true");
            try {
                refuses(route, Spec.on("10.0.*.*"), DECLARED, Code.PUBLIC_MEMBER_INTERFACE);
            } finally {
                System.clearProperty(property);
            }
        }
    }

    @ParameterizedTest
    @EnumSource(value = Route.class, names = {"CONFIG", "USING"})
    void bindAnySetAsAConfigPropertyIsRefused(Route route) {
        for (String property : List.of(BIND_ANY, SERVER_BIND_ANY)) {
            refuses(route, Spec.on("10.0.*.*").withProperty(property, "true"), DECLARED,
                    Code.PUBLIC_MEMBER_INTERFACE);
        }
    }

    @ParameterizedTest
    @EnumSource(value = Route.class, names = {"CONFIG", "USING"})
    void aPublicAddressInTheNetworkConfigIsRefusedUnlessPrivateOrRanged(Route route) {
        refuses(route, Spec.on("10.0.*.*").withPublicAddress(PUBLIC_ADDRESS), DECLARED,
                Code.PUBLIC_MEMBER_INTERFACE);
        passes(route, Spec.on("10.0.*.*").withPublicAddress("10.0.0.5"), DECLARED);
        passes(route, Spec.on("10.0.*.*").withPublicAddress(PUBLIC_ADDRESS), IN_RANGE);
    }

    @ParameterizedTest
    @EnumSource(value = Route.class, names = {"CONFIG", "USING"})
    void addressPropertiesInTheConfigAreJudgedToo(Route route) {
        for (String property : List.of(PUBLIC_ADDRESS_PROPERTY, LOCAL_ADDRESS_PROPERTY)) {
            refuses(route, Spec.on("10.0.*.*").withProperty(property, PUBLIC_ADDRESS), DECLARED,
                    Code.PUBLIC_MEMBER_INTERFACE);
            passes(route, Spec.on("10.0.*.*").withProperty(property, "10.0.0.5"), DECLARED);
            passes(route, Spec.on("10.0.*.*").withProperty(property, PUBLIC_ADDRESS), IN_RANGE);
        }
    }
}

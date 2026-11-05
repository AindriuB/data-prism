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

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.net.InetAddress;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.OptionalInt;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Task 287, review round 1: the trusted-network rule fires on "this member is reachable beyond this host", not on
 * "this member joins others". A keyed {@code Config} with no joiner is judged the same way as a joining one.
 */
class TrustedNetworkReachabilityTest {

    private static final ClusterSecuritySettings UNDECLARED = ClusterSecuritySettings.defaults();
    private static final ClusterSecuritySettings DECLARED = UNDECLARED.withTrustedNetwork(true, List.of(), false);

    private final RecordingLogger log = new RecordingLogger();
    private final PrivacyCluster.Environment environment = new PrivacyCluster.Environment(Clock.systemUTC(),
            PrivacyMetrics.none(), log.logger(), Duration.ofHours(1));

    @AfterEach
    void shutDown() {
        Hazelcast.shutdownAll();
    }

    /** The two entry points that take a Config, or an instance that reports one. */
    enum Route {
        CONFIG, USING
    }

    /** A keyed Config with no joiner, on the given interfaces; bind-any is set as given. */
    private static Config keyed(String name, int port, boolean bindAny, String... interfaces) {
        Config config = new Config().setClusterName(name);
        config.getJetConfig().setEnabled(false);
        config.setProperty("hazelcast.phone.home.enabled", "false");
        config.getNetworkConfig().setPort(port).setPortAutoIncrement(false);
        config.getNetworkConfig().getJoin().getMulticastConfig().setEnabled(false);
        config.getNetworkConfig().getJoin().getTcpIpConfig().setEnabled(false);
        config.setProperty("hazelcast.socket.bind.any", String.valueOf(bindAny));
        config.setProperty("hazelcast.socket.server.bind.any", String.valueOf(bindAny));
        var named = config.getNetworkConfig().getInterfaces();
        named.setEnabled(interfaces.length > 0);
        List.of(interfaces).forEach(named::addInterface);
        return config;
    }

    private PrivacyCluster run(Route route, Config config, ClusterSecuritySettings settings) {
        if (route == Route.CONFIG) {
            return PrivacyCluster.embedded(config, false, OptionalInt.empty(), PendingSweeper.DEFAULT_INTERVAL,
                    TestMembers.KEYS, settings, environment);
        }
        // A real member bound to loopback, which reports the config under test.
        Config real = keyed(config.getClusterName(), FreePorts.consecutive(1), false, "127.0.0.1");
        HazelcastInstance instance = Hazelcast.newHazelcastInstance(PrivacyCluster.configure(real));
        return PrivacyCluster.using(reporting(instance, PrivacyCluster.configure(config)), false,
                PendingSweeper.DEFAULT_INTERVAL, TestMembers.KEYS, settings, environment);
    }

    private static HazelcastInstance reporting(HazelcastInstance instance, Config reported) {
        return (HazelcastInstance) Proxy.newProxyInstance(TrustedNetworkReachabilityTest.class.getClassLoader(),
                new Class<?>[] {HazelcastInstance.class}, (proxy, method, args) -> {
                    if (method.getName().equals("getConfig")) {
                        return reported;
                    }
                    try {
                        return method.invoke(instance, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    private void refuses(Route route, Config config, ClusterSecuritySettings settings, Code expected) {
        Throwable refused = catchThrowable(() -> run(route, config, settings));
        assertThat(refused).as("%s %s", route, expected).isInstanceOf(PrivacyClusterRefusal.class);
        assertThat(((PrivacyClusterRefusal) refused).code()).as("%s", route).isEqualTo(expected);
        if (route == Route.CONFIG) {
            assertThat(Hazelcast.getAllHazelcastInstances()).as("no member is left running").isEmpty();
        } else {
            assertThat(Hazelcast.getAllHazelcastInstances()).as("the supplied instance keeps running").hasSize(1);
        }
    }

    @ParameterizedTest
    @EnumSource(Route.class)
    void aKeyedMemberWithNoInterfaceThatListensOnEveryInterfaceIsRefused(Route route) {
        refuses(route, keyed(TestMembers.uniqueName("noif"), FreePorts.consecutive(1), true), DECLARED,
                Code.MISSING_MEMBER_INTERFACE);
    }

    @ParameterizedTest
    @EnumSource(Route.class)
    void aKeyedMemberOnAPublicInterfaceIsRefusedEvenWithBindAnyOff(Route route) {
        refuses(route, keyed(TestMembers.uniqueName("pub"), FreePorts.consecutive(1), false, "198.51.100.*"),
                DECLARED, Code.PUBLIC_MEMBER_INTERFACE);
    }

    @ParameterizedTest
    @EnumSource(Route.class)
    void aKeyedMemberOnAPrivateInterfaceNeedsTheDeclaration(Route route) {
        refuses(route, keyed(TestMembers.uniqueName("priv"), FreePorts.consecutive(1), false, "10.0.*.*"),
                UNDECLARED, Code.MISSING_TRUSTED_NETWORK);
    }

    @ParameterizedTest
    @EnumSource(Route.class)
    void aKeyedMemberOnAPrivateInterfaceThatIsDeclaredIsNotRefusedAsUntrusted(Route route) {
        PrivacyCluster started = null;
        try {
            started = run(route, keyed(TestMembers.uniqueName("privok"), FreePorts.consecutive(1), false,
                    "10.0.*.*"), DECLARED);
        } catch (PrivacyClusterRefusal refused) {
            assertThat(refused.code()).isNotIn(Code.PUBLIC_MEMBER_INTERFACE, Code.MISSING_TRUSTED_NETWORK,
                    Code.INVALID_TRUSTED_RANGE, Code.MISSING_MEMBER_INTERFACE);
        } catch (RuntimeException hazelcastCouldNotBind) {
            assertThat(route).as("only a start can fail to bind").isEqualTo(Route.CONFIG);
        } finally {
            if (started != null) {
                started.close();
            }
        }
    }

    @ParameterizedTest
    @EnumSource(Route.class)
    void aLoopbackOnlyKeyedMemberStartsUndeclaredAndKeepsItsName(Route route) {
        String name = TestMembers.uniqueName("loop");
        try (PrivacyCluster started = run(route, keyed(name, FreePorts.consecutive(1), false, "127.0.0.1"),
                UNDECLARED)) {
            assertThat(started.instance().getLifecycleService().isRunning()).isTrue();
            assertThat(started.instance().getConfig().getClusterName()).isEqualTo(name);
        }
    }

    @Test
    void aWildcardInTheBoundSetIsRefusedWhateverRangeIsDeclared() throws Exception {
        Config config = keyed(TestMembers.uniqueName("wild"), FreePorts.consecutive(1), false, "10.0.*.*");
        ClusterSecuritySettings declared = UNDECLARED.withTrustedNetwork(true, List.of("0.0.0.0/24"), false);
        Set<InetAddress> bound = Set.of(InetAddress.getByAddress(new byte[4]));
        Throwable refused = catchThrowable(
                () -> PrivacyCluster.refuseUnlessTrusted(config, bound, declared, log.logger()));
        assertThat(refused).isInstanceOf(PrivacyClusterRefusal.class);
        assertThat(((PrivacyClusterRefusal) refused).code()).isEqualTo(Code.PUBLIC_MEMBER_INTERFACE);
    }
}

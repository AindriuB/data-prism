package io.github.aindriub.dataprism.spring.boot;

import com.hazelcast.core.Hazelcast;
import io.github.aindriub.dataprism.hazelcast.PrivacyCluster;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.assertj.AssertableWebApplicationContext;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 284: a member that joins others starts only when the operator declared the network trusted and the member
 * listens and advertises on private addresses. Addresses are private or documentation blocks.
 */
@ExtendWith(OutputCaptureExtension.class)
class TrustedNetworkConfigurationTest {

    private static final String TRUSTED = "dataprism.hazelcast.trusted-network";
    private static final String INTERFACE = "dataprism.hazelcast.member.interface";
    private static final String RANGES = "dataprism.hazelcast.member.trusted-ranges";
    private static final String[] SYSTEM_PROPERTIES = {"hazelcast.local.localAddress",
            "hazelcast.local.publicAddress", "hazelcast.socket.bind.any", "hazelcast.socket.server.bind.any"};
    private static final String ASSERTION = "only Data Prism members can reach";

    @AfterEach
    void shutDown() {
        for (String name : SYSTEM_PROPERTIES) {
            System.clearProperty(name);
        }
        Hazelcast.shutdownAll();
    }

    private static WebApplicationContextRunner runner(String topology, Map<String, String> properties) {
        List<String> all = new ArrayList<>();
        properties.forEach((k, v) -> all.add(k + "=" + v));
        return new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(DataPrismAutoConfiguration.class))
                .withUserConfiguration(ClusterKeyConfigurationTest.KeyIntegrations.class)
                .withPropertyValues(OversightConfigurationTest.base(topology))
                .withPropertyValues(all.toArray(String[]::new));
    }

    /** A joining member with keys, a private interface, a free member port and the assertion made. */
    private static Map<String, String> joining(String mode, String interfaceAddress) {
        Map<String, String> p = ClusterKeyConfigurationTest.valid(mode);
        p.put("dataprism.hazelcast.member.port", String.valueOf(ClusterConfigurationTest.freePort()));
        p.put(INTERFACE, interfaceAddress);
        return p;
    }

    private static String rootMessage(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage();
    }

    private static String everyMessage(Throwable failure) {
        StringBuilder all = new StringBuilder();
        for (Throwable current = failure; current != null; current = current.getCause()) {
            all.append(current.getMessage()).append('\n');
        }
        return all.toString();
    }

    private static void assertRefused(AssertableWebApplicationContext result, String code, String names,
            String... neverShown) {
        assertThat(result).hasFailed();
        assertThat(rootMessage(result.getStartupFailure())).startsWith(code + ":");
        assertThat(everyMessage(result.getStartupFailure())).contains(names);
        for (String value : neverShown) {
            assertThat(everyMessage(result.getStartupFailure())).doesNotContain(value);
        }
    }

    /**
     * Not refused for the network. Whether a member then starts depends on this host: Hazelcast refuses an interface
     * pattern that matches none of its addresses, and there is no Kubernetes API here.
     */
    private static void assertNotRefusedForTheNetwork(String mode, AssertableWebApplicationContext result) {
        if (result.getStartupFailure() != null) {
            assertThat(rootMessage(result.getStartupFailure())).doesNotStartWith("PUBLIC_MEMBER_INTERFACE")
                    .doesNotStartWith("MISSING_TRUSTED_NETWORK").doesNotStartWith("INVALID_TRUSTED_RANGE");
        }
    }

    private static Stream<String> joinModes() {
        return Stream.of("tcp-ip", "kubernetes");
    }

    private static Stream<Object[]> modesAnd(String... values) {
        return joinModes().flatMap(mode -> Stream.of(values).map(value -> new Object[] {mode, value}));
    }

    // ---- the assertion is required --------------------------------------------------------------

    @ParameterizedTest(name = "{0}: trusted-network {1}")
    @MethodSource("unsetOrFalse")
    void a_joining_member_without_the_assertion_is_refused(String mode, String value) {
        Map<String, String> p = joining(mode, "10.0.*.*");
        p.remove(TRUSTED);
        if (value != null) {
            p.put(TRUSTED, value);
        }
        runner("embedded", p).run(result -> {
            assertRefused(result, "MISSING_TRUSTED_NETWORK", TRUSTED, "10.0.");
            assertThat(rootMessage(result.getStartupFailure())).contains(ASSERTION).contains("unsealed")
                    .contains("dataprism.hazelcast.member.port").contains("NetworkPolicy").contains("hostNetwork")
                    .contains("dedicated namespace");
        });
    }

    static Stream<Object[]> unsetOrFalse() {
        return Stream.of("tcp-ip", "kubernetes").flatMap(mode -> Stream.of(new Object[] {mode, null},
                new Object[] {mode, "false"}));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("joinModes")
    void a_joining_member_with_the_assertion_starts(String mode) {
        runner("embedded", joining(mode, "10.0.*.*")).run(result -> assertNotRefusedForTheNetwork(mode, result));
    }

    @Test
    void a_started_joining_member_says_the_assertion_once_with_the_port_and_no_address(CapturedOutput output) {
        Map<String, String> p = joining("tcp-ip", "127.0.0.1");
        String port = p.get("dataprism.hazelcast.member.port");
        runner("embedded", p).run(result -> assertThat(result).hasNotFailed());
        String logged = output.getAll();
        assertThat(logged.split(ASSERTION, -1)).hasSize(2);
        assertThat(logged).contains("member port " + port);
        String line = logged.lines().filter(l -> l.contains(ASSERTION)).findFirst().orElseThrow();
        assertThat(line).doesNotContain("127.0.0.1");
    }

    // ---- the interface --------------------------------------------------------------------------

    static Stream<Object[]> publicInterfaces() {
        return modesAnd("203.0.113.*", "198.51.100.7", "169.254.1.*", "100.64.0.*", "172.15-16.*.*");
    }

    @ParameterizedTest(name = "{0}: {1}")
    @MethodSource("publicInterfaces")
    void a_public_interface_is_refused_even_when_the_assertion_is_made(String mode, String address) {
        runner("embedded", joining(mode, address)).run(result ->
                assertRefused(result, "PUBLIC_MEMBER_INTERFACE", INTERFACE, address, "203.0.113", "198.51.100"));
    }

    static Stream<Object[]> privateInterfaces() {
        return modesAnd("127.0.0.1", "10.*.*.*", "172.16-31.*.*", "192.168.4.*");
    }

    @ParameterizedTest(name = "{0}: {1}")
    @MethodSource("privateInterfaces")
    void a_private_interface_starts(String mode, String address) {
        runner("embedded", joining(mode, address)).run(result -> assertNotRefusedForTheNetwork(mode, result));
    }

    @Test
    void a_public_interface_is_checked_before_the_assertion() {
        Map<String, String> p = joining("tcp-ip", "203.0.113.*");
        p.remove(TRUSTED);
        runner("embedded", p).run(result -> assertRefused(result, "PUBLIC_MEMBER_INTERFACE", INTERFACE));
    }

    @Test
    void an_interface_that_is_missing_unbounded_or_invalid_is_refused_before_the_trusted_network_codes() {
        Map<String, String> missing = joining("tcp-ip", "10.0.*.*");
        missing.remove(INTERFACE);
        runner("embedded", missing).run(result -> assertRefused(result, "MISSING_MEMBER_INTERFACE", "member.interface"));
        runner("embedded", joining("tcp-ip", "*.*.*.*")).run(result ->
                assertRefused(result, "UNBOUNDED_MEMBER_INTERFACE", "member.interface"));
        runner("embedded", joining("tcp-ip", "not-an-ip")).run(result ->
                assertRefused(result, "INVALID_CLUSTER_INTERFACE", "member.interface"));
        Map<String, String> unset = joining("tcp-ip", "not-an-ip");
        unset.remove(TRUSTED);
        runner("embedded", unset).run(result -> assertRefused(result, "INVALID_CLUSTER_INTERFACE", "member.interface"));
    }

    // ---- trusted ranges -------------------------------------------------------------------------

    @Test
    void a_public_interface_inside_a_trusted_range_starts_and_logs_the_count_only(CapturedOutput output) {
        Map<String, String> p = joining("tcp-ip", "203.0.113.*");
        p.put(RANGES + "[0]", "203.0.113.0/24");
        // Only an interface that is on this host can be bound, so the check is made on the verdict, which the
        // refusal-free path shows: the member is refused for binding a foreign address, never for the network.
        runner("embedded", p).run(result -> {
            if (result.getStartupFailure() != null) {
                assertThat(rootMessage(result.getStartupFailure())).doesNotStartWith("PUBLIC_MEMBER_INTERFACE")
                        .doesNotStartWith("MISSING_TRUSTED_NETWORK").doesNotStartWith("INVALID_TRUSTED_RANGE");
            }
        });
        String warning = output.getAll().lines().filter(l -> l.contains("WARN") && l.contains("trusted-ranges"))
                .findFirst().orElseThrow();
        assertThat(warning).contains("1 range").doesNotContain("203.0.113");
        assertThat(output.getAll().lines().filter(l -> l.contains("WARN") && l.contains("trusted-ranges")).count())
                .isEqualTo(1);
    }

    @Test
    void a_private_range_is_accepted_without_a_warning(CapturedOutput output) {
        Map<String, String> p = joining("tcp-ip", "127.0.0.1");
        p.put(RANGES + "[0]", "10.0.0.0/8");
        runner("embedded", p).run(result -> assertThat(result).hasNotFailed());
        assertThat(output.getAll()).doesNotContain("outside the private blocks");
    }

    @ParameterizedTest(name = "{0}: {1}")
    @MethodSource("invalidRanges")
    void a_range_that_is_not_valid_is_refused_naming_its_index_and_never_its_value(String mode, String range) {
        Map<String, String> p = joining(mode, "10.0.*.*");
        p.put(RANGES + "[0]", "10.1.0.0/16");
        p.put(RANGES + "[1]", range);
        runner("embedded", p).run(result -> {
            assertRefused(result, "INVALID_TRUSTED_RANGE", RANGES + "[1]", range.isEmpty() ? "\u0000" : range);
            assertThat(rootMessage(result.getStartupFailure())).doesNotContain("[0]");
        });
    }

    static Stream<Object[]> invalidRanges() {
        return modesAnd("203.0.113.0/33", "not-a-range", "0.0.0.0/0", "198.0.0.0/8", "198.51.100.0/23");
    }

    @Test
    void ranges_are_not_read_when_the_assertion_is_missing() {
        Map<String, String> p = joining("tcp-ip", "10.0.*.*");
        p.remove(TRUSTED);
        p.put(RANGES + "[0]", "not-a-range");
        runner("embedded", p).run(result -> assertRefused(result, "MISSING_TRUSTED_NETWORK", TRUSTED));
    }

    // ---- bind any and advertised addresses -----------------------------------------------------

    @ParameterizedTest(name = "{0}")
    @org.junit.jupiter.params.provider.ValueSource(strings = {"hazelcast.socket.bind.any",
            "hazelcast.socket.server.bind.any"})
    void binding_every_address_by_system_property_is_refused(String property) {
        System.setProperty(property, "true");
        runner("embedded", joining("tcp-ip", "10.0.*.*")).run(result ->
                assertRefused(result, "PUBLIC_MEMBER_INTERFACE", INTERFACE));
    }

    @ParameterizedTest(name = "{0}")
    @org.junit.jupiter.params.provider.ValueSource(strings = {"hazelcast.local.publicAddress",
            "hazelcast.local.localAddress"})
    void a_public_advertised_address_is_refused_before_any_member_starts(String property) {
        System.setProperty(property, "198.51.100.7:5701");
        runner("embedded", joining("tcp-ip", "10.0.*.*")).run(result -> {
            assertRefused(result, "PUBLIC_MEMBER_INTERFACE", INTERFACE, "198.51.100.7");
            assertThat(Hazelcast.getAllHazelcastInstances()).isEmpty();
        });
    }

    @ParameterizedTest(name = "{0}")
    @org.junit.jupiter.params.provider.ValueSource(strings = {"hazelcast.local.publicAddress",
            "hazelcast.local.localAddress"})
    void a_private_or_trusted_advertised_address_is_not_refused(String property) {
        System.setProperty(property, "10.0.0.5:5701");
        runner("embedded", joining("tcp-ip", "10.0.*.*")).run(result -> {
            if (result.getStartupFailure() != null) {
                assertThat(rootMessage(result.getStartupFailure())).doesNotStartWith("PUBLIC_MEMBER_INTERFACE");
            }
        });
        System.setProperty(property, "198.51.100.7:5701");
        Map<String, String> p = joining("tcp-ip", "10.0.*.*");
        p.put(RANGES + "[0]", "198.51.100.0/24");
        runner("embedded", p).run(result -> {
            if (result.getStartupFailure() != null) {
                assertThat(rootMessage(result.getStartupFailure())).doesNotStartWith("PUBLIC_MEMBER_INTERFACE");
            }
        });
    }

    // ---- where the settings do not apply --------------------------------------------------------

    @ParameterizedTest(name = "trusted-network: {0}")
    @org.junit.jupiter.params.provider.ValueSource(strings = {"true", "false"})
    void the_assertion_is_refused_where_no_member_joins_another(String value) {
        runner("single-node", Map.of(TRUSTED, value)).run(result ->
                assertRefused(result, "CLUSTER_SETTINGS_IGNORED", "trusted-network"));
        Map<String, String> none = ClusterKeyConfigurationTest.valid("none");
        none.put(TRUSTED, value);
        runner("embedded", none).run(result -> assertRefused(result, "CLUSTER_SETTINGS_IGNORED", TRUSTED));
        runner("embedded", Map.of(TRUSTED, value)).withUserConfiguration(ClusterConfigurationTest.SuppliedCluster.class)
                .run(result -> assertRefused(result, "CLUSTER_SETTINGS_IGNORED", "trusted-network"));
    }

    @Test
    void trusted_ranges_are_refused_where_no_member_joins_another() {
        runner("single-node", Map.of(RANGES + "[0]", "203.0.113.0/24")).run(result ->
                assertRefused(result, "CLUSTER_SETTINGS_IGNORED", "member.*", "203.0.113"));
        Map<String, String> none = ClusterKeyConfigurationTest.valid("none");
        none.put(RANGES + "[0]", "203.0.113.0/24");
        runner("embedded", none).run(result ->
                assertRefused(result, "CLUSTER_SETTINGS_IGNORED", "trusted-ranges", "203.0.113"));
        runner("embedded", Map.of(RANGES + "[0]", "203.0.113.0/24"))
                .withUserConfiguration(ClusterConfigurationTest.SuppliedCluster.class).run(result ->
                        assertRefused(result, "CLUSTER_SETTINGS_IGNORED", "member.*", "203.0.113"));
    }

    @Test
    void a_solo_member_with_neither_property_starts_unchanged(CapturedOutput output) {
        Map<String, String> none = ClusterKeyConfigurationTest.valid("none");
        none.put("dataprism.hazelcast.member.port", String.valueOf(ClusterConfigurationTest.freePort()));
        runner("embedded", none).run(result -> {
            assertThat(result).hasNotFailed();
            assertThat(result.getBean(PrivacyCluster.class)).isNotNull();
        });
        assertThat(output.getAll()).doesNotContain(ASSERTION);
    }
}

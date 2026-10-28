package io.github.aindriub.dataprism.hazelcast;

import com.hazelcast.config.Config;
import io.github.aindriub.dataprism.hazelcast.TrustedNetwork.Verdict;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Task 284: the trusted-network rule on its own, with no Spring. Addresses are private or documentation blocks. */
class TrustedNetworkTest {

    private static final String[] SYSTEM_PROPERTIES = {"hazelcast.local.localAddress",
            "hazelcast.local.publicAddress", "hazelcast.socket.bind.any", "hazelcast.socket.server.bind.any"};

    @AfterEach
    void clearSystemProperties() {
        for (String name : SYSTEM_PROPERTIES) {
            System.clearProperty(name);
        }
    }

    /** As the starter builds it: the interface set and both bind-any settings off (Hazelcast defaults them on). */
    private static Config on(String interfaceAddress) {
        Config config = new Config();
        config.setProperty("hazelcast.socket.bind.any", "false");
        config.setProperty("hazelcast.socket.server.bind.any", "false");
        config.getNetworkConfig().getInterfaces().setEnabled(true).addInterface(interfaceAddress);
        return config;
    }

    private static Verdict verdict(String interfaceAddress, String... ranges) {
        return TrustedNetwork.evaluate(on(interfaceAddress), List.of(ranges));
    }

    // ---- block boundaries ---------------------------------------------------------------------

    @ParameterizedTest(name = "{0} is {1}")
    @CsvSource({
            "9.255.255.255,PUBLIC_MEMBER_INTERFACE", "10.0.0.0,TRUSTED", "10.255.255.255,TRUSTED",
            "11.0.0.0,PUBLIC_MEMBER_INTERFACE",
            "172.15.255.255,PUBLIC_MEMBER_INTERFACE", "172.16.0.0,TRUSTED", "172.31.255.255,TRUSTED",
            "172.32.0.0,PUBLIC_MEMBER_INTERFACE",
            "192.167.255.255,PUBLIC_MEMBER_INTERFACE", "192.168.0.0,TRUSTED", "192.168.255.255,TRUSTED",
            "192.169.0.0,PUBLIC_MEMBER_INTERFACE",
            "126.255.255.255,PUBLIC_MEMBER_INTERFACE", "127.0.0.0,TRUSTED", "127.255.255.255,TRUSTED",
            "128.0.0.0,PUBLIC_MEMBER_INTERFACE",
            "169.254.1.1,PUBLIC_MEMBER_INTERFACE", "100.64.0.1,PUBLIC_MEMBER_INTERFACE",
            "203.0.113.5,PUBLIC_MEMBER_INTERFACE"})
    void a_literal_interface_is_judged_at_every_block_boundary(String address, Verdict expected) {
        assertThat(verdict(address)).isEqualTo(expected);
    }

    @ParameterizedTest(name = "{0} is {1}")
    @CsvSource({
            "10.*.*.*,TRUSTED", "10.0.*.*,TRUSTED", "172.16-31.*.*,TRUSTED", "192.168.4.*,TRUSTED",
            "172.15-16.*.*,PUBLIC_MEMBER_INTERFACE", "172.16-32.*.*,PUBLIC_MEMBER_INTERFACE",
            "192.168-169.*.*,PUBLIC_MEMBER_INTERFACE", "9-10.*.*.*,PUBLIC_MEMBER_INTERFACE",
            "203.0.113.*,PUBLIC_MEMBER_INTERFACE", "169.254.1.*,PUBLIC_MEMBER_INTERFACE",
            "100.64.0.*,PUBLIC_MEMBER_INTERFACE", "*.0.0.1,PUBLIC_MEMBER_INTERFACE",
            "10.5-2.0.1,PUBLIC_MEMBER_INTERFACE", "10.0.0.256,PUBLIC_MEMBER_INTERFACE",
            "10.0.0,PUBLIC_MEMBER_INTERFACE", "not-an-address,PUBLIC_MEMBER_INTERFACE"})
    void a_pattern_is_trusted_only_when_every_address_it_can_match_is(String pattern, Verdict expected) {
        assertThat(verdict(pattern)).isEqualTo(expected);
    }

    @Test
    void a_range_trusts_an_interface_only_when_it_covers_all_of_it() {
        assertThat(verdict("172.15-16.*.*")).isEqualTo(Verdict.PUBLIC_MEMBER_INTERFACE);
        assertThat(verdict("203.0.113.*", "203.0.113.0/24")).isEqualTo(Verdict.TRUSTED);
        assertThat(verdict("203.0.113.*", "203.0.113.0/25")).isEqualTo(Verdict.PUBLIC_MEMBER_INTERFACE);
        assertThat(verdict("203.0.113.5", "203.0.113.4/30")).isEqualTo(Verdict.TRUSTED);
    }

    // ---- the interfaces -----------------------------------------------------------------------

    @Test
    void every_enabled_interface_must_be_trusted() {
        Config config = on("10.0.0.1");
        config.getNetworkConfig().getInterfaces().addInterface("203.0.113.1");
        assertThat(TrustedNetwork.evaluate(config, List.of())).isEqualTo(Verdict.PUBLIC_MEMBER_INTERFACE);
    }

    @Test
    void hazelcasts_default_of_binding_every_address_is_public() {
        Config config = new Config();
        config.getNetworkConfig().getInterfaces().setEnabled(true).addInterface("10.0.0.1");
        assertThat(TrustedNetwork.evaluate(config, List.of())).isEqualTo(Verdict.PUBLIC_MEMBER_INTERFACE);
    }

    @Test
    void no_interface_or_a_disabled_list_binds_every_address() {
        assertThat(TrustedNetwork.evaluate(new Config(), List.of())).isEqualTo(Verdict.PUBLIC_MEMBER_INTERFACE);
        Config disabled = on("10.0.0.1");
        disabled.getNetworkConfig().getInterfaces().setEnabled(false);
        assertThat(TrustedNetwork.evaluate(disabled, List.of())).isEqualTo(Verdict.PUBLIC_MEMBER_INTERFACE);
    }

    // ---- bind any -----------------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"hazelcast.socket.bind.any", "hazelcast.socket.server.bind.any"})
    void bind_any_as_a_system_property_is_public_and_false_is_trusted(String name) {
        System.setProperty(name, "true");
        assertThat(verdict("10.0.*.*")).isEqualTo(Verdict.PUBLIC_MEMBER_INTERFACE);
        System.setProperty(name, "false");
        assertThat(verdict("10.0.*.*")).isEqualTo(Verdict.TRUSTED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"hazelcast.socket.bind.any", "hazelcast.socket.server.bind.any"})
    void bind_any_as_a_config_property_is_public_and_false_is_trusted(String name) {
        Config config = on("10.0.*.*").setProperty(name, "true");
        assertThat(TrustedNetwork.evaluate(config, List.of())).isEqualTo(Verdict.PUBLIC_MEMBER_INTERFACE);
        config.setProperty(name, "false");
        assertThat(TrustedNetwork.evaluate(config, List.of())).isEqualTo(Verdict.TRUSTED);
    }

    // ---- advertised addresses -----------------------------------------------------------------

    @Test
    void the_network_config_public_address_is_judged() {
        Config config = on("10.0.*.*");
        config.getNetworkConfig().setPublicAddress("198.51.100.7:5701");
        assertThat(TrustedNetwork.evaluate(config, List.of())).isEqualTo(Verdict.PUBLIC_MEMBER_INTERFACE);
        config.getNetworkConfig().setPublicAddress("10.0.0.5:5701");
        assertThat(TrustedNetwork.evaluate(config, List.of())).isEqualTo(Verdict.TRUSTED);
        config.getNetworkConfig().setPublicAddress("198.51.100.7");
        assertThat(TrustedNetwork.evaluate(config, List.of("198.51.100.0/24"))).isEqualTo(Verdict.TRUSTED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"hazelcast.local.localAddress", "hazelcast.local.publicAddress"})
    void an_advertised_address_as_a_config_property_is_judged(String name) {
        assertThat(TrustedNetwork.evaluate(on("10.0.*.*").setProperty(name, "198.51.100.7:5701"), List.of()))
                .isEqualTo(Verdict.PUBLIC_MEMBER_INTERFACE);
        assertThat(TrustedNetwork.evaluate(on("10.0.*.*").setProperty(name, "10.0.0.5:5701"), List.of()))
                .isEqualTo(Verdict.TRUSTED);
        assertThat(TrustedNetwork.evaluate(on("10.0.*.*").setProperty(name, "198.51.100.7"),
                List.of("198.51.100.0/24"))).isEqualTo(Verdict.TRUSTED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"hazelcast.local.localAddress", "hazelcast.local.publicAddress"})
    void an_advertised_address_as_a_system_property_is_judged(String name) {
        System.setProperty(name, "198.51.100.7:5701");
        assertThat(verdict("10.0.*.*")).isEqualTo(Verdict.PUBLIC_MEMBER_INTERFACE);
        assertThat(verdict("10.0.*.*", "198.51.100.0/24")).isEqualTo(Verdict.TRUSTED);
        System.setProperty(name, "10.0.0.5:5701");
        assertThat(verdict("10.0.*.*")).isEqualTo(Verdict.TRUSTED);
    }

    @Test
    void a_name_as_an_advertised_address_is_not_trusted() {
        assertThat(TrustedNetwork.evaluate(on("10.0.*.*").setProperty("hazelcast.local.publicAddress",
                "member.example.invalid:5701"), List.of())).isEqualTo(Verdict.PUBLIC_MEMBER_INTERFACE);
    }

    @Test
    void ipv6_unique_local_and_loopback_advertised_addresses_are_private_and_others_are_not() {
        assertThat(TrustedNetwork.evaluate(on("10.0.*.*").setProperty("hazelcast.local.publicAddress", "fd00::5"),
                List.of())).isEqualTo(Verdict.TRUSTED);
        assertThat(TrustedNetwork.evaluate(on("10.0.*.*").setProperty("hazelcast.local.publicAddress", "::1"),
                List.of())).isEqualTo(Verdict.TRUSTED);
        assertThat(TrustedNetwork.evaluate(on("10.0.*.*").setProperty("hazelcast.local.publicAddress",
                "2001:db8::5"), List.of())).isEqualTo(Verdict.PUBLIC_MEMBER_INTERFACE);
    }

    // ---- trusted ranges -----------------------------------------------------------------------

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"203.0.113.0/33", "not-a-range", "0.0.0.0/0", "198.0.0.0/8", "198.51.100.0/23",
            "203.0.113.0", "203.0.113.0/", "203.0.113/24", "203.0.113.0/-1", "203.0.113.256/24", "::1/128", ""})
    void a_range_that_does_not_parse_or_is_public_and_wider_than_a_24_is_invalid(String range) {
        assertThat(verdict("10.0.*.*", range)).isEqualTo(Verdict.INVALID_TRUSTED_RANGE);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "127.0.0.0/8", "203.0.113.0/24",
            "203.0.113.7/32", "203.0.113.5/24", "198.51.100.128/25", "10.1.0.0/16"})
    void a_range_that_is_private_or_at_most_a_24_is_accepted(String range) {
        assertThat(verdict("10.0.*.*", range)).isEqualTo(Verdict.TRUSTED);
    }

    @Test
    void a_range_wider_than_a_private_block_is_not_private() {
        assertThat(verdict("10.0.*.*", "10.0.0.0/7")).isEqualTo(Verdict.INVALID_TRUSTED_RANGE);
        assertThat(verdict("10.0.*.*", "172.16.0.0/11")).isEqualTo(Verdict.INVALID_TRUSTED_RANGE);
        assertThat(verdict("10.0.*.*", "192.168.0.0/15")).isEqualTo(Verdict.INVALID_TRUSTED_RANGE);
        assertThat(verdict("10.0.*.*", "127.0.0.0/7")).isEqualTo(Verdict.INVALID_TRUSTED_RANGE);
    }

    @Test
    void an_invalid_range_wins_over_a_public_interface_and_a_null_list_is_empty() {
        assertThat(verdict("203.0.113.*", "not-a-range")).isEqualTo(Verdict.INVALID_TRUSTED_RANGE);
        assertThat(TrustedNetwork.evaluate(on("10.0.*.*"), null)).isEqualTo(Verdict.TRUSTED);
    }

    @Test
    void only_ranges_outside_the_private_blocks_are_counted() {
        assertThat(TrustedNetwork.nonPrivateRangeCount(List.of("10.0.0.0/8", "203.0.113.0/24", "198.51.100.0/24",
                "not-a-range"))).isEqualTo(2);
        assertThat(TrustedNetwork.nonPrivateRangeCount(null)).isZero();
    }
}

package io.github.aindriub.dataprism.hazelcast.spike;

import com.hazelcast.config.Config;
import com.hazelcast.core.HazelcastInstance;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Question 1: is an inbound member connection from an address outside {@code network/interfaces} accepted?
 * The joiner dials the first member on this host's non-loopback address, so the first member sees the
 * connection arrive from that address, while its own {@code interfaces} lists loopback only.
 */
class Q01InboundAllowlistSpikeTest {

    private HazelcastInstance first;
    private HazelcastInstance second;

    @AfterEach
    void stop() {
        Spike.shutdown(first, second);
    }

    private static String nonLoopbackAddress() throws Exception {
        for (NetworkInterface nic : Collections.list(NetworkInterface.getNetworkInterfaces())) {
            if (!nic.isUp() || nic.isLoopback() || nic.isVirtual()) {
                continue;
            }
            for (InetAddress address : Collections.list(nic.getInetAddresses())) {
                if (address instanceof Inet4Address && !address.isLinkLocalAddress()) {
                    return address.getHostAddress();
                }
            }
        }
        return null;
    }

    private int joined(String source) {
        String hostOfFirst = source;
        int ports = SpikePorts.consecutive(2);
        Config a = Spike.config(Spike.name("q1"), ports, List.of());
        a.getNetworkConfig().getJoin().getTcpIpConfig().setMembers(List.of());
        // Listen on every address so the joiner can arrive from a non-loopback one.
        a.setProperty("hazelcast.socket.bind.any", "true");
        first = Spike.start(a);
        Config b = Spike.config(a.getClusterName(), ports + 1, List.of(hostOfFirst + ":" + ports));
        // The joiner binds its outgoing connection to its own interface, so it arrives from `source`.
        b.getNetworkConfig().getInterfaces().clear().addInterface(source);
        second = Spike.start(b);
        Spike.await(20, () -> first.getCluster().getMembers().size() == 2);
        return first.getCluster().getMembers().size();
    }

    @Test
    void aMemberOutsideTheInterfacesIsAccepted() throws Exception {
        String source = nonLoopbackAddress();
        assumeTrue(source != null, "this host has no non-loopback IPv4 address");
        assertThat(joined(source))
                .as("the first member lists only 127.0.0.1 yet the second, arriving from %s, joined", source)
                .isEqualTo(2);
    }
}

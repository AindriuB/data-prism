package io.github.aindriub.dataprism.hazelcast.spike;

import com.hazelcast.config.Config;
import com.hazelcast.core.HazelcastInstance;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Question 1: is an inbound member connection from an address outside {@code network/interfaces} accepted?
 * The joiner dials the first member on this host's primary non-loopback address, so the first member sees the
 * connection arrive from that address, while its own {@code interfaces} lists loopback only.
 */
class Q01InboundAllowlistSpikeTest {

    private HazelcastInstance first;
    private HazelcastInstance second;

    @AfterEach
    void stop() {
        Spike.shutdown(first, second);
    }

    /** An address of this host and the interface that carries it. */
    private record Source(String address, String interfaceName) {
    }

    /**
     * The address the joiner arrives from. First the primary route's source (a UDP connect to TEST-NET-1 sends no
     * packet); failing that, or if it cannot reach itself, the up, non-loopback, non-virtual, non-point-to-point,
     * non-link-local IPv4 addresses in interface-index order. The first that can connect to a wildcard listener
     * on this host wins, so a VM or bridge address that cannot carry a join is never chosen. Null if none can.
     */
    private static Source nonLoopbackSource() throws Exception {
        String primary = primaryRouteSource();
        if (primary != null) {
            NetworkInterface nic = NetworkInterface.getByInetAddress(InetAddress.getByName(primary));
            if (nic != null && reachesItself(primary)) {
                return new Source(primary, nic.getName());
            }
        }
        List<NetworkInterface> nics = new ArrayList<>(Collections.list(NetworkInterface.getNetworkInterfaces()));
        nics.sort(Comparator.comparingInt(NetworkInterface::getIndex));
        for (NetworkInterface nic : nics) {
            if (!nic.isUp() || nic.isLoopback() || nic.isVirtual() || nic.isPointToPoint()) {
                continue;
            }
            for (InetAddress address : Collections.list(nic.getInetAddresses())) {
                if (address instanceof Inet4Address && !address.isLinkLocalAddress()
                        && reachesItself(address.getHostAddress())) {
                    return new Source(address.getHostAddress(), nic.getName());
                }
            }
        }
        return null;
    }

    private static String primaryRouteSource() {
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.connect(InetAddress.getByName("192.0.2.1"), 9);
            InetAddress local = socket.getLocalAddress();
            return local instanceof Inet4Address && !local.isAnyLocalAddress() && !local.isLoopbackAddress()
                    ? local.getHostAddress() : null;
        } catch (IOException | RuntimeException noRoute) {
            return null;
        }
    }

    /** A client bound to {@code candidate} connects to {@code candidate} on a wildcard listener within 2 s. */
    private static boolean reachesItself(String candidate) {
        try (ServerSocket server = new ServerSocket(0, 1); Socket client = new Socket()) {
            client.bind(new InetSocketAddress(candidate, 0));
            client.connect(new InetSocketAddress(candidate, server.getLocalPort()), 2000);
            return true;
        } catch (IOException | RuntimeException unreachable) {
            return false;
        }
    }

    private int joined(String source) {
        int ports = SpikePorts.consecutive(2);
        Config a = Spike.config(Spike.name("q1"), ports, List.of());
        a.getNetworkConfig().getJoin().getTcpIpConfig().setMembers(List.of());
        // Listen on every address so the joiner can arrive from a non-loopback one.
        a.setProperty("hazelcast.socket.bind.any", "true");
        first = Spike.start(a);
        Config b = Spike.config(a.getClusterName(), ports + 1, List.of(source + ":" + ports));
        // The joiner binds its outgoing connection to its own interface, so it arrives from `source`.
        b.getNetworkConfig().getInterfaces().clear().addInterface(source);
        second = Spike.start(b);
        Spike.await(20, () -> first.getCluster().getMembers().size() == 2);
        return first.getCluster().getMembers().size();
    }

    @Test
    void aMemberOutsideTheInterfacesIsAccepted() throws Exception {
        Source source = nonLoopbackSource();
        assumeTrue(source != null, "no self-reachable non-loopback IPv4 address was found on this host");
        assertThat(joined(source.address()))
                .as("the first member lists only 127.0.0.1; the second, arriving from %s on %s, should have joined",
                        source.address(), source.interfaceName())
                .isEqualTo(2);
    }
}

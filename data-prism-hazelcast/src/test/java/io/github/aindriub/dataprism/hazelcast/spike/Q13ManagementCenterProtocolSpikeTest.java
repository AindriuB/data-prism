package io.github.aindriub.dataprism.hazelcast.spike;

import com.hazelcast.client.impl.protocol.codec.MCChangeClusterStateCodec;
import com.hazelcast.cluster.ClusterState;
import com.hazelcast.config.AdvancedNetworkConfig;
import com.hazelcast.config.Config;
import com.hazelcast.core.HazelcastInstance;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Question 13: does anything in OSS 5.7.0 stop a client that knows only the cluster name from sending Management
 * Center protocol messages (here, change cluster state to FROZEN)? Tested, one member each:
 * default config; {@code ManagementCenterConfig} with console, scripting and data access off; {@code trustedInterfaces}
 * not matching the client (127.0.0.1) and matching it; advanced network with a member endpoint and no client
 * endpoint (Data Prism's {@code PrivacyCluster} refuses advanced network, so that is not a usable switch).
 */
class Q13ManagementCenterProtocolSpikeTest {

    private HazelcastInstance member;
    private HazelcastInstance client;

    @AfterEach
    void stop() {
        Spike.shutdown(client, member);
    }

    /** Starts a member with {@code tweak} applied, connects a client, sends the message, reports the outcome. */
    private String freezeFromAClient(Consumer<Config> tweak) {
        int port = SpikePorts.consecutive(1);
        Config config = Spike.config(Spike.name("q13"), port, List.of());
        tweak.accept(config);
        member = Spike.start(config);
        client = Spike.client(config.getClusterName(), port);
        String outcome = Spike.sendManagementMessage(client,
                MCChangeClusterStateCodec.encodeRequest(ClusterState.FROZEN.getId()));
        return outcome + "/" + member.getCluster().getClusterState();
    }

    @Test
    void withTheDefaultConfigAClientFreezesTheCluster() {
        assertThat(freezeFromAClient(c -> { })).isEqualTo("allowed/FROZEN");
    }

    @Test
    void managementCenterConsoleScriptingAndDataAccessSwitchesDoNotStopAClientFreezingTheCluster() {
        assertThat(freezeFromAClient(c -> c.getManagementCenterConfig().setConsoleEnabled(false)
                .setScriptingEnabled(false).setDataAccessEnabled(false))).isEqualTo("allowed/FROZEN");
    }

    @Test
    void aTrustedInterfacesListThatDoesNotMatchTheClientRefusesTheFreezeButNotOrdinaryMapUse() {
        assertThat(freezeFromAClient(c -> c.getManagementCenterConfig()
                .setTrustedInterfaces(Set.of("10.255.255.1")))).isEqualTo("AccessControlException/ACTIVE");
        client.getMap("m").put("k", "v");
        assertThat(client.getMap("m").get("k")).isEqualTo("v");
    }

    @Test
    void aTrustedInterfacesListThatMatchesTheClientStillLetsItFreezeTheCluster() {
        assertThat(freezeFromAClient(c -> c.getManagementCenterConfig()
                .setTrustedInterfaces(Set.of("127.0.0.1")))).isEqualTo("allowed/FROZEN");
    }

    @Test
    void advancedNetworkWithoutAClientEndpointLetsNoClientConnect() {
        int port = SpikePorts.consecutive(1);
        Config config = Spike.config(Spike.name("q13a"), port, List.of());
        AdvancedNetworkConfig advanced = config.getAdvancedNetworkConfig();
        advanced.setEnabled(true);
        advanced.setMemberEndpointConfig(new com.hazelcast.config.ServerSocketEndpointConfig().setPort(port));
        advanced.getJoin().getTcpIpConfig().setEnabled(true);
        member = Spike.start(config);
        com.hazelcast.client.config.ClientConfig clientConfig = new com.hazelcast.client.config.ClientConfig();
        clientConfig.setClusterName(config.getClusterName());
        clientConfig.getNetworkConfig().addAddress("127.0.0.1:" + port);
        clientConfig.getConnectionStrategyConfig().getConnectionRetryConfig().setClusterConnectTimeoutMillis(5000);
        String outcome = Spike.attempt(() -> client = com.hazelcast.client.HazelcastClient
                .newHazelcastClient(clientConfig));
        assertThat(outcome).isEqualTo("IllegalStateException");
    }
}

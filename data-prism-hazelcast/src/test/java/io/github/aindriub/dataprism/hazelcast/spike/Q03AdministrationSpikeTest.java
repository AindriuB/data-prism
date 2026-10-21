package io.github.aindriub.dataprism.hazelcast.spike;

import com.hazelcast.client.impl.protocol.codec.MCChangeClusterStateCodec;
import com.hazelcast.client.impl.protocol.codec.MCShutdownClusterCodec;
import com.hazelcast.cluster.ClusterState;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.map.IMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Question 3: which administrative operations does a joined member, or a client, get to run on OSS 5.7 when it
 * knows only the cluster name (no Data Prism cluster key)? Each test starts its own cluster, because an operation
 * that is allowed may take the cluster down.
 */
class Q03AdministrationSpikeTest {

    private HazelcastInstance victim;
    private HazelcastInstance keylessMember;
    private HazelcastInstance client;

    @BeforeEach
    void start() {
        String name = Spike.name("q3");
        int first = SpikePorts.consecutive(2);
        victim = Spike.start(Spike.config(name, first, List.of()));
        keylessMember = Spike.start(Spike.config(name, first + 1, List.of("127.0.0.1:" + first)));
        assertThat(Spike.await(20, () -> victim.getCluster().getMembers().size() == 2)).isTrue();
        client = Spike.client(name, first);
    }

    @AfterEach
    void stop() {
        Spike.shutdown(client, keylessMember, victim);
    }

    @Test
    void aKeylessMemberCanChangeTheClusterState() {
        assertThat(Spike.attempt(() -> keylessMember.getCluster().changeClusterState(ClusterState.FROZEN)))
                .isEqualTo("allowed");
        assertThat(victim.getCluster().getClusterState()).isEqualTo(ClusterState.FROZEN);
    }

    @Test
    void theJavaClientApiRefusesAClusterStateChangeLocally() {
        assertThat(Spike.attempt(() -> client.getCluster().changeClusterState(ClusterState.FROZEN)))
                .isEqualTo("UnsupportedOperationException");
        assertThat(victim.getCluster().getClusterState()).isEqualTo(ClusterState.ACTIVE);
    }

    @Test
    void aKeylessClientCanChangeTheClusterStateWithAManagementCenterMessage() {
        // The refusal above is thrown by the Java client proxy, before anything is sent. The member itself accepts
        // the protocol message from a client that knows only the cluster name.
        assertThat(Spike.sendManagementMessage(client, MCChangeClusterStateCodec
                .encodeRequest(ClusterState.FROZEN.getId()))).isEqualTo("allowed");
        assertThat(victim.getCluster().getClusterState()).isEqualTo(ClusterState.FROZEN);
    }

    @Test
    void aKeylessClientCanShutDownTheClusterWithAManagementCenterMessage() {
        // The reply may be lost because the cluster is already going down (TargetDisconnectedException).
        assertThat(Spike.sendManagementMessage(client, MCShutdownClusterCodec.encodeRequest()))
                .isIn("allowed", "TargetDisconnectedException");
        assertThat(Spike.await(30, () -> !victim.getLifecycleService().isRunning()
                && !keylessMember.getLifecycleService().isRunning())).isTrue();
    }

    @Test
    void aKeylessMemberCanShutDownTheCluster() {
        assertThat(Spike.attempt(() -> keylessMember.getCluster().shutdown())).isEqualTo("allowed");
        assertThat(Spike.await(20, () -> !victim.getLifecycleService().isRunning())).isTrue();
    }

    @Test
    void theJavaClientApiRefusesAClusterShutdownLocally() {
        assertThat(Spike.attempt(() -> client.getCluster().shutdown())).isEqualTo("UnsupportedOperationException");
        assertThat(victim.getLifecycleService().isRunning()).isTrue();
    }

    @Test
    void aKeylessMemberCanDestroyAMap() {
        victim.getMap("m").put("k", "v");
        assertThat(Spike.attempt(() -> keylessMember.getMap("m").destroy())).isEqualTo("allowed");
        assertThat(victim.getDistributedObjects().stream().anyMatch(o -> o.getName().equals("m"))).isFalse();
    }

    @Test
    void aClientCanDestroyAMap() {
        victim.getMap("m").put("k", "v");
        assertThat(Spike.attempt(() -> client.getMap("m").destroy())).isEqualTo("allowed");
        assertThat(victim.getDistributedObjects().stream().anyMatch(o -> o.getName().equals("m"))).isFalse();
    }

    @Test
    void aKeylessMemberCanForceUnlockAKey() {
        IMap<String, String> map = victim.getMap("m");
        map.lock("k");
        assertThat(Spike.attempt(() -> keylessMember.getMap("m").forceUnlock("k"))).isEqualTo("allowed");
        assertThat(map.isLocked("k")).isFalse();
    }

    @Test
    void aClientCanForceUnlockAKey() {
        IMap<String, String> map = victim.getMap("m");
        map.lock("k");
        assertThat(Spike.attempt(() -> client.getMap("m").forceUnlock("k"))).isEqualTo("allowed");
        assertThat(map.isLocked("k")).isFalse();
    }
}

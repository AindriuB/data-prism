package io.github.aindriub.dataprism.hazelcast.spike;

import com.hazelcast.config.Config;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.instance.impl.DefaultNodeContext;
import com.hazelcast.instance.impl.DefaultNodeExtension;
import com.hazelcast.instance.impl.HazelcastInstanceFactory;
import com.hazelcast.instance.impl.Node;
import com.hazelcast.instance.impl.NodeExtension;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Question 6: what does 5.7 do when a member joins with the UUID of a live member? The joiner is started through
 * Hazelcast's own node extension hook, which is how an attacker with the jar would choose a UUID.
 */
class Q06DuplicateUuidSpikeTest {

    private HazelcastInstance first;
    private HazelcastInstance impostor;

    @AfterEach
    void stop() {
        Spike.shutdown(impostor, first);
    }

    private static HazelcastInstance startWithUuid(Config config, UUID uuid) {
        return HazelcastInstanceFactory.newHazelcastInstance(config, null, new DefaultNodeContext() {
            @Override
            public NodeExtension createNodeExtension(Node node) {
                return new DefaultNodeExtension(node) {
                    @Override
                    public UUID createMemberUuid() {
                        return uuid;
                    }
                };
            }
        });
    }

    @Test
    void aMemberWithALiveMembersUuidStartsAloneAndDoesNotJoinWithinFifteenSeconds() {
        List<Config> configs = Spike.configs(Spike.name("q6"), 2);
        first = Spike.start(configs.get(0));
        UUID taken = first.getCluster().getLocalMember().getUuid();
        impostor = startWithUuid(configs.get(1), taken);
        // Each side takes the other for itself ("Packet send task is rejected. Target is this node!"), so both
        // stay clusters of one. The impostor is never in the live member list, so the member-card design (d2)
        // never sees a second member to verify or to reject.
        assertThat(Spike.await(15, () -> first.getCluster().getMembers().size() > 1
                || impostor.getCluster().getMembers().size() > 1)).isFalse();
        assertThat(impostor.getLifecycleService().isRunning()).isTrue();
        assertThat(impostor.getCluster().getLocalMember().getUuid()).isEqualTo(taken);
    }
}

package io.github.aindriub.dataprism.hazelcast.spike;

import com.hazelcast.config.Config;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.core.LifecycleEvent;
import com.hazelcast.nio.ObjectDataInput;
import com.hazelcast.nio.ObjectDataOutput;
import com.hazelcast.spi.merge.MergingValue;
import com.hazelcast.spi.merge.SplitBrainMergePolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Question 11: after a split heals, does the smaller side's lifecycle listener get {@code MERGED}, and in what order
 * relative to the merge policies running? Two members form the larger side and a third, listing only itself, the
 * smaller; the larger side's members keep looking for it.
 */
class Q11MergedEventSpikeTest {

    /** Everything that happened, in order: lifecycle events as "member:STATE", policy runs as "policy:key". */
    static final List<String> EVENTS = new CopyOnWriteArrayList<>();

    public static final class RecordingPolicy implements SplitBrainMergePolicy<String, MergingValue<String>, String> {
        @Override
        public String merge(MergingValue<String> merging, MergingValue<String> existing) {
            EVENTS.add("policy:" + merging.getValue());
            return merging.getValue();
        }

        @Override
        public void writeData(ObjectDataOutput out) {
        }

        @Override
        public void readData(ObjectDataInput in) {
        }
    }

    private final List<HazelcastInstance> started = new ArrayList<>();

    @BeforeEach
    void clear() {
        EVENTS.clear();
    }

    @AfterEach
    void stop() {
        Spike.shutdown(started.toArray(new HazelcastInstance[0]));
    }

    private HazelcastInstance start(Config config, String name) {
        config.setProperty("hazelcast.merge.first.run.delay.seconds", "5");
        config.setProperty("hazelcast.merge.next.run.delay.seconds", "5");
        config.getMapConfig("m").getMergePolicyConfig().setPolicy(RecordingPolicy.class.getName());
        config.addListenerConfig(new com.hazelcast.config.ListenerConfig(
                (com.hazelcast.core.LifecycleListener) event -> EVENTS.add(name + ":" + event.getState())));
        HazelcastInstance instance = Spike.start(config);
        started.add(instance);
        return instance;
    }

    @Test
    void theSmallerSideGetsMergingThenThePolicyRunsThenMergedAndTheLargerSideGetsNeither() {
        int first = SpikePorts.consecutive(3);
        List<String> all = List.of("127.0.0.1:" + first, "127.0.0.1:" + (first + 1), "127.0.0.1:" + (first + 2));
        String cluster = Spike.name("q11");
        HazelcastInstance a = start(Spike.config(cluster, first, all.subList(0, 2)).setInstanceName("a"), "a");
        HazelcastInstance b = start(Spike.config(cluster, first + 1, all.subList(0, 2)).setInstanceName("b"), "b");
        assertThat(Spike.await(20, () -> a.getCluster().getMembers().size() == 2)).isTrue();
        // The larger side lists the loner so that it keeps looking for it.
        a.getConfig().getNetworkConfig().getJoin().getTcpIpConfig().addMember(all.get(2));
        b.getConfig().getNetworkConfig().getJoin().getTcpIpConfig().addMember(all.get(2));
        a.getMap("m").put("k", "on-larger");
        HazelcastInstance c = start(Spike.config(cluster, first + 2, List.of(all.get(2))).setInstanceName("c"), "c");
        c.getMap("m").put("k", "on-smaller");
        assertThat(Spike.await(90, () -> c.getCluster().getMembers().size() == 3)).as("the sides merged").isTrue();
        assertThat(Spike.await(30, () -> EVENTS.contains("c:MERGED"))).as("c was told it merged").isTrue();
        // MERGING, then the policy for the smaller side's entry, then MERGED: the policy has finished by MERGED.
        assertThat(EVENTS.subList(EVENTS.indexOf("c:MERGING"), EVENTS.size()))
                .containsExactly("c:MERGING", "policy:on-smaller", "c:MERGED");
        // The larger side's members are told nothing about the merge.
        assertThat(EVENTS).noneMatch(e -> e.startsWith("a:MERG") || e.startsWith("b:MERG"));
    }
}

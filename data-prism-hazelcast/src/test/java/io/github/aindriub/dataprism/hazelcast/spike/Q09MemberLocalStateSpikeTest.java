package io.github.aindriub.dataprism.hazelcast.spike;

import com.hazelcast.config.Config;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.core.HazelcastInstanceAware;
import com.hazelcast.map.EntryProcessor;
import com.hazelcast.map.IMap;
import com.hazelcast.nio.ObjectDataInput;
import com.hazelcast.nio.ObjectDataOutput;
import com.hazelcast.spi.merge.MergingValue;
import com.hazelcast.spi.merge.SplitBrainMergePolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Question 9: can an entry processor and a split-brain merge policy reach member-local state that is not replicated
 * (here the member's {@code Config.getUserContext()}, standing in for a key ring)? Each member is given its own tag.
 */
class Q09MemberLocalStateSpikeTest {

    /** Writes the tag of the member it runs on, found through the injected instance. */
    static final class StoreMemberTag implements EntryProcessor<String, String, String>, HazelcastInstanceAware {
        private transient HazelcastInstance instance;

        @Override
        public void setHazelcastInstance(HazelcastInstance instance) {
            this.instance = instance;
        }

        @Override
        public String process(Map.Entry<String, String> entry) {
            String tag = instance == null ? "no-instance" : (String) instance.getUserContext().get("tag");
            entry.setValue(tag);
            return tag;
        }
    }

    /** What an instance-aware merge policy found, per merge. */
    static final List<String> MERGE_SAW = new CopyOnWriteArrayList<>();

    /** Keeps the merging value but records the member tag it can see. */
    public static final class TagPolicy
            implements SplitBrainMergePolicy<String, MergingValue<String>, String>, HazelcastInstanceAware {
        private transient HazelcastInstance instance;

        @Override
        public void setHazelcastInstance(HazelcastInstance instance) {
            this.instance = instance;
        }

        @Override
        public String merge(MergingValue<String> merging, MergingValue<String> existing) {
            MERGE_SAW.add(instance == null ? "no-instance"
                    : instance.getConfig().getUserContext().get("tag") + "/"
                    + instance.getUserContext().get("tag"));
            return merging.getValue();
        }

        @Override
        public void writeData(ObjectDataOutput out) {
        }

        @Override
        public void readData(ObjectDataInput in) {
        }
    }

    private HazelcastInstance first;
    private HazelcastInstance second;

    @BeforeEach
    void clear() {
        MERGE_SAW.clear();
    }

    @AfterEach
    void stop() {
        Spike.shutdown(second, first);
    }

    @Test
    void anInstanceAwareProcessorSeesTheTagOfTheMemberItRunsOnIncludingOnTheBackup() {
        List<Config> configs = Spike.configs(Spike.name("q9p"), 2);
        configs.get(0).getUserContext().put("tag", "tag-first");
        configs.get(1).getUserContext().put("tag", "tag-second");
        first = Spike.start(configs.get(0));
        second = Spike.start(configs.get(1));
        assertThat(Spike.await(20, () -> first.getCluster().getMembers().size() == 2)).isTrue();
        String key = Spike.keyOwnedBy(second, "k");
        IMap<String, String> map = first.getMap("m");
        map.put(key, "initial");
        assertThat(map.executeOnKey(key, new StoreMemberTag())).isEqualTo("tag-second");
        // The default backup re-runs process on the other member, which found that member's own tag.
        second.getLifecycleService().terminate();
        assertThat(Spike.await(30, () -> {
            var owner = first.getPartitionService().getPartition(key).getOwner();
            return owner != null && owner.localMember();
        })).isTrue();
        assertThat(map.get(key)).isEqualTo("tag-first");
    }

    @Test
    void anInstanceAwareMergePolicySeesTheTagOfTheMemberItRunsOn() {
        List<Config> configs = Spike.splitPair(Spike.name("q9m"));
        configs.get(0).getUserContext().put("tag", "tag-first");
        configs.get(1).getUserContext().put("tag", "tag-second");
        for (Config config : configs) {
            config.getMapConfig("m").getMergePolicyConfig().setPolicy(TagPolicy.class.getName());
        }
        first = Spike.start(configs.get(0));
        second = Spike.start(configs.get(1));
        first.getMap("m").put("k", "on-first");
        second.getMap("m").put("k", "on-second");
        assertThat(Spike.await(60, () -> first.getCluster().getMembers().size() == 2
                && second.getCluster().getMembers().size() == 2)).as("the clusters merged").isTrue();
        assertThat(Spike.await(30, () -> !MERGE_SAW.isEmpty())).as("the policy ran").isTrue();
        assertThat(MERGE_SAW).allMatch(s -> s.equals("tag-first/tag-first") || s.equals("tag-second/tag-second"));
    }
}

package io.github.aindriub.dataprism.hazelcast.spike;

import com.hazelcast.config.Config;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.map.IMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Question 7: is {@code IMap.localKeySet()} limited to entries this member owns as primary, does it stay correct
 * during migration, and is there a partition-local entry iterator in OSS?
 */
class Q07LocalKeySetSpikeTest {

    private static final int KEYS = 500;

    private final List<HazelcastInstance> members = new java.util.concurrent.CopyOnWriteArrayList<>();
    private List<Config> configs;

    @AfterEach
    void stop() {
        Spike.shutdown(members.toArray(new HazelcastInstance[0]));
    }

    private static Set<String> allKeys() {
        Set<String> keys = new HashSet<>();
        IntStream.range(0, KEYS).forEach(i -> keys.add("key-" + i));
        return keys;
    }

    /** The keys {@code member} owns as primary, according to the partition table. */
    private static Set<String> primaryKeys(HazelcastInstance member) {
        String uuid = member.getCluster().getLocalMember().getUuid().toString();
        Set<String> owned = new HashSet<>();
        for (String key : allKeys()) {
            var owner = member.getPartitionService().getPartition(key).getOwner();
            if (owner != null && owner.getUuid().toString().equals(uuid)) {
                owned.add(key);
            }
        }
        return owned;
    }

    private void startMembers(int count) {
        configs = Spike.configs(Spike.name("q7"), 3);
        for (int i = 0; i < count; i++) {
            members.add(Spike.start(configs.get(i)));
        }
        assertThat(Spike.await(30, () -> members.get(0).getCluster().getMembers().size() == count)).isTrue();
    }

    private void fill() {
        IMap<String, String> map = members.get(0).getMap("m");
        allKeys().forEach(k -> map.put(k, "v"));
    }

    @Test
    void localKeySetIsTheKeysThisMemberOwnsAsPrimaryAndNoBackups() {
        startMembers(2);
        fill();
        assertThat(Spike.await(30, () -> members.get(0).getPartitionService().isClusterSafe())).isTrue();
        Set<String> onFirst = members.get(0).<String, String>getMap("m").localKeySet();
        Set<String> onSecond = members.get(1).<String, String>getMap("m").localKeySet();
        assertThat(onFirst).isEqualTo(primaryKeys(members.get(0))).isNotEmpty();
        assertThat(onSecond).isEqualTo(primaryKeys(members.get(1))).isNotEmpty();
        assertThat(onFirst).doesNotContainAnyElementsOf(onSecond);
        // Each key is also held as a backup on the other member, yet is not in that member's local key set.
        assertThat(onFirst.size() + onSecond.size()).isEqualTo(KEYS);
        assertThat(members.get(0).getMap("m").getLocalMapStats().getBackupEntryCount()).isEqualTo(onSecond.size());
    }

    @Test
    void afterAMemberJoinsAndMigrationSettlesTheLocalKeySetsPartitionTheKeysAgain() throws Exception {
        startMembers(2);
        fill();
        AtomicBoolean joining = new AtomicBoolean(true);
        AtomicInteger samples = new AtomicInteger();
        AtomicInteger anomalous = new AtomicInteger();
        Thread sampler = new Thread(() -> {
            while (joining.get()) {
                Set<String> union = new HashSet<>();
                int total = 0;
                for (int i = 0; i < 2; i++) {
                    Set<String> local = members.get(i).<String, String>getMap("m").localKeySet();
                    union.addAll(local);
                    total += local.size();
                }
                samples.incrementAndGet();
                if (union.size() != KEYS || total != KEYS) {
                    anomalous.incrementAndGet();
                }
            }
        });
        sampler.start();
        try {
            members.add(Spike.start(configs.get(2)));
            // Keep sampling until migration has finished.
            assertThat(Spike.await(60, () -> members.get(0).getPartitionService().isClusterSafe())).isTrue();
        } finally {
            joining.set(false);
            sampler.join();
        }
        // Sampled while the third member joined; not asserted, because it depends on timing.
        System.out.println("Q7 migration samples " + samples + " anomalous " + anomalous);
        assertThat(Spike.await(60, () -> members.get(0).getPartitionService().isClusterSafe())).isTrue();
        boolean settled = Spike.await(30, () -> {
            Set<String> union = new HashSet<>();
            int total = 0;
            for (HazelcastInstance member : members) {
                Set<String> local = member.<String, String>getMap("m").localKeySet();
                if (!local.equals(primaryKeys(member))) {
                    return false;
                }
                union.addAll(local);
                total += local.size();
            }
            return union.equals(allKeys()) && total == KEYS;
        });
        assertThat(settled).isTrue();
    }

    @Test
    void imapHasNoPartitionLocalIteratorOnlyTheClusterWideOnes() {
        List<Integer> parameterCounts = new ArrayList<>();
        for (Method method : IMap.class.getMethods()) {
            if (method.getName().equals("iterator")) {
                parameterCounts.add(method.getParameterCount());
            }
        }
        // iterator() and iterator(fetchSize), both over the whole cluster; neither takes a partition id.
        assertThat(parameterCounts).containsExactlyInAnyOrder(0, 1);
    }
}

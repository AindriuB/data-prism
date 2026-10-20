package io.github.aindriub.dataprism.hazelcast.spike;

import com.hazelcast.config.Config;
import com.hazelcast.core.EntryView;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.map.EntryProcessor;
import com.hazelcast.map.ExtendedMapEntry;
import com.hazelcast.map.IMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Question 8: how does an entry processor reach the backup replica? Members share one JVM, so static counters see
 * every run on every member. The backup is read by killing the primary and reading from the survivor, which
 * promotes its backup.
 */
class Q08EntryProcessorBackupSpikeTest {

    static final AtomicInteger PROCESS_RUNS = new AtomicInteger();
    static final AtomicInteger BACKUP_RUNS = new AtomicInteger();
    static volatile CountDownLatch entered = new CountDownLatch(1);
    static volatile CountDownLatch release = new CountDownLatch(1);

    /** Seals "inside process": a fresh random value each time process runs, as a nonce would give. */
    static final class RandomInProcess implements EntryProcessor<String, String, String> {
        @Override
        public String process(Map.Entry<String, String> entry) {
            PROCESS_RUNS.incrementAndGet();
            String value = "sealed-" + UUID.randomUUID();
            entry.setValue(value);
            return value;
        }
    }

    /** As above, with a backup processor that carries the primary's bytes to the backup. */
    static final class RandomThenCopyToBackup implements EntryProcessor<String, String, String> {
        private transient String sealed;

        @Override
        public String process(Map.Entry<String, String> entry) {
            PROCESS_RUNS.incrementAndGet();
            sealed = "sealed-" + UUID.randomUUID();
            entry.setValue(sealed);
            return sealed;
        }

        @Override
        public EntryProcessor<String, String, String> getBackupProcessor() {
            return new CopyValue(sealed);
        }
    }

    record CopyValue(String value) implements EntryProcessor<String, String, String> {
        @Override
        public String process(Map.Entry<String, String> entry) {
            BACKUP_RUNS.incrementAndGet();
            entry.setValue(value);
            return value;
        }
    }

    /** Sets a value with a time to live, through the extended entry. */
    record SetWithTtl(String value, long seconds) implements EntryProcessor<String, String, String> {
        @Override
        public String process(Map.Entry<String, String> entry) {
            ((ExtendedMapEntry<String, String>) entry).setValue(value, seconds, TimeUnit.SECONDS);
            return value;
        }
    }

    /** Sets the value with a time to live on the primary; its backup processor only copies the value. */
    record SetWithTtlCopyToBackup(String value, long seconds) implements EntryProcessor<String, String, String> {
        @Override
        public String process(Map.Entry<String, String> entry) {
            ((ExtendedMapEntry<String, String>) entry).setValue(value, seconds, TimeUnit.SECONDS);
            return value;
        }

        @Override
        public EntryProcessor<String, String, String> getBackupProcessor() {
            return new CopyValue(value);
        }
    }

    /** Blocks the first time it runs, anywhere, until released; later runs go straight through. */
    record BlockFirstRun(String value) implements EntryProcessor<String, String, String> {
        @Override
        public String process(Map.Entry<String, String> entry) {
            if (PROCESS_RUNS.incrementAndGet() == 1) {
                entered.countDown();
                try {
                    release.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            entry.setValue(value);
            return value;
        }
    }

    private HazelcastInstance caller;
    private HazelcastInstance owner;
    private String key;

    @BeforeEach
    void start() {
        PROCESS_RUNS.set(0);
        BACKUP_RUNS.set(0);
        entered = new CountDownLatch(1);
        release = new CountDownLatch(1);
        List<Config> configs = Spike.configs(Spike.name("q8"), 2);
        caller = Spike.start(configs.get(0));
        owner = Spike.start(configs.get(1));
        assertThat(Spike.await(20, () -> caller.getCluster().getMembers().size() == 2)).isTrue();
        key = Spike.keyOwnedBy(owner, "k");
        caller.getMap("m").put(key, "initial");
    }

    @AfterEach
    void stop() {
        release.countDown();
        Spike.shutdown(owner, caller);
    }

    /** Kills the primary and waits until the survivor owns the key. */
    private void killPrimary() {
        owner.getLifecycleService().terminate();
        String survivor = caller.getCluster().getLocalMember().getUuid().toString();
        assertThat(Spike.await(30, () -> {
            var partitionOwner = caller.getPartitionService().getPartition(key).getOwner();
            return partitionOwner != null && partitionOwner.getUuid().toString().equals(survivor);
        })).isTrue();
    }

    @Test
    void processRunsAgainOnTheBackupAndARandomValueDiffersFromThePrimarys() {
        IMap<String, String> map = caller.getMap("m");
        String onPrimary = map.executeOnKey(key, new RandomInProcess()).toString();
        assertThat(PROCESS_RUNS.get()).as("primary and backup both ran process").isEqualTo(2);
        killPrimary();
        assertThat(map.get(key)).isNotEqualTo(onPrimary).startsWith("sealed-");
    }

    @Test
    void aBackupProcessorReplacesTheReRunAndTheBackupHoldsThePrimarysBytes() {
        IMap<String, String> map = caller.getMap("m");
        String onPrimary = map.executeOnKey(key, new RandomThenCopyToBackup()).toString();
        assertThat(PROCESS_RUNS.get()).as("process ran on the primary only").isEqualTo(1);
        assertThat(BACKUP_RUNS.get()).as("the backup processor ran on the backup").isEqualTo(1);
        killPrimary();
        assertThat(map.get(key)).isEqualTo(onPrimary);
    }

    @Test
    void theBackupHoldsTheTtlSetOnThePrimaryWhenProcessRunsOnTheBackupToo() {
        IMap<String, String> map = caller.getMap("m");
        map.executeOnKey(key, new SetWithTtl("with-ttl", 600));
        killPrimary();
        EntryView<String, String> view = map.getEntryView(key);
        assertThat(view.getValue()).isEqualTo("with-ttl");
        assertThat(view.getTtl()).isEqualTo(TimeUnit.SECONDS.toMillis(600));
    }

    @Test
    void aBackupProcessorThatOnlyCopiesTheValueLeavesTheBackupWithoutTheTtl() {
        IMap<String, String> map = caller.getMap("m");
        map.executeOnKey(key, new SetWithTtlCopyToBackup("with-ttl", 600));
        assertThat(map.getEntryView(key).getTtl()).isEqualTo(TimeUnit.SECONDS.toMillis(600));
        killPrimary();
        EntryView<String, String> view = map.getEntryView(key);
        assertThat(view.getValue()).isEqualTo("with-ttl");
        // Long.MAX_VALUE is how a 5.7 entry view reports "no time to live".
        assertThat(view.getTtl()).isEqualTo(Long.MAX_VALUE);
    }

    @Test
    void afterTheOwnerIsKilledMidInvocationTheProcessorRunsAgainOnTheNewOwner() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            IMap<String, String> map = caller.getMap("m");
            Future<String> result = executor.submit(() -> map.executeOnKey(key, new BlockFirstRun("applied")));
            assertThat(entered.await(20, TimeUnit.SECONDS)).as("the processor started on the owner").isTrue();
            owner.getLifecycleService().terminate();
            release.countDown();
            assertThat(result.get(60, TimeUnit.SECONDS)).isEqualTo("applied");
            // The first run was held before it applied anything; the invocation was retried on the new owner.
            assertThat(PROCESS_RUNS.get()).isEqualTo(2);
            assertThat(map.get(key)).isEqualTo("applied");
        } finally {
            executor.shutdownNow();
        }
    }
}

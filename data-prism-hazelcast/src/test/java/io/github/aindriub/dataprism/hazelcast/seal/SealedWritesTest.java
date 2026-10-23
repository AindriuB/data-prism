package io.github.aindriub.dataprism.hazelcast.seal;

import com.hazelcast.config.Config;
import com.hazelcast.core.EntryView;
import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.core.HazelcastInstanceAware;
import com.hazelcast.map.EntryProcessor;
import com.hazelcast.map.IMap;
import io.github.aindriub.dataprism.core.metrics.PrivacyMetrics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Both processor shapes on a two-member cluster: the backup holds the primary's exact bytes, with the primary's time
 * to live, and a retried invocation neither re-seals nor under-counts. The backup is read by killing the primary and
 * reading from the survivor, which promotes its backup (spike 219, answer 8).
 */
class SealedWritesTest {

    private static final String MAP = "test.sealed.s1";
    private static final long TTL_MILLIS = 600_000;

    static final AtomicInteger RUNS = new AtomicInteger();
    static volatile CountDownLatch entered = new CountDownLatch(1);
    static volatile CountDownLatch release = new CountDownLatch(1);

    private HazelcastInstance caller;
    private HazelcastInstance owner;
    private Sealer callerSealer;
    private String key;

    @BeforeEach
    void start() throws IOException {
        RUNS.set(0);
        entered = new CountDownLatch(1);
        release = new CountDownLatch(1);
        int first = freePort();
        int second = freePort();
        String name = "sealed-writes-" + System.nanoTime();
        List<String> both = List.of("127.0.0.1:" + first, "127.0.0.1:" + second);
        caller = Hazelcast.newHazelcastInstance(config(name, first, both));
        owner = Hazelcast.newHazelcastInstance(config(name, second, both));
        assertThat(await(20, () -> caller.getCluster().getMembers().size() == 2)).isTrue();
        callerSealer = register(caller);
        register(owner);
        key = keyOwnedBy(owner);
    }

    @AfterEach
    void stop() {
        release.countDown();
        for (HazelcastInstance instance : new HazelcastInstance[]{owner, caller}) {
            if (instance != null) {
                try {
                    instance.getLifecycleService().terminate();
                } catch (RuntimeException ignored) {
                    // best effort
                }
            }
        }
    }

    @Test
    void aCompareAndSetLeavesTheSameBytesAndTheSameLifetimeOnThePrimaryAndTheBackup() {
        IMap<String, String> map = caller.getMap(MAP);
        String first = callerSealer.seal(MAP, key, bytes("one"));
        assertThat(map.executeOnKey(key, SealedWrites.compareAndSet(null, first, TTL_MILLIS))).isEqualTo(true);
        String second = callerSealer.seal(MAP, key, bytes("two"));
        assertThat(map.executeOnKey(key, SealedWrites.compareAndSet(first, second, TTL_MILLIS))).isEqualTo(true);
        assertThat(map.executeOnKey(key, SealedWrites.compareAndSet(first, "other", TTL_MILLIS)))
                .as("the stored value is not the expected one").isEqualTo(false);

        killOwner();
        EntryView<String, String> onBackup = map.getEntryView(key);
        assertThat(onBackup.getValue()).isEqualTo(second);
        assertThat(onBackup.getTtl()).isEqualTo(TTL_MILLIS);
    }

    @Test
    void aComputingWriteLeavesTheSameBytesAndTheSameLifetimeOnThePrimaryAndTheBackup() {
        IMap<String, String> map = caller.getMap(MAP);
        SealedWrites.Applied one = (SealedWrites.Applied) map.executeOnKey(key,
                SealedWrites.computing(MAP, new Increment()));
        SealedWrites.Applied two = (SealedWrites.Applied) map.executeOnKey(key,
                SealedWrites.computing(MAP, new Increment()));
        assertThat(two.result()).isEqualTo(2);
        assertThat(two.sealed()).isNotEqualTo(one.sealed());

        killOwner();
        EntryView<String, String> onBackup = map.getEntryView(key);
        assertThat(onBackup.getValue()).as("the backup holds the primary's bytes").isEqualTo(two.sealed());
        assertThat(onBackup.getTtl()).isEqualTo(TTL_MILLIS);
        Opened opened = callerSealer.open(MAP, key, onBackup.getValue());
        assertThat(opened.outcome()).isEqualTo(Opened.Outcome.AUTHENTIC);
        assertThat(new String(opened.plaintext(), StandardCharsets.UTF_8)).isEqualTo("2");
    }

    @Test
    void aComputingWriteOnAnEntryThatDoesNotOpenIsRefusedAndWritesNothing() {
        IMap<String, String> map = caller.getMap(MAP);
        map.put(key, "not-a-sealed-value");
        try {
            map.executeOnKey(key, SealedWrites.computing(MAP, new Increment()));
            throw new AssertionError("expected a refusal");
        } catch (RuntimeException refused) {
            assertThat(refused).isNotNull();
        }
        assertThat(map.get(key)).isEqualTo("not-a-sealed-value");
    }

    @Test
    void aCompareAndSetRetriedAfterTheOwnerWasKilledMidInvocationFindsItsOwnBytes() throws Exception {
        IMap<String, String> map = caller.getMap(MAP);
        String sealed = callerSealer.seal(MAP, key, bytes("applied"));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Object> result = executor.submit(() -> map.executeOnKey(key,
                    new BlockFirstRun<>(SealedWrites.compareAndSet(null, sealed, TTL_MILLIS))));
            assertThat(entered.await(20, TimeUnit.SECONDS)).as("the processor started on the owner").isTrue();
            owner.getLifecycleService().terminate();
            release.countDown();
            assertThat(result.get(60, TimeUnit.SECONDS)).isEqualTo(true);
            assertThat(RUNS.get()).as("the invocation was retried on the new owner").isEqualTo(2);
            assertThat(map.get(key)).isEqualTo(sealed);
            // The reply was lost after the write applied: the same processor sent again recognises its own bytes.
            assertThat(map.executeOnKey(key, SealedWrites.compareAndSet(null, sealed, TTL_MILLIS)))
                    .isEqualTo(true);
            assertThat(map.get(key)).isEqualTo(sealed);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void aComputingWriteRetriedAfterTheOwnerWasKilledMidInvocationNeverUnderCounts() throws Exception {
        IMap<String, String> map = caller.getMap(MAP);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Object> result = executor.submit(() -> map.executeOnKey(key,
                    new BlockFirstRun<>(SealedWrites.computing(MAP, new Increment()))));
            assertThat(entered.await(20, TimeUnit.SECONDS)).as("the processor started on the owner").isTrue();
            owner.getLifecycleService().terminate();
            release.countDown();
            SealedWrites.Applied applied = (SealedWrites.Applied) result.get(60, TimeUnit.SECONDS);
            assertThat((Integer) applied.result()).isGreaterThanOrEqualTo(1);
            // The reply was lost after the write applied: running the same step again adds again, never less.
            SealedWrites.Applied again = (SealedWrites.Applied) map.executeOnKey(key,
                    SealedWrites.computing(MAP, new Increment()));
            assertThat((Integer) again.result()).isGreaterThan((Integer) applied.result());
            Opened stored = callerSealer.open(MAP, key, map.get(key));
            assertThat(new String(stored.plaintext(), StandardCharsets.UTF_8))
                    .isEqualTo(String.valueOf(again.result()));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void aProcessorRunOverTwoKeysFailsInsteadOfCorruptingABackup() {
        IMap<String, String> map = caller.getMap(MAP);
        String second = null;
        for (int i = 0; i < 10_000 && second == null; i++) {
            String candidate = key + "-x" + i;
            if (caller.getPartitionService().getPartition(candidate).getPartitionId()
                    == caller.getPartitionService().getPartition(key).getPartitionId()) {
                second = candidate;
            }
        }
        assertThat(second).isNotNull();
        final String samePartition = second;
        map.put(key, callerSealer.seal(MAP, key, bytes("0")));
        map.put(samePartition, callerSealer.seal(MAP, samePartition, bytes("0")));
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> map.executeOnKeys(java.util.Set.of(key, samePartition),
                        SealedWrites.computing(MAP, new Increment())))
                .isInstanceOf(RuntimeException.class);
        killOwner();
        // Whatever the failed call left, every surviving value still opens.
        for (String k : List.of(key, samePartition)) {
            assertThat(callerSealer.open(MAP, k, map.get(k)).outcome()).isEqualTo(Opened.Outcome.AUTHENTIC);
        }
    }

    @Test
    void anUnchangedPlanLeavesPrimaryAndBackupByteIdenticalAndSealsNothing() {
        IMap<String, String> map = caller.getMap(MAP);
        String sealed = callerSealer.seal(MAP, key, bytes("kept"));
        map.put(key, sealed);
        SealedWrites.Applied applied = (SealedWrites.Applied) map.executeOnKey(key,
                SealedWrites.computing(MAP, current -> SealedWrites.Plan.unchanged("same")));
        assertThat(applied.sealed()).isNull();
        assertThat(applied.result()).isEqualTo("same");
        assertThat(map.get(key)).isEqualTo(sealed);
        killOwner();
        assertThat(map.get(key)).isEqualTo(sealed);
    }

    @Test
    void aSecondRegistrationOnOneInstanceNeitherReplacesNorLosesTheFirstOnClose() {
        ClusterKeys keys = Fixtures.keys();
        Sealer other = Sealer.builder(keys, PrivacyMetrics.none()).build();
        SealedWrites.register(caller, other, new KeyedNames(keys)); // second wrapper; counted only
        assertThat(SealedWrites.sealerOf(caller)).isSameAs(callerSealer);
        SealedWrites.unregister(caller); // the second wrapper closes
        IMap<String, String> map = caller.getMap(MAP);
        SealedWrites.Applied applied = (SealedWrites.Applied) map.executeOnKey(key,
                SealedWrites.computing(MAP, new Increment()));
        assertThat(applied.result()).isEqualTo(1);
        SealedWrites.unregister(caller); // the first closes: now gone
        assertThat(SealedWrites.sealerOf(caller)).isNull();
    }

    /** Adds one to the decimal count in the stored plaintext. */
    record Increment() implements SealedWrites.Step {
        @Override
        public SealedWrites.Plan apply(byte[] current) {
            int next = current == null ? 1 : Integer.parseInt(new String(current, StandardCharsets.UTF_8)) + 1;
            return SealedWrites.Plan.write(bytes(Integer.toString(next)), TTL_MILLIS, next);
        }
    }

    /** Blocks the first run, anywhere, until released; later runs go straight through. */
    static final class BlockFirstRun<R> implements EntryProcessor<String, String, R>, HazelcastInstanceAware {
        private final EntryProcessor<String, String, R> inner;

        BlockFirstRun(EntryProcessor<String, String, R> inner) {
            this.inner = inner;
        }

        @Override
        public void setHazelcastInstance(HazelcastInstance instance) {
            if (inner instanceof HazelcastInstanceAware aware) {
                aware.setHazelcastInstance(instance);
            }
        }

        @Override
        public R process(Map.Entry<String, String> entry) {
            if (RUNS.incrementAndGet() == 1) {
                entered.countDown();
                try {
                    release.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            return inner.process(entry);
        }

        @Override
        public EntryProcessor<String, String, R> getBackupProcessor() {
            return inner.getBackupProcessor();
        }
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static Sealer register(HazelcastInstance instance) {
        ClusterKeys keys = Fixtures.keys();
        Sealer sealer = Sealer.builder(keys, PrivacyMetrics.none()).build();
        SealedWrites.register(instance, sealer, new KeyedNames(keys));
        return sealer;
    }

    private void killOwner() {
        owner.getLifecycleService().terminate();
        String survivor = caller.getCluster().getLocalMember().getUuid().toString();
        assertThat(await(30, () -> {
            var partitionOwner = caller.getPartitionService().getPartition(key).getOwner();
            return partitionOwner != null && partitionOwner.getUuid().toString().equals(survivor);
        })).isTrue();
    }

    private String keyOwnedBy(HazelcastInstance member) {
        String uuid = member.getCluster().getLocalMember().getUuid().toString();
        caller.getMap("partition-warmup").put("w", "w");
        assertThat(await(20, () -> member.getPartitionService().getPartition("warm").getOwner() != null)).isTrue();
        for (int i = 0; i < 10_000; i++) {
            String candidate = "k-" + i;
            if (member.getPartitionService().getPartition(candidate).getOwner().getUuid().toString().equals(uuid)) {
                return candidate;
            }
        }
        throw new IllegalStateException("no key owned by the member");
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        }
    }

    private static Config config(String clusterName, int port, List<String> peers) {
        Config config = new Config();
        config.setClusterName(clusterName);
        config.getJetConfig().setEnabled(false);
        config.setProperty("hazelcast.phone.home.enabled", "false");
        config.setProperty("hazelcast.logging.type", "jdk");
        config.setProperty("hazelcast.operation.call.timeout.millis", "5000");
        config.setProperty("hazelcast.operation.backup.timeout.millis", "5000");
        config.getNetworkConfig().setPort(port).setPortAutoIncrement(false);
        var join = config.getNetworkConfig().getJoin();
        join.getAutoDetectionConfig().setEnabled(false);
        join.getMulticastConfig().setEnabled(false);
        join.getTcpIpConfig().setEnabled(true).setMembers(peers);
        config.getNetworkConfig().getInterfaces().setEnabled(true).addInterface("127.0.0.1");
        config.setProperty("hazelcast.socket.bind.any", "false");
        config.getMapConfig(MAP).setBackupCount(1);
        return config;
    }

    private static boolean await(long seconds, BooleanSupplier condition) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(50));
        }
        return condition.getAsBoolean();
    }
}

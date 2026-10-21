package io.github.aindriub.dataprism.hazelcast.spike;

import com.hazelcast.config.Config;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.map.IMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Question 10: against a key that another member or a client holds locked, which of {@code tryPut(.., timeout)},
 * {@code tryRemove(.., timeout)}, {@code replace(key, old, new)} and {@code remove(key, value)} wait without bound
 * and which return within their timeout? "Without bound" is shown as still waiting after 8 seconds, past the 5 s
 * {@code hazelcast.operation.call.timeout.millis} these members are configured with and with a timeout of 300 ms, and completing once the lock is released.
 */
class Q10LockedKeySpikeTest {

    private HazelcastInstance caller;
    private HazelcastInstance otherMember;
    private HazelcastInstance client;
    private String key;
    private ExecutorService executor;

    @BeforeEach
    void start() {
        List<Config> configs = Spike.configs(Spike.name("q10"), 2);
        caller = Spike.start(configs.get(0));
        otherMember = Spike.start(configs.get(1));
        assertThat(Spike.await(20, () -> caller.getCluster().getMembers().size() == 2)).isTrue();
        client = Spike.client(configs.get(0).getClusterName(), configs.get(0).getNetworkConfig().getPort());
        key = Spike.keyOwnedBy(caller, "k");
        caller.getMap("m").put(key, "old");
        executor = Executors.newSingleThreadExecutor();
    }

    @AfterEach
    void stop() {
        executor.shutdownNow();
        Spike.shutdown(client, otherMember, caller);
    }

    private enum Holder { MEMBER, CLIENT }

    /** "returned X" if the call returned within 8 s while the key stayed locked; else "waits" once it finished after the unlock. */
    private String probe(Holder holder, Callable<Object> call) throws Exception {
        HazelcastInstance locker = holder == Holder.MEMBER ? otherMember : client;
        IMap<String, String> held = locker.getMap("m");
        held.lock(key);
        long start = System.nanoTime();
        Future<Object> result = executor.submit(call);
        try {
            Object returned = result.get(8, TimeUnit.SECONDS);
            elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            return "returned " + returned;
        } catch (TimeoutException stillWaiting) {
            held.forceUnlock(key);
            Object afterUnlock = result.get(20, TimeUnit.SECONDS);
            return "waits, then returned " + afterUnlock;
        } finally {
            if (held.isLocked(key)) {
                held.forceUnlock(key);
            }
        }
    }

    private long elapsedMillis;

    private IMap<String, String> map() {
        return caller.getMap("m");
    }

    @Test
    void tryPutReturnsFalseWithinItsTimeoutOnAKeyAMemberHoldsLocked() throws Exception {
        assertThat(probe(Holder.MEMBER, () -> map().tryPut(key, "new", 300, TimeUnit.MILLISECONDS)))
                .isEqualTo("returned false");
        assertThat(elapsedMillis).as("a 300 ms timeout returned within 2 s").isLessThan(2000);
    }

    @Test
    void tryRemoveReturnsFalseWithinItsTimeoutOnAKeyAMemberHoldsLocked() throws Exception {
        assertThat(probe(Holder.MEMBER, () -> map().tryRemove(key, 300, TimeUnit.MILLISECONDS)))
                .isEqualTo("returned false");
        assertThat(elapsedMillis).as("a 300 ms timeout returned within 2 s").isLessThan(2000);
    }

    @Test
    void replaceWaitsWithoutBoundOnAKeyAMemberHoldsLocked() throws Exception {
        assertThat(probe(Holder.MEMBER, () -> map().replace(key, "old", "new")))
                .isEqualTo("waits, then returned true");
    }

    @Test
    void removeByValueWaitsWithoutBoundOnAKeyAMemberHoldsLocked() throws Exception {
        assertThat(probe(Holder.MEMBER, () -> map().remove(key, "old"))).isEqualTo("waits, then returned true");
    }

    @Test
    void tryPutReturnsFalseWithinItsTimeoutOnAKeyAClientHoldsLocked() throws Exception {
        assertThat(probe(Holder.CLIENT, () -> map().tryPut(key, "new", 300, TimeUnit.MILLISECONDS)))
                .isEqualTo("returned false");
        assertThat(elapsedMillis).as("a 300 ms timeout returned within 2 s").isLessThan(2000);
    }

    @Test
    void tryRemoveReturnsFalseWithinItsTimeoutOnAKeyAClientHoldsLocked() throws Exception {
        assertThat(probe(Holder.CLIENT, () -> map().tryRemove(key, 300, TimeUnit.MILLISECONDS)))
                .isEqualTo("returned false");
        assertThat(elapsedMillis).as("a 300 ms timeout returned within 2 s").isLessThan(2000);
    }

    @Test
    void replaceWaitsWithoutBoundOnAKeyAClientHoldsLocked() throws Exception {
        assertThat(probe(Holder.CLIENT, () -> map().replace(key, "old", "new")))
                .isEqualTo("waits, then returned true");
    }

    @Test
    void removeByValueWaitsWithoutBoundOnAKeyAClientHoldsLocked() throws Exception {
        assertThat(probe(Holder.CLIENT, () -> map().remove(key, "old"))).isEqualTo("waits, then returned true");
    }
}

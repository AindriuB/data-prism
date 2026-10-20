package io.github.aindriub.dataprism.hazelcast.spike;

import com.hazelcast.config.ClassFilter;
import com.hazelcast.config.Config;
import com.hazelcast.config.JavaSerializationFilterConfig;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.map.EntryProcessor;
import com.hazelcast.map.IMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Question 4: can {@code java-serialization-filter} with {@code defaults-disabled} and an allowlist of String,
 * Integer and Boolean run every operation Data Prism uses, and what fails when a class is not listed?
 */
class Q04SerializationFilterSpikeTest {

    /** Mirrors the shape of Data Prism's entry processors: a Serializable record of Strings and a long. */
    record Fields(String expected, String next, long ttlMillis) implements EntryProcessor<String, String, Boolean> {
        @Override
        public Boolean process(Map.Entry<String, String> entry) {
            entry.setValue(next);
            return true;
        }
    }

    /** As above, with a java.time field, as the sweep processor has. */
    record WithInstant(Instant now, long slackMillis) implements EntryProcessor<String, String, Integer> {
        @Override
        public Integer process(Map.Entry<String, String> entry) {
            return entry.getValue().length();
        }
    }

    private static final List<String> THREE = List.of("java.lang.String", "java.lang.Integer", "java.lang.Boolean");

    private HazelcastInstance first;
    private HazelcastInstance second;
    private HazelcastInstance client;

    @AfterEach
    void stop() {
        Spike.shutdown(client, second, first);
    }

    private static Config filtered(Config config, List<String> classes, List<String> prefixes) {
        ClassFilter allow = new ClassFilter().setClasses(classes).setPrefixes(prefixes);
        config.getSerializationConfig().setJavaSerializationFilterConfig(
                new JavaSerializationFilterConfig().setDefaultsDisabled(true).setWhitelist(allow));
        return config;
    }

    private void startPair(List<String> classes, List<String> prefixes) {
        List<Config> configs = Spike.configs(Spike.name("q4"), 2);
        first = Spike.start(filtered(configs.get(0), classes, prefixes));
        second = Spike.start(filtered(configs.get(1), classes, prefixes));
        assertThat(Spike.await(20, () -> first.getCluster().getMembers().size() == 2)).isTrue();
    }

    @Test
    void membersJoinAndMapsAndLocksWorkWithOnlyTheThreeClassesListed() {
        startPair(THREE, List.of());
        IMap<String, Object> map = first.getMap("m");
        String remote = Spike.keyOwnedBy(second, "k");
        map.put(remote, "text");
        map.put(remote + "i", 7);
        map.put(remote + "b", true);
        assertThat(second.getMap("m").get(remote)).isEqualTo("text");
        assertThat(second.getMap("m").get(remote + "i")).isEqualTo(7);
        assertThat(second.getMap("m").get(remote + "b")).isEqualTo(true);
        assertThat(map.tryLock(remote)).isTrue();
        map.unlock(remote);
        assertThat(map.containsKey(remote)).isTrue();
    }

    private static final String SPIKE_PACKAGE = "io.github.aindriub.dataprism.hazelcast.spike.";

    @Test
    void anEntryProcessorOnARemoteKeyTimesOutWithOnlyTheThreeClassesListed() {
        startPair(THREE, List.of());
        IMap<String, String> map = first.getMap("m");
        String remote = Spike.keyOwnedBy(second, "k");
        map.put(remote, "a");
        // The owner refuses the processor class, but its error reply is itself a Java-serialised exception that
        // the caller's filter then refuses, so the caller never gets an answer and times out.
        assertThat(describe(catchThrowable(() -> map.executeOnKey(remote, new Fields("a", "b", 1)))))
                .startsWith("OperationTimeoutException");
        assertThat(map.get(remote)).isEqualTo("a");
    }

    @Test
    void aProcessorOfStringsAndLongsRunsOnceItsPackagePrefixIsListed() {
        startPair(THREE, List.of(SPIKE_PACKAGE));
        IMap<String, String> map = first.getMap("m");
        String remote = Spike.keyOwnedBy(second, "k");
        map.put(remote, "a");
        assertThat(map.executeOnKey(remote, new Fields("a", "b", 1))).isEqualTo(true);
        assertThat(second.getMap("m").get(remote)).isEqualTo("b");
    }

    @Test
    void aProcessorHoldingAnInstantNeedsJavaTimeSerListedAsWell() {
        startPair(THREE, List.of(SPIKE_PACKAGE));
        IMap<String, String> map = first.getMap("m");
        String remote = Spike.keyOwnedBy(second, "k");
        map.put(remote, "a");
        assertThat(describe(catchThrowable(() -> map.executeOnKey(remote, new WithInstant(Instant.EPOCH, 1)))))
                .startsWith("OperationTimeoutException");
        Spike.shutdown(second, first);

        startPair(THREE, List.of(SPIKE_PACKAGE, "java.time.Ser"));
        IMap<String, String> again = first.getMap("m");
        String owned = Spike.keyOwnedBy(second, "k");
        again.put(owned, "a");
        assertThat(again.executeOnKey(owned, new WithInstant(Instant.EPOCH, 1))).isEqualTo(1);
    }

    @Test
    void aDisallowedClassFromAKeylessClientFailsWithASecurityException() {
        startPair(THREE, List.of());
        client = Spike.client(first.getConfig().getClusterName(), first.getConfig().getNetworkConfig().getPort());
        IMap<String, String> map = client.getMap("m");
        String remote = Spike.keyOwnedBy(second, "k");
        Throwable thrown = catchThrowable(() -> map.executeOnKey(remote, new Fields("a", "b", 1)));
        assertThat(describe(thrown)).startsWith("HazelcastSerializationException")
                .contains("SecurityException: Resolving class");
    }

    @Test
    void aSplitBrainMergeRunsWithOnlyTheThreeClassesListed() {
        List<Config> configs = Spike.splitPair(Spike.name("q4merge"));
        for (Config config : configs) {
            filtered(config, THREE, List.of()).getMapConfig("m").getMergePolicyConfig()
                    .setPolicy("PutIfAbsentMergePolicy");
        }
        first = Spike.start(configs.get(0));
        second = Spike.start(configs.get(1));
        first.getMap("m").put("k", "on-first");
        second.getMap("m").put("k", "on-second");
        assertThat(Spike.await(60, () -> first.getCluster().getMembers().size() == 2
                && second.getCluster().getMembers().size() == 2)).as("the clusters merged").isTrue();
        assertThat(Spike.await(30, () -> first.getMap("m").get("k").equals(second.getMap("m").get("k"))))
                .as("both members agree on one value").isTrue();
        assertThat(first.getMap("m").get("k")).isIn("on-first", "on-second");
    }

    private static String describe(Throwable t) {
        StringBuilder out = new StringBuilder();
        for (Throwable c = t; c != null && out.length() < 600; c = c.getCause()) {
            out.append(c.getClass().getSimpleName()).append(": ").append(c.getMessage()).append(" <- ");
        }
        return out.toString();
    }
}

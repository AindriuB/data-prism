package io.github.aindriub.dataprism.hazelcast.boundary;

import com.hazelcast.config.EvictionPolicy;
import com.hazelcast.config.MapConfig;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.map.IMap;
import io.github.aindriub.dataprism.core.metrics.PrivacyMetrics;
import io.github.aindriub.dataprism.hazelcast.PrivacyCluster;
import io.github.aindriub.dataprism.hazelcast.seal.ClusterKeys;
import io.github.aindriub.dataprism.hazelcast.seal.KeyedNames;
import io.github.aindriub.dataprism.hazelcast.seal.Opened;
import io.github.aindriub.dataprism.hazelcast.seal.Sealer;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The pause map holds exactly one entry, the pause document of task 228: a sealed envelope under the keyed name of a
 * fixed canonical string. The scope id and the tool name appear only inside its plaintext, never in the key or in
 * the envelope text. A member that holds the shared test keys can open it; a keyless solo member seals under keys
 * drawn at random, which no test holds, so for it only the shape is checked (see {@link #opensUnderTestKey}).
 */
public final class OversightMapsBoundary implements AreaBoundary {

    /** The canonical string of the document's keyed name; recomputed here, not read from the code under test. */
    private static final String DOCUMENT_CANONICAL = "pause-document";

    /** The 0.7 oversight map, which keeps the fence entry. */
    private static final String FENCE_MAP = "dataprism.oversight";

    /** The test keys: fixed bytes, never real key material. The ids and bytes are those of the shared test keys. */
    private static ClusterKeys testKeys() {
        byte[] cluster = new byte[32];
        byte[] name = new byte[32];
        Arrays.fill(cluster, (byte) 'c');
        Arrays.fill(name, (byte) 'n');
        return new ClusterKeys("test-cluster-1", cluster, null, null, null, null, "test-name-1", name);
    }

    @Override
    public Set<String> maps() {
        return Set.of(PrivacyCluster.OVERSIGHT_MAP);
    }

    @Override
    public void check(String map, IMap<Object, Object> contents, Inputs in) {
        String key = in.names().mapKey(PrivacyCluster.OVERSIGHT_MAP, DOCUMENT_CANONICAL);
        in.assertKeys(map, contents, key);
        Object value = contents.get(key);
        assertThat(value).as("%s value", map).isInstanceOf(String.class);
        String envelope = (String) value;
        assertThat(envelope).as("%s envelope", map).startsWith("dps1.");
        for (String name : new String[]{in.scopeId(), in.tool()}) {
            assertThat(envelope).as("%s envelope must not contain a scope or tool name", map).doesNotContain(name);
        }
        Opened opened = Sealer.builder(testKeys(), PrivacyMetrics.none()).build().open(map, key, envelope);
        if (namesAreTheTestNames(in)) {
            // The cluster under test derived its names from the shared test name key, so it holds the shared test
            // keys, and its document must open under them. A document that does not is a failure, never a skip.
            assertThat(opened.outcome()).as("%s envelope must open under the test key", map)
                    .isEqualTo(Opened.Outcome.AUTHENTIC);
        }
        if (opened.outcome() == Opened.Outcome.AUTHENTIC) {
            // The names are in the plaintext, which only a key holder can read.
            String plaintext = new String(opened.plaintext(), StandardCharsets.UTF_8);
            assertThat(plaintext).contains(in.scopeId()).contains(in.tool());
        }
    }

    /** Whether the keyed names under test are those of the shared test name key (a solo member's are random). */
    private static boolean namesAreTheTestNames(Inputs in) {
        KeyedNames test = new KeyedNames(testKeys());
        return test.mapKey(PrivacyCluster.OVERSIGHT_MAP, DOCUMENT_CANONICAL)
                .equals(in.names().mapKey(PrivacyCluster.OVERSIGHT_MAP, DOCUMENT_CANONICAL));
    }

    /**
     * Both oversight maps, the sealed one and the 0.7 map that holds the fence, are configured never to evict and
     * never to expire: losing the document or the fence reopens a paused path. Reads the effective config of the
     * member, so it fails if an eviction policy or a time to live is set, explicitly or by a default.
     */
    public static void checkMapConfigs(HazelcastInstance instance) {
        for (String name : new String[]{PrivacyCluster.OVERSIGHT_MAP, FENCE_MAP}) {
            MapConfig config = instance.getConfig().findMapConfig(name);
            assertThat(config.getEvictionConfig().getEvictionPolicy()).as("%s eviction", name)
                    .isEqualTo(EvictionPolicy.NONE);
            assertThat(config.getTimeToLiveSeconds()).as("%s time to live", name).isZero();
            assertThat(config.getMaxIdleSeconds()).as("%s max idle", name).isZero();
        }
    }

    /** Whether the document in {@code contents} opens under the shared test keys. */
    public static boolean opensUnderTestKey(IMap<Object, Object> contents, Inputs in) {
        String key = in.names().mapKey(PrivacyCluster.OVERSIGHT_MAP, DOCUMENT_CANONICAL);
        return contents.get(key) instanceof String envelope
                && Sealer.builder(testKeys(), PrivacyMetrics.none()).build()
                .open(PrivacyCluster.OVERSIGHT_MAP, key, envelope).outcome() == Opened.Outcome.AUTHENTIC;
    }
}

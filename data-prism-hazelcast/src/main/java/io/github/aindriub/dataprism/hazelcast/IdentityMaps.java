package io.github.aindriub.dataprism.hazelcast;

import com.hazelcast.config.Config;
import com.hazelcast.config.MapConfig;

import io.github.aindriub.dataprism.core.metrics.PrivacyMetrics;
import io.github.aindriub.dataprism.hazelcast.seal.ClusterKeys;
import io.github.aindriub.dataprism.hazelcast.seal.Opened;
import io.github.aindriub.dataprism.hazelcast.seal.Sealer;
import io.github.aindriub.dataprism.hazelcast.seal.ServingGate;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

/**
 * The identity cache and the re-identification index. Both use the common map settings and nothing more: an
 * evicted identity entry costs a recomputed value, and an evicted re-identification entry is the documented
 * durability limit of the embedded cluster.
 *
 * <p>Both maps hold only {@code dps1} envelopes (docs/plan/design/cluster-security.md 1.1, 1.2). The plaintext of
 * an envelope is the canonical key parts and the value, length-prefixed, so a value opened for a key can be checked
 * to belong to the parts that key was derived from. Values are sealed and opened under the current cluster key
 * only: a cluster-key change is a planned full restart that empties both maps.
 */
final class IdentityMaps {

    static final List<String> NAMES = List.of(PrivacyCluster.IDENTITY_MAP, PrivacyCluster.REIDENTIFICATION_MAP);

    private IdentityMaps() {
    }

    static void configure(Config config) {
        NAMES.forEach(name -> config.addMapConfig(PrivacyCluster.privacyMap(new MapConfig(name))));
    }

    private static final String SOLO_KEYS_CONTEXT_KEY = "dataprism.cluster.solo.keys";

    /**
     * A sealer for this cluster's keys that counts into {@code metrics}. A keyless solo member seals under the
     * random keys {@link PrivacyCluster} drew for its instance.
     */
    static Sealer sealerFor(PrivacyCluster cluster, PrivacyMetrics metrics, ServingGate gate) {
        ClusterKeys keys = cluster.keys().orElseGet(() -> (ClusterKeys) cluster.instance().getUserContext()
                .get(SOLO_KEYS_CONTEXT_KEY));
        if (keys == null) {
            throw new IllegalStateException("no cluster keys are available to seal with");
        }
        return Sealer.builder(keys, metrics).servingGate(gate).build();
    }

    /**
     * Opens an envelope read from the re-identification map and returns its subject. The one decrypt-and-return
     * site of the index: only {@link ScopeIdentityIndex} calls it (architecture rule T14, second rule).
     *
     * @throws io.github.aindriub.dataprism.core.refusal.ClusterOperationRefusedException if the member is not serving
     */
    static Optional<String> subjectOf(Sealer sealer, String key, String stored, String canonical) {
        return valueOf(sealer.open(PrivacyCluster.REIDENTIFICATION_MAP, key, stored), canonical);
    }

    /** {@code canonical} and {@code value}, each preceded by its length. */
    static byte[] plaintext(String canonical, String value) {
        byte[] c = canonical.getBytes(StandardCharsets.UTF_8);
        byte[] v = value.getBytes(StandardCharsets.UTF_8);
        return ByteBuffer.allocate(8 + c.length + v.length).putInt(c.length).put(c).putInt(v.length).put(v).array();
    }

    /**
     * The value of an authentic envelope whose canonical key parts are {@code canonical}; empty for anything
     * else, including an authentic envelope sealed for other parts.
     */
    static Optional<String> valueOf(Opened opened, String canonical) {
        if (opened.outcome() != Opened.Outcome.AUTHENTIC) {
            return Optional.empty();
        }
        try {
            ByteBuffer in = ByteBuffer.wrap(opened.plaintext());
            byte[] c = new byte[in.getInt()];
            in.get(c);
            byte[] v = new byte[in.getInt()];
            in.get(v);
            if (in.hasRemaining() || !canonical.equals(new String(c, StandardCharsets.UTF_8))) {
                return Optional.empty();
            }
            return Optional.of(new String(v, StandardCharsets.UTF_8));
        } catch (RuntimeException malformed) {
            return Optional.empty();
        }
    }
}

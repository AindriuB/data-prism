package io.github.aindriub.dataprism.hazelcast;

import com.hazelcast.map.IMap;
import io.github.aindriub.dataprism.hazelcast.seal.ClusterKeys;
import io.github.aindriub.dataprism.hazelcast.seal.Opened;
import io.github.aindriub.dataprism.hazelcast.seal.Sealer;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Objects;

/**
 * The sentinel of docs/plan/design/cluster-security.md 4.7: a sealed constant that the first member writes, under
 * the reserved key {@value #KEY} of the member-card map, and every later member reads. A member whose keys do not
 * open it cannot read the cluster, so it refuses to join ({@code CLUSTER_KEY_MISMATCH}) and never gets a card.
 *
 * <p>Which held key opens it decides the outcome (4.6): {@code current} is in step; {@code previous} means this
 * member is ahead and still joins; {@code next} means it is behind and still joins; none is a mismatch. This class
 * writes the sentinel only when it is absent, and only under {@code current}: a {@code next} key is never written,
 * and re-sealing the sentinel when the key advances is the rotation task's, not this one's.
 *
 * <p>It seals with a sealer that has no serving gate, because a closed gate must not stop a member proving itself.
 */
final class ClusterSentinel {

    /** The reserved map key. A keyed name is 43 characters, so no member card can have this key. */
    static final String KEY = "sentinel";

    private static final byte[] CONSTANT = "dp-sentinel".getBytes(StandardCharsets.UTF_8);

    /** What the sentinel in the cluster says about this member's keys. */
    enum Outcome {
        /** No sentinel is stored. */
        ABSENT,
        /** It opens under the member's {@code current} key. */
        CURRENT,
        /** It opens under the member's {@code previous} key: this member is ahead. */
        PREVIOUS,
        /** It opens under the member's {@code next} key: this member is behind. */
        NEXT,
        /** It opens under no held key, or is not a sentinel. */
        MISMATCH
    }

    private final PrivacyCluster cluster;
    private final ClusterKeys keys;
    private final Sealer sealer;

    ClusterSentinel(PrivacyCluster cluster, ClusterKeys keys, Sealer sealer) {
        this.cluster = Objects.requireNonNull(cluster, "cluster");
        this.keys = Objects.requireNonNull(keys, "keys");
        this.sealer = Objects.requireNonNull(sealer, "sealer");
    }

    private IMap<String, String> map() {
        return cluster.instance().getMap(PrivacyCluster.MEMBER_CARD_MAP);
    }

    /** Reads the sentinel and reports which held key opens it; writes nothing. */
    Outcome read() {
        return outcomeOf(map().get(KEY));
    }

    /**
     * Reads the sentinel and, if there is none, writes it under {@code current} with {@code putIfAbsent}; a
     * sentinel that another member wrote in the meantime is the one judged.
     */
    Outcome ensure() {
        IMap<String, String> cards = map();
        String stored = cards.get(KEY);
        if (stored == null) {
            String sealed = sealer.seal(PrivacyCluster.MEMBER_CARD_MAP, KEY, CONSTANT);
            stored = cards.putIfAbsent(KEY, sealed);
            if (stored == null) {
                return Outcome.CURRENT;
            }
        }
        return outcomeOf(stored);
    }

    private Outcome outcomeOf(String stored) {
        if (stored == null) {
            return Outcome.ABSENT;
        }
        Opened opened = sealer.open(PrivacyCluster.MEMBER_CARD_MAP, KEY, stored);
        if (opened.outcome() != Opened.Outcome.AUTHENTIC
                || !MessageDigest.isEqual(CONSTANT, opened.plaintext())) {
            return Outcome.MISMATCH;
        }
        String id = opened.keyId();
        if (id.equals(keys.current().id())) {
            return Outcome.CURRENT;
        }
        if (keys.previous().map(ClusterKeys.Key::id).filter(id::equals).isPresent()) {
            return Outcome.PREVIOUS;
        }
        if (keys.next().map(ClusterKeys.Key::id).filter(id::equals).isPresent()) {
            return Outcome.NEXT;
        }
        return Outcome.MISMATCH;
    }
}

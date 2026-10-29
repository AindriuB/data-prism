package io.github.aindriub.dataprism.hazelcast.boundary;

import com.hazelcast.map.IMap;
import io.github.aindriub.dataprism.core.metrics.PrivacyMetrics;
import io.github.aindriub.dataprism.hazelcast.PrivacyCluster;
import io.github.aindriub.dataprism.hazelcast.seal.ClusterKeys;
import io.github.aindriub.dataprism.hazelcast.seal.Opened;
import io.github.aindriub.dataprism.hazelcast.seal.Sealer;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The member-card map. A member without keys is solo and publishes nothing, so the map may be empty. Otherwise every
 * value is an envelope that opens under the test key (the fixed test bytes the multi-member tests share), the
 * sentinel sits at the reserved key and nowhere else, and every other key is the keyed name of the member UUID inside
 * its own card, recomputed here from the UUID and not read back from the code under test.
 */
public final class MemberCardMapsBoundary implements AreaBoundary {

    /** The reserved key of the sentinel (design 1.9 and 4.7). */
    private static final String SENTINEL_KEY = "sentinel";
    private static final String SENTINEL_PLAINTEXT = "dp-sentinel";

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
        return Set.of(PrivacyCluster.MEMBER_CARD_MAP);
    }

    @Override
    public void check(String map, IMap<Object, Object> contents, Inputs in) {
        if (contents.isEmpty()) {
            return;
        }
        Sealer sealer = Sealer.builder(testKeys(), PrivacyMetrics.none()).build();
        int sentinels = 0;
        for (var entry : contents.entrySet()) {
            assertThat(entry.getKey()).as("%s key", map).isInstanceOf(String.class);
            assertThat(entry.getValue()).as("%s value", map).isInstanceOf(String.class);
            String key = (String) entry.getKey();
            Opened opened = sealer.open(map, key, (String) entry.getValue());
            assertThat(opened.outcome()).as("%s value opens under the test key", map)
                    .isEqualTo(Opened.Outcome.AUTHENTIC);
            boolean isSentinel = new String(opened.plaintext(), StandardCharsets.UTF_8).equals(SENTINEL_PLAINTEXT);
            if (key.equals(SENTINEL_KEY)) {
                assertThat(isSentinel).as("the reserved key holds the sentinel").isTrue();
                sentinels++;
            } else {
                assertThat(isSentinel).as("the sentinel sits only at the reserved key").isFalse();
                assertThat(key).as("a card key is a keyed name").hasSize(43).doesNotContain(".");
                assertThat(key).as("a card key is the keyed name of the member UUID in the card")
                        .isEqualTo(in.names().mapKey(map, memberOf(opened.plaintext())));
            }
        }
        assertThat(sentinels).as("one sentinel").isLessThanOrEqualTo(1);
    }

    /** The member UUID at the start of a card: the magic, the format version, then the UUID, as the card writes them. */
    private static String memberOf(byte[] card) {
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(card));
            assertThat(in.readUTF()).as("a card starts with its magic").isEqualTo("dpcard");
            in.readInt();
            return in.readUTF();
        } catch (IOException unreadable) {
            throw new AssertionError("a card could not be read", unreadable);
        }
    }
}

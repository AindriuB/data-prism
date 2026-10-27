package io.github.aindriub.dataprism.hazelcast.boundary;

import com.hazelcast.map.IMap;
import io.github.aindriub.dataprism.core.model.PrivacyContext;
import io.github.aindriub.dataprism.core.model.PrivacyScopeType;
import io.github.aindriub.dataprism.core.model.PseudonymisationVersion;
import io.github.aindriub.dataprism.hazelcast.PrivacyCluster;
import io.github.aindriub.dataprism.hazelcast.seal.Opened;
import io.github.aindriub.dataprism.hazelcast.seal.Sealer;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The identity and re-identification maps: keyed names, and values that are {@code dps1} envelopes. Each opens under
 * the member's key, and its decrypted parts recompute the keyed name and the value the generator reproduces.
 */
public final class IdentityMapsBoundary implements AreaBoundary {

    @Override
    public Set<String> maps() {
        return Set.of(PrivacyCluster.IDENTITY_MAP, PrivacyCluster.REIDENTIFICATION_MAP);
    }

    @Override
    public void check(String map, IMap<Object, Object> contents, Inputs in) {
        PrivacyContext context = new PrivacyContext(in.scopeId(), PrivacyScopeType.CASE, "DEFAULT", "test",
                Instant.now().plus(1, ChronoUnit.HOURS), PseudonymisationVersion.HMAC_SHA256_V1);
        Sealer sealer = (Sealer) ((com.hazelcast.spi.impl.AbstractDistributedObject<?>) contents).getNodeEngine()
                .getHazelcastInstance().getUserContext().get("dataprism.cluster.sealer");
        assertThat(sealer).as("the member's sealer").isNotNull();
        char nul = 0;
        if (map.equals(PrivacyCluster.IDENTITY_MAP)) {
            in.assertKeys(map, contents, in.identityKey());
            String[] parts = open(sealer, map, in.identityKey(), contents.get(in.identityKey()));
            assertThat(parts[0]).isEqualTo(in.scopeId() + nul + in.namespace().name() + nul + in.subjectId());
            // The canonical parts recompute the keyed name; the value is what the generator makes of them.
            assertThat(in.names().scoped(map, in.scopeId(), parts[0])).isEqualTo(in.identityKey());
            assertThat(parts[1]).isEqualTo(in.generator().syntheticValue(in.subjectId(), in.namespace(), context));
        } else {
            in.assertKeys(map, contents, in.reidentificationKey());
            String[] parts = open(sealer, map, in.reidentificationKey(), contents.get(in.reidentificationKey()));
            assertThat(parts[0]).isEqualTo(in.scopeId() + nul + in.namespace().name() + nul + in.synthetic());
            assertThat(in.names().scoped(map, in.scopeId(), parts[0])).isEqualTo(in.reidentificationKey());
            assertThat(parts[1]).isEqualTo(in.subjectId());
            assertThat(in.synthetic()).isEqualTo(in.generator().syntheticValue(parts[1], in.namespace(), context));
        }
    }

    /** Opens the stored value and returns its canonical key parts and its value; fails on anything but a dps1 envelope. */
    private static String[] open(Sealer sealer, String map, String key, Object stored) {
        assertThat(stored).as("%s value", map).isInstanceOf(String.class);
        assertThat((String) stored).as("%s value is an envelope", map).startsWith("dps1.");
        Opened opened = sealer.open(map, key, (String) stored);
        assertThat(opened.outcome()).as("%s value opens", map).isEqualTo(Opened.Outcome.AUTHENTIC);
        ByteBuffer in = ByteBuffer.wrap(opened.plaintext());
        byte[] canonical = new byte[in.getInt()];
        in.get(canonical);
        byte[] value = new byte[in.getInt()];
        in.get(value);
        assertThat(in.hasRemaining()).isFalse();
        return new String[]{new String(canonical, StandardCharsets.UTF_8), new String(value, StandardCharsets.UTF_8)};
    }
}

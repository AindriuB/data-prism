package io.github.aindriub.dataprism.hazelcast;

import com.hazelcast.config.Config;
import com.hazelcast.map.IMap;
import io.github.aindriub.dataprism.annotations.PrivacyNamespace;
import io.github.aindriub.dataprism.core.metrics.Metric;
import io.github.aindriub.dataprism.core.model.PrivacyContext;
import io.github.aindriub.dataprism.core.model.PrivacyScopeType;
import io.github.aindriub.dataprism.core.model.PseudonymisationVersion;
import io.github.aindriub.dataprism.core.spi.SyntheticValueSource;
import io.github.aindriub.dataprism.hazelcast.seal.ClusterKeys;
import io.github.aindriub.dataprism.hazelcast.seal.Sealer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.OptionalInt;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T3: arbitrary data written into the identity map under the keyed name of a real entry can never change a value
 * Data Prism returns (cluster-security.md 1.1, boundary 6). Each writer is simulated by the bytes it could store: a
 * keyless member or client stores plaintext, a 0.7 member stores its format, a member with another key stores an
 * envelope of its own, and anybody can store random bytes shaped like an envelope.
 */
class IdentityPoisoningTest {

    private static final String SCOPE = "CASE-POISON";
    private static final String SUBJECT = "subject-p1";

    private PrivacyCluster cluster;

    @AfterEach
    void shutDown() {
        if (cluster != null) {
            cluster.close();
        }
    }

    private PrivacyCluster start() {
        Config config = new Config();
        config.setClusterName("dataprism-poison-" + System.nanoTime());
        config.getJetConfig().setEnabled(false);
        config.getNetworkConfig().getJoin().getMulticastConfig().setEnabled(false);
        config.getNetworkConfig().getJoin().getTcpIpConfig().setEnabled(false);
        cluster = PrivacyCluster.embedded(config, true, OptionalInt.empty(), TestMembers.KEYS);
        return cluster;
    }

    private static PrivacyContext scope() {
        return new PrivacyContext(SCOPE, PrivacyScopeType.CASE, "DEFAULT", "test",
                Instant.now().plus(1, ChronoUnit.HOURS), PseudonymisationVersion.HMAC_SHA256_V1);
    }

    private static final SyntheticValueSource GENERATOR = (subject, namespace, context) ->
            "generated:" + context.scopeId() + ":" + namespace.name() + ":" + subject;

    private static String expected() {
        return GENERATOR.syntheticValue(SUBJECT, PrivacyNamespace.PERSON_NAME, scope());
    }

    private static String envelopeOfAnotherKey(String id, byte fill, String map, String key) {
        byte[] bytes = new byte[32];
        java.util.Arrays.fill(bytes, fill);
        byte[] nameBytes = new byte[32];
        java.util.Arrays.fill(nameBytes, (byte) 'y');
        var other = new ClusterKeys(id, bytes, null, null, null, null, "other-name", nameBytes);
        return Sealer.builder(other, io.github.aindriub.dataprism.core.metrics.PrivacyMetrics.none()).build()
                .seal(map, key, IdentityMaps.plaintext("anything", "poisoned"));
    }

    private void poisonAndRead(String poison, Metric counted) {
        var metrics = new RecordingPrivacyMetrics();
        var caching = new CachingSyntheticValueSource(GENERATOR, start(), metrics);
        assertThat(caching.syntheticValue(SUBJECT, PrivacyNamespace.PERSON_NAME, scope())).isEqualTo(expected());
        String key = cluster.scopeKeys().identity(SCOPE, SUBJECT, PrivacyNamespace.PERSON_NAME);
        IMap<String, String> identities = cluster.instance().getMap(PrivacyCluster.IDENTITY_MAP);
        String poisoned = poison.replace("<key>", key);
        identities.set(key, poisoned);
        long before = metrics.countOf(counted);

        assertThat(caching.syntheticValue(SUBJECT, PrivacyNamespace.PERSON_NAME, scope())).isEqualTo(expected());

        assertThat(metrics.countOf(counted)).as("%s counted", counted).isGreaterThan(before);
        // The bad entry was replaced, so a later read is a hit on an authentic value.
        assertThat(identities.get(key)).isNotEqualTo(poisoned).startsWith("dps1.");
        long hits = caching.hits();
        assertThat(caching.syntheticValue(SUBJECT, PrivacyNamespace.PERSON_NAME, scope())).isEqualTo(expected());
        assertThat(caching.hits()).isEqualTo(hits + 1);
    }

    @Test
    void aKeylessWriterStoringPlaintextChangesNothing() {
        poisonAndRead("attacker-chosen-pseudonym", Metric.CLUSTER_SEAL_UNSUPPORTED_FORMAT);
    }

    @Test
    void a07FormatValueChangesNothing() {
        poisonAndRead("generated:CASE-POISON:PERSON_NAME:someone-else", Metric.CLUSTER_SEAL_UNSUPPORTED_FORMAT);
    }

    @Test
    void anEnvelopeOfARandomShapeChangesNothing() {
        poisonAndRead("dps1.test-cluster-1.AAAAAAAAAAAAAAAAAAAAAA.AAAAAAAAAAAAAAAA.AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
                Metric.CLUSTER_SEAL_TAMPER);
    }

    @Test
    void anEnvelopeOfAMemberWithAnotherKeyIdChangesNothing() {
        poisonAndRead(envelopeOfAnotherKey("foreign-key", (byte) 'x', PrivacyCluster.IDENTITY_MAP, "unused"),
                Metric.CLUSTER_SEAL_UNSUPPORTED_FORMAT);
    }

    @Test
    void anEnvelopeUnderTheSameKeyIdButOtherBytesChangesNothing() {
        poisonAndRead(envelopeOfAnotherKey("test-cluster-1", (byte) 'x', PrivacyCluster.IDENTITY_MAP, "unused"),
                Metric.CLUSTER_SEAL_TAMPER);
    }
}

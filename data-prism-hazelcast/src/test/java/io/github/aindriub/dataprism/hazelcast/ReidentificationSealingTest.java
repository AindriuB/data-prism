package io.github.aindriub.dataprism.hazelcast;

import com.hazelcast.config.Config;
import com.hazelcast.map.IMap;
import io.github.aindriub.dataprism.annotations.PrivacyNamespace;
import io.github.aindriub.dataprism.core.metrics.Metric;
import io.github.aindriub.dataprism.core.metrics.PrivacyMetrics;
import io.github.aindriub.dataprism.core.model.PrivacyContext;
import io.github.aindriub.dataprism.core.model.PrivacyScopeType;
import io.github.aindriub.dataprism.core.model.PseudonymisationVersion;
import io.github.aindriub.dataprism.core.refusal.ClusterOperationRefusedException;
import io.github.aindriub.dataprism.core.spi.SyntheticValueSource;
import io.github.aindriub.dataprism.hazelcast.seal.Opened;
import io.github.aindriub.dataprism.hazelcast.seal.Sealer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Task 225: the re-identification index holds sealed subjects, and a bad entry reads as nothing. */
class ReidentificationSealingTest {

    private static final String SCOPE = "CASE-SEAL";
    private static final String SUBJECT = "subject-r1";
    private static final PrivacyNamespace NS = PrivacyNamespace.PERSON_NAME;
    private static final SyntheticValueSource GENERATOR = (subject, namespace, context) ->
            "generated:" + context.scopeId() + ":" + namespace.name() + ":" + subject;

    private PrivacyCluster cluster;
    private final RecordingPrivacyMetrics metrics = new RecordingPrivacyMetrics();

    @AfterEach
    void shutDown() {
        if (cluster != null) {
            cluster.close();
        }
    }

    private PrivacyCluster start() {
        Config config = new Config();
        config.setClusterName("dataprism-reid-seal-" + System.nanoTime());
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

    private String issue() {
        return new CachingSyntheticValueSource(GENERATOR, start(), PrivacyMetrics.none())
                .syntheticValue(SUBJECT, NS, scope());
    }

    private IMap<String, String> index() {
        return cluster.instance().getMap(PrivacyCluster.REIDENTIFICATION_MAP);
    }

    private String indexKey(String synthetic) {
        return cluster.scopeKeys().reidentification(SCOPE, NS, synthetic);
    }

    @Test
    void theIndexHoldsASealedEnvelopeWithTheCanonicalPartsInside() {
        String synthetic = issue();
        String stored = index().get(indexKey(synthetic));
        assertThat(stored).startsWith("dps1.").doesNotContain(SUBJECT);
        var sealer = new ScopeIdentitySealer(cluster).sealer;
        Opened opened = sealer.open(PrivacyCluster.REIDENTIFICATION_MAP, indexKey(synthetic), stored);
        assertThat(IdentityMaps.valueOf(opened, ScopeKeys.reidentificationCanonical(SCOPE, NS, synthetic)))
                .contains(SUBJECT);
        assertThat(new ScopeIdentityIndex(cluster, metrics).subjectFor(SCOPE, NS, synthetic)).contains(SUBJECT);
    }

    @Test
    @DisplayName("a tampered or foreign index entry reads as empty and is counted")
    void aTamperedOrForeignEntryReadsAsEmptyAndIsCounted() {
        String synthetic = issue();
        String key = indexKey(synthetic);
        var sealer = new ScopeIdentitySealer(cluster).sealer;
        String good = index().get(key);

        // Flip a character of the ciphertext.
        String flipped = good.substring(0, good.length() - 2) + (good.endsWith("AA") ? "BB" : "AA");
        index().set(key, flipped);
        assertThat(new ScopeIdentityIndex(cluster, metrics).subjectFor(SCOPE, NS, synthetic)).isEmpty();
        assertThat(metrics.countOf(Metric.CLUSTER_SEAL_TAMPER)).isEqualTo(1);

        // Plaintext of another subject.
        index().set(key, "another-subject");
        assertThat(new ScopeIdentityIndex(cluster, metrics).subjectFor(SCOPE, NS, synthetic)).isEmpty();
        assertThat(metrics.countOf(Metric.CLUSTER_SEAL_UNSUPPORTED_FORMAT)).isEqualTo(1);

        // An authentic envelope that was sealed for another entry of the same map.
        String otherKey = indexKey("some-other-pseudonym");
        index().set(otherKey, sealer.seal(PrivacyCluster.REIDENTIFICATION_MAP, otherKey,
                IdentityMaps.plaintext(ScopeKeys.reidentificationCanonical(SCOPE, NS, "some-other-pseudonym"),
                        "subject-other")));
        index().set(key, index().get(otherKey));
        assertThat(new ScopeIdentityIndex(cluster, metrics).subjectFor(SCOPE, NS, synthetic)).isEmpty();
        assertThat(metrics.countOf(Metric.CLUSTER_SEAL_TAMPER)).isEqualTo(2);
        assertThat(metrics.countOf(Metric.REIDENTIFICATION)).isZero();
    }

    @Test
    void deletedReidentificationEntriesAreLost() {
        String synthetic = issue();
        index().delete(indexKey(synthetic));
        assertThat(new ScopeIdentityIndex(cluster, metrics).subjectFor(SCOPE, NS, synthetic)).isEmpty();
        // Issuing the same pseudonym again does not bring the lost entry back by itself; the cache hit skips the write.
        assertThat(metrics.countOf(Metric.CLUSTER_SEAL_TAMPER)).isZero();
    }

    @Test
    void anIdentityCiphertextCopiedIntoTheIndexDoesNotOpen() {
        String synthetic = issue();
        String identityKey = cluster.scopeKeys().identity(SCOPE, SUBJECT, NS);
        String identityValue = cluster.instance().<String, String>getMap(PrivacyCluster.IDENTITY_MAP)
                .get(identityKey);
        // Same keyed name in the other map: the subkey and the associated data differ.
        var sealer = new ScopeIdentitySealer(cluster).sealer;
        assertThat(sealer.open(PrivacyCluster.REIDENTIFICATION_MAP, identityKey, identityValue).outcome())
                .isEqualTo(Opened.Outcome.TAMPERED);
        index().set(indexKey(synthetic), identityValue);
        assertThat(new ScopeIdentityIndex(cluster, metrics).subjectFor(SCOPE, NS, synthetic)).isEmpty();
    }

    @Test
    void aClosedServingGateIsARefusalNeverATamperOrAnEmptyResult() {
        String synthetic = issue();
        var serving = new AtomicBoolean(true);
        var index = new ScopeIdentityIndex(cluster, metrics, Duration.ofSeconds(2), serving::get);
        assertThat(index.subjectFor(SCOPE, NS, synthetic)).contains(SUBJECT);

        serving.set(false);
        assertThatThrownBy(() -> index.subjectFor(SCOPE, NS, synthetic))
                .isInstanceOf(ClusterOperationRefusedException.class);
        assertThat(metrics.countOf(Metric.CLUSTER_SEAL_TAMPER)).isZero();

        // The identity cache recomputes, as for any cache failure.
        var caching = new CachingSyntheticValueSource(GENERATOR, cluster, metrics, Duration.ofSeconds(2),
                serving::get);
        assertThat(caching.syntheticValue(SUBJECT, NS, scope())).isEqualTo(
                GENERATOR.syntheticValue(SUBJECT, NS, scope()));
        assertThat(caching.failures()).isEqualTo(1);
        assertThat(metrics.countOf(Metric.CLUSTER_SEAL_TAMPER)).isZero();
    }

    @Test
    void endScopeStillRemovesTheScopesSealedEntries() {
        String synthetic = issue();
        new ScopeIdentityIndex(cluster, metrics).endScope(SCOPE);
        assertThat(index().isEmpty()).isTrue();
        assertThat(cluster.instance().getMap(PrivacyCluster.IDENTITY_MAP).isEmpty()).isTrue();
        assertThat(new ScopeIdentityIndex(cluster, metrics).subjectFor(SCOPE, NS, synthetic)).isEmpty();
    }

    /** The sealer a member of this cluster registered: the one that wrote the entries. */
    private static final class ScopeIdentitySealer {
        final Sealer sealer;

        ScopeIdentitySealer(PrivacyCluster cluster) {
            this.sealer = (Sealer) cluster.instance().getUserContext().get("dataprism.cluster.sealer");
        }
    }
}

package io.github.aindriub.dataprism.hazelcast;

import com.hazelcast.config.Config;
import io.github.aindriub.dataprism.annotations.PrivacyNamespace;
import io.github.aindriub.dataprism.core.PrivacyContext;
import io.github.aindriub.dataprism.core.PrivacyScopeType;
import io.github.aindriub.dataprism.core.PseudonymisationVersion;
import io.github.aindriub.dataprism.core.SyntheticValueSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class CachingSyntheticValueSourceTest {

    /**
     * Stands in for the HMAC generator: deterministic in exactly the way the real
     * one is, and counting calls so a cache hit is observable.
     */
    private static final class CountingGenerator implements SyntheticValueSource {
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public String syntheticValue(String subjectId, PrivacyNamespace namespace, PrivacyContext context) {
            calls.incrementAndGet();
            return "synthetic:" + context.scopeId() + ":" + namespace + ":" + subjectId;
        }
    }

    private PrivacyCluster cluster;

    @AfterEach
    void shutDown() {
        if (cluster != null) {
            cluster.close();
        }
    }

    private PrivacyCluster start(boolean reidentification) {
        // Loopback only, no discovery: a test must never find a real cluster.
        Config config = new Config();
        config.setClusterName("dataprism-test-" + System.nanoTime());
        config.getJetConfig().setEnabled(false);
        config.getNetworkConfig().getJoin().getMulticastConfig().setEnabled(false);
        config.getNetworkConfig().getJoin().getTcpIpConfig().setEnabled(false);
        cluster = PrivacyCluster.embedded(config, reidentification);
        return cluster;
    }

    private static PrivacyContext scope(String scopeId) {
        return new PrivacyContext(scopeId, PrivacyScopeType.CASE, "DEFAULT", "test",
                Instant.now().plus(1, ChronoUnit.HOURS), PseudonymisationVersion.HMAC_SHA256_V1);
    }

    @Test
    @DisplayName("the cache saves computation and changes no answer")
    void cachingIsInvisible() {
        var generator = new CountingGenerator();
        var caching = new CachingSyntheticValueSource(generator, start(false));

        String first = caching.syntheticValue("s-1", PrivacyNamespace.PERSON_NAME, scope("CASE-A"));
        String second = caching.syntheticValue("s-1", PrivacyNamespace.PERSON_NAME, scope("CASE-A"));

        assertThat(second).isEqualTo(first);
        assertThat(generator.calls).hasValue(1);
        assertThat(caching.hits()).isEqualTo(1);
    }

    @Test
    @DisplayName("output is identical whether the cluster is there or not")
    void outputSurvivesTheClusterDying() {
        var generator = new CountingGenerator();
        var caching = new CachingSyntheticValueSource(generator, start(false));

        List<String> withCluster = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            withCluster.add(caching.syntheticValue("s-" + i, PrivacyNamespace.PERSON_NAME, scope("CASE-A")));
        }

        // Not a graceful drain: the member is stopped underneath a live caller,
        // which is what a crashed node looks like.
        cluster.close();

        List<String> withoutCluster = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            withoutCluster.add(caching.syntheticValue("s-" + i, PrivacyNamespace.PERSON_NAME, scope("CASE-A")));
        }

        // The whole point of deriving rather than storing. Losing the cluster
        // costs recomputation; it must never cost a different name for a person.
        assertThat(withoutCluster).isEqualTo(withCluster);
        assertThat(caching.failures()).isPositive();
    }

    @Test
    @DisplayName("the failure test would notice if the cache were deciding answers")
    void failureTestIsNotVacuous() {
        // A generator whose answers drift proves the previous test is comparing
        // real values rather than passing because everything is constant.
        var drifting = new SyntheticValueSource() {
            private int n;

            @Override
            public String syntheticValue(String subjectId, PrivacyNamespace ns, PrivacyContext ctx) {
                return "drift-" + n++;
            }
        };
        var caching = new CachingSyntheticValueSource(drifting, start(false));

        String cached = caching.syntheticValue("s-1", PrivacyNamespace.PERSON_NAME, scope("CASE-A"));
        cluster.close();
        String uncached = caching.syntheticValue("s-1", PrivacyNamespace.PERSON_NAME, scope("CASE-A"));

        assertThat(uncached).isNotEqualTo(cached);
    }

    @Test
    @DisplayName("scopes do not share cached identities")
    void scopesAreIsolated() {
        var caching = new CachingSyntheticValueSource(new CountingGenerator(), start(false));

        assertThat(caching.syntheticValue("s-1", PrivacyNamespace.PERSON_NAME, scope("CASE-B")))
                .isNotEqualTo(caching.syntheticValue("s-1", PrivacyNamespace.PERSON_NAME, scope("CASE-A")));
    }

    @Test
    @DisplayName("the reverse index is empty unless a deployment asks for it")
    void reidentificationIsOffByDefault() {
        var caching = new CachingSyntheticValueSource(new CountingGenerator(), start(false));
        String synthetic = caching.syntheticValue("s-1", PrivacyNamespace.PERSON_NAME, scope("CASE-A"));

        // The one store that can undo a pseudonym does not exist unless someone
        // decided it should.
        assertThat(new ScopeIdentityIndex(cluster)
                .subjectFor("CASE-A", PrivacyNamespace.PERSON_NAME, synthetic)).isEmpty();
    }

    @Test
    @DisplayName("with the index on, a pseudonym resolves back to its subject")
    void reidentificationResolvesWhenEnabled() {
        var caching = new CachingSyntheticValueSource(new CountingGenerator(), start(true));
        String synthetic = caching.syntheticValue("s-1", PrivacyNamespace.PERSON_NAME, scope("CASE-A"));

        assertThat(new ScopeIdentityIndex(cluster)
                .subjectFor("CASE-A", PrivacyNamespace.PERSON_NAME, synthetic)).contains("s-1");

        // And only within its own scope.
        assertThat(new ScopeIdentityIndex(cluster)
                .subjectFor("CASE-B", PrivacyNamespace.PERSON_NAME, synthetic)).isEmpty();
    }

    @Test
    @DisplayName("ending a scope removes its mappings rather than waiting for a TTL")
    void endingAScopeRemovesEverything() {
        var caching = new CachingSyntheticValueSource(new CountingGenerator(), start(true));
        var index = new ScopeIdentityIndex(cluster);
        var budget = new HazelcastScopeBudget(cluster);

        String synthetic = caching.syntheticValue("s-1", PrivacyNamespace.PERSON_NAME, scope("CASE-A"));
        caching.syntheticValue("s-1", PrivacyNamespace.PERSON_NAME, scope("CASE-KEEP"));
        budget.tryRead("CASE-A", "s-1", 5);

        index.endScope("CASE-A");

        assertThat(index.subjectFor("CASE-A", PrivacyNamespace.PERSON_NAME, synthetic)).isEmpty();
        assertThat(budget.reads("CASE-A", "s-1")).isZero();
        // An investigation ending must not disturb one still running.
        assertThat(cluster.instance().getMap(PrivacyCluster.IDENTITY_MAP).size())
                .as("the other scope's identities are untouched").isPositive();
    }
}

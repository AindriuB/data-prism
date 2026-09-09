package io.github.aindriub.dataprism.hazelcast;

import com.hazelcast.config.Config;
import io.github.aindriub.dataprism.annotations.PrivacyNamespace;
import io.github.aindriub.dataprism.core.Metric;
import io.github.aindriub.dataprism.core.PrivacyContext;
import io.github.aindriub.dataprism.core.PrivacyMetrics;
import io.github.aindriub.dataprism.core.PrivacyScopeType;
import io.github.aindriub.dataprism.core.PseudonymisationVersion;
import io.github.aindriub.dataprism.core.SyntheticValueSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What {@link CachingSyntheticValueSource} and {@link ScopeIdentityIndex}
 * publish through {@code PrivacyMetrics}.
 *
 * <p>One embedded member for the whole class, per the note on the other two
 * test classes in this module about how slow starting one is. Tests that need
 * reidentification on or off share it through two {@link PrivacyCluster}
 * wrappers over the same {@code HazelcastInstance}, and each test uses its own
 * scope id so they cannot see one another's state. Only the cluster-failure
 * test starts (and stops) a member of its own, because it has to take one down
 * mid-test.
 */
class IdentityMetricsTest {

    private static PrivacyCluster notReidentifying;
    private static PrivacyCluster reidentifying;

    @BeforeAll
    static void startCluster() {
        Config config = new Config();
        config.setClusterName("dataprism-metrics-test-" + System.nanoTime());
        config.getJetConfig().setEnabled(false);
        config.getNetworkConfig().getJoin().getMulticastConfig().setEnabled(false);
        config.getNetworkConfig().getJoin().getTcpIpConfig().setEnabled(false);
        notReidentifying = PrivacyCluster.embedded(config, false);
        reidentifying = PrivacyCluster.using(notReidentifying.instance(), true);
    }

    @AfterAll
    static void stopCluster() {
        notReidentifying.close();
    }

    private static final class EchoGenerator implements SyntheticValueSource {
        @Override
        public String syntheticValue(String subjectId, PrivacyNamespace namespace, PrivacyContext context) {
            return "synthetic:" + context.scopeId() + ":" + namespace + ":" + subjectId;
        }
    }

    /** Counts invocations, so a test can tell a cache hit from a re-derivation. */
    private static final class CountingGenerator implements SyntheticValueSource {
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public String syntheticValue(String subjectId, PrivacyNamespace namespace, PrivacyContext context) {
            calls.incrementAndGet();
            return "synthetic:" + context.scopeId() + ":" + namespace + ":" + subjectId;
        }

        int callCount() {
            return calls.get();
        }
    }

    /** Every method throws, the way a Micrometer meter conflict would. */
    private static PrivacyMetrics throwingMetrics() {
        return throwingOnly(null);
    }

    /**
     * Throws only for {@code target}, so a test can prove a single call site is
     * guarded without a throw at an earlier site (a miss, say) short-circuiting
     * the path the test means to exercise. {@code null} throws for every metric.
     */
    private static PrivacyMetrics throwingOnly(Metric target) {
        return new PrivacyMetrics() {
            @Override
            public void increment(Metric metric) {
                maybeThrow(metric);
            }

            @Override
            public void increment(Metric metric, String sourceName) {
                maybeThrow(metric);
            }

            @Override
            public void record(Metric metric, String sourceName, Duration duration) {
                maybeThrow(metric);
            }

            private void maybeThrow(Metric metric) {
                if (target == null || target == metric) {
                    throw new IllegalStateException("conflicting meter registration");
                }
            }
        };
    }

    private static PrivacyContext scope(String scopeId) {
        return new PrivacyContext(scopeId, PrivacyScopeType.CASE, "DEFAULT", "test",
                Instant.now().plus(1, ChronoUnit.HOURS), PseudonymisationVersion.HMAC_SHA256_V1);
    }

    @Test
    @DisplayName("a miss then a hit are each counted once, labelled by namespace")
    void hitAndMissAreRecordedByNamespace() {
        var metrics = new RecordingPrivacyMetrics();
        var caching = new CachingSyntheticValueSource(new EchoGenerator(), notReidentifying, metrics);
        var context = scope("M-CASE-HITMISS");

        caching.syntheticValue("s-1", PrivacyNamespace.PERSON_NAME, context);
        caching.syntheticValue("s-1", PrivacyNamespace.PERSON_NAME, context);

        assertThat(metrics.countOf(Metric.IDENTITY_CACHE_MISS)).isEqualTo(1);
        assertThat(metrics.countOf(Metric.IDENTITY_CACHE_HIT)).isEqualTo(1);
        assertThat(metrics.increments())
                .extracting(RecordingPrivacyMetrics.Increment::sourceName)
                .containsOnly(PrivacyNamespace.PERSON_NAME.name());
    }

    @Test
    @DisplayName("a stored value that disagrees with the generator counts as a collision, not a hit")
    void collisionIsRecordedWhenAConcurrentWriteDisagrees() throws Exception {
        var metrics = new RecordingPrivacyMetrics();
        var context = scope("M-CASE-COLLISION");
        String key = ScopeKeys.identity(context.scopeId(), "s-1", PrivacyNamespace.PERSON_NAME);

        // Ordering is enforced with latches, not timing: the generator signals
        // that the lookup already came back empty (a real miss) and then blocks,
        // so the direct write below is guaranteed to land before the store.
        CountDownLatch generatorEntered = new CountDownLatch(1);
        CountDownLatch proceed = new CountDownLatch(1);
        var blockingGenerator = new SyntheticValueSource() {
            @Override
            public String syntheticValue(String subjectId, PrivacyNamespace namespace, PrivacyContext ctx) {
                generatorEntered.countDown();
                await(proceed);
                return "generated-value";
            }
        };
        var caching = new CachingSyntheticValueSource(blockingGenerator, notReidentifying, metrics);

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<String> pending = executor.submit(
                    () -> caching.syntheticValue("s-1", PrivacyNamespace.PERSON_NAME, context));

            assertThat(generatorEntered.await(5, TimeUnit.SECONDS)).isTrue();
            // What "the key, algorithm version or vocabulary changed mid-scope"
            // looks like from the cache's point of view.
            notReidentifying.instance().getMap(PrivacyCluster.IDENTITY_MAP).set(key, "stale-value");
            proceed.countDown();

            String result = pending.get(5, TimeUnit.SECONDS);

            assertThat(result).isEqualTo("generated-value");
            assertThat(metrics.countOf(Metric.IDENTITY_COLLISION)).isEqualTo(1);
        } finally {
            executor.shutdown();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    @DisplayName("a cluster failure never counts as a hit, and the metrics describe a miss")
    void clusterFailureNeverRecordsAHit() {
        Config config = new Config();
        config.setClusterName("dataprism-metrics-fail-" + System.nanoTime());
        config.getJetConfig().setEnabled(false);
        config.getNetworkConfig().getJoin().getMulticastConfig().setEnabled(false);
        config.getNetworkConfig().getJoin().getTcpIpConfig().setEnabled(false);
        var failing = PrivacyCluster.embedded(config, false);
        var generator = new EchoGenerator();
        var metrics = new RecordingPrivacyMetrics();
        var caching = new CachingSyntheticValueSource(generator, failing, metrics);
        var context = scope("M-CASE-FAIL");

        // Not a graceful drain: the member is stopped underneath a live caller,
        // which is what a crashed node looks like.
        failing.close();

        String result = caching.syntheticValue("s-1", PrivacyNamespace.PERSON_NAME, context);

        assertThat(result).isEqualTo(generator.syntheticValue("s-1", PrivacyNamespace.PERSON_NAME, context));
        assertThat(metrics.countOf(Metric.IDENTITY_CACHE_HIT)).isZero();
        assertThat(metrics.countOf(Metric.IDENTITY_CACHE_MISS)).isEqualTo(1);
    }

    @Test
    @DisplayName("a generator that throws after a real miss counts that miss once, not twice")
    void generatorFailureDoesNotDoubleCountTheMiss() {
        var metrics = new RecordingPrivacyMetrics();
        var refusing = new SyntheticValueSource() {
            @Override
            public String syntheticValue(String subjectId, PrivacyNamespace namespace, PrivacyContext ctx) {
                throw new IllegalStateException("scope pinned to a different vocabulary");
            }
        };
        var caching = new CachingSyntheticValueSource(refusing, notReidentifying, metrics);
        var context = scope("M-CASE-GENFAIL");

        assertThatThrownBy(() -> caching.syntheticValue("s-1", PrivacyNamespace.PERSON_NAME, context))
                .isInstanceOf(IllegalStateException.class);

        assertThat(metrics.countOf(Metric.IDENTITY_CACHE_MISS)).isEqualTo(1);
        assertThat(caching.misses()).isEqualTo(1);
    }

    @Test
    @DisplayName("a metrics failure on the fallback miss never escapes the lookup")
    void metricsFailureOnFallbackMissDoesNotFailTheLookup() {
        Config config = new Config();
        config.setClusterName("dataprism-metrics-throw-" + System.nanoTime());
        config.getJetConfig().setEnabled(false);
        config.getNetworkConfig().getJoin().getMulticastConfig().setEnabled(false);
        config.getNetworkConfig().getJoin().getTcpIpConfig().setEnabled(false);
        var failing = PrivacyCluster.embedded(config, false);
        var generator = new EchoGenerator();
        var caching = new CachingSyntheticValueSource(generator, failing, throwingMetrics());
        var context = scope("M-CASE-METRICSFAIL");

        // Not a graceful drain: the member is stopped underneath a live caller,
        // which is what a crashed node looks like.
        failing.close();

        String result = caching.syntheticValue("s-1", PrivacyNamespace.PERSON_NAME, context);

        assertThat(result).isEqualTo(generator.syntheticValue("s-1", PrivacyNamespace.PERSON_NAME, context));
    }

    @Test
    @DisplayName("a metrics failure on a cache hit returns the cached value without re-deriving it")
    void metricsFailureOnHitDoesNotReDeriveOrEscape() {
        var generator = new CountingGenerator();
        // Only the hit emit throws, so a real miss on the first call seeds the
        // cache uneventfully and the second call is the one under test.
        var caching = new CachingSyntheticValueSource(generator, notReidentifying,
                throwingOnly(Metric.IDENTITY_CACHE_HIT));
        var context = scope("M-CASE-HITTHROW");

        String first = caching.syntheticValue("s-1", PrivacyNamespace.PERSON_NAME, context);
        String second = caching.syntheticValue("s-1", PrivacyNamespace.PERSON_NAME, context);

        assertThat(second).isEqualTo(first);
        // Two lookups, one derivation: the second must have come from the cache,
        // not from the generator running again because the hit's metric call threw.
        assertThat(generator.callCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("a metrics failure on a collision still overwrites the disagreeing cached value")
    void metricsFailureOnCollisionStillOverwritesTheStaleValue() throws Exception {
        var context = scope("M-CASE-COLLISIONTHROW");
        String key = ScopeKeys.identity(context.scopeId(), "s-1", PrivacyNamespace.PERSON_NAME);

        // Same latch technique as the collision test above: the generator signals
        // that its own lookup already came back empty, then blocks, so the direct
        // write below is guaranteed to land before the store.
        CountDownLatch generatorEntered = new CountDownLatch(1);
        CountDownLatch proceed = new CountDownLatch(1);
        var blockingGenerator = new SyntheticValueSource() {
            @Override
            public String syntheticValue(String subjectId, PrivacyNamespace namespace, PrivacyContext ctx) {
                generatorEntered.countDown();
                await(proceed);
                return "generated-value";
            }
        };
        // Only the collision emit throws, so the real miss above it in store()
        // proceeds and the write this test cares about is the one under test.
        var caching = new CachingSyntheticValueSource(blockingGenerator, notReidentifying,
                throwingOnly(Metric.IDENTITY_COLLISION));

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<String> pending = executor.submit(
                    () -> caching.syntheticValue("s-1", PrivacyNamespace.PERSON_NAME, context));

            assertThat(generatorEntered.await(5, TimeUnit.SECONDS)).isTrue();
            notReidentifying.instance().getMap(PrivacyCluster.IDENTITY_MAP).set(key, "stale-value");
            proceed.countDown();

            String result = pending.get(5, TimeUnit.SECONDS);
            assertThat(result).isEqualTo("generated-value");
        } finally {
            executor.shutdown();
        }

        // The corrective overwrite must have completed even though the collision
        // metric call above it threw; a metrics failure must not leave the
        // disagreeing value in place for every later lookup to return.
        String storedAfter = notReidentifying.instance()
                .<String, String>getMap(PrivacyCluster.IDENTITY_MAP).get(key);
        assertThat(storedAfter).isEqualTo("generated-value");
    }

    @Test
    @DisplayName("a successful reverse resolution is counted; a lookup that finds nothing is not")
    void reidentificationIsCountedOnlyOnSuccess() {
        var metrics = new RecordingPrivacyMetrics();
        var caching = new CachingSyntheticValueSource(new EchoGenerator(), reidentifying);
        var index = new ScopeIdentityIndex(reidentifying, metrics);
        String synthetic = caching.syntheticValue("s-1", PrivacyNamespace.PERSON_NAME, scope("M-CASE-REID"));

        assertThat(index.subjectFor("M-CASE-REID", PrivacyNamespace.PERSON_NAME, synthetic)).contains("s-1");
        assertThat(metrics.countOf(Metric.REIDENTIFICATION)).isEqualTo(1);

        assertThat(index.subjectFor("M-CASE-REID", PrivacyNamespace.PERSON_NAME, "no-such-pseudonym")).isEmpty();
        assertThat(metrics.countOf(Metric.REIDENTIFICATION)).isEqualTo(1);
    }

    @Test
    @DisplayName("with the index off, a lookup finds nothing and counts nothing")
    void reidentificationDisabledCountsNothing() {
        var metrics = new RecordingPrivacyMetrics();
        var caching = new CachingSyntheticValueSource(new EchoGenerator(), notReidentifying);
        var index = new ScopeIdentityIndex(notReidentifying, metrics);
        String synthetic = caching.syntheticValue("s-1", PrivacyNamespace.PERSON_NAME, scope("M-CASE-REID-OFF"));

        assertThat(index.subjectFor("M-CASE-REID-OFF", PrivacyNamespace.PERSON_NAME, synthetic)).isEmpty();
        assertThat(metrics.increments()).isEmpty();
    }

    @Test
    @DisplayName("no metric call in this module carries a scope id, subject id or pseudonym as its label")
    void sourceNameIsNeverAnIdentifier() throws Exception {
        var metrics = new RecordingPrivacyMetrics();
        var caching = new CachingSyntheticValueSource(new EchoGenerator(), reidentifying, metrics);
        var index = new ScopeIdentityIndex(reidentifying, metrics);
        String scopeId = "M-CASE-SAFE";
        String subjectId = "s-safe-subject";
        var context = scope(scopeId);

        // Hit (second call), miss (first call) and reidentification.
        String synthetic = caching.syntheticValue(subjectId, PrivacyNamespace.PERSON_NAME, context);
        caching.syntheticValue(subjectId, PrivacyNamespace.PERSON_NAME, context);
        index.subjectFor(scopeId, PrivacyNamespace.PERSON_NAME, synthetic);

        // Collision, so the assertion below also covers CachingSyntheticValueSource:125.
        String collisionScopeId = "M-CASE-SAFE-COLLISION";
        var collisionContext = scope(collisionScopeId);
        String collisionKey = ScopeKeys.identity(collisionScopeId, subjectId, PrivacyNamespace.PERSON_NAME);
        CountDownLatch generatorEntered = new CountDownLatch(1);
        CountDownLatch proceed = new CountDownLatch(1);
        var blockingGenerator = new SyntheticValueSource() {
            @Override
            public String syntheticValue(String subject, PrivacyNamespace namespace, PrivacyContext ctx) {
                generatorEntered.countDown();
                await(proceed);
                return "generated-value";
            }
        };
        var collisionCaching = new CachingSyntheticValueSource(blockingGenerator, reidentifying, metrics);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<String> pending = executor.submit(() ->
                    collisionCaching.syntheticValue(subjectId, PrivacyNamespace.PERSON_NAME, collisionContext));
            assertThat(generatorEntered.await(5, TimeUnit.SECONDS)).isTrue();
            reidentifying.instance().getMap(PrivacyCluster.IDENTITY_MAP).set(collisionKey, "stale-value");
            proceed.countDown();
            pending.get(5, TimeUnit.SECONDS);
        } finally {
            executor.shutdown();
        }

        // Fallback miss on a cluster failure, so the assertion below also covers
        // CachingSyntheticValueSource:105.
        String fallbackScopeId = "M-CASE-SAFE-FALLBACK";
        Config fallbackConfig = new Config();
        fallbackConfig.setClusterName("dataprism-metrics-label-fail-" + System.nanoTime());
        fallbackConfig.getJetConfig().setEnabled(false);
        fallbackConfig.getNetworkConfig().getJoin().getMulticastConfig().setEnabled(false);
        fallbackConfig.getNetworkConfig().getJoin().getTcpIpConfig().setEnabled(false);
        var failing = PrivacyCluster.embedded(fallbackConfig, false);
        var failingCaching = new CachingSyntheticValueSource(new EchoGenerator(), failing, metrics);
        // Not a graceful drain: the member is stopped underneath a live caller,
        // which is what a crashed node looks like.
        failing.close();
        failingCaching.syntheticValue(subjectId, PrivacyNamespace.PERSON_NAME, scope(fallbackScopeId));

        Set<String> namespaceNames = Arrays.stream(PrivacyNamespace.values())
                .map(Enum::name)
                .collect(Collectors.toSet());

        assertThat(metrics.increments())
                .as("every emit site this assertion claims to cover must actually run")
                .extracting(RecordingPrivacyMetrics.Increment::metric)
                .contains(Metric.IDENTITY_CACHE_HIT, Metric.IDENTITY_CACHE_MISS,
                        Metric.IDENTITY_COLLISION, Metric.REIDENTIFICATION);
        metrics.increments().forEach(increment -> {
            assertThat(increment.sourceName())
                    .as("metric %s must not be labelled with an identifier", increment.metric())
                    .isNotEqualTo(scopeId)
                    .isNotEqualTo(subjectId)
                    .isNotEqualTo(synthetic)
                    .isNotEqualTo(collisionScopeId)
                    .isNotEqualTo(fallbackScopeId);
            if (increment.sourceName() != null) {
                assertThat(namespaceNames)
                        .as("the only permitted argument is a configured name")
                        .contains(increment.sourceName());
            }
        });
    }
}

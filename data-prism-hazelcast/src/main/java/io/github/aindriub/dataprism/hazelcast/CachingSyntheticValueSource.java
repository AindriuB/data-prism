package io.github.aindriub.dataprism.hazelcast;

import com.hazelcast.map.IMap;
import io.github.aindriub.dataprism.annotations.PrivacyNamespace;
import io.github.aindriub.dataprism.core.Metric;
import io.github.aindriub.dataprism.core.PrivacyContext;
import io.github.aindriub.dataprism.core.PrivacyMetrics;
import io.github.aindriub.dataprism.core.SyntheticValueSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Caches synthetic values in the cluster, without ever deciding one.
 *
 * <p>The invariant this class exists to preserve is that it makes no difference.
 * A synthetic value is a pure function of scope, subject, namespace, algorithm
 * version and key; the cache saves an HMAC, and that is the entire benefit. Every
 * path through here returns what the generator would have returned:
 *
 * <ul>
 *   <li>a hit returns the cached value, which equals the generated one;
 *   <li>a miss generates and stores;
 *   <li>a cluster failure generates and gives up on storing.
 * </ul>
 *
 * <p>The third case is the one worth being strict about. A cache that can fail
 * open into a <em>different</em> answer would rename people whenever the cluster
 * hiccupped, and the rename would look like ordinary output. So every Hazelcast
 * call here is wrapped, failures are counted and logged, and the generator's
 * answer is returned regardless.
 *
 * <p>There is one departure from the specification's own sketch, which returns
 * the previously stored value when a concurrent write finds one. Two threads
 * racing on the same key generate identical values, so a stored value that
 * differs cannot be a race — it means the key, the algorithm version or the
 * vocabulary changed while a scope was live, which the pinning in
 * {@code PseudonymisationVersion} is supposed to prevent. Preferring the stored
 * value there would make output depend on cache state, which is exactly the
 * property this class is meant to protect. It warns loudly and overwrites.
 */
public final class CachingSyntheticValueSource implements SyntheticValueSource {

    private static final Logger LOG = LoggerFactory.getLogger(CachingSyntheticValueSource.class);

    private final SyntheticValueSource generator;
    private final PrivacyCluster cluster;
    private final PrivacyMetrics metrics;

    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();
    private final AtomicLong failures = new AtomicLong();
    private final AtomicLong conflicts = new AtomicLong();

    public CachingSyntheticValueSource(SyntheticValueSource generator, PrivacyCluster cluster) {
        this(generator, cluster, PrivacyMetrics.none());
    }

    public CachingSyntheticValueSource(SyntheticValueSource generator, PrivacyCluster cluster,
                                        PrivacyMetrics metrics) {
        this.generator = Objects.requireNonNull(generator, "generator");
        this.cluster = Objects.requireNonNull(cluster, "cluster");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    @Override
    public String syntheticValue(String subjectId, PrivacyNamespace namespace, PrivacyContext context) {
        String generated = null;
        try {
            IMap<String, String> identities = cluster.instance().getMap(PrivacyCluster.IDENTITY_MAP);
            String key = ScopeKeys.identity(context.scopeId(), subjectId, namespace);

            String cached = identities.get(key);
            if (cached != null) {
                hits.incrementAndGet();
                metrics.increment(Metric.IDENTITY_CACHE_HIT, namespace.name());
                return cached;
            }

            misses.incrementAndGet();
            metrics.increment(Metric.IDENTITY_CACHE_MISS, namespace.name());
            generated = generator.syntheticValue(subjectId, namespace, context);
            store(identities, key, generated, subjectId, namespace, context);
            return generated;
        } catch (RuntimeException cacheFailure) {
            // Deliberately broad. Whatever the cluster is doing, the answer is not
            // allowed to change; the only thing lost is the saved computation.
            failures.incrementAndGet();
            LOG.warn("identity cache unavailable, falling back to computation: {}",
                    cacheFailure.getClass().getSimpleName());
            if (generated == null) {
                // The failure happened before either counter above ran: the cluster
                // was gone before the lookup completed. Functionally that is a
                // miss — computation was not saved — never a hit, so it is counted
                // as one rather than left unrecorded.
                metrics.increment(Metric.IDENTITY_CACHE_MISS, namespace.name());
            }
            return generated != null
                    ? generated
                    : generator.syntheticValue(subjectId, namespace, context);
        }
    }

    private void store(IMap<String, String> identities, String key, String generated,
                       String subjectId, PrivacyNamespace namespace, PrivacyContext context) {
        long ttlMillis = ttlMillis(context);
        String existing = identities.putIfAbsent(key, generated, ttlMillis, TimeUnit.MILLISECONDS);

        if (existing != null && !existing.equals(generated)) {
            // Not a race: two threads deriving the same key derive the same value.
            // Something about the derivation changed while the scope was live.
            conflicts.incrementAndGet();
            metrics.increment(Metric.IDENTITY_COLLISION, namespace.name());
            LOG.warn("cached identity for a live scope disagrees with the generator; "
                    + "the key, algorithm version or vocabulary changed mid-scope. "
                    + "Overwriting so output stays a function of the generator, not the cache.");
            identities.set(key, generated, ttlMillis, TimeUnit.MILLISECONDS);
        }

        if (cluster.reidentificationEnabled()) {
            cluster.instance().<String, String>getMap(PrivacyCluster.REIDENTIFICATION_MAP)
                    .set(ScopeKeys.reidentification(context.scopeId(), namespace, generated),
                            subjectId, ttlMillis, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * Entries die with their scope. Zero means "no per-entry TTL", so a context
     * with no expiry falls back to the map's eviction rather than living forever.
     */
    private static long ttlMillis(PrivacyContext context) {
        if (context.expiresAt() == null) {
            return 0;
        }
        long remaining = Duration.between(Instant.now(), context.expiresAt()).toMillis();
        return Math.max(remaining, 1);
    }

    public long hits() {
        return hits.get();
    }

    public long misses() {
        return misses.get();
    }

    /** Cluster failures survived. Non-zero is an operational problem, never a privacy one. */
    public long failures() {
        return failures.get();
    }

    /** Cached values that disagreed with the generator. Non-zero means look at configuration. */
    public long conflicts() {
        return conflicts.get();
    }
}

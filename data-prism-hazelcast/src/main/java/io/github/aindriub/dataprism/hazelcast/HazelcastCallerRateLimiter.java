package io.github.aindriub.dataprism.hazelcast;

import com.hazelcast.map.EntryProcessor;
import com.hazelcast.map.IMap;
import com.hazelcast.map.ExtendedMapEntry;
import io.github.aindriub.dataprism.oversight.CallerRateLimiter;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * A per-caller fixed-window counter shared across the cluster, so a limit of
 * sixty means sixty and not sixty per instance.
 *
 * <p>Fails closed: an unreachable cluster throws, and so does a lock held past the lock timeout
 * ({@link io.github.aindriub.dataprism.core.refusal.ClusterLockTimeoutException}), and the caller refuses.
 * Windows align to the epoch, like the in-memory counter. The value is
 * {@code windowIndex:count}; no subject or scope appears in it.
 */
public final class HazelcastCallerRateLimiter implements CallerRateLimiter {

    private final PrivacyCluster cluster;
    private final Duration lockTimeout;

    /** With the default lock timeout of two seconds. */
    public HazelcastCallerRateLimiter(PrivacyCluster cluster) {
        this(cluster, ClusterLocks.DEFAULT_TIMEOUT);
    }

    /**
     * @param lockTimeout how long a call waits for the caller's lock before it refuses, {@code PT100MS}
     *                    to {@code PT30S}
     * @throws IllegalArgumentException if the timeout is outside that range
     */
    public HazelcastCallerRateLimiter(PrivacyCluster cluster, Duration lockTimeout) {
        this.cluster = Objects.requireNonNull(cluster, "cluster");
        this.lockTimeout = ClusterLocks.checked(lockTimeout);
    }

    @Override
    public boolean tryAcquire(String principalId, int limit, Duration window, Instant now) {
        long windowMillis = Math.max(1L, window.toMillis());
        long index = Math.floorDiv(now.toEpochMilli(), windowMillis);
        IMap<String, String> counts = cluster.servingInstance().getMap(PrivacyCluster.CALLER_RATE_MAP);
        String key = cluster.scopeKeys().callerRate(principalId);
        ClusterLocks.acquire(counts, key, lockTimeout);
        try {
            return counts.executeOnKey(key, new AcquireWithinWindow(limit, index, windowMillis));
        } finally {
            ClusterLocks.release(counts, key);
        }
    }

    private record AcquireWithinWindow(int limit, long index, long windowMillis)
            implements EntryProcessor<String, String, Boolean> {
        @Override
        public Boolean process(Map.Entry<String, String> entry) {
            String current = entry.getValue();
            int count = 0;
            if (current != null) {
                int split = current.indexOf(':');
                if (Long.parseLong(current.substring(0, split)) == index) {
                    count = Integer.parseInt(current.substring(split + 1));
                }
            }
            if (count >= limit) return false;
            // Count and two-window expiry change together, independently of the caller's lease.
            ((ExtendedMapEntry<String, String>) entry).setValue(index + ":" + (count + 1),
                    2 * windowMillis, TimeUnit.MILLISECONDS);
            return true;
        }
    }
}

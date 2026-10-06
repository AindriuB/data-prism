package io.github.aindriub.dataprism.hazelcast;

import com.hazelcast.map.IMap;
import io.github.aindriub.dataprism.oversight.CallerRateLimiter;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * A per-caller fixed-window counter shared across the cluster, so a limit of
 * sixty means sixty and not sixty per instance.
 *
 * <p>Fails closed: an unreachable cluster throws, and the caller refuses.
 * Windows align to the epoch, like the in-memory counter. The value is
 * {@code windowIndex:count}; no subject or scope appears in it.
 */
public final class HazelcastCallerRateLimiter implements CallerRateLimiter {

    private final PrivacyCluster cluster;

    public HazelcastCallerRateLimiter(PrivacyCluster cluster) {
        this.cluster = Objects.requireNonNull(cluster, "cluster");
    }

    @Override
    public boolean tryAcquire(String principalId, int limit, Duration window, Instant now) {
        long windowMillis = Math.max(1L, window.toMillis());
        long index = Math.floorDiv(now.toEpochMilli(), windowMillis);
        IMap<String, String> counts = cluster.instance().getMap(PrivacyCluster.CALLER_RATE_MAP);
        counts.lock(principalId);
        try {
            String current = counts.get(principalId);
            int count = 0;
            if (current != null) {
                int split = current.indexOf(':');
                if (Long.parseLong(current.substring(0, split)) == index) {
                    count = Integer.parseInt(current.substring(split + 1));
                }
            }
            if (count >= limit) {
                return false;
            }
            // Expire two windows on, so an idle caller's entry does not linger.
            counts.set(principalId, index + ":" + (count + 1), 2 * windowMillis, TimeUnit.MILLISECONDS);
            return true;
        } finally {
            try {
                counts.unlock(principalId);
            } catch (RuntimeException ignored) {
                // The lock dies with the member; throwing here would mask the real failure.
            }
        }
    }
}

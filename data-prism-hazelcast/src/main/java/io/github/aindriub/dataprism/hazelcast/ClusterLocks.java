package io.github.aindriub.dataprism.hazelcast;

import com.hazelcast.map.IMap;
import io.github.aindriub.dataprism.core.refusal.ClusterLockTimeoutException;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Every lock this module takes goes through here, so no wait is unbounded.
 *
 * <p>A mutating {@code IMap} call on a key another owner has locked waits for that lock, so an approval
 * change must hold the lock itself, taken with a timeout, before it writes. Hazelcast gives such a lock no
 * protection under split brain; correctness does not rest on it, only the bound does (approvals
 * change by compare-and-set and counters by atomic entry processors). The lock lease is {@value #LEASE_FACTOR} times the timeout, so a lock whose
 * holder died is released on its own and the longest stall it can cause is known.
 */
final class ClusterLocks {

    static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(2);
    static final Duration MIN_TIMEOUT = Duration.ofMillis(100);
    static final Duration MAX_TIMEOUT = Duration.ofSeconds(30);

    /** The lease of every lock taken here, as a multiple of the lock timeout. */
    static final int LEASE_FACTOR = 5;

    private ClusterLocks() {
    }

    /** The timeout itself, once checked to lie in {@code PT100MS..PT30S}. */
    static Duration checked(Duration timeout) {
        Objects.requireNonNull(timeout, "lockTimeout");
        if (timeout.compareTo(MIN_TIMEOUT) < 0 || timeout.compareTo(MAX_TIMEOUT) > 0) {
            throw new IllegalArgumentException("lock timeout must be between " + MIN_TIMEOUT + " and " + MAX_TIMEOUT);
        }
        return timeout;
    }

    static long leaseMillis(Duration timeout) {
        return timeout.toMillis() * LEASE_FACTOR;
    }

    /**
     * Takes the lock on {@code key}, waiting at most {@code timeout}.
     *
     * @throws ClusterLockTimeoutException if it was not obtained in time, or the thread was interrupted
     */
    static void acquire(IMap<String, ?> map, String key, Duration timeout) {
        try {
            if (!map.tryLock(key, timeout.toMillis(), TimeUnit.MILLISECONDS, leaseMillis(timeout),
                    TimeUnit.MILLISECONDS)) {
                throw new ClusterLockTimeoutException();
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new ClusterLockTimeoutException();
        }
    }

    /** Releases a lock taken by {@link #acquire}; never throws, so it cannot mask the real failure. */
    static void release(IMap<String, ?> map, String key) {
        try {
            map.unlock(key);
        } catch (RuntimeException ignored) {
            // The lock dies with the member, or its lease ran out; throwing here would mask the real failure.
        }
    }
}

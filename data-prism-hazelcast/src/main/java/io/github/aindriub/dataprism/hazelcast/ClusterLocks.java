package io.github.aindriub.dataprism.hazelcast;

import com.hazelcast.map.IMap;
import io.github.aindriub.dataprism.core.refusal.ClusterLockTimeoutException;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Every lock this module takes goes through here, so no wait is unbounded.
 *
 * <p>A mutating {@code IMap} call on a key another owner has locked waits for that lock, so a change must hold
 * the lock itself, taken with a timeout, before it writes. The lock bounds waits only. Hazelcast gives it no
 * protection under split brain and a paused owner loses it when its lease runs out, so correctness never rests
 * on it: approvals change by compare-and-set, approval ids are claimed by {@code putIfAbsent}, and counters and
 * pending-approval sets change by atomic entry processors. Every lock has a lease of {@value #LEASE_FACTOR}
 * times the timeout, so no owner can hold one for ever.
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
     * Takes the lock on {@code key}, waiting at most {@code timeout}, for a lease of
     * {@link #leaseMillis(Duration)}.
     *
     * @throws ClusterLockTimeoutException       if it was not obtained in time, or the thread was interrupted
     * @throws ClusterOperationRefusedException if Hazelcast rejected the lock or its lease (for example because
     *                                          {@code hazelcast.lock.max.lease.time.seconds} is below it)
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
        } catch (IllegalArgumentException rejected) {
            throw new ClusterOperationRefusedException();
        }
    }

    /**
     * Takes the lock only if it is free right now, with the lease {@link #acquire} would give for {@code timeout}.
     * Never waits and never throws: a lock Hazelcast rejects counts as not taken.
     */
    static boolean tryAcquireNow(IMap<String, ?> map, String key, Duration timeout) {
        try {
            return map.tryLock(key, 0, TimeUnit.MILLISECONDS, leaseMillis(timeout), TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        } catch (RuntimeException rejected) {
            return false;
        }
    }

    /** Releases a lock; never throws, so it cannot mask the real failure. */
    static void release(IMap<String, ?> map, String key) {
        try {
            map.unlock(key);
        } catch (RuntimeException ignored) {
            // The lock dies with the member, or its lease ran out; throwing here would mask the real failure.
        }
    }
}

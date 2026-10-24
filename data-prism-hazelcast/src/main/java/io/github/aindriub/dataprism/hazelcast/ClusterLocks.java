package io.github.aindriub.dataprism.hazelcast;

import com.hazelcast.map.IMap;
import io.github.aindriub.dataprism.core.refusal.ClusterLockTimeoutException;
import io.github.aindriub.dataprism.core.refusal.ClusterOperationRefusedException;

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
     * <p>A call that throws here has written nothing under this lock, but the caller's earlier steps may have
     * changed state, so the exception is not a promise that the whole operation changed nothing.
     *
     * @throws ClusterLockTimeoutException       if it was not obtained in time, or the thread was interrupted
     * @throws ClusterOperationRefusedException if Hazelcast rejected the lock or its lease (for example because
     *                                          {@code hazelcast.lock.max.lease.time.seconds} is below it)
     */
    static void acquire(IMap<String, ?> map, String key, Duration timeout) {
        acquire(map, key, timeout.toMillis(), leaseMillis(timeout));
    }

    /** As {@link #acquire(IMap, String, Duration)}, waiting only for what is left of a deadline the caller fixed. */
    static void acquireUntil(IMap<String, ?> map, String key, long deadlineNanos, Duration timeout) {
        long remaining = Math.max(0, TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime()));
        acquire(map, key, remaining, leaseMillis(timeout));
    }

    private static void acquire(IMap<String, ?> map, String key, long waitMillis, long leaseMillis) {
        try {
            if (!map.tryLock(key, waitMillis, TimeUnit.MILLISECONDS, leaseMillis, TimeUnit.MILLISECONDS)) {
                throw new ClusterLockTimeoutException();
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new ClusterLockTimeoutException();
        } catch (IllegalArgumentException rejected) {
            throw ClusterRefusals.refused(rejected);
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

    /**
     * Bounded writes. A {@code set}, {@code put}, {@code delete} or {@code putIfAbsent} on a key another owner has
     * locked waits for that lock without bound (spike 219, answer 10), so each of these takes the key's lock first,
     * waiting at most {@code timeout}, and refuses with {@link ClusterLockTimeoutException} if it cannot. The write
     * itself is then not blocked by anyone else's lock. The lock bounds the wait only; correctness never rests on it.
     */
    static <V> void set(IMap<String, V> map, String key, V value, long ttlMillis, Duration timeout) {
        acquire(map, key, timeout);
        try {
            map.set(key, value, ttlMillis, TimeUnit.MILLISECONDS);
        } finally {
            release(map, key);
        }
    }

    /** {@code putIfAbsent} under the key's bounded lock; returns the existing value, or null if it stored. */
    static <V> V putIfAbsent(IMap<String, V> map, String key, V value, long ttlMillis, Duration timeout) {
        acquire(map, key, timeout);
        try {
            return map.putIfAbsent(key, value, ttlMillis, TimeUnit.MILLISECONDS);
        } finally {
            release(map, key);
        }
    }

    /** {@code delete} under the key's bounded lock. */
    static void delete(IMap<String, ?> map, String key, Duration timeout) {
        acquire(map, key, timeout);
        try {
            map.delete(key);
        } finally {
            release(map, key);
        }
    }

    /**
     * Deletes each key under its own bounded lock and goes on after a key whose lock times out; returns how many it
     * left in place for that reason. The whole call shares one deadline of {@code timeout}: each lock waits for what
     * is left of it, and once it has passed a key is still deleted if its lock is free right now. A caller that must
     * report the failure throws {@link ClusterLockTimeoutException} when the count is not zero, after the last key,
     * so one held key does not stop the rest of a scope from ending. A lock Hazelcast refuses is treated the same way
     * for the remaining keys, and the first such refusal is rethrown after the last key.
     */
    static int deleteAll(IMap<String, ?> map, java.util.Collection<String> keys, Duration timeout) {
        Sweep sweep = deleteAll(map, keys, System.nanoTime() + timeout.toNanos(), timeout);
        if (sweep.refusal() != null) {
            throw sweep.refusal();
        }
        return sweep.skipped();
    }

    /**
     * What a sweep left behind: how many keys it skipped for a lock timeout, and the first refusal, if any. Nothing
     * is thrown, so a caller sweeping several maps can finish them all and then throw once.
     */
    record Sweep(int skipped, ClusterOperationRefusedException refusal) {
        Sweep plus(Sweep other) {
            return new Sweep(skipped + other.skipped, refusal != null ? refusal : other.refusal);
        }
    }

    /**
     * As {@link #deleteAll(IMap, java.util.Collection, Duration)} against a deadline the caller fixed ({@link
     * System#nanoTime()} units), so several sweeps can share one. A {@code HazelcastException} from a delete counts as
     * a refusal for that key and the sweep goes on. Never throws for a key.
     */
    static Sweep deleteAll(IMap<String, ?> map, java.util.Collection<String> keys, long deadlineNanos,
                           Duration timeout) {
        long lease = leaseMillis(timeout);
        int skipped = 0;
        ClusterOperationRefusedException firstRefusal = null;
        for (String key : keys) {
            long remaining = Math.max(0, TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime()));
            try {
                acquire(map, key, remaining, lease);
                try {
                    map.delete(key);
                } finally {
                    release(map, key);
                }
            } catch (ClusterLockTimeoutException held) {
                skipped++;
            } catch (ClusterOperationRefusedException refused) {
                if (firstRefusal == null) {
                    firstRefusal = refused;
                }
            } catch (com.hazelcast.core.HazelcastException failed) {
                ClusterOperationRefusedException refused = ClusterRefusals.refused(failed);
                if (firstRefusal == null) {
                    firstRefusal = refused;
                }
            }
        }
        return new Sweep(skipped, firstRefusal);
    }
}

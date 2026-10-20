package io.github.aindriub.dataprism.hazelcast;

import com.hazelcast.config.Config;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.map.IMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Bounds how long a requester's pending entry, and an id claim, outlive their approvals; see {@link ClaimEntries} for
 * the claims.
 *
 * <p>Bounds how long a requester's pending entry outlives its approvals. The entry has no TTL, because any TTL could
 * drop a live id and let the cap be passed. Instead each member periodically runs {@link PendingSet.Sweep} over the
 * pending keys it owns, passing its own clock reading in. Retention is therefore bounded by an approval's expiry,
 * plus {@link #SLACK}, plus the sweep interval.
 *
 * <p>Starts with the {@link PrivacyCluster} and stops with it, or when the member stops running. A sweep that fails
 * (for example because this member has lost the write quorum) is logged and tried again at the next interval; a
 * failed sweep can only leave an entry in place, never lower a count.
 */
final class PendingSweeper implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(PendingSweeper.class);

    /** The Hazelcast config property that sets the sweep interval, in seconds. */
    static final String INTERVAL_PROPERTY = "dataprism.approval.pending.sweep-seconds";

    static final Duration DEFAULT_INTERVAL = Duration.ofMinutes(5);

    /** How long past its expiry an id stays counted, which covers clock differences between callers. */
    static final Duration SLACK = Duration.ofMinutes(1);

    private final HazelcastInstance instance;
    private final Clock clock;
    private final ScheduledExecutorService executor;
    private volatile Duration lockTimeout = ClusterLocks.DEFAULT_TIMEOUT;

    PendingSweeper(HazelcastInstance instance, Duration interval, Clock clock) {
        this.instance = instance;
        this.clock = clock;
        this.executor = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "dataprism-pending-sweep");
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleWithFixedDelay(this::sweepQuietly, interval.toMillis(), interval.toMillis(),
                TimeUnit.MILLISECONDS);
    }

    /**
     * The interval a config asks for.
     *
     * @throws IllegalArgumentException if the property is not a positive whole number of seconds
     */
    static Duration intervalOf(Config config) {
        String raw = config.getProperty(INTERVAL_PROPERTY);
        if (raw == null || raw.isBlank()) {
            return DEFAULT_INTERVAL;
        }
        try {
            long seconds = Long.parseLong(raw.strip());
            if (seconds >= 1 && seconds <= Duration.ofDays(1).toSeconds()) {
                return Duration.ofSeconds(seconds);
            }
        } catch (NumberFormatException notANumber) {
            // fall through
        }
        throw new IllegalArgumentException(INTERVAL_PROPERTY + " must be a whole number of seconds from 1 to 86400");
    }

    /** One sweep of the pending keys this member owns; returns how many ids it removed. */
    int sweep(Instant now) {
        IMap<String, String> pending = instance.getMap(PrivacyCluster.APPROVAL_PENDING_MAP);
        Set<String> owned = pending.localKeySet();
        if (owned.isEmpty()) {
            return 0;
        }
        int removed = 0;
        PendingSet.Sweep sweep = new PendingSet.Sweep(now, SLACK.toMillis());
        for (String key : owned) {
            // A key someone holds is left until the next interval, so no sweep waits on a lock.
            if (!ClusterLocks.tryAcquireNow(pending, key, lockTimeout)) {
                continue;
            }
            try {
                removed += pending.executeOnKey(key, sweep);
            } finally {
                ClusterLocks.release(pending, key);
            }
        }
        return removed;
    }

    /** One sweep of the id claims this member owns; returns how many stale claims it removed. */
    int sweepClaims() {
        return ClaimEntries.sweep(instance.getMap(PrivacyCluster.APPROVAL_ID_MAP),
                instance.getMap(PrivacyCluster.APPROVAL_MAP), lockTimeout);
    }

    /** The lock timeout the stores were configured with; the claim sweep leases its brief locks for that. */
    void useLockTimeout(Duration lockTimeout) {
        this.lockTimeout = lockTimeout;
    }

    private void sweepQuietly() {
        if (!instance.getLifecycleService().isRunning()) {
            executor.shutdown();
            return;
        }
        try {
            sweep(clock.instant());
        } catch (RuntimeException failure) {
            LOG.warn("pending-approval sweep failed: {}", failure.getClass().getSimpleName());
        }
        try {
            sweepClaims();
        } catch (RuntimeException failure) {
            LOG.warn("approval-id claim sweep failed: {}", failure.getClass().getSimpleName());
        }
    }

    boolean isStopped() {
        return executor.isShutdown();
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}

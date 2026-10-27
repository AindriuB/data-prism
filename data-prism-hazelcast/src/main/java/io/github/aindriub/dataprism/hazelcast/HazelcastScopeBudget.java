package io.github.aindriub.dataprism.hazelcast;

import com.hazelcast.map.EntryProcessor;
import com.hazelcast.map.IMap;
import io.github.aindriub.dataprism.core.limits.ReadReservation;
import io.github.aindriub.dataprism.core.limits.ScopeBudget;
import io.github.aindriub.dataprism.core.refusal.ClusterLockTimeoutException;
import io.github.aindriub.dataprism.core.refusal.ClusterOperationRefusedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A read budget shared across the cluster.
 *
 * <p>This is what makes the number mean what it says. Held per instance, a budget
 * of a hundred lets a caller spread across eight instances read a subject eight
 * hundred times, and the setting quietly means something other than it appears to.
 *
 * <p>Failing closed here, unlike the identity cache. The two are different kinds
 * of thing: an unreachable identity cache costs a recomputed HMAC and changes no
 * answer, whereas an unreachable budget means nobody is counting. Falling back to
 * an uncounted read would turn a cluster outage into an open door on the one
 * control that limits how much a caller can extract from one subject, and it
 * would do it silently.
 *
 * <p>Reservations: a reservation changes a subject counter and the scope total, which are two entries, so one
 * entry processor cannot do both (a processor sees only its own entry, and the keys are keyed names that do not
 * share a partition). Every operation on a scope therefore takes the scope's lock, the lock on the scope-total key,
 * bounded by the lock timeout, and updates each counter by its own atomic processor. Each processor enforces its own
 * budget, so neither counter can pass its budget even if a lock lease expires; the lock makes the pair one unit. If
 * the second update fails the first is undone, and if the undo fails the counter is left over-counted, never
 * under-counted. The total entry is created by the first {@code reserve} of a scope, from the counters already
 * there, so a scope that only ever uses {@code tryRead} holds one entry per subject, as before; from then on
 * {@code tryRead} adds to it.
 */
public final class HazelcastScopeBudget implements ScopeBudget {

    private static volatile Logger LOG = LoggerFactory.getLogger(HazelcastScopeBudget.class);

    private final PrivacyCluster cluster;
    private final Duration lockTimeout;

    /** With the default lock timeout of two seconds. */
    public HazelcastScopeBudget(PrivacyCluster cluster) {
        this(cluster, ClusterLocks.DEFAULT_TIMEOUT);
    }

    /**
     * @param lockTimeout how long a read waits for the subject's lock before it is refused, {@code PT100MS}
     *                    to {@code PT30S}
     * @throws IllegalArgumentException if the timeout is outside that range
     */
    public HazelcastScopeBudget(PrivacyCluster cluster, Duration lockTimeout) {
        this.cluster = Objects.requireNonNull(cluster, "cluster");
        this.lockTimeout = ClusterLocks.checked(lockTimeout);
    }

    /** What an update processor reports. */
    private static final int GRANTED = 0;
    private static final int OVER = 1;
    private static final int OVERFLOW = 2;

    /**
     * The reservations this instance granted, per scope, so a forget on this instance can invalidate them. Keyed by
     * the scope's keyed prefix, never the raw scope id. Each set holds its origins weakly, so a reservation the caller
     * dropped does not keep itself here; a scope whose reservations are all gone or fully released has no entry.
     */
    private final ConcurrentHashMap<String, Set<Origin>> issued = new ConcurrentHashMap<>();
    private final AtomicInteger grants = new AtomicInteger();
    private static final int SWEEP_EVERY = 64;

    /**
     * What a granted reservation carries as its origin. It points back at its own reservation, so a reservation
     * built around a live origin is not recognised, and it dies when this instance forgets the scope. A
     * {@code forget} made through another member or {@link ScopeIdentityIndex#endScope} does not reach it.
     */
    private static final class Origin {
        private final HazelcastScopeBudget owner;
        private final String scope;
        private final AtomicInteger held;
        private volatile boolean live = true;
        private volatile ReadReservation reservation;

        private Origin(HazelcastScopeBudget owner, String scope, int reads) {
            this.owner = owner;
            this.scope = scope;
            this.held = new AtomicInteger(reads);
        }
    }

    /**
     * @throws ClusterLockTimeoutException if the scope's lock or the subject's lock is held and not released within
     *                                     the lock timeout. That is not an exhausted budget and is not reported as
     *                                     one.
     * @throws ClusterOperationRefusedException if the cluster rejected the lock or its lease. That is a refusal of
     *                                          the read with its own code, not an exhausted budget, and it is
     *                                          already logged where it was made.
     */
    @Override
    public boolean tryRead(String scopeId, String subjectId, int budget) {
        String subjectKey = cluster.scopeKeys().budget(scopeId, subjectId);
        String scopeKey = cluster.scopeKeys().budgetTotal(scopeId);
        IMap<String, Integer> counts;
        // Obtaining the map and taking the lock are themselves cluster calls and
        // throw when the member is gone. They belong inside the guard: leaving
        // them outside meant the fail-closed path could not run, because the
        // exception escaped before reaching it.
        try {
            counts = counts();
            ClusterLocks.acquire(counts, scopeKey, lockTimeout);
        } catch (ClusterLockTimeoutException held) {
            LOG.error("read budget lock not obtained within {}; refusing the read", lockTimeout);
            throw held;
        } catch (ClusterOperationRefusedException rejected) {
            throw rejected;
        } catch (RuntimeException clusterFailure) {
            return refuse(clusterFailure);
        }

        try {
            try {
                ClusterLocks.acquire(counts, subjectKey, lockTimeout);
            } catch (ClusterLockTimeoutException held) {
                LOG.error("read budget lock not obtained within {}; refusing the read", lockTimeout);
                throw held;
            } catch (ClusterOperationRefusedException rejected) {
                throw rejected;
            } catch (RuntimeException clusterFailure) {
                return refuse(clusterFailure);
            }
            try {
                // The leases bound lock stalls; correctness belongs to the atomic map transitions.
                int code = counts.executeOnKey(subjectKey, new AddWithin(1, budget));
                if (code != GRANTED) {
                    // Refusals do not count, so a later budget increase can still admit reads.
                    return false;
                }
                try {
                    // Counted toward the scope total, which this read does not check.
                    if (counts.executeOnKey(scopeKey, new IncrementIfPresent()) == GRANTED) {
                        return true;
                    }
                } catch (RuntimeException totalFailed) {
                    // Falls through to the undo: a read that cannot reach the total is not counted.
                }
                undo(counts, subjectKey, 1);
                return false;
            } catch (RuntimeException clusterFailure) {
                return refuse(clusterFailure);
            } finally {
                ClusterLocks.release(counts, subjectKey);
            }
        } finally {
            ClusterLocks.release(counts, scopeKey);
        }
    }

    /**
     * Reserves {@code reads} against the subject's counter and the scope total as one unit, under the scope's lock.
     * The subject is checked first, so a reservation both limits would refuse reports {@code SUBJECT}.
     *
     * @throws IllegalArgumentException if an argument is not positive or a counter would overflow; nothing changes
     * @throws ClusterLockTimeoutException if the scope's lock was not obtained within the lock timeout
     * @throws ClusterOperationRefusedException if the cluster rejected a lock or an update; both counters are as
     *                                          they were, or over-counted if an undo failed too
     */
    @Override
    public ReadReservation reserve(
            String scopeId, String subjectId, int reads, int subjectBudget, int scopeTotalBudget) {
        Objects.requireNonNull(scopeId, "scopeId");
        Objects.requireNonNull(subjectId, "subjectId");
        if (reads <= 0 || subjectBudget <= 0 || scopeTotalBudget <= 0) {
            throw new IllegalArgumentException("reads and both budgets must be positive");
        }
        ScopeKeys keys = cluster.scopeKeys();
        String subjectKey = keys.budget(scopeId, subjectId);
        String scopeKey = keys.budgetTotal(scopeId);
        IMap<String, Integer> counts = lockScope(scopeKey);
        boolean subjectLocked = false;
        try {
            lockSubject(counts, subjectKey);
            subjectLocked = true;
            ensureTotal(counts, scopeId, scopeKey);
            int subject = counts.executeOnKey(subjectKey, new AddWithin(reads, subjectBudget));
            if (subject == OVERFLOW) {
                throw overflow();
            }
            if (subject == OVER) {
                return ReadReservation.refused(scopeId, subjectId, reads, ReadReservation.Refusal.SUBJECT);
            }
            int total;
            try {
                total = counts.executeOnKey(scopeKey, new AddWithin(reads, scopeTotalBudget));
            } catch (RuntimeException totalFailed) {
                undo(counts, subjectKey, reads);
                throw totalFailed;
            }
            if (total != GRANTED) {
                undo(counts, subjectKey, reads);
                if (total == OVERFLOW) {
                    throw overflow();
                }
                return ReadReservation.refused(scopeId, subjectId, reads, ReadReservation.Refusal.SCOPE_TOTAL);
            }
            String scope = keys.scopePrefix(scopeId);
            Origin origin = new Origin(this, scope, reads);
            ReadReservation granted = ReadReservation.granted(scopeId, subjectId, reads, origin);
            origin.reservation = granted;
            issued.compute(scope, (k, origins) -> {
                Set<Origin> set = origins != null ? origins
                        : Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));
                set.add(origin);
                return set;
            });
            if (grants.incrementAndGet() % SWEEP_EVERY == 0) {
                sweepIssued();
            }
            return granted;
        } catch (IllegalArgumentException invalid) {
            throw invalid;
        } catch (RuntimeException failed) {
            throw ClusterRefusals.mapped(failed);
        } finally {
            if (subjectLocked) {
                ClusterLocks.release(counts, subjectKey);
            }
            ClusterLocks.release(counts, scopeKey);
        }
    }

    /**
     * Gives back at most what the reservation still holds, once. A release this budget cannot match (a refused
     * reservation, one built by hand, one from another budget instance, or one from a scope this instance has since
     * forgotten) does nothing. Taken under the scope's lock, then the subject's.
     *
     * <p>If the cluster fails after the reads were taken out of the reservation, they stay charged: the counters keep
     * them. The one exception is a failure of both the total's update and the restore of the subject: the subject
     * is then under-counted by those reads, and one WARN line says so.
     *
     * <p><strong>Residual (OD-285-1 (a)).</strong> "A release after {@code forget} does nothing" holds for a
     * {@code forget} made on this instance only. A {@code forget} or {@code ScopeIdentityIndex.endScope} on another
     * member does not reach this instance's reservations; if the scope id is then reused, a release of an old
     * reservation can subtract from the new scope's counters, clamped to what they hold. Stored scope generations
     * (task 226) close it. Whichever task first calls {@code forget} or {@code endScope} in production must close it
     * first or depend on 226.
     *
     * @throws ClusterLockTimeoutException if the scope's lock was not obtained within the lock timeout
     * @throws ClusterOperationRefusedException if the cluster rejected the lock
     */
    @Override
    public void release(ReadReservation reservation, int unused) {
        if (unused < 0) {
            throw new IllegalArgumentException("unused must not be negative");
        }
        Objects.requireNonNull(reservation, "reservation");
        if (!(reservation.origin() instanceof Origin origin) || origin.owner != this
                || origin.reservation != reservation || !origin.live) {
            return;
        }
        ScopeKeys keys = cluster.scopeKeys();
        String scopeKey = keys.budgetTotal(reservation.scopeId());
        String subjectKey = keys.budget(reservation.scopeId(), reservation.subjectId());
        IMap<String, Integer> counts = lockScope(scopeKey);
        boolean subjectLocked = false;
        try {
            lockSubject(counts, subjectKey);
            subjectLocked = true;
            if (!origin.live) {
                return;
            }
            int give = reservation.takeUnused(unused);
            if (give == 0) {
                return;
            }
            if (origin.held.addAndGet(-give) == 0) {
                untrack(origin);
            }
            try {
                int back = counts.executeOnKey(subjectKey, new Subtract(give));
                if (back > 0) {
                    try {
                        counts.executeOnKey(scopeKey, new Subtract(back));
                    } catch (RuntimeException totalFailed) {
                        restore(counts, subjectKey, back);
                        throw totalFailed;
                    }
                }
            } catch (RuntimeException failed) {
                LOG.error("read budget unavailable; the reads of a release stay charged: {}",
                        failed.getClass().getSimpleName());
            }
        } finally {
            if (subjectLocked) {
                ClusterLocks.release(counts, subjectKey);
            }
            ClusterLocks.release(counts, scopeKey);
        }
    }

    /** Drops the records of scopes whose reservations have all been collected or released. */
    private void sweepIssued() {
        for (String scope : List.copyOf(issued.keySet())) {
            issued.computeIfPresent(scope, (k, origins) -> origins.isEmpty() ? null : origins);
        }
    }

    /** Stops tracking a reservation that holds nothing, and its scope if that was the last. */
    private void untrack(Origin origin) {
        issued.computeIfPresent(origin.scope, (k, origins) -> {
            origins.remove(origin);
            return origins.isEmpty() ? null : origins;
        });
    }

    /** @return reads counted against the whole scope, across subjects */
    @Override
    public int scopeReads(String scopeId) {
        Objects.requireNonNull(scopeId, "scopeId");
        IMap<String, Integer> counts = counts();
        String scopeKey = cluster.scopeKeys().budgetTotal(scopeId);
        Integer total = counts.get(scopeKey);
        return total != null ? total : (int) Math.min(Integer.MAX_VALUE, sumOfSubjects(counts, scopeId, scopeKey));
    }

    /**
     * Creates the scope total from the counters already there, if the scope has none. Called under the scope's lock,
     * which is the lock on the total's own key, held by this thread: the write cannot wait on another holder.
     */
    private void ensureTotal(IMap<String, Integer> counts, String scopeId, String scopeKey) {
        if (counts.get(scopeKey) == null) {
            counts.putIfAbsent(scopeKey, (int) Math.min(Integer.MAX_VALUE, sumOfSubjects(counts, scopeId, scopeKey)));
        }
    }

    private long sumOfSubjects(IMap<String, Integer> counts, String scopeId, String scopeKey) {
        Set<String> keys = new HashSet<>(ScopeKeys.keysWithPrefix(counts, cluster.scopeKeys().scopePrefix(scopeId)));
        keys.remove(scopeKey);
        long sum = 0;
        for (Integer value : counts.getAll(keys).values()) {
            sum += value;
        }
        return sum;
    }

    /** Takes a subject counter's lock, inside the scope's, or throws the refusal for it. */
    private void lockSubject(IMap<String, Integer> counts, String subjectKey) {
        try {
            ClusterLocks.acquire(counts, subjectKey, lockTimeout);
        } catch (ClusterLockTimeoutException held) {
            LOG.error("read budget lock not obtained within {}; refusing", lockTimeout);
            throw held;
        } catch (ClusterOperationRefusedException rejected) {
            throw rejected;
        } catch (RuntimeException failed) {
            throw ClusterRefusals.mapped(failed);
        }
    }

    /** Takes the scope's lock, or throws the refusal for it. */
    private IMap<String, Integer> lockScope(String scopeKey) {
        try {
            IMap<String, Integer> counts = counts();
            ClusterLocks.acquire(counts, scopeKey, lockTimeout);
            return counts;
        } catch (ClusterLockTimeoutException held) {
            LOG.error("read budget lock not obtained within {}; refusing", lockTimeout);
            throw held;
        } catch (ClusterOperationRefusedException rejected) {
            throw rejected;
        } catch (RuntimeException failed) {
            throw ClusterRefusals.mapped(failed);
        }
    }

    /** Takes {@code reads} back off a counter. If that fails too the counter stays over-counted; one line says so. */
    private static void undo(IMap<String, Integer> counts, String key, int reads) {
        try {
            counts.executeOnKey(key, new Subtract(reads));
        } catch (RuntimeException undoFailed) {
            LOG.warn("read budget undo failed; a counter is left over-counted: {}",
                    undoFailed.getClass().getSimpleName());
        }
    }

    /** Puts {@code reads} back on a counter after a release that could not finish. */
    private static void restore(IMap<String, Integer> counts, String key, int reads) {
        try {
            counts.executeOnKey(key, new AddWithin(reads, Integer.MAX_VALUE));
        } catch (RuntimeException restoreFailed) {
            LOG.warn("read budget restore failed; a counter is left under-counted: {}",
                    restoreFailed.getClass().getSimpleName());
        }
    }

    private static IllegalArgumentException overflow() {
        return new IllegalArgumentException("reservation overflows a counter");
    }

    /** Adds {@code delta} if the result stays within {@code cap}. */
    private record AddWithin(int delta, int cap) implements EntryProcessor<String, Integer, Integer> {
        @Override
        public Integer process(Map.Entry<String, Integer> entry) {
            int current = entry.getValue() == null ? 0 : entry.getValue();
            long next = (long) current + delta;
            if (next > Integer.MAX_VALUE) return OVERFLOW;
            if (next > cap) return OVER;
            entry.setValue((int) next);
            return GRANTED;
        }
    }

    /** Adds one to an entry that exists and does nothing to one that does not. */
    private record IncrementIfPresent() implements EntryProcessor<String, Integer, Integer> {
        @Override
        public Integer process(Map.Entry<String, Integer> entry) {
            Integer current = entry.getValue();
            if (current == null) return GRANTED;
            if (current == Integer.MAX_VALUE) return OVERFLOW;
            entry.setValue(current + 1);
            return GRANTED;
        }
    }

    /** Takes off at most {@code delta}, never below zero; returns how much it took. */
    private record Subtract(int delta) implements EntryProcessor<String, Integer, Integer> {
        @Override
        public Integer process(Map.Entry<String, Integer> entry) {
            int current = entry.getValue() == null ? 0 : entry.getValue();
            int taken = Math.min(delta, current);
            if (taken > 0) entry.setValue(current - taken);
            return taken;
        }
    }

    private static boolean refuse(RuntimeException clusterFailure) {
        LOG.error("read budget unavailable; refusing the read rather than leaving it "
                + "uncounted: {}", clusterFailure.getClass().getSimpleName());
        return false;
    }

    @Override
    public int reads(String scopeId, String subjectId) {
        return counts().getOrDefault(cluster.scopeKeys().budget(scopeId, subjectId), 0);
    }

    /**
     * Deletes the scope's counts and its total under the scope's lock, each key also under its own bounded lock.
     * {@link ScopeIdentityIndex#endScope} does not call this: it deletes the budget keys itself, under the one
     * deadline it shares across all its maps. Because {@code forget} can be called on its own, with no caller's
     * deadline, it takes a lock timeout of its own per key. Reservations this instance granted for the scope can no
     * longer be released.
     *
     * @throws ClusterLockTimeoutException if the scope's lock or the lock of a key timed out; that count is left in
     *                                     place
     */
    @Override
    public void forget(String scopeId) {
        ScopeKeys keys = cluster.scopeKeys();
        String scopeKey = keys.budgetTotal(scopeId);
        IMap<String, Integer> counts = lockScope(scopeKey);
        try {
            List<String> found;
            try {
                found = ScopeKeys.keysWithPrefix(counts, keys.scopePrefix(scopeId));
            } catch (RuntimeException failed) {
                throw ClusterRefusals.mapped(failed);
            }
            // Each delete runs under its key's bounded lock, so a key another member holds cannot make this wait.
            int skipped = ClusterLocks.deleteAll(counts, found, lockTimeout);
            if (skipped > 0) {
                throw new ClusterLockTimeoutException();
            }
        } finally {
            // However the sweep ended: a reservation of a scope being forgotten must not release into what is left.
            Set<Origin> origins = issued.remove(keys.scopePrefix(scopeId));
            if (origins != null) {
                synchronized (origins) {
                    origins.forEach(origin -> origin.live = false);
                }
            }
            ClusterLocks.release(counts, scopeKey);
        }
    }

    /** Routes this class's log lines to {@code replacement} until the returned handle is closed; for tests. */
    static AutoCloseable captureTo(Logger replacement) {
        Logger previous = LOG;
        LOG = replacement;
        return () -> LOG = previous;
    }

    /** How many scopes this instance holds reservation records for; for tests. */
    int trackedScopes() {
        return issued.size();
    }

    private IMap<String, Integer> counts() {
        return cluster.instance().getMap(PrivacyCluster.BUDGET_MAP);
    }
}

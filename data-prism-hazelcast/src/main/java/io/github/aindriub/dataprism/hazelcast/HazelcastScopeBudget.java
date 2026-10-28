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
 * share a partition). Every {@code reserve}, {@code release} and {@code forget} on a scope therefore takes the
 * scope's lock, the lock on the scope-total key, bounded by the lock timeout, and updates each counter by its own
 * atomic processor. A {@code tryRead} takes the scope's lock only while the scope has no total; after that it needs
 * only its subject's lock, so reads of different subjects do not wait for each other. Each processor enforces its own
 * budget, so neither counter can pass its budget even if a lock lease expires. Under the scope's lock the pair is
 * one unit; the unlocked {@code tryRead} path updates the subject and the total under different locks, and a reader
 * may see one without the other for a moment, which under-states the total for that moment only, never in a finished
 * total. If the second update of a
 * {@code reserve} fails the first is undone, and if the undo fails the counter is left over-counted, never
 * under-counted. The total entry is created by the first {@code reserve} of a scope, from the counters already
 * there, so a scope that only ever uses {@code tryRead} holds one entry per subject, as before; from then on
 * {@code tryRead} adds to it.
 *
 * <p>Invariant: at every moment the scope total, when it is not being built, is at least the sum of the reads
 * granted in the scope, so a {@code reserve} never goes past the scope budget; it can be over-counted, never
 * under-counted, except across scope-id reuse (see the residual in the {@code release} Javadoc), which task 226
 * closes. See {@code ensureTotal} for how a total is built without depending on any lock's lease.
 */
public final class HazelcastScopeBudget implements ScopeBudget {

    private static final Logger DEFAULT_LOG = LoggerFactory.getLogger(HazelcastScopeBudget.class);

    private final PrivacyCluster cluster;
    private final Duration lockTimeout;
    private final Logger log;

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
        this(cluster, lockTimeout, DEFAULT_LOG);
    }

    /** As the public constructor, logging to {@code log}; for tests, so that no logger is shared between them. */
    HazelcastScopeBudget(PrivacyCluster cluster, Duration lockTimeout, Logger log) {
        this.cluster = Objects.requireNonNull(cluster, "cluster");
        this.lockTimeout = ClusterLocks.checked(lockTimeout);
        this.log = Objects.requireNonNull(log, "log");
    }

    /** What an update processor reports. */
    private static final int GRANTED = 0;
    private static final int OVER = 1;
    private static final int OVERFLOW = 2;
    /** The scope total is absent or still being built, so it cannot be checked against. */
    private static final int NOT_READY = 3;
    /** A total being built, with no read counted since it was created. A negative value is {@code -(reads + 1)}. */
    private static final int BUILDING = -1;

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
        /** Reads released through this instance so far; only decides when to stop tracking, never what to give. */
        private final AtomicInteger released = new AtomicInteger();
        private volatile boolean live = true;
        private volatile ReadReservation reservation;

        private Origin(HazelcastScopeBudget owner, String scope) {
            this.owner = owner;
            this.scope = scope;
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
        boolean scopeLocked = false;
        // Obtaining the map and taking the lock are themselves cluster calls and
        // throw when the member is gone. They belong inside the guard: leaving
        // them outside meant the fail-closed path could not run, because the
        // exception escaped before reaching it.
        try {
            counts = counts();
            // Once the scope has its total a read is a pair of atomic additions, each under its own subject's lock,
            // so reads of different subjects do not wait for each other. The scope's lock is taken only while the
            // total does not exist. While the builder's lease holds, that keeps a read from being counted both in the
            // builder's sum and in the marker's count, so it reduces over-counting during a build; the build is
            // correct without it, because its marker makes every read be counted at least once.
            if (!counts.containsKey(scopeKey)) {
                ClusterLocks.acquire(counts, scopeKey, lockTimeout);
                scopeLocked = true;
            }
        } catch (ClusterLockTimeoutException held) {
            log.error("read budget lock not obtained within {}; refusing the read", lockTimeout);
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
                log.error("read budget lock not obtained within {}; refusing the read", lockTimeout);
                throw held;
            } catch (ClusterOperationRefusedException rejected) {
                throw rejected;
            } catch (RuntimeException clusterFailure) {
                return refuse(clusterFailure);
            }
            boolean subjectHeld = true;
            try {
                // The leases bound lock stalls; correctness belongs to the atomic map transitions.
                int code = counts.executeOnKey(subjectKey, new AddWithin(1, budget));
                if (code != GRANTED) {
                    // Refusals do not count, so a later budget increase can still admit reads.
                    return false;
                }
                if (!scopeLocked) {
                    // Without the scope's lock the order scope, then subject, cannot be kept, and the add to the
                    // total waits whenever someone holds the total's key. Holding the subject's lock through that
                    // wait would make a reserve, release or forget of this subject wait for it, so it is let go.
                    ClusterLocks.release(counts, subjectKey);
                    subjectHeld = false;
                }
                try {
                    // Counted toward the scope total, which this read does not check.
                    if (counts.executeOnKey(scopeKey, new IncrementIfPresent()) == GRANTED) {
                        return true;
                    }
                } catch (RuntimeException totalFailed) {
                    // Falls through to the undo: a read that cannot reach the total is not counted. Logged as any
                    // other cluster failure of a read is, so it cannot pass for an exhausted budget.
                    refuse(totalFailed);
                }
                if (subjectHeld) {
                    undo(counts, subjectKey, 1);
                } else {
                    undoRelocking(counts, subjectKey, 1);
                }
                return false;
            } catch (RuntimeException clusterFailure) {
                return refuse(clusterFailure);
            } finally {
                if (subjectHeld) {
                    ClusterLocks.release(counts, subjectKey);
                }
            }
        } finally {
            if (scopeLocked) {
                ClusterLocks.release(counts, scopeKey);
            }
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
                total = counts.executeOnKey(scopeKey, new AddToTotal(reads, scopeTotalBudget));
            } catch (RuntimeException totalFailed) {
                undo(counts, subjectKey, reads);
                throw totalFailed;
            }
            if (total == NOT_READY) {
                undo(counts, subjectKey, reads);
                throw ClusterRefusals.refused(new IllegalStateException("scope total not ready"));
            }
            if (total != GRANTED) {
                undo(counts, subjectKey, reads);
                if (total == OVERFLOW) {
                    throw overflow();
                }
                return ReadReservation.refused(scopeId, subjectId, reads, ReadReservation.Refusal.SCOPE_TOTAL);
            }
            String scope = keys.scopePrefix(scopeId);
            Origin origin = new Origin(this, scope);
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
     * <p>Once both locks are held, the reads to give back are claimed from the reservation itself, atomically
     * ({@code takeUnused}), before any counter is written; then the subject counter is lowered, then the total.
     * Failures are sorted by whether a write could already have happened. One raised before any write
     * (a lock timeout or refusal taking the scope's or the subject's lock) leaves the reservation whole, and the
     * release can be tried again. One raised from either update itself may have come after the write, so the
     * outcome is unknown: the reservation is spent, the counters are left as they are (over-counted if the write did
     * not run, never under-counted), the total is not attempted after a subject update of unknown outcome, and the
     * failure is thrown as a refusal after one WARN line with the cause's class name only. A retry is then a
     * no-op and cannot subtract what other reservations hold.
     *
     * <p><strong>Residual (OD-285-1 (a)).</strong> "A release after {@code forget} does nothing" holds for a
     * {@code forget} made on this instance only. A {@code forget} or {@code ScopeIdentityIndex.endScope} on another
     * member does not reach this instance's reservations; if the scope id is then reused, a release of an old
     * reservation can subtract from the new scope's counters, clamped to what they hold. Stored scope generations
     * (task 226) close it. Whichever task first calls {@code forget} or {@code endScope} in production must close it
     * first or depend on 226. The same family: a total builder whose lock lapsed can finish late, with a sum taken
     * before a {@code forget} or {@code endScope}, onto a marker built after the scope id was reused, which
     * under-counts the new scope.
     *
     * <p><strong>Residual (OD-285-3 (a)), the same family and needing no reservation.</strong> An unlocked
     * {@code tryRead} (the scope already has a total) lets go of its subject's lock before its update of the total. A
     * {@code forget} on any instance, this one included, can delete that subject in the gap. If the scope id is then
     * reused and the read's total update fails, the read's undo subtracts from the new scope's counter, an
     * under-count. The same holds for a release whose locks lapse after it has claimed (stalled for longer than 5
     * times the lock timeout): if {@code forget} runs on this instance in that gap, its Subtract can land on a reused
     * scope's counters. Task 226 closes it with the rest; {@code forget} and {@code endScope} have no production
     * callers yet.
     *
     * @throws ClusterLockTimeoutException if the scope's lock was not obtained within the lock timeout
     * @throws ClusterOperationRefusedException if the cluster rejected a lock or failed an update. The failure is not
     *                                          swallowed. Only a failure before any write (taking a lock) leaves the
     *                                          reservation whole and releasable again; one from an update leaves it
     *                                          spent
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
            // The reservation itself says what is left, and the claim is atomic, so nothing depends on the locks for
            // this: two releases that overlap (a lease can lapse) claim disjoint reads, and reads already taken out
            // by any other holder are not subtracted. A failure before the claim (taking a lock) has written
            // nothing and leaves the reservation whole; the updates below are sorted next.
            int give = reservation.takeUnused(unused);
            if (give == 0) {
                return;
            }
            // From here on a failure may have come after the write. The reservation is spent either way, so that a
            // retry cannot subtract again what another reservation holds; the counters stay as they are, and
            // whatever the unknown outcome left is over-counted, never under-counted. The total is not attempted
            // after a subject update of unknown outcome.
            int back;
            try {
                back = counts.executeOnKey(subjectKey, new Subtract(give));
            } catch (RuntimeException failed) {
                tracked(origin, give);
                throw ClusterRefusals.mapped(failed);
            }
            try {
                if (back > 0) {
                    counts.executeOnKey(scopeKey, new Subtract(back));
                }
            } catch (RuntimeException failed) {
                tracked(origin, give);
                throw ClusterRefusals.mapped(failed);
            }
            tracked(origin, give);
        } finally {
            if (subjectLocked) {
                ClusterLocks.release(counts, subjectKey);
            }
            ClusterLocks.release(counts, scopeKey);
        }
    }

    /** Notes that {@code give} reads were claimed, and stops tracking a reservation that has given up all its reads. */
    private void tracked(Origin origin, int give) {
        if (origin.released.addAndGet(give) == origin.reservation.reads()) {
            untrack(origin);
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

    /**
     * @return reads counted against the whole scope, across subjects. A scope that has counters and no total gets its
     *         total here, under the scope's lock, as the first {@code reserve} would, so later calls read one entry
     *         and not every counter of the scope.
     * @throws ClusterLockTimeoutException if the scope has no total and its lock was not obtained in time
     */
    @Override
    public int scopeReads(String scopeId) {
        Objects.requireNonNull(scopeId, "scopeId");
        IMap<String, Integer> counts = counts();
        String scopeKey = cluster.scopeKeys().budgetTotal(scopeId);
        Integer total = counts.get(scopeKey);
        if (total != null && total >= 0) {
            return total;
        }
        counts = lockScope(scopeKey);
        try {
            if (total == null && sumOfSubjects(counts, scopeId, scopeKey) == 0) {
                return 0;
            }
            ensureTotal(counts, scopeId, scopeKey);
            Integer built = counts.get(scopeKey);
            // Not a normal total again (the scope was ended and reused meanwhile): report the most, which fails closed.
            return built == null ? 0 : built < 0 ? Integer.MAX_VALUE : built;
        } catch (RuntimeException failed) {
            throw ClusterRefusals.mapped(failed);
        } finally {
            ClusterLocks.release(counts, scopeKey);
        }
    }

    /**
     * Makes the scope's total a normal one, so a check against it means something. It does not depend on the scope's
     * lock (which has a lease) for its correctness; the lock only avoids two members doing the work.
     *
     * <p>The total is first created as a marker, a negative value {@code -(c + 1)} meaning "being built, with c reads
     * counted since the marker". Every read that finds the marker adds to c. Only then are the subject counters
     * summed, and the marker is replaced, atomically, by {@code sum + c}. A read whose subject counter was updated
     * before the marker is in the sum, because the sum is taken after the marker exists; a read whose total update
     * came after the marker is in c; the two overlap for a read in both, which counts it twice, an over-count. So at
     * every moment the total, when not being built, is at least the sum of the reads granted in the scope, except
     * across scope-id reuse (see the residual in the {@code release} Javadoc), which task 226 closes.
     *
     * <p>A builder that dies after the marker leaves it there. The next call finds a marker and finishes it the same
     * way, which cannot under-count for the same reason. Until then a {@code reserve} is refused, never granted
     * against a partial value. Locks are only waited on, never relied on: a write to the total's key by a member
     * whose own lease lapsed simply waits for the holder.
     */
    private void ensureTotal(IMap<String, Integer> counts, String scopeId, String scopeKey) {
        Integer current = counts.get(scopeKey);
        if (current != null && current >= 0) {
            return;
        }
        if (current == null) {
            counts.putIfAbsent(scopeKey, BUILDING);
        }
        counts.executeOnKey(scopeKey, new FinishBuild(sumOfSubjects(counts, scopeId, scopeKey)));
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
            log.error("read budget lock not obtained within {}; refusing", lockTimeout);
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
            log.error("read budget lock not obtained within {}; refusing", lockTimeout);
            throw held;
        } catch (ClusterOperationRefusedException rejected) {
            throw rejected;
        } catch (RuntimeException failed) {
            throw ClusterRefusals.mapped(failed);
        }
    }

    /** Takes {@code reads} back off a counter. If that fails too the counter stays over-counted; one line says so. */
    private void undo(IMap<String, Integer> counts, String key, int reads) {
        try {
            counts.executeOnKey(key, new Subtract(reads));
        } catch (RuntimeException undoFailed) {
            log.warn("read budget undo failed; a counter is left over-counted: {}",
                    undoFailed.getClass().getSimpleName());
        }
    }

    /** As {@link #undo}, for a caller that no longer holds the subject's lock: takes it, bounded, first. */
    private void undoRelocking(IMap<String, Integer> counts, String key, int reads) {
        try {
            ClusterLocks.acquire(counts, key, lockTimeout);
        } catch (RuntimeException notLocked) {
            log.warn("read budget undo failed; a counter is left over-counted: {}",
                    notLocked.getClass().getSimpleName());
            return;
        }
        try {
            undo(counts, key, reads);
        } finally {
            ClusterLocks.release(counts, key);
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

    /** Adds to the scope total if it is a normal total and the result stays within {@code cap}. */
    private record AddToTotal(int delta, int cap) implements EntryProcessor<String, Integer, Integer> {
        @Override
        public Integer process(Map.Entry<String, Integer> entry) {
            Integer current = entry.getValue();
            if (current == null || current < 0) return NOT_READY;
            long next = (long) current + delta;
            if (next > Integer.MAX_VALUE) return OVERFLOW;
            if (next > cap) return OVER;
            entry.setValue((int) next);
            return GRANTED;
        }
    }

    /** Turns a total that is being built into a normal one: the sum of the counters plus the reads counted since. */
    private record FinishBuild(long sum) implements EntryProcessor<String, Integer, Integer> {
        @Override
        public Integer process(Map.Entry<String, Integer> entry) {
            Integer current = entry.getValue();
            if (current == null || current >= 0) return GRANTED;
            long counted = -(long) current - 1;
            entry.setValue((int) Math.min(Integer.MAX_VALUE, sum + counted));
            return GRANTED;
        }
    }

    /** Adds one to an entry that exists and does nothing to one that does not. */
    private record IncrementIfPresent() implements EntryProcessor<String, Integer, Integer> {
        @Override
        public Integer process(Map.Entry<String, Integer> entry) {
            Integer current = entry.getValue();
            if (current == null) return GRANTED;
            if (current < 0) {
                // Being built: one more read counted since the marker.
                if (current == Integer.MIN_VALUE) return OVERFLOW;
                entry.setValue(current - 1);
                return GRANTED;
            }
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
            // A total being built is left alone: not lowering it can only over-count.
            if (current < 0) return 0;
            int taken = Math.min(delta, current);
            if (taken > 0) entry.setValue(current - taken);
            return taken;
        }
    }

    private boolean refuse(RuntimeException clusterFailure) {
        log.error("read budget unavailable; refusing the read rather than leaving it "
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
     * longer be released, even when a partial sweep left some counters behind: those stay charged, so the scope is
     * over-counted, which fails closed. The alternative, keeping the reservations valid, would let a release after
     * the scope id is reused subtract from the new scope, an under-count and a way past the budget.
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

    /** How many scopes this instance holds reservation records for; for tests. */
    int trackedScopes() {
        return issued.size();
    }

    private IMap<String, Integer> counts() {
        return cluster.servingInstance().getMap(PrivacyCluster.BUDGET_MAP);
    }
}

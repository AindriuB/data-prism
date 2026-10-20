package io.github.aindriub.dataprism.core.limits;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A per-instance read counter.
 *
 * <p>This is the mitigation the generalisation work could not provide for itself.
 * A banded value resists a single look and not a determined series of them: ask
 * for the same subject under several profiles, or ask about a population and
 * subtract, and the band narrows toward the value. Nothing else in the pipeline
 * notices, because every individual response is correct.
 *
 * <p>It is also the second half of the cost ceiling. An agent in a retry loop
 * reads the same subject repeatedly, and the per-request limits do not see a
 * pattern that only exists across requests.
 *
 * <p><strong>Per instance, therefore a brake rather than a limit.</strong> Across
 * a horizontally scaled deployment the effective ceiling is instances × budget.
 * Correct for a single instance and for tests; a deployment that needs the number
 * to mean what it says uses the Hazelcast implementation.
 */
public final class InMemoryScopeBudget implements ScopeBudget {

    /**
     * One scope's counters and the lock that makes them one unit. {@code dead}
     * is set by {@link #forget} under the lock, so an operation that fetched the
     * state just before it was dropped notices and starts again on a fresh one.
     */
    private static final class ScopeState {
        private final Object lock = new Object();
        private final Map<String, Integer> subjects = new HashMap<>();
        private int total;
        private boolean dead;
    }

    private final Map<String, ScopeState> scopes = new ConcurrentHashMap<>();

    /**
     * @return whether this read is within budget. A refused read is not counted,
     *         so a caller that keeps asking after being refused does not push the
     *         count somewhere it can never recover from. A granted read also
     *         counts toward the scope total, which it does not check.
     */
    @Override
    public boolean tryRead(String scopeId, String subjectId, int budget) {
        while (true) {
            ScopeState state = scopes.computeIfAbsent(scopeId, k -> new ScopeState());
            synchronized (state.lock) {
                if (state.dead) {
                    continue;
                }
                int current = state.subjects.getOrDefault(subjectId, 0);
                if (current >= budget) {
                    return false;
                }
                state.subjects.put(subjectId, current + 1);
                state.total++;
                return true;
            }
        }
    }

    @Override
    public ReadReservation reserve(
            String scopeId, String subjectId, int reads, int subjectBudget, int scopeTotalBudget) {
        if (reads <= 0 || subjectBudget <= 0 || scopeTotalBudget <= 0) {
            throw new IllegalArgumentException("reads and both budgets must be positive");
        }
        while (true) {
            ScopeState state = scopes.computeIfAbsent(scopeId, k -> new ScopeState());
            synchronized (state.lock) {
                if (state.dead) {
                    continue;
                }
                int current = state.subjects.getOrDefault(subjectId, 0);
                int newSubject;
                int newTotal;
                try {
                    newSubject = Math.addExact(current, reads);
                    newTotal = Math.addExact(state.total, reads);
                } catch (ArithmeticException e) {
                    throw new IllegalArgumentException("reservation overflows a counter", e);
                }
                if (newSubject > subjectBudget) {
                    return ReadReservation.refused(scopeId, subjectId, reads, ReadReservation.Refusal.SUBJECT);
                }
                if (newTotal > scopeTotalBudget) {
                    return ReadReservation.refused(scopeId, subjectId, reads, ReadReservation.Refusal.SCOPE_TOTAL);
                }
                state.subjects.put(subjectId, newSubject);
                state.total = newTotal;
                return ReadReservation.grantedBy(state, scopeId, subjectId, reads);
            }
        }
    }

    @Override
    public void release(ReadReservation reservation, int unused) {
        if (unused < 0) {
            throw new IllegalArgumentException("unused must not be negative");
        }
        // The state the reservation was granted from, not whatever the scope id
        // names now: after forget a later scope with the same id is a different one.
        if (!(reservation.origin() instanceof ScopeState state)) {
            return;
        }
        synchronized (state.lock) {
            if (state.dead) {
                return;
            }
            // Taken under the scope lock, so a release that loses a race with
            // forget returns nothing and cannot be applied to a later scope.
            int give = reservation.takeUnused(unused);
            if (give == 0) {
                return;
            }
            Integer current = state.subjects.get(reservation.subjectId());
            int subject = current == null ? 0 : current;
            int back = Math.min(give, Math.min(subject, state.total));
            if (back > 0) {
                state.subjects.put(reservation.subjectId(), subject - back);
                state.total -= back;
            }
        }
    }

    @Override
    public int reads(String scopeId, String subjectId) {
        ScopeState state = scopes.get(scopeId);
        if (state == null) {
            return 0;
        }
        synchronized (state.lock) {
            return state.subjects.getOrDefault(subjectId, 0);
        }
    }

    @Override
    public int scopeReads(String scopeId) {
        ScopeState state = scopes.get(scopeId);
        if (state == null) {
            return 0;
        }
        synchronized (state.lock) {
            return state.total;
        }
    }

    /** Drops the subject counters and the scope total. Called when a scope ends. */
    @Override
    public void forget(String scopeId) {
        ScopeState state = scopes.remove(scopeId);
        if (state != null) {
            synchronized (state.lock) {
                state.dead = true;
                state.subjects.clear();
                state.total = 0;
            }
        }
    }
}

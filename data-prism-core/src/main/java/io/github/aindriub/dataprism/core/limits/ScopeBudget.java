package io.github.aindriub.dataprism.core.limits;

import java.util.Objects;

/**
 * Counts how often one subject has been read inside one scope.
 *
 * <p>The mitigation generalisation cannot provide for itself. A banded value
 * resists a single look and not a determined series of them: ask about the same
 * subject repeatedly, or about a population and subtract, and the band narrows
 * toward the value. Nothing else in the pipeline notices, because every
 * individual response is correct.
 *
 * <p>An interface because where the count lives decides what it is worth. Held
 * per instance it is a brake — a caller spread across a horizontally scaled
 * deployment gets instances times the budget. Held in shared state it is a limit.
 */
public interface ScopeBudget {

    /**
     * <p>A granted read counts toward the scope total but is not capped by it:
     * only {@link #reserve} enforces the scope total, so {@code tryRead} can only
     * make a later {@code reserve} stricter. The 0.8 fan-out call sites move to
     * {@code reserve} (task 246).
     *
     * @return whether this read is within budget. A refused read is not counted,
     *         so a caller that keeps asking after being refused does not push the
     *         count somewhere a later budget increase could never recover from
     */
    boolean tryRead(String scopeId, String subjectId, int budget);

    int reads(String scopeId, String subjectId);

    /** Drops the subject counters and the scope total, when the scope ends. */
    void forget(String scopeId);

    /**
     * Reserves {@code reads} against the subject's counter and the scope's total
     * in one step: either both grow by {@code reads} or neither changes and the
     * result is refused. Equality with a budget is allowed.
     *
     * <p>Atomicity is per scope, not per subject. The scope total is shared by
     * every subject of the scope, so the check and update of a subject counter
     * and the scope total must happen as one unit that no other operation on the
     * same scope can interleave with. {@link #release}, {@link #forget} and
     * {@link #tryRead} meet the same contract for the counters they touch.
     * Correctness depends on no clock, time-to-live or eviction.
     *
     * <p>The scope total is enforced here and only here; reads counted by
     * {@link #tryRead} count toward it without being capped by it. Task 246 moves
     * the fan-out call sites to {@code reserve}.
     *
     * <p>The default validates its arguments and then refuses, so an implementation that has not been updated
     * refuses every read rather than reading uncounted.
     *
     * @throws IllegalArgumentException if {@code reads} or either budget is not
     *         positive, or a counter would overflow; no counter changes
     */
    default ReadReservation reserve(
            String scopeId, String subjectId, int reads, int subjectBudget, int scopeTotalBudget) {
        Objects.requireNonNull(scopeId, "scopeId");
        Objects.requireNonNull(subjectId, "subjectId");
        if (reads <= 0 || subjectBudget <= 0 || scopeTotalBudget <= 0) {
            throw new IllegalArgumentException("reads and both budgets must be positive");
        }
        return ReadReservation.refused(scopeId, subjectId, reads, ReadReservation.Refusal.SUBJECT);
    }

    /**
     * Returns up to {@code unused} reads that were reserved and never used. Gives
     * back at most what the reservation still holds, once per read; a second
     * release of the same reads, a release of a refused reservation, or a release
     * after {@link #forget} does nothing.
     *
     * <p>An implementation no-ops any release it cannot match to a reservation it
     * granted: a reservation built by hand or around another reservation's origin,
     * one from another budget, or one from a scope that was forgotten and
     * recreated. It does not throw and changes no counter.
     */
    default void release(ReadReservation reservation, int unused) {
    }

    /** @return reads counted against the whole scope, across subjects */
    default int scopeReads(String scopeId) {
        return 0;
    }
}

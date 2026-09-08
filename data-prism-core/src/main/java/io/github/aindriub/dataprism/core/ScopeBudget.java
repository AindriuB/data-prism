package io.github.aindriub.dataprism.core;

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
     * @return whether this read is within budget. A refused read is not counted,
     *         so a caller that keeps asking after being refused does not push the
     *         count somewhere a later budget increase could never recover from
     */
    boolean tryRead(String scopeId, String subjectId, int budget);

    int reads(String scopeId, String subjectId);

    /** Drops the counters for a scope, when the scope ends. */
    void forget(String scopeId);
}

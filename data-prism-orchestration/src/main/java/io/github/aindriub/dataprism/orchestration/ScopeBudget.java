package io.github.aindriub.dataprism.orchestration;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Counts how often one subject has been read inside one scope.
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
 * <p><strong>In memory, therefore per instance.</strong> Across a horizontally
 * scaled deployment the effective ceiling is instances × budget, and a caller
 * spread across instances gets that many times the reads. Stated rather than
 * hidden: the shared counter belongs in the distributed scope state that arrives
 * with Hazelcast in S7, and until then this is a brake rather than a guarantee.
 */
public final class ScopeBudget {

    /**
     * Joins the two halves of a key. NUL because it cannot occur in an
     * identifier, so no scope id can be crafted to collide with another
     * scope's subject. Declared as a constant rather than written inline:
     * an escape for it is invisible in source and easy to mangle.
     */
    private static final char SEPARATOR = 0;

    private final Map<String, AtomicInteger> reads = new ConcurrentHashMap<>();

    /**
     * @return whether this read is within budget. A refused read is not counted,
     *         so a caller that keeps asking after being refused does not push the
     *         count somewhere it can never recover from
     */
    public boolean tryRead(String scopeId, String subjectId, int budget) {
        AtomicInteger count = reads.computeIfAbsent(key(scopeId, subjectId), k -> new AtomicInteger());
        while (true) {
            int current = count.get();
            if (current >= budget) {
                return false;
            }
            if (count.compareAndSet(current, current + 1)) {
                return true;
            }
        }
    }

    public int reads(String scopeId, String subjectId) {
        AtomicInteger count = reads.get(key(scopeId, subjectId));
        return count == null ? 0 : count.get();
    }

    /** Drops the counters for a scope. Called when a scope ends. */
    public void forget(String scopeId) {
        reads.keySet().removeIf(key -> key.startsWith(scopeId + SEPARATOR));
    }

    private static String key(String scopeId, String subjectId) {
        // NUL separator: a scope id containing the separator could otherwise be
        // made to collide with another scope's subject.
        return scopeId + SEPARATOR + subjectId;
    }
}

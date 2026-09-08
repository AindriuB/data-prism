package io.github.aindriub.dataprism.core;

import java.time.Duration;
import java.util.Objects;

/**
 * The ceilings on what one request may cost.
 *
 * <p>The failure these exist for is not an attack. It is an agent in a retry loop,
 * or one that decides a broad question is best answered by asking about everything:
 * one tool call becomes a hundred source calls and ten thousand records, and the
 * bill and the load on the source systems arrive before anyone notices. See
 * docs/pack.md §74 and docs/design-review.md §D4.
 *
 * @param maxSources          sources one request may call
 * @param maxRecords          records one request may carry
 * @param maxResponseBytes    serialised size of the response
 * @param perSourceTimeout    how long any one source may take before it is treated
 *                            as absent
 * @param maxConcurrency      concurrent downstream calls across the whole request.
 *                            A bulkhead: the platform must not be the reason a
 *                            source system falls over
 * @param scopeReadBudget     reads of one subject within one scope. Generalisation
 *                            resists a single look and not a determined series of
 *                            them — a banded value narrows under repeated asking,
 *                            and nothing else in the pipeline notices
 */
public record RequestLimits(
        int maxSources,
        int maxRecords,
        int maxResponseBytes,
        Duration perSourceTimeout,
        int maxConcurrency,
        int scopeReadBudget) {

    /**
     * Deliberately modest. A limit that never fires teaches nobody anything, and
     * raising one after seeing it fire is a better position than discovering the
     * ceiling was never there.
     */
    public static final RequestLimits DEFAULT = new RequestLimits(
            8, 500, 512 * 1024, Duration.ofSeconds(3), 4, 100);

    public RequestLimits {
        Objects.requireNonNull(perSourceTimeout, "perSourceTimeout");
        positive(maxSources, "maxSources");
        positive(maxRecords, "maxRecords");
        positive(maxResponseBytes, "maxResponseBytes");
        positive(maxConcurrency, "maxConcurrency");
        positive(scopeReadBudget, "scopeReadBudget");
        if (perSourceTimeout.isZero() || perSourceTimeout.isNegative()) {
            throw new IllegalArgumentException("perSourceTimeout must be positive");
        }
    }

    private static void positive(int value, String name) {
        if (value <= 0) {
            // Zero would disable the limit while looking like configuration, which
            // is the shape of an accident rather than a decision.
            throw new IllegalArgumentException(name + " must be positive, was " + value);
        }
    }
}

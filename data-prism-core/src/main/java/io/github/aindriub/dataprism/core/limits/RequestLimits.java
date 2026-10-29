package io.github.aindriub.dataprism.core.limits;

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
 * <p>A record is each returned source's root object plus every object that is
 * an array element at any depth. Other nested objects and scalar array elements
 * do not add records. Withheld source payloads count zero.
 *
 * <p>The byte limit bounds the compact UTF-8 context body (including source
 * statuses and findings), before ALLOW is audited. It excludes the MCP/JSON-RPC
 * envelope and the transport's duplicate text/structured representations.
 * Comparison requests also bound the projected identity and findings (including
 * their consistency booleans); the larger of the two body bounds is enforced.
 * A mapper-free walk reserves six bytes per surrogate code unit, so it may
 * refuse conservatively; ordinary JSON is exact apart from that reservation.
 * Unsupported opaque nodes fail closed. Equality with either limit is allowed.
 *
 * @param maxSources          sources one request may call
 * @param maxRecords          records one request may carry
 * @param maxResponseBytes    conservative compact UTF-8 context-body size
 * @param perSourceTimeout    how long any one source may take before it is treated
 *                            as absent
 * @param maxConcurrency      concurrent downstream calls across the whole request.
 *                            A bulkhead: the platform must not be the reason a
 *                            source system falls over
 * @param scopeReadBudget     reads of one subject within one scope. Generalisation
 *                            resists a single look and not a determined series of
 *                            them — a banded value narrows under repeated asking,
 *                            and nothing else in the pipeline notices. Counts source
 *                            reads: one per source called, not one per request
 * @param requestTimeout      the overall deadline for one request's fan-out, from its
 *                            start. Bounds the wait for a bulkhead permit as well as
 *                            the calls; at least {@code perSourceTimeout}
 * @param scopeTotalReadBudget source reads across every subject of one scope
 */
public record RequestLimits(
        int maxSources,
        int maxRecords,
        int maxResponseBytes,
        Duration perSourceTimeout,
        int maxConcurrency,
        int scopeReadBudget,
        Duration requestTimeout,
        int scopeTotalReadBudget) {

    /**
     * Deliberately modest. A limit that never fires teaches nobody anything, and
     * raising one after seeing it fire is a better position than discovering the
     * ceiling was never there.
     */
    public static final RequestLimits DEFAULT = new RequestLimits(
            8, 500, 512 * 1024, Duration.ofSeconds(3), 4, 100,
            Duration.ofSeconds(10), 50_000);

    public RequestLimits {
        Objects.requireNonNull(perSourceTimeout, "perSourceTimeout");
        Objects.requireNonNull(requestTimeout, "requestTimeout");
        positive(maxSources, "maxSources");
        positive(maxRecords, "maxRecords");
        positive(maxResponseBytes, "maxResponseBytes");
        positive(maxConcurrency, "maxConcurrency");
        positive(scopeReadBudget, "scopeReadBudget");
        positive(scopeTotalReadBudget, "scopeTotalReadBudget");
        if (perSourceTimeout.isZero() || perSourceTimeout.isNegative()) {
            throw new IllegalArgumentException("perSourceTimeout must be positive");
        }
        if (requestTimeout.isZero() || requestTimeout.isNegative()) {
            throw new IllegalArgumentException("requestTimeout must be positive");
        }
        if (requestTimeout.compareTo(perSourceTimeout) < 0) {
            throw new IllegalArgumentException("requestTimeout must be at least perSourceTimeout");
        }
    }

    private static void positive(int value, String name) {
        if (value <= 0) {
            // Zero would disable the limit while looking like configuration, which
            // is the shape of an accident rather than a decision.
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}

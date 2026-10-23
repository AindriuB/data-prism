package io.github.aindriub.dataprism.core.link;

/**
 * The failed-attempt limiter of sealed-links.md section 4. The codec never calls it: the caller records a
 * failure when {@link LinkOpenResult.Refused#counted()} is true and checks {@link #locked} before opening.
 *
 * <p>The counter key comes from {@link LinkKeys#attemptCounterKey} and holds neither the scope nor the
 * principal. Implementations are thread-safe.
 */
public interface LinkAttemptLimiter {

    /** Whether the counter has reached its limit within its window. A locked key refuses valid links too. */
    boolean locked(String counterKey);

    /** Records one counted failure. Does not extend the window. */
    void recordFailure(String counterKey);

    /** Whether the count is shared by every cluster member; the in-memory limiter is not. */
    default boolean clusterWide() {
        return false;
    }
}

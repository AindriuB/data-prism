package io.github.aindriub.dataprism.core.link;

/**
 * The failed-attempt limiter of sealed-links.md section 4. Code outside {@code core.link} never calls it: the one
 * way to open a token is {@link LinkAttemptGate#open}, which checks the lock, opens, and records a failure only
 * when {@link LinkOpenResult.Refused#counted()} is true.
 *
 * <p>Implementations, a cluster-wide one included, must keep all of these:
 * <ul>
 * <li>{@link #recordFailure} is atomic per subject: one call reads, updates and decides;</li>
 * <li>the window is decided by the stored start and the clock, never by an entry expiring; a time-to-live only
 * bounds memory and must be at least the window plus the clock-skew tolerance, measured from the last write;</li>
 * <li>no live entry is evicted, so a flood cannot lift a lock;</li>
 * <li>a new subject at the ceiling gets {@link Admission#CEILING} and is not stored;</li>
 * <li>fail closed through {@link LockState#UNAVAILABLE} or {@link Admission#UNAVAILABLE}, never by throwing or
 * returning {@code null}.</li>
 * </ul>
 *
 * <p>The implementation keys a subject from {@link AttemptSubject#canonical()} under a key that does not rotate
 * with the link key, and holds neither id in the clear. Implementations are thread-safe.
 */
public interface LinkAttemptLimiter {

    /** What {@link #lockState} decided. {@code LOCKED} means {@code count >= limit} within the current window. */
    enum LockState { UNLOCKED, LOCKED, UNAVAILABLE }

    /** What {@link #recordFailure} decided. Every value except {@code WITHIN_LIMIT} refuses. */
    enum Admission { WITHIN_LIMIT, OVER_LIMIT, CEILING, UNAVAILABLE }

    /** Whether the subject has reached its limit within its window. A locked subject is refused valid links too. */
    LockState lockState(AttemptSubject subject);

    /**
     * Records one counted failure atomically: opens a window if none is live, adds one (saturating), and answers
     * {@code WITHIN_LIMIT} when the new count is at most the limit and {@code OVER_LIMIT} otherwise. Does not
     * extend the window.
     */
    Admission recordFailure(AttemptSubject subject);

    /** Whether the count is shared by every cluster member; the in-memory limiter is not. */
    default boolean clusterWide() {
        return false;
    }
}

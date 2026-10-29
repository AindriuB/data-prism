package io.github.aindriub.dataprism.hazelcast;

/**
 * Whether this member may serve, and why not (docs/plan/design/cluster-security.md 2.4 d2 and 4.4). A snapshot: the
 * member cards produce a new one each time they look at the cluster.
 *
 * <p>Nothing here names an address, a key id or a member UUID, so {@link #text()} and {@link #toString()} are safe
 * for a log line and a health endpoint. Task 228 adds pause freshness and age, and never the pause content; task 286
 * reports the status as health.
 *
 * @param serving         whether sealed operations are allowed on this member
 * @param reason          why not, {@link Reason#NONE} while serving
 * @param memberCount     the members in the live member list
 * @param unverifiedCount the joined members without a valid member card (inside the grace period or not)
 * @param pauseFresh      whether the pause document is present, authentic and inside its lease; {@code true} in a
 *                        status that does not speak for the pause document (the member cards produce those)
 * @param pauseAgeSeconds the age of the accepted pause document from local monotonic receipt, in whole seconds;
 *                        {@code -1} when none has been accepted or the producer does not know
 */
public record ClusterSecurityStatus(boolean serving, Reason reason, int memberCount, int unverifiedCount,
                                    boolean pauseFresh, long pauseAgeSeconds) {

    /** The cause of a member not serving. */
    public enum Reason {
        /** Serving. */
        NONE,
        /** The cluster sentinel does not open under any key this member holds. */
        SENTINEL_MISMATCH,
        /** A member has had no valid card for longer than the grace period and the action is {@code refuse}. */
        UNVERIFIED_MEMBER,
        /** A verified member's key check value differs from this member's for a key id both hold. */
        KEY_CHECK_MISMATCH,
        /** The cards could not be read or refreshed, or the last look is older than a lease: unknown is not serving. */
        CARDS_UNAVAILABLE,
        /** The pause document is absent, unauthentic or not fresh: admission cannot be evaluated (design 2.3.3). */
        PAUSE_NOT_FRESH
    }

    /** A status that does not speak for the pause document: fresh by default, age unknown. */
    public ClusterSecurityStatus(boolean serving, Reason reason, int memberCount, int unverifiedCount) {
        this(serving, reason, memberCount, unverifiedCount, true, -1);
    }

    public ClusterSecurityStatus {
        if (serving != (reason == Reason.NONE)) {
            throw new IllegalArgumentException("a member serves exactly when it has no reason not to");
        }
    }

    /** A status that serves. */
    static ClusterSecurityStatus serving(int memberCount, int unverifiedCount) {
        return new ClusterSecurityStatus(true, Reason.NONE, memberCount, unverifiedCount);
    }

    /** A status that does not serve. */
    static ClusterSecurityStatus notServing(Reason reason, int memberCount, int unverifiedCount) {
        return new ClusterSecurityStatus(false, reason, memberCount, unverifiedCount);
    }

    /**
     * This status with the pause document's freshness and age. A status that serves stops serving, for
     * {@link Reason#PAUSE_NOT_FRESH}, when the document is not fresh; a status that already does not serve keeps its
     * own reason. Carries no pause content.
     */
    public ClusterSecurityStatus withPause(boolean fresh, long ageSeconds) {
        if (!fresh && serving) {
            return new ClusterSecurityStatus(false, Reason.PAUSE_NOT_FRESH, memberCount, unverifiedCount, false,
                    ageSeconds);
        }
        return new ClusterSecurityStatus(serving, reason, memberCount, unverifiedCount, fresh, ageSeconds);
    }

    /** A constant description: the state, the cause when not serving, and the two counts. */
    public String text() {
        String state = switch (reason) {
            case NONE -> "serving";
            case SENTINEL_MISMATCH -> "not serving: the cluster sentinel does not open under a held key";
            case UNVERIFIED_MEMBER -> "not serving: a member has no valid member card";
            case KEY_CHECK_MISMATCH -> "not serving: a member's key check value differs";
            case CARDS_UNAVAILABLE -> "not serving: the member cards could not be read or refreshed";
            case PAUSE_NOT_FRESH -> "not serving: the pause document is absent, unauthentic or not fresh";
        };
        return state + " (members " + memberCount + ", unverified " + unverifiedCount + ")";
    }

    @Override
    public String toString() {
        return text();
    }
}

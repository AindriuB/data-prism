package io.github.aindriub.dataprism.hazelcast;

/**
 * Whether this member may serve, and why not (docs/plan/design/cluster-security.md 2.4 d2 and 4.4). A snapshot: the
 * member cards produce a new one each time they look at the cluster.
 *
 * <p>Nothing here names an address, a key id or a member UUID, so {@link #text()} and {@link #toString()} are safe
 * for a log line and a health endpoint. Task 228 adds pause freshness; task 286 reports the status as health.
 *
 * @param serving         whether sealed operations are allowed on this member
 * @param reason          why not, {@link Reason#NONE} while serving
 * @param memberCount     the members in the live member list
 * @param unverifiedCount the joined members without a valid member card (inside the grace period or not)
 */
public record ClusterSecurityStatus(boolean serving, Reason reason, int memberCount, int unverifiedCount) {

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
        CARDS_UNAVAILABLE
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

    /** A constant description: the state, the cause when not serving, and the two counts. */
    public String text() {
        String state = switch (reason) {
            case NONE -> "serving";
            case SENTINEL_MISMATCH -> "not serving: the cluster sentinel does not open under a held key";
            case UNVERIFIED_MEMBER -> "not serving: a member has no valid member card";
            case KEY_CHECK_MISMATCH -> "not serving: a member's key check value differs";
            case CARDS_UNAVAILABLE -> "not serving: the member cards could not be read or refreshed";
        };
        return state + " (members " + memberCount + ", unverified " + unverifiedCount + ")";
    }

    @Override
    public String toString() {
        return text();
    }
}

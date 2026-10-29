package io.github.aindriub.dataprism.hazelcast;

import java.time.Duration;

/**
 * The runtime settings of the sealed cluster that the stores of tasks 226 to 228 read
 * (docs/plan/design/cluster-security.md 4.1): the pause lease, the clock-skew tolerance, what to do with an
 * unverified member and for how long to wait first, and whether the per-instance floor is enforced.
 *
 * <p>Immutable. The constructor refuses a value outside its bounds with {@link IllegalArgumentException}; the
 * message names the component and never the value.
 *
 * @param pauseLease              how long a pause document, or a member card, stays fresh; {@code PT5S} to
 *                                {@code PT5M}, default {@code PT30S}
 * @param clockSkewTolerance      the wall-clock slack used only to gate the first acceptance of a pause document after
 *                                a cold start; {@code PT0S} to half of {@code pauseLease}, default {@code PT5S}
 * @param unverifiedMemberAction  what an unverified member causes; default {@link UnverifiedMemberAction#REFUSE}
 * @param unverifiedMemberGrace   how long a joined member may lack a valid card before it counts as unverified;
 *                                {@code PT0S} to {@code PT10M}, default {@code PT30S}
 * @param localFloorEnabled       whether each instance also enforces its own count beside the shared one; default
 *                                {@code true}
 */
public record ClusterSecuritySettings(Duration pauseLease, Duration clockSkewTolerance,
                                      UnverifiedMemberAction unverifiedMemberAction,
                                      Duration unverifiedMemberGrace, boolean localFloorEnabled) {

    /** What an unverified member causes (design 4.4, OD-8). */
    public enum UnverifiedMemberAction {
        /** Stop serving after the grace. */
        REFUSE,
        /** Keep serving; raise the metric, the rate-bounded WARN line and the health detail. */
        ALERT
    }

    public static final Duration MIN_PAUSE_LEASE = Duration.ofSeconds(5);
    public static final Duration MAX_PAUSE_LEASE = Duration.ofMinutes(5);
    public static final Duration DEFAULT_PAUSE_LEASE = Duration.ofSeconds(30);
    public static final Duration MIN_CLOCK_SKEW_TOLERANCE = Duration.ZERO;
    public static final Duration DEFAULT_CLOCK_SKEW_TOLERANCE = Duration.ofSeconds(5);
    public static final Duration MIN_UNVERIFIED_MEMBER_GRACE = Duration.ZERO;
    public static final Duration MAX_UNVERIFIED_MEMBER_GRACE = Duration.ofMinutes(10);
    public static final Duration DEFAULT_UNVERIFIED_MEMBER_GRACE = Duration.ofSeconds(30);

    public ClusterSecuritySettings {
        require(pauseLease != null && within(pauseLease, MIN_PAUSE_LEASE, MAX_PAUSE_LEASE), "pauseLease");
        require(clockSkewTolerance != null
                && within(clockSkewTolerance, MIN_CLOCK_SKEW_TOLERANCE, pauseLease.dividedBy(2)),
                "clockSkewTolerance");
        require(unverifiedMemberAction != null, "unverifiedMemberAction");
        require(unverifiedMemberGrace != null
                && within(unverifiedMemberGrace, MIN_UNVERIFIED_MEMBER_GRACE, MAX_UNVERIFIED_MEMBER_GRACE),
                "unverifiedMemberGrace");
    }

    /** The defaults of design 4.1. */
    public static ClusterSecuritySettings defaults() {
        return new ClusterSecuritySettings(DEFAULT_PAUSE_LEASE, DEFAULT_CLOCK_SKEW_TOLERANCE,
                UnverifiedMemberAction.REFUSE, DEFAULT_UNVERIFIED_MEMBER_GRACE, true);
    }

    private static boolean within(Duration value, Duration min, Duration max) {
        return value.compareTo(min) >= 0 && value.compareTo(max) <= 0;
    }

    private static void require(boolean ok, String component) {
        if (!ok) {
            throw new IllegalArgumentException(component + " is missing or outside its bounds");
        }
    }
}

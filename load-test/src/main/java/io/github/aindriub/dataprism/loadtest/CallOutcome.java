package io.github.aindriub.dataprism.loadtest;

import java.util.Map;
import java.util.Objects;

/**
 * What one {@code get_entity_context} call came to.
 *
 * @param completed       whether the server returned a tool result at all (an error result included)
 * @param latencyMicros   the call's latency from its scheduled time; for a call that was timed but did not
 *                        complete it is the time to the failure, a lower bound on what the call would have
 *                        taken; zero for any other call that did not complete
 * @param refusalCode     the code of a refused or denied call, otherwise {@code null}
 * @param sourceStatuses  each source's status in a returned response, keyed by the name the response uses
 * @param transportFailure the exception class when the call did not complete, otherwise {@code null}
 * @param timedOut        whether the call hit a timeout (the client's, or the connect timeout)
 * @param failureCause    why the call did not complete; {@code null} exactly when it completed
 * @param timed           whether the latency enters the figures even though the call failed: every timeout, and
 *                        any other failure that took long enough to matter
 */
record CallOutcome(boolean completed, long latencyMicros, String refusalCode, Map<String, String> sourceStatuses,
                   String transportFailure, boolean timedOut, FailureCause failureCause, boolean timed) {

    CallOutcome {
        sourceStatuses = Map.copyOf(sourceStatuses);
        if (!completed) {
            Objects.requireNonNull(failureCause, "a call that did not complete needs a cause");
        }
    }

    static CallOutcome completed(long latencyMicros, Map<String, String> sourceStatuses) {
        return new CallOutcome(true, latencyMicros, null, sourceStatuses, null, false, null, true);
    }

    static CallOutcome refused(long latencyMicros, String code) {
        return new CallOutcome(true, latencyMicros, code, Map.of(), null, false, null, true);
    }

    /** A failure that is not a timeout and was quick (connection refused, reset): counted, never timed. */
    static CallOutcome transportFailure(String kind) {
        return transportFailure(kind, FailureCause.OTHER);
    }

    static CallOutcome transportFailure(String kind, FailureCause cause) {
        return new CallOutcome(false, 0, null, Map.of(), kind, false, cause, false);
    }

    /** A failure that is not a timeout but took long: counted and timed, so it cannot hide from the tail. */
    static CallOutcome slowFailure(String kind, FailureCause cause, long latencyMicros) {
        return new CallOutcome(false, latencyMicros, null, Map.of(), kind, false, cause, true);
    }

    /** A call that hit the client timeout, timed from its scheduled time. */
    static CallOutcome timeout(String kind, long latencyMicros) {
        return timeout(kind, FailureCause.READ_TIMEOUT, latencyMicros);
    }

    static CallOutcome timeout(String kind, FailureCause cause, long latencyMicros) {
        return new CallOutcome(false, latencyMicros, null, Map.of(), kind, true, cause, true);
    }
}

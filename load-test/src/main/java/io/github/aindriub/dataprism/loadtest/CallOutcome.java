package io.github.aindriub.dataprism.loadtest;

import java.util.Map;

/**
 * What one {@code get_entity_context} call came to.
 *
 * @param completed       whether the server returned a tool result at all (an error result included)
 * @param latencyMicros   the call's latency from its scheduled time; for a call that timed out it is the
 *                        time to the timeout, a lower bound on what the call would have taken; zero for any
 *                        other call that did not complete
 * @param refusalCode     the code of a refused or denied call, otherwise {@code null}
 * @param sourceStatuses  each source's status in a returned response, keyed by the name the response uses
 * @param transportFailure the exception class when the call did not complete, otherwise {@code null}
 * @param timedOut        whether the call hit the client timeout; only those failures enter the latency figures
 */
record CallOutcome(boolean completed, long latencyMicros, String refusalCode, Map<String, String> sourceStatuses,
                   String transportFailure, boolean timedOut) {

    CallOutcome {
        sourceStatuses = Map.copyOf(sourceStatuses);
    }

    static CallOutcome completed(long latencyMicros, Map<String, String> sourceStatuses) {
        return new CallOutcome(true, latencyMicros, null, sourceStatuses, null, false);
    }

    static CallOutcome refused(long latencyMicros, String code) {
        return new CallOutcome(true, latencyMicros, code, Map.of(), null, false);
    }

    /** A failure that is not a timeout (connection refused, reset): counted, never timed. */
    static CallOutcome transportFailure(String kind) {
        return new CallOutcome(false, 0, null, Map.of(), kind, false);
    }

    /** A call that hit the client timeout, timed from its scheduled time. */
    static CallOutcome timeout(String kind, long latencyMicros) {
        return new CallOutcome(false, latencyMicros, null, Map.of(), kind, true);
    }
}

package io.github.aindriub.dataprism.loadtest;

import java.util.Map;

/**
 * What one {@code get_entity_context} call came to.
 *
 * @param completed       whether the server returned a tool result at all (an error result included)
 * @param latencyMicros   the call's latency; meaningful only when {@code completed}
 * @param refusalCode     the code of a refused or denied call, otherwise {@code null}
 * @param sourceStatuses  each source's status in a returned response, keyed by the name the response uses
 * @param transportFailure the exception class when the call did not complete, otherwise {@code null}
 */
record CallOutcome(boolean completed, long latencyMicros, String refusalCode, Map<String, String> sourceStatuses,
                   String transportFailure) {

    CallOutcome {
        sourceStatuses = Map.copyOf(sourceStatuses);
    }

    static CallOutcome completed(long latencyMicros, Map<String, String> sourceStatuses) {
        return new CallOutcome(true, latencyMicros, null, sourceStatuses, null);
    }

    static CallOutcome refused(long latencyMicros, String code) {
        return new CallOutcome(true, latencyMicros, code, Map.of(), null);
    }

    static CallOutcome transportFailure(String kind) {
        return new CallOutcome(false, 0, null, Map.of(), kind);
    }
}

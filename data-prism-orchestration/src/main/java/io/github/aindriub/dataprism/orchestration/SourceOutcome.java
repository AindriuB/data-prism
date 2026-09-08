package io.github.aindriub.dataprism.orchestration;

import java.time.Duration;

/**
 * What happened when one source was asked.
 *
 * <p>Every source produces one of these, including the ones that failed. A source
 * that timed out is data about the answer: the response is built from fewer
 * systems than intended, and a reader who cannot see that will read absence as
 * evidence rather than as a gap. Making the gap explicit is the same instinct as
 * the consistency findings — do not let the data look better than it is.
 *
 * @param detail a code or short reason, never a payload fragment. A downstream
 *               error message can carry the record that caused it
 */
public record SourceOutcome(String sourceName, Status status, Duration took, String detail) {

    public enum Status {
        /** The source answered with a record. */
        ANSWERED,
        /** The source answered, and has nothing for this subject. */
        NO_DATA,
        /** The source did not answer inside its timeout. */
        TIMED_OUT,
        /** The source answered with an error. */
        FAILED,
        /** Not called: the source is failing and its breaker is open. */
        CIRCUIT_OPEN,
        /** Not called: the request had already reached its source limit. */
        SKIPPED_OVER_LIMIT
    }

    public boolean answered() {
        return status == Status.ANSWERED;
    }

    /** Whether this counts against the source's failure streak. */
    public boolean failure() {
        return status == Status.TIMED_OUT || status == Status.FAILED;
    }

    public static SourceOutcome answered(String source, Duration took) {
        return new SourceOutcome(source, Status.ANSWERED, took, null);
    }

    public static SourceOutcome noData(String source, Duration took) {
        return new SourceOutcome(source, Status.NO_DATA, took, null);
    }
}

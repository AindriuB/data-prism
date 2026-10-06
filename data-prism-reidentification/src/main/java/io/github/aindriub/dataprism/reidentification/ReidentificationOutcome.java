package io.github.aindriub.dataprism.reidentification;

/** The result of one step. Only {@link Resolved} carries a subject id. */
public sealed interface ReidentificationOutcome {

    record Resolved(String subjectId) implements ReidentificationOutcome {
        @Override
        public String toString() {
            return "Resolved[subjectId=<withheld>]";
        }
    }

    record PendingApproval(String approvalId) implements ReidentificationOutcome { }

    record Approved() implements ReidentificationOutcome { }

    record Refused(String code) implements ReidentificationOutcome { }
}

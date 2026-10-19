package io.github.aindriub.dataprism.loadtest;

import java.util.List;

/** The seven per-source status codes of {@code SourceOutcome.Status}, in the server's declaration order. */
final class SourceStatuses {

    static final List<String> ALL = List.of("ANSWERED", "NO_DATA", "TIMED_OUT", "FAILED", "CIRCUIT_OPEN",
            "SKIPPED_OVER_LIMIT", "REFUSED");

    private SourceStatuses() { }
}

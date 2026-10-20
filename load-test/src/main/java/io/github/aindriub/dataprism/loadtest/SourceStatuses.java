package io.github.aindriub.dataprism.loadtest;

import java.util.List;

/**
 * The per-source status codes of {@code SourceOutcome.Status}, in the server's declaration order, with
 * {@code RESOLVER_FAILED} (0.8) last. A status outside this list is still tallied under its own name.
 */
final class SourceStatuses {

    static final List<String> ALL = List.of("ANSWERED", "NO_DATA", "TIMED_OUT", "FAILED", "CIRCUIT_OPEN",
            "SKIPPED_OVER_LIMIT", "REFUSED", "RESOLVER_FAILED");

    private SourceStatuses() { }

    /**
     * Whether a source gave a usable reading: it answered, or it answered that it holds nothing. Every other
     * status, including one this list does not know, means the source did not answer.
     */
    static boolean isAnswered(String status) {
        return "ANSWERED".equals(status) || "NO_DATA".equals(status);
    }
}

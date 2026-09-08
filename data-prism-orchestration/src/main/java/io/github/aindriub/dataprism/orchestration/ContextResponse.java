package io.github.aindriub.dataprism.orchestration;

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A privacy-safe view of one entity.
 *
 * <p>{@code subject} is the scope-local pseudonym, not the real identifier — the
 * model gets something stable to reason with and to quote back, without ever
 * holding the key to the source systems.
 *
 * <p>{@code sources} names every source that was asked and how it answered,
 * including the ones that did not. A response assembled from four systems out of
 * five is a real answer with a stated gap, and a reader who cannot see the gap
 * will read absence as evidence. Same principle as the consistency findings:
 * never let the data look better than it is.
 *
 * <p>Statuses only, and deliberately not the timings. How long a source took is
 * operational telemetry that belongs in audit and metrics; putting it here would
 * tell an untrusted reader about the shape and health of infrastructure it
 * otherwise cannot see, and the model has no use for it.
 *
 * <p>Source names are emitted plainly. Pseudonymising them is S6; it is safe for
 * now only because the example sources are fictional.
 */
public record ContextResponse(
        String entityType,
        String subject,
        Map<String, String> sources,
        ObjectNode entity) {

    public ContextResponse {
        sources = Map.copyOf(sources);
    }

    static ContextResponse of(String entityType, String subject,
                              List<SourceOutcome> outcomes, ObjectNode entity) {
        Map<String, String> statuses = new LinkedHashMap<>();
        outcomes.forEach(outcome -> statuses.put(outcome.sourceName(), outcome.status().name()));
        return new ContextResponse(entityType, subject, statuses, entity);
    }

    /** The sources that actually contributed to {@code entity}. */
    public List<String> answered() {
        return sources.entrySet().stream()
                .filter(e -> e.getValue().equals(SourceOutcome.Status.ANSWERED.name()))
                .map(Map.Entry::getKey)
                .toList();
    }

    /** Whether a source was asked and did not answer. */
    public boolean incomplete() {
        return sources.values().stream()
                .anyMatch(status -> !status.equals(SourceOutcome.Status.ANSWERED.name())
                        && !status.equals(SourceOutcome.Status.NO_DATA.name()));
    }
}

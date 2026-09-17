package io.github.aindriub.dataprism.orchestration;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.aindriub.dataprism.annotations.PrivacyNamespace;
import io.github.aindriub.dataprism.core.ConsistencyFinding;
import io.github.aindriub.dataprism.core.InvestigationContext;

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
 * <p>{@code findings} is the other half of the platform's purpose. The identity
 * in {@code entity} is deliberately consistent — one subject reads the same in
 * every source — and that consistency would hide the fact that the systems
 * disagree about it. The findings say so, without saying what any source held.
 *
 * <p>{@code fieldsByNamespace} is bookkeeping for task 42, never a value: a
 * {@link ConsistencyFinding} on a namespace-compared field carries the
 * namespace's own name in {@code field()} (see {@code NamespaceCorrelationService}),
 * not the serialised field name(s) {@code entity} is keyed by — two sources can
 * (and often do) use different field names for the same namespace. This is how
 * a caller maps one back to the other, so it is excluded from serialisation
 * ({@link JsonIgnore}): it must never change what {@code get_entity_context}
 * emits, only let {@code compare_entity_sources} find the right node in
 * {@code entity}.
 */
public record ContextResponse(
        String entityType,
        String subject,
        Map<String, String> sources,
        List<ConsistencyFinding> findings,
        ObjectNode entity,
        @JsonIgnore Map<PrivacyNamespace, List<String>> fieldsByNamespace) {

    public ContextResponse {
        sources = Map.copyOf(sources);
        findings = List.copyOf(findings);
        Map<PrivacyNamespace, List<String>> copy = new LinkedHashMap<>();
        fieldsByNamespace.forEach((namespace, names) -> copy.put(namespace, List.copyOf(names)));
        fieldsByNamespace = Map.copyOf(copy);
    }

    /**
     * Pre-task-42 shape: leaves {@code fieldsByNamespace} empty, so a
     * {@code compare_entity_sources} response built from this carries an empty
     * {@code identity}. {@link DefaultContextOrchestrator} is what populates it;
     * use that, not this, unless a bare {@code ContextResponse} is genuinely all
     * that's needed.
     */
    public ContextResponse(String entityType, String subject, Map<String, String> sources,
                           List<ConsistencyFinding> findings, ObjectNode entity) {
        this(entityType, subject, sources, findings, entity, Map.of());
    }

    static ContextResponse of(String entityType, String subject, List<SourceOutcome> outcomes,
                              List<ConsistencyFinding> findings, ObjectNode entity,
                              Map<PrivacyNamespace, List<String>> fieldsByNamespace,
                              SourceAliasing aliasing, InvestigationContext investigationContext,
                              io.github.aindriub.dataprism.core.PrivacyContext context) {
        Map<String, String> statuses = new LinkedHashMap<>();
        outcomes.forEach(outcome -> statuses.put(
                aliasing.nameFor(outcome.sourceName(), investigationContext, context),
                outcome.status().name()));
        return new ContextResponse(entityType, subject, statuses, findings, entity, fieldsByNamespace);
    }

    /**
     * The serialised field name(s) {@code entity} holds this namespace under,
     * in first-seen order across every source that answered. Empty for a
     * namespace nothing was correlated on, or for a response built before this
     * bookkeeping existed.
     */
    public List<String> fieldsFor(PrivacyNamespace namespace) {
        return fieldsByNamespace.getOrDefault(namespace, List.of());
    }

    /** Whether any source disagreed with another about a shared field. */
    public boolean hasDisagreement() {
        return findings.stream().anyMatch(ConsistencyFinding::disagreement);
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

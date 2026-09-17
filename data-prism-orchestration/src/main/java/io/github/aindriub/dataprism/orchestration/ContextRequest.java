package io.github.aindriub.dataprism.orchestration;

import io.github.aindriub.dataprism.core.ConsistencyFinding;

import java.util.Objects;
import java.util.Set;

/**
 * What a caller asked for.
 *
 * <p>An entity type and a subject, and nothing that selects a source, a scope or
 * a profile. Those come from configuration and the authenticated session, so a
 * caller cannot widen its own access by asking differently.
 *
 * <p>{@code toolName} and {@code includeAgreementFindings} exist for task 42's
 * comparison path and default to the shipped tool's behaviour on every
 * constructor and factory that predates them: {@code toolName} defaults to
 * {@value #DEFAULT_TOOL_NAME} — the name audited against a request built any
 * other way — and {@code includeAgreementFindings} defaults to {@code false},
 * so {@code get_entity_context}'s response is unchanged unless a caller
 * explicitly asks via {@link #comparison}.
 *
 * @param rejectedArguments        the *names* of any reserved argument the caller
 *                                 attempted to supply — {@code scopeId}, {@code purpose}
 *                                 and the like. A value must never be put in this set;
 *                                 it is audited and logged verbatim, and the component
 *                                 being {@code Set<String>} of names is what makes
 *                                 putting a value in it impossible by type.
 * @param toolName                 the name this request is audited under.
 * @param includeAgreementFindings whether {@link ConsistencyFinding.Kind#CONSISTENT}
 *                                 findings are kept in the response rather than
 *                                 filtered out. Agreement is only knowable before
 *                                 scrubbing, so this cannot be decided later from a
 *                                 {@link ContextResponse}; it has to be asked for here.
 */
public record ContextRequest(String entityType, String subjectId, Set<String> rejectedArguments,
                             String toolName, boolean includeAgreementFindings) {

    /** The tool every pre-task-42 constructor and factory audits a request under. */
    public static final String DEFAULT_TOOL_NAME = "get_entity_context";

    public ContextRequest {
        Objects.requireNonNull(entityType, "entityType");
        Objects.requireNonNull(subjectId, "subjectId");
        Objects.requireNonNull(rejectedArguments, "rejectedArguments");
        Objects.requireNonNull(toolName, "toolName");
        if (entityType.isBlank() || subjectId.isBlank()) {
            throw new IllegalArgumentException("entityType and subjectId must not be blank");
        }
        if (toolName.isBlank()) {
            throw new IllegalArgumentException("toolName must not be blank");
        }
        rejectedArguments = Set.copyOf(rejectedArguments);
    }

    /** Source-compatible with every call site that predates {@code toolName} and agreement findings. */
    public ContextRequest(String entityType, String subjectId, Set<String> rejectedArguments) {
        this(entityType, subjectId, rejectedArguments, DEFAULT_TOOL_NAME, false);
    }

    /** No reserved argument was rejected — the ordinary case. */
    public static ContextRequest of(String entityType, String subjectId) {
        return new ContextRequest(entityType, subjectId, Set.of(), DEFAULT_TOOL_NAME, false);
    }

    /**
     * A request for the comparison path: audited under {@code toolName} rather
     * than {@link #DEFAULT_TOOL_NAME}, and with agreement findings kept in the
     * response rather than filtered out.
     */
    public static ContextRequest comparison(String entityType, String subjectId,
                                            Set<String> rejectedArguments, String toolName) {
        return new ContextRequest(entityType, subjectId, rejectedArguments, toolName, true);
    }
}

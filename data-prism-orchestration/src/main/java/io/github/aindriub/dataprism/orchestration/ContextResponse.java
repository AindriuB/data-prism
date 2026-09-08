package io.github.aindriub.dataprism.orchestration;

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;

/**
 * A privacy-safe view of one entity.
 *
 * <p>{@code subject} is the scope-local pseudonym, not the real identifier — the
 * model gets something stable to reason with and to quote back, without ever
 * holding the key to the source systems.
 *
 * <p>{@code sources} names which systems answered. Pseudonymising those names is
 * S6; S0 emits them plainly, which is safe only because the example sources are
 * fictional.
 */
public record ContextResponse(
        String entityType,
        String subject,
        List<String> sources,
        ObjectNode entity) {

    public ContextResponse {
        sources = List.copyOf(sources);
    }
}

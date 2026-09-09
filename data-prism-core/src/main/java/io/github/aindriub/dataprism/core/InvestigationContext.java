package io.github.aindriub.dataprism.core;

import java.util.Objects;
import java.util.Set;

/**
 * Who is asking, for one invocation.
 *
 * <p>Deliberate deviation from docs/pack.md §51: the specification's
 * {@code InvestigationContext} also carries {@code scopeId}, {@code purpose} and
 * {@code expiresAt}. Those already live on {@link PrivacyContext}, which is the
 * pseudonymisation key — repeating them here would give the platform two sources
 * of truth for one scope, and a defect the moment the copies disagree. This type
 * is the other half of a session: the caller, never the scope.
 *
 * <p>Never trust the LLM to supply its own {@code principalId}, {@code caseId} or
 * capabilities. Like {@link PrivacyContext}, every component is derived from
 * authenticated infrastructure; a tool argument claiming one is ignored and
 * audited, not read.
 *
 * @param capabilities what this caller may do, from {@link Capability}
 */
public record InvestigationContext(
        String principalId, String clientId, String caseId, Set<String> capabilities) {

    public InvestigationContext {
        requireNonBlank(principalId, "principalId");
        requireNonBlank(clientId, "clientId");
        requireNonBlank(caseId, "caseId");
        Objects.requireNonNull(capabilities, "capabilities");
        capabilities = Set.copyOf(capabilities);
    }

    private static void requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be null or blank");
        }
    }

    public boolean has(String capability) {
        return capabilities.contains(capability);
    }
}

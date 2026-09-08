package io.github.aindriub.dataprism.core;

import java.time.Instant;
import java.util.Objects;

/**
 * The scope every transformation happens inside.
 *
 * <p>Nothing here comes from the caller. Scope, purpose and profile are derived
 * from the authenticated session; a tool argument claiming any of them is
 * ignored and audited. S0 hardcodes one context — deriving it from
 * authentication is S8.
 */
public record PrivacyContext(
        String scopeId,
        PrivacyScopeType scopeType,
        String redactionProfile,
        String purpose,
        Instant expiresAt,
        PseudonymisationVersion pseudonymisationVersion) {

    public PrivacyContext {
        Objects.requireNonNull(scopeId, "scopeId");
        Objects.requireNonNull(scopeType, "scopeType");
        Objects.requireNonNull(redactionProfile, "redactionProfile");
        Objects.requireNonNull(pseudonymisationVersion, "pseudonymisationVersion");
        if (scopeId.isBlank()) {
            throw new IllegalArgumentException("scopeId must not be blank");
        }
    }

    public boolean expired(Instant now) {
        return expiresAt != null && !expiresAt.isAfter(now);
    }
}

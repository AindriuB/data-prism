package io.github.aindriub.dataprism.core.link;

import java.time.Instant;
import java.util.Objects;

/**
 * What the authenticated session says about a link, never what the token says: the scope id, the principal id,
 * the scope's expiry and the caller token's expiry. {@code toString} shows no value.
 */
public record LinkBinding(String scopeId, String principalId, Instant scopeExpiry, Instant tokenExpiry) {

    public LinkBinding {
        Objects.requireNonNull(scopeId, "scopeId");
        Objects.requireNonNull(principalId, "principalId");
        Objects.requireNonNull(scopeExpiry, "scopeExpiry");
        Objects.requireNonNull(tokenExpiry, "tokenExpiry");
    }

    @Override
    public String toString() {
        return "LinkBinding[redacted]";
    }
}

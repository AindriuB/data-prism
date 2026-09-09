package io.github.aindriub.dataprism.security;

import io.github.aindriub.dataprism.core.PrivacyScopeType;

import java.util.Objects;
import java.util.Set;

/**
 * What one caller may do about one tool call. See docs/pack.md §50.
 *
 * <p>Deliberately cannot represent "denied but here are some capabilities
 * anyway": the compact constructor rejects a denied instance that carries
 * capabilities, so there is no path back to a decision a caller could use
 * despite being refused.
 */
public record AuthorizationDecision(
        boolean allowed,
        String privacyProfile,
        PrivacyScopeType scopeType,
        Set<String> capabilities,
        String denialCode) {

    public AuthorizationDecision {
        Objects.requireNonNull(capabilities, "capabilities");
        capabilities = Set.copyOf(capabilities);
        if (allowed && denialCode != null) {
            throw new IllegalArgumentException("an allowed decision must not carry a denialCode");
        }
        if (!allowed && !capabilities.isEmpty()) {
            throw new IllegalArgumentException("a denied decision must not carry capabilities");
        }
    }

    public static AuthorizationDecision denied(String denialCode) {
        Objects.requireNonNull(denialCode, "denialCode");
        return new AuthorizationDecision(false, null, null, Set.of(), denialCode);
    }
}

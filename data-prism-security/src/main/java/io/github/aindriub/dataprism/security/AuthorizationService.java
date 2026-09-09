package io.github.aindriub.dataprism.security;

import io.github.aindriub.dataprism.core.PrivacyScopeType;

import java.util.Objects;
import java.util.Set;

/**
 * Decides what an authenticated caller may do, from capabilities configured
 * per role in a {@link SecurityPolicy}.
 *
 * <p>{@code privacyProfile} and {@code scopeType} are fixed for one service
 * instance rather than selected per role: this slice does not vary the
 * redaction profile by who is asking, only by what they may do. See
 * docs/pack.md §50.
 */
public final class AuthorizationService {

    private final SecurityPolicy policy;
    private final String privacyProfile;
    private final PrivacyScopeType scopeType;

    public AuthorizationService(SecurityPolicy policy, String privacyProfile, PrivacyScopeType scopeType) {
        this.policy = Objects.requireNonNull(policy, "policy");
        this.privacyProfile = Objects.requireNonNull(privacyProfile, "privacyProfile");
        this.scopeType = Objects.requireNonNull(scopeType, "scopeType");
    }

    public AuthorizationDecision authorize(AuthenticatedCaller caller, ToolInvocation invocation) {
        Objects.requireNonNull(caller, "caller");
        Objects.requireNonNull(invocation, "invocation");

        Set<String> capabilities = policy.capabilitiesFor(caller.roles());
        if (capabilities.isEmpty()) {
            return AuthorizationDecision.denied("NO_CAPABILITIES");
        }
        if (!capabilities.contains(invocation.requiredCapability())) {
            return AuthorizationDecision.denied("TOOL_NOT_PERMITTED");
        }
        return new AuthorizationDecision(true, privacyProfile, scopeType, capabilities, null);
    }
}

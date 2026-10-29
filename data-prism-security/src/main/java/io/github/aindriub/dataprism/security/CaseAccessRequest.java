package io.github.aindriub.dataprism.security;

import java.util.Objects;
import java.util.Set;

/**
 * What a {@link CaseAuthorizer} is asked to decide: may this principal, acting
 * through this client and for this purpose and roles, work on this case.
 *
 * <p>Carries only values taken from the verified token. It deliberately holds
 * no {@code PrivacyContext}, scope id or subject id: the decision is made
 * before any of those exist.
 */
public record CaseAccessRequest(
        String principalId,
        String clientId,
        String caseId,
        String purpose,
        Set<String> roles) {

    public CaseAccessRequest {
        Objects.requireNonNull(principalId, "principalId");
        Objects.requireNonNull(clientId, "clientId");
        Objects.requireNonNull(caseId, "caseId");
        Objects.requireNonNull(purpose, "purpose");
        roles = Set.copyOf(Objects.requireNonNull(roles, "roles"));
    }
}

package io.github.aindriub.dataprism.security;

import java.util.List;
import java.util.Objects;

/**
 * Which claim in an already-verified token carries which fact about the
 * caller.
 *
 * <p>Configurable because issuers disagree on names; the defaults follow the
 * common OAuth2/OIDC convention. {@code client} names a fallback list rather
 * than a single claim because {@code azp} and {@code client_id} both appear in
 * the wild for the same fact, and an issuer that sets only one of them should
 * not need its own configuration entry.
 */
public record ClaimNames(String principal, List<String> client, String roles, String purpose, String caseId) {

    public static final ClaimNames DEFAULT =
            new ClaimNames("sub", List.of("azp", "client_id"), "roles", "purpose", "case_id");

    public ClaimNames {
        requireNonBlank(principal, "principal");
        Objects.requireNonNull(client, "client");
        if (client.isEmpty()) {
            throw new IllegalArgumentException("client must name at least one claim");
        }
        client = List.copyOf(client);
        requireNonBlank(roles, "roles");
        requireNonBlank(purpose, "purpose");
        requireNonBlank(caseId, "caseId");
    }

    private static void requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be null or blank");
        }
    }
}

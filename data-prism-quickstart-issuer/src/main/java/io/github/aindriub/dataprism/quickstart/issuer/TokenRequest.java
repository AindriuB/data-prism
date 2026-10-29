package io.github.aindriub.dataprism.quickstart.issuer;

import java.util.List;

/**
 * What a caller may ask this issuer to put in a token, every field optional
 * so {@code curl -sk -X POST .../token} with no body at all still mints a
 * usable development token — the "one-command path to a token" the
 * quickstart guide names. Every field left unset takes the fixture-only
 * default {@link #withDefaults()} supplies. {@code audience} and {@code scope}
 * stay null by default: the token is then the MCP token, with no {@code scope}
 * claim. They are validated by the controller, not here.
 */
public record TokenRequest(String subject, String clientId, List<String> roles, String purpose,
                            String caseId, Integer ttlMinutes, String audience, String scope) {

    /** The shape before the operator surface: no audience, no scope. */
    public TokenRequest(String subject, String clientId, List<String> roles, String purpose, String caseId,
                        Integer ttlMinutes) {
        this(subject, clientId, roles, purpose, caseId, ttlMinutes, null, null);
    }

    TokenRequest withDefaults() {
        return new TokenRequest(
                blankToDefault(subject, "quickstart-principal"),
                blankToDefault(clientId, "quickstart-cli"),
                roles == null || roles.isEmpty() ? List.of("investigator") : roles,
                blankToDefault(purpose, "investigation"),
                blankToDefault(caseId, "CASE-QUICKSTART-1"),
                ttlMinutes == null || ttlMinutes <= 0 ? 10 : ttlMinutes,
                audience, scope);
    }

    private static String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}

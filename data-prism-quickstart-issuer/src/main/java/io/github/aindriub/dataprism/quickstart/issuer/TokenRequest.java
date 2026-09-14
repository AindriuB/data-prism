package io.github.aindriub.dataprism.quickstart.issuer;

import java.util.List;

/**
 * What a caller may ask this issuer to put in a token, every field optional
 * so {@code curl -sk -X POST .../token} with no body at all still mints a
 * usable development token — the "one-command path to a token" the
 * quickstart guide names. Every field left unset takes the fixture-only
 * default {@link #withDefaults()} supplies.
 */
public record TokenRequest(String subject, String clientId, List<String> roles, String purpose,
                            String caseId, Integer ttlMinutes) {

    TokenRequest withDefaults() {
        return new TokenRequest(
                blankToDefault(subject, "quickstart-principal"),
                blankToDefault(clientId, "quickstart-cli"),
                roles == null || roles.isEmpty() ? List.of("investigator") : roles,
                blankToDefault(purpose, "investigation"),
                blankToDefault(caseId, "CASE-QUICKSTART-1"),
                ttlMinutes == null || ttlMinutes <= 0 ? 10 : ttlMinutes);
    }

    private static String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}

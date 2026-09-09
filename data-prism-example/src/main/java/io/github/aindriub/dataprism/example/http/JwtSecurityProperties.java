package io.github.aindriub.dataprism.example.http;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The issuer and audience a bearer JWT must carry, read from
 * {@code application.yaml} rather than derived from the JWKS URL. Both are
 * checked by {@link SecurityConfig}'s {@code JwtDecoder} on every token; a
 * token failing either is rejected before an {@code AuthenticatedCaller} is
 * ever built from it.
 */
@ConfigurationProperties(prefix = "dataprism.security.jwt")
public record JwtSecurityProperties(String issuer, String audience) {

    public JwtSecurityProperties {
        requireNonBlank(issuer, "dataprism.security.jwt.issuer");
        requireNonBlank(audience, "dataprism.security.jwt.audience");
    }

    private static void requireNonBlank(String value, String property) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(property + " must be set");
        }
    }
}

package io.github.aindriub.dataprism.security;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * A caller, as claimed by an already-verified token.
 *
 * <p>Signature verification happens in the application's filter chain, not
 * here — see this module's package-level contract in docs/plan/tasks/03. This
 * record is built from claims that are already trusted; {@link #fromClaims}
 * either produces a complete caller or refuses with a stable code, never a
 * caller with a defaulted field. Once built, this record retains neither the
 * claims map nor the token string: it is everything downstream code may read
 * about the caller.
 */
public record AuthenticatedCaller(
        String principalId,
        String clientId,
        Set<String> roles,
        String purpose,
        String caseId,
        Instant expiresAt) {

    public AuthenticatedCaller {
        requireNonBlank(principalId, "principalId");
        requireNonBlank(clientId, "clientId");
        Objects.requireNonNull(roles, "roles");
        roles = Set.copyOf(roles);
        requireNonBlank(purpose, "purpose");
        requireNonBlank(caseId, "caseId");
    }

    /**
     * @throws SecurityRefusedException with code {@code MISSING_PRINCIPAL},
     *                                  {@code MISSING_CLIENT}, {@code MISSING_PURPOSE} or
     *                                  {@code MISSING_CASE} when the corresponding claim is
     *                                  absent or blank
     */
    public static AuthenticatedCaller fromClaims(Map<String, Object> claims, ClaimNames claimNames) {
        Objects.requireNonNull(claims, "claims");
        Objects.requireNonNull(claimNames, "claimNames");

        String principalId = stringClaim(claims, claimNames.principal());
        if (principalId == null) {
            throw new SecurityRefusedException("MISSING_PRINCIPAL",
                    "no usable value for claim '" + claimNames.principal() + "'");
        }
        String clientId = firstStringClaim(claims, claimNames.client());
        if (clientId == null) {
            throw new SecurityRefusedException("MISSING_CLIENT",
                    "no usable value for claims " + claimNames.client());
        }
        String purpose = stringClaim(claims, claimNames.purpose());
        if (purpose == null) {
            throw new SecurityRefusedException("MISSING_PURPOSE",
                    "no usable value for claim '" + claimNames.purpose() + "'");
        }
        String caseId = stringClaim(claims, claimNames.caseId());
        if (caseId == null) {
            throw new SecurityRefusedException("MISSING_CASE",
                    "no usable value for claim '" + claimNames.caseId() + "'");
        }

        return new AuthenticatedCaller(
                principalId, clientId, rolesClaim(claims, claimNames.roles()), purpose, caseId,
                expiryClaim(claims));
    }

    private static String stringClaim(Map<String, Object> claims, String name) {
        Object value = claims.get(name);
        if (value == null) {
            return null;
        }
        String string = String.valueOf(value).trim();
        return string.isBlank() ? null : string;
    }

    private static String firstStringClaim(Map<String, Object> claims, List<String> names) {
        for (String name : names) {
            String value = stringClaim(claims, name);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static Set<String> rolesClaim(Map<String, Object> claims, String name) {
        Object value = claims.get(name);
        if (value == null) {
            return Set.of();
        }
        Set<String> out = new LinkedHashSet<>();
        if (value instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                addRole(out, String.valueOf(item));
            }
        } else {
            for (String part : String.valueOf(value).split("[,\\s]+")) {
                addRole(out, part);
            }
        }
        return out;
    }

    private static void addRole(Set<String> out, String candidate) {
        String role = candidate.trim();
        if (!role.isBlank()) {
            out.add(role);
        }
    }

    /** Standard JWT {@code exp}: epoch seconds, not configurable per {@link ClaimNames}. */
    private static Instant expiryClaim(Map<String, Object> claims) {
        Object value = claims.get("exp");
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return Instant.ofEpochSecond(number.longValue());
        }
        try {
            return Instant.ofEpochSecond(Long.parseLong(String.valueOf(value).trim()));
        } catch (NumberFormatException e) {
            throw new SecurityRefusedException("MALFORMED_EXPIRY", "'exp' claim is not a number");
        }
    }

    private static void requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be null or blank");
        }
    }
}

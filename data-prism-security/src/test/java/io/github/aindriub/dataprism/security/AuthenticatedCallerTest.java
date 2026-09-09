package io.github.aindriub.dataprism.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthenticatedCallerTest {

    private static Map<String, Object> fullClaims() {
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("sub", "investigator-1");
        claims.put("azp", "console");
        claims.put("roles", List.of("investigator", "reviewer"));
        claims.put("purpose", "fraud_investigation");
        claims.put("case_id", "case-42");
        claims.put("exp", 2_000_000_000L);
        return claims;
    }

    @Test
    @DisplayName("a complete claims map builds a caller with every field")
    void buildsCallerFromCompleteClaims() {
        AuthenticatedCaller caller = AuthenticatedCaller.fromClaims(fullClaims(), ClaimNames.DEFAULT);

        assertThat(caller.principalId()).isEqualTo("investigator-1");
        assertThat(caller.clientId()).isEqualTo("console");
        assertThat(caller.roles()).containsExactlyInAnyOrder("investigator", "reviewer");
        assertThat(caller.purpose()).isEqualTo("fraud_investigation");
        assertThat(caller.caseId()).isEqualTo("case-42");
        assertThat(caller.expiresAt()).isEqualTo(Instant.ofEpochSecond(2_000_000_000L));
    }

    @Test
    @DisplayName("a claims map with no subject claim refuses with MISSING_PRINCIPAL")
    void missingPrincipalRefuses() {
        Map<String, Object> claims = fullClaims();
        claims.remove("sub");

        assertThatThrownBy(() -> AuthenticatedCaller.fromClaims(claims, ClaimNames.DEFAULT))
                .isInstanceOf(SecurityRefusedException.class)
                .satisfies(e -> assertThat(((SecurityRefusedException) e).code()).isEqualTo("MISSING_PRINCIPAL"));
    }

    @Test
    @DisplayName("a claims map with neither azp nor client_id refuses with MISSING_CLIENT")
    void missingClientRefuses() {
        Map<String, Object> claims = fullClaims();
        claims.remove("azp");

        assertThatThrownBy(() -> AuthenticatedCaller.fromClaims(claims, ClaimNames.DEFAULT))
                .isInstanceOf(SecurityRefusedException.class)
                .satisfies(e -> assertThat(((SecurityRefusedException) e).code()).isEqualTo("MISSING_CLIENT"));
    }

    @Test
    @DisplayName("a claims map with no purpose claim refuses with MISSING_PURPOSE")
    void missingPurposeRefuses() {
        Map<String, Object> claims = fullClaims();
        claims.remove("purpose");

        assertThatThrownBy(() -> AuthenticatedCaller.fromClaims(claims, ClaimNames.DEFAULT))
                .isInstanceOf(SecurityRefusedException.class)
                .satisfies(e -> assertThat(((SecurityRefusedException) e).code()).isEqualTo("MISSING_PURPOSE"));
    }

    @Test
    @DisplayName("a claims map with no case_id claim refuses with MISSING_CASE")
    void missingCaseRefuses() {
        Map<String, Object> claims = fullClaims();
        claims.remove("case_id");

        assertThatThrownBy(() -> AuthenticatedCaller.fromClaims(claims, ClaimNames.DEFAULT))
                .isInstanceOf(SecurityRefusedException.class)
                .satisfies(e -> assertThat(((SecurityRefusedException) e).code()).isEqualTo("MISSING_CASE"));
    }

    @Test
    @DisplayName("client_id is used when azp is absent")
    void fallsBackToClientId() {
        Map<String, Object> claims = fullClaims();
        claims.remove("azp");
        claims.put("client_id", "batch-job");

        AuthenticatedCaller caller = AuthenticatedCaller.fromClaims(claims, ClaimNames.DEFAULT);

        assertThat(caller.clientId()).isEqualTo("batch-job");
    }

    @Test
    @DisplayName("a caller with no roles claim has an empty role set, not a refusal")
    void absentRolesYieldsEmptySet() {
        Map<String, Object> claims = fullClaims();
        claims.remove("roles");

        AuthenticatedCaller caller = AuthenticatedCaller.fromClaims(claims, ClaimNames.DEFAULT);

        assertThat(caller.roles()).isEmpty();
    }

    @Test
    @DisplayName("a blank subject claim is treated as missing rather than a blank principal")
    void blankPrincipalRefuses() {
        Map<String, Object> claims = fullClaims();
        claims.put("sub", "   ");

        assertThatThrownBy(() -> AuthenticatedCaller.fromClaims(claims, ClaimNames.DEFAULT))
                .isInstanceOf(SecurityRefusedException.class)
                .satisfies(e -> assertThat(((SecurityRefusedException) e).code()).isEqualTo("MISSING_PRINCIPAL"));
    }

    @Test
    @DisplayName("null capabilities-shaped input is rejected by the compact constructor, not the factory")
    void compactConstructorRejectsNullRoles() {
        assertThatThrownBy(() -> new AuthenticatedCaller("p", "c", null, "purpose", "case", null))
                .isInstanceOf(NullPointerException.class);
    }
}

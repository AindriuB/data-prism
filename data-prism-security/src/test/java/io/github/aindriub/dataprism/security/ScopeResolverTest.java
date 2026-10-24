package io.github.aindriub.dataprism.security;

import io.github.aindriub.dataprism.core.model.PrivacyScopeType;
import io.github.aindriub.dataprism.core.model.PseudonymisationVersion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScopeResolverTest {

    private static final PseudonymisationVersion VERSION =
            PseudonymisationVersion.HMAC_SHA256_V1.withKey("key-1").withVocabulary("vocab-1");

    private static final PurposeValidator ALLOWS_FRAUD_INVESTIGATION =
            new PurposeValidator(Set.of("fraud_investigation"));

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);

    private static AuthenticatedCaller caller(String caseId, Instant expiresAt) {
        return new AuthenticatedCaller(
                "principal-1", "client-1", Set.of("investigator"), "fraud_investigation", caseId, expiresAt);
    }

    private static ScopeResolver resolverWith(CaseAuthorizer authorizer) {
        return new ScopeResolver(VERSION, Duration.ofHours(8), ALLOWS_FRAUD_INVESTIGATION, authorizer);
    }

    private static AuthorizationDecision allowed() {
        return new AuthorizationDecision(
                true, "DEFAULT", PrivacyScopeType.CASE, Set.of("GET_ENTITY_CONTEXT"), null);
    }

    @Test
    @DisplayName("the resolved session pins keyId and vocabularyId from resolver configuration")
    void pinsPseudonymisationVersionFromConfiguration() {
        ScopeResolver resolver = new ScopeResolver(VERSION, Duration.ofHours(8), ALLOWS_FRAUD_INVESTIGATION,
                CaseAuthorizer.tokenTrusted());
        Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);

        PrivacySession session = resolver.resolve(
                caller("case-1", Instant.parse("2026-01-01T12:00:00Z")), allowed(), clock);

        assertThat(session.privacyContext().pseudonymisationVersion().keyId()).isEqualTo("key-1");
        assertThat(session.privacyContext().pseudonymisationVersion().vocabularyId()).isEqualTo("vocab-1");
        assertThat(session.privacyContext().purpose()).isEqualTo("fraud_investigation");
        assertThat(session.privacyContext().scopeType()).isEqualTo(PrivacyScopeType.CASE);
        assertThat(session.investigationContext().principalId()).isEqualTo("principal-1");
        assertThat(session.investigationContext().clientId()).isEqualTo("client-1");
        assertThat(session.investigationContext().caseId()).isEqualTo("case-1");
        assertThat(session.investigationContext().capabilities()).containsExactly("GET_ENTITY_CONTEXT");
    }

    @Test
    @DisplayName("two different case_id claims resolve to different scopeIds")
    void differentCaseIdsResolveToDifferentScopeIds() {
        ScopeResolver resolver = new ScopeResolver(VERSION, Duration.ofHours(8), ALLOWS_FRAUD_INVESTIGATION,
                CaseAuthorizer.tokenTrusted());
        Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);

        String scopeIdOne = resolver.resolve(
                caller("case-1", Instant.parse("2026-01-01T12:00:00Z")), allowed(), clock)
                .privacyContext().scopeId();
        String scopeIdTwo = resolver.resolve(
                caller("case-2", Instant.parse("2026-01-01T12:00:00Z")), allowed(), clock)
                .privacyContext().scopeId();

        assertThat(scopeIdOne).isNotEqualTo(scopeIdTwo);
    }

    @Test
    @DisplayName("the same case_id in two separate resolver instances resolves to the same scopeId")
    void sameCaseIdAcrossResolversResolvesToSameScopeId() {
        Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
        ScopeResolver resolverOne = new ScopeResolver(VERSION, Duration.ofHours(8), ALLOWS_FRAUD_INVESTIGATION,
                CaseAuthorizer.tokenTrusted());
        ScopeResolver resolverTwo = new ScopeResolver(VERSION, Duration.ofHours(8), ALLOWS_FRAUD_INVESTIGATION,
                CaseAuthorizer.tokenTrusted());

        String scopeIdOne = resolverOne.resolve(
                caller("case-shared", Instant.parse("2026-01-01T12:00:00Z")), allowed(), clock)
                .privacyContext().scopeId();
        String scopeIdTwo = resolverTwo.resolve(
                caller("case-shared", Instant.parse("2026-01-01T12:00:00Z")), allowed(), clock)
                .privacyContext().scopeId();

        assertThat(scopeIdOne).isEqualTo(scopeIdTwo);
    }

    @Test
    @DisplayName("expiresAt is the token expiry when it is earlier than the configured maximum lifetime")
    void expiresAtIsTokenExpiryWhenEarlier() {
        ScopeResolver resolver = new ScopeResolver(VERSION, Duration.ofDays(1), ALLOWS_FRAUD_INVESTIGATION,
                CaseAuthorizer.tokenTrusted());
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        Clock clock = Clock.fixed(now, ZoneOffset.UTC);
        Instant tokenExpiry = now.plusSeconds(30);

        PrivacySession session = resolver.resolve(caller("case-1", tokenExpiry), allowed(), clock);

        assertThat(session.privacyContext().expiresAt()).isEqualTo(tokenExpiry);
    }

    @Test
    @DisplayName("expiresAt is the configured maximum lifetime when the token expiry is later")
    void expiresAtIsConfiguredMaximumWhenEarlier() {
        ScopeResolver resolver = new ScopeResolver(VERSION, Duration.ofMinutes(30), ALLOWS_FRAUD_INVESTIGATION,
                CaseAuthorizer.tokenTrusted());
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        Clock clock = Clock.fixed(now, ZoneOffset.UTC);
        Instant tokenExpiry = now.plus(Duration.ofDays(1));

        PrivacySession session = resolver.resolve(caller("case-1", tokenExpiry), allowed(), clock);

        assertThat(session.privacyContext().expiresAt()).isEqualTo(now.plus(Duration.ofMinutes(30)));
    }

    @Test
    @DisplayName("a caller whose token has already expired against the injected clock is refused")
    void expiredTokenIsRefused() {
        ScopeResolver resolver = new ScopeResolver(VERSION, Duration.ofHours(8), ALLOWS_FRAUD_INVESTIGATION,
                CaseAuthorizer.tokenTrusted());
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        Clock clock = Clock.fixed(now, ZoneOffset.UTC);
        Instant tokenExpiry = now.minusSeconds(1);

        assertThatThrownBy(() -> resolver.resolve(caller("case-1", tokenExpiry), allowed(), clock))
                .isInstanceOf(SecurityRefusedException.class)
                .satisfies(e -> assertThat(((SecurityRefusedException) e).code()).isEqualTo("TOKEN_EXPIRED"));
    }

    @Test
    @DisplayName("a denied decision is refused rather than resolved into a session")
    void deniedDecisionIsRefused() {
        ScopeResolver resolver = new ScopeResolver(VERSION, Duration.ofHours(8), ALLOWS_FRAUD_INVESTIGATION,
                CaseAuthorizer.tokenTrusted());
        Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
        AuthorizationDecision denied = AuthorizationDecision.denied("NO_CAPABILITIES");

        assertThatThrownBy(() -> resolver.resolve(
                caller("case-1", Instant.parse("2026-01-01T12:00:00Z")), denied, clock))
                .isInstanceOf(SecurityRefusedException.class)
                .satisfies(e -> assertThat(((SecurityRefusedException) e).code()).isEqualTo("NO_CAPABILITIES"));
    }

    @Test
    @DisplayName("a caller whose purpose is not in the configured list is refused with UNKNOWN_PURPOSE")
    void unknownPurposeIsRefused() {
        PurposeValidator noPurposesAllowed = new PurposeValidator(Set.of());
        ScopeResolver resolver = new ScopeResolver(VERSION, Duration.ofHours(8), noPurposesAllowed,
                CaseAuthorizer.tokenTrusted());
        Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);

        assertThatThrownBy(() -> resolver.resolve(
                caller("case-1", Instant.parse("2026-01-01T12:00:00Z")), allowed(), clock))
                .isInstanceOf(SecurityRefusedException.class)
                .satisfies(e -> assertThat(((SecurityRefusedException) e).code())
                        .isEqualTo(PurposeValidator.UNKNOWN_PURPOSE));
    }

    @Test
    @DisplayName("a caller whose purpose is in the configured list resolves to a session carrying that purpose")
    void configuredPurposeResolvesIntoSession() {
        ScopeResolver resolver = new ScopeResolver(VERSION, Duration.ofHours(8), ALLOWS_FRAUD_INVESTIGATION,
                CaseAuthorizer.tokenTrusted());
        Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);

        PrivacySession session = resolver.resolve(
                caller("case-1", Instant.parse("2026-01-01T12:00:00Z")), allowed(), clock);

        assertThat(session.privacyContext().purpose()).isEqualTo("fraud_investigation");
    }

    @Test
    @DisplayName("the case authorizer is called exactly once per resolve, with the caller's own values")
    void authorizerCalledExactlyOnce() {
        AtomicInteger calls = new AtomicInteger();
        List<CaseAccessRequest> seen = new ArrayList<>();
        ScopeResolver resolver = resolverWith(request -> {
            calls.incrementAndGet();
            seen.add(request);
            return true;
        });

        resolver.resolve(caller("case-1", null), allowed(), CLOCK);

        assertThat(calls).hasValue(1);
        assertThat(seen).singleElement().isEqualTo(new CaseAccessRequest(
                "principal-1", "client-1", "case-1", "fraud_investigation", Set.of("investigator")));
    }

    @Test
    @DisplayName("the case authorizer is not called when the purpose is refused")
    void authorizerNotCalledWhenPurposeRefused() {
        AtomicInteger calls = new AtomicInteger();
        ScopeResolver resolver = new ScopeResolver(VERSION, Duration.ofHours(8),
                new PurposeValidator(Set.of()), request -> {
                    calls.incrementAndGet();
                    return true;
                });

        assertThatThrownBy(() -> resolver.resolve(caller("case-1", null), allowed(), CLOCK))
                .isInstanceOfSatisfying(SecurityRefusedException.class,
                        e -> assertThat(e.code()).isEqualTo(PurposeValidator.UNKNOWN_PURPOSE));
        assertThat(calls).hasValue(0);
    }

    @Test
    @DisplayName("an authorizer that returns false refuses with CASE_NOT_AUTHORISED")
    void falseRefusesWithCaseNotAuthorised() {
        ScopeResolver resolver = resolverWith(request -> false);

        assertThatThrownBy(() -> resolver.resolve(caller("case-1", null), allowed(), CLOCK))
                .isInstanceOfSatisfying(SecurityRefusedException.class,
                        e -> assertThat(e.code()).isEqualTo("CASE_NOT_AUTHORISED"));
    }

    @Test
    @DisplayName("an authorizer that throws refuses with CASE_AUTHORISATION_UNAVAILABLE and leaks nothing")
    void throwRefusesWithUnavailableAndLeaksNothing() {
        String sentinel = "sentinel-store-down-/srv/assignments.db";
        ScopeResolver resolver = resolverWith(request -> {
            throw new IllegalStateException(sentinel);
        });

        assertThatThrownBy(() -> resolver.resolve(caller("case-1", null), allowed(), CLOCK))
                .isInstanceOfSatisfying(SecurityRefusedException.class, e -> {
                    assertThat(e.code()).isEqualTo("CASE_AUTHORISATION_UNAVAILABLE");
                    assertThat(e.getMessage()).doesNotContain(sentinel);
                    assertThat(e.getCause()).isNull();
                });
    }

    @Test
    @DisplayName("an Error from the authorizer is not caught and no session is returned")
    void errorFromAuthorizerPropagates() {
        ScopeResolver resolver = resolverWith(request -> {
            throw new AssertionError("boom");
        });

        assertThatThrownBy(() -> resolver.resolve(caller("case-1", null), allowed(), CLOCK))
                .isInstanceOf(AssertionError.class);
    }
}

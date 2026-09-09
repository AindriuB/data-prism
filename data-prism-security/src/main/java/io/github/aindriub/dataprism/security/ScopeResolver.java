package io.github.aindriub.dataprism.security;

import io.github.aindriub.dataprism.core.InvestigationContext;
import io.github.aindriub.dataprism.core.PrivacyContext;
import io.github.aindriub.dataprism.core.PseudonymisationVersion;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Turns an authenticated caller and its authorisation decision into a
 * {@link PrivacySession}.
 *
 * <p>{@code scopeId} is a pure function of the caller's {@code case_id} claim:
 * no cache, no counter, nothing that would let two resolver instances disagree
 * about the same case. {@code keyId} and {@code vocabularyId} come from this
 * resolver's own configuration — the {@link PseudonymisationVersion} handed to
 * the constructor — and that same version is pinned onto every session this
 * instance produces.
 *
 * <p>{@link #resolve} fails closed on purpose: a caller whose purpose is not in
 * the configured list is refused rather than resolved into a session, with
 * code {@code UNKNOWN_PURPOSE}.
 */
public final class ScopeResolver {

    private final PseudonymisationVersion pseudonymisationVersion;
    private final Duration maxScopeLifetime;
    private final PurposeValidator purposeValidator;

    public ScopeResolver(
            PseudonymisationVersion pseudonymisationVersion,
            Duration maxScopeLifetime,
            PurposeValidator purposeValidator) {
        this.pseudonymisationVersion = Objects.requireNonNull(pseudonymisationVersion, "pseudonymisationVersion");
        this.maxScopeLifetime = Objects.requireNonNull(maxScopeLifetime, "maxScopeLifetime");
        this.purposeValidator = Objects.requireNonNull(purposeValidator, "purposeValidator");
        if (maxScopeLifetime.isZero() || maxScopeLifetime.isNegative()) {
            throw new IllegalArgumentException("maxScopeLifetime must be positive");
        }
    }

    /**
     * @throws SecurityRefusedException with code {@code TOKEN_EXPIRED} when
     *                                  {@code caller}'s token has already expired against
     *                                  {@code clock}, with the decision's own
     *                                  {@code denialCode} when {@code decision} is not allowed, or
     *                                  with code {@link PurposeValidator#UNKNOWN_PURPOSE} when
     *                                  {@code caller}'s purpose is not in the configured list
     */
    public PrivacySession resolve(AuthenticatedCaller caller, AuthorizationDecision decision, Clock clock) {
        Objects.requireNonNull(caller, "caller");
        Objects.requireNonNull(decision, "decision");
        Objects.requireNonNull(clock, "clock");

        Instant now = clock.instant();
        if (caller.expiresAt() != null && !caller.expiresAt().isAfter(now)) {
            throw new SecurityRefusedException("TOKEN_EXPIRED", "caller's token has already expired");
        }
        if (!decision.allowed()) {
            throw new SecurityRefusedException(decision.denialCode(), "caller is not authorised");
        }
        String purpose = purposeValidator.validate(caller.purpose());

        Instant configuredExpiry = now.plus(maxScopeLifetime);
        Instant expiresAt = caller.expiresAt() == null || configuredExpiry.isBefore(caller.expiresAt())
                ? configuredExpiry
                : caller.expiresAt();

        PrivacyContext privacyContext = new PrivacyContext(
                scopeId(caller.caseId()),
                decision.scopeType(),
                decision.privacyProfile(),
                purpose,
                expiresAt,
                pseudonymisationVersion);

        InvestigationContext investigationContext = new InvestigationContext(
                caller.principalId(), caller.clientId(), caller.caseId(), decision.capabilities());

        return new PrivacySession(privacyContext, investigationContext);
    }

    private static String scopeId(String caseId) {
        return "case:" + caseId;
    }
}

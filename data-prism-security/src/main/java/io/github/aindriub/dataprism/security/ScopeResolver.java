package io.github.aindriub.dataprism.security;

import io.github.aindriub.dataprism.core.model.InvestigationContext;
import io.github.aindriub.dataprism.core.model.PrivacyContext;
import io.github.aindriub.dataprism.core.model.PseudonymisationVersion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
 * code {@code UNKNOWN_PURPOSE}. It then asks the {@link CaseAuthorizer} whether
 * the principal may work on the case, and refuses if the answer is no or cannot
 * be obtained.
 */
public final class ScopeResolver {

    private static final Logger LOG = LoggerFactory.getLogger(ScopeResolver.class);

    public static final String CASE_NOT_AUTHORISED = "CASE_NOT_AUTHORISED";
    public static final String CASE_AUTHORISATION_UNAVAILABLE = "CASE_AUTHORISATION_UNAVAILABLE";

    private final PseudonymisationVersion pseudonymisationVersion;
    private final Duration maxScopeLifetime;
    private final PurposeValidator purposeValidator;
    private final CaseAuthorizer caseAuthorizer;

    public ScopeResolver(
            PseudonymisationVersion pseudonymisationVersion,
            Duration maxScopeLifetime,
            PurposeValidator purposeValidator,
            CaseAuthorizer caseAuthorizer) {
        this.pseudonymisationVersion = Objects.requireNonNull(pseudonymisationVersion, "pseudonymisationVersion");
        this.maxScopeLifetime = Objects.requireNonNull(maxScopeLifetime, "maxScopeLifetime");
        this.purposeValidator = Objects.requireNonNull(purposeValidator, "purposeValidator");
        this.caseAuthorizer = Objects.requireNonNull(caseAuthorizer, "caseAuthorizer");
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
     *                                  {@code caller}'s purpose is not in the configured list,
     *                                  with code {@code CASE_NOT_AUTHORISED} when the
     *                                  {@link CaseAuthorizer} refuses, or with code
     *                                  {@code CASE_AUTHORISATION_UNAVAILABLE} when it throws
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
        requireCaseAccess(caller);

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

    private void requireCaseAccess(AuthenticatedCaller caller) {
        boolean authorised;
        try {
            authorised = caseAuthorizer.isAuthorised(new CaseAccessRequest(
                    caller.principalId(), caller.clientId(), caller.caseId(), caller.purpose(), caller.roles()));
        } catch (RuntimeException e) {
            // Server log only, for the operator; the client message stays code-only. The log
            // message names no case or principal (docs/conventions.md, deliberate exception).
            LOG.warn("case authorizer threw; refusing with {}", CASE_AUTHORISATION_UNAVAILABLE, e);
            throw new SecurityRefusedException(CASE_AUTHORISATION_UNAVAILABLE, "case authorisation could not be decided");
        }
        if (!authorised) {
            throw new SecurityRefusedException(CASE_NOT_AUTHORISED, "caller is not authorised for the case");
        }
    }

    private static String scopeId(String caseId) {
        return "case:" + caseId;
    }
}

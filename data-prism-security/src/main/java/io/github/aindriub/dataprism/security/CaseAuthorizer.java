package io.github.aindriub.dataprism.security;

/**
 * Decides whether a principal may work on the case named in its token.
 *
 * <p>{@link ScopeResolver} calls this once per resolution, after the purpose
 * check and before any privacy context is built. {@code false} refuses with
 * {@code CASE_NOT_AUTHORISED}; any {@link RuntimeException} refuses with
 * {@code CASE_AUTHORISATION_UNAVAILABLE}. Both fail closed.
 */
@FunctionalInterface
public interface CaseAuthorizer {

    boolean isAuthorised(CaseAccessRequest request);

    /**
     * An authorizer that always permits, and always the same instance. It trusts the token's case claim
     * without checking that the principal is assigned to that case. A Spring deployment gets it only by setting
     * {@code dataprism.security-policy.case-authorisation=token-trusted}, which logs a startup warning; with no
     * {@code CaseAuthorizer} bean and no such setting, startup is refused.
     */
    static CaseAuthorizer tokenTrusted() {
        return TokenTrustedCaseAuthorizer.INSTANCE;
    }
}

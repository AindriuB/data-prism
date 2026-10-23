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
     * An authorizer that always permits. It trusts the token's case claim
     * without checking that the principal is assigned to that case. A Spring
     * deployment gets it only through the explicit
     * {@code dataprism.security-policy.case-authorisation=token-trusted}
     * setting (D-1.0-1).
     */
    static CaseAuthorizer tokenTrusted() {
        return request -> true;
    }
}

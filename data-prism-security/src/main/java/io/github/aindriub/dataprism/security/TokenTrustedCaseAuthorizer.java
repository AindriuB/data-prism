package io.github.aindriub.dataprism.security;

/** The one {@link CaseAuthorizer#tokenTrusted()} instance, so that callers can rely on its identity. */
final class TokenTrustedCaseAuthorizer implements CaseAuthorizer {
    static final TokenTrustedCaseAuthorizer INSTANCE = new TokenTrustedCaseAuthorizer();

    private TokenTrustedCaseAuthorizer() {
    }

    @Override
    public boolean isAuthorised(CaseAccessRequest request) {
        return true;
    }
}

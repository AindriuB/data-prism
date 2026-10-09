package io.github.aindriub.dataprism.spring.boot;

import java.util.Set;

public class SecurityProperties {
    private Jwt jwt = new Jwt();
    private CallerClaims callerClaims = new CallerClaims();

    public Jwt getJwt() {
        return jwt;
    }

    public void setJwt(Jwt v) {
        jwt = v == null ? new Jwt() : v;
    }

    public CallerClaims getCallerClaims() {
        return callerClaims;
    }

    public void setCallerClaims(CallerClaims v) {
        callerClaims = v == null ? new CallerClaims() : v;
    }

    public static class Jwt {
        /** Trusted JWT issuer. Required for HTTP. */
        private String issuer;
        /** Required JWT audience. Required for HTTP. */
        private String audience;
        /** Trusted JWKS location. Exactly one of this and issuer-discovery-uri. */
        private String jwkSetUri;
        /** Trusted issuer-discovery location. Exactly one of this and jwk-set-uri. */
        private String issuerDiscoveryUri;

        public String getIssuer() {
            return issuer;
        }

        public void setIssuer(String v) {
            issuer = v;
        }

        public String getAudience() {
            return audience;
        }

        public void setAudience(String v) {
            audience = v;
        }

        public String getJwkSetUri() {
            return jwkSetUri;
        }

        public void setJwkSetUri(String v) {
            jwkSetUri = v;
        }

        public String getIssuerDiscoveryUri() {
            return issuerDiscoveryUri;
        }

        public void setIssuerDiscoveryUri(String v) {
            issuerDiscoveryUri = v;
        }
    }

    public static class CallerClaims {
        /** Name of the claim carrying the caller principal. */
        private String principal;
        /** Name of the claim carrying the caller roles. */
        private String roles;
        /** Name of the trusted claim carrying the investigation or case attribute. */
        private String investigation;

        public String getPrincipal() {
            return principal;
        }

        public void setPrincipal(String v) {
            principal = v;
        }

        public String getRoles() {
            return roles;
        }

        public void setRoles(String v) {
            roles = v;
        }

        public String getInvestigation() {
            return investigation;
        }

        public void setInvestigation(String v) {
            investigation = v;
        }
    }
}

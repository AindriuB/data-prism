package io.github.aindriub.dataprism.spring.boot;

import java.time.Duration;

public class SourceProperties {
    /** Server-controlled HTTPS base URL of the source. */
    private String baseUrl;
    /** Reference to the service credential. A reference only, never a literal secret. */
    private String serviceCredentialReference;
    /** Positive request timeout for the source. */
    private Duration timeout;
    private Mtls mtls = new Mtls();

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String v) {
        baseUrl = v;
    }

    public Duration getTimeout() {
        return timeout;
    }

    public void setTimeout(Duration v) {
        timeout = v;
    }

    public String getServiceCredentialReference() {
        return serviceCredentialReference;
    }

    public void setServiceCredentialReference(String v) {
        serviceCredentialReference = v;
    }

    public Mtls getMtls() {
        return mtls;
    }

    public void setMtls(Mtls v) {
        mtls = v == null ? new Mtls() : v;
    }

    public static class Mtls {
        /** Reference to the mTLS client key material. */
        private String keyReference;
        /** Reference to the mTLS trust material. */
        private String trustReference;

        public String getKeyReference() {
            return keyReference;
        }

        public void setKeyReference(String v) {
            keyReference = v;
        }

        public String getTrustReference() {
            return trustReference;
        }

        public void setTrustReference(String v) {
            trustReference = v;
        }
    }
}

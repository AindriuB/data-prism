package io.github.aindriub.dataprism.spring.boot;

import java.time.Duration;

public class SourceProperties {
    private String baseUrl, serviceCredentialReference;
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
        private String keyReference, trustReference;

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

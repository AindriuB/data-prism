package io.github.aindriub.dataprism.spring.boot;

import java.time.Duration;

public class PrivacyProperties {
    private String profile, locale = "neutral";
    private Duration scopeLifetime;
    private HmacKey hmacKey = new HmacKey();
    private String descriptorFile;

    public String getProfile() {
        return profile;
    }

    public void setProfile(String v) {
        profile = v;
    }

    public String getLocale() {
        return locale;
    }

    public void setLocale(String v) {
        locale = v;
    }

    public Duration getScopeLifetime() {
        return scopeLifetime;
    }

    public void setScopeLifetime(Duration v) {
        scopeLifetime = v;
    }

    public HmacKey getHmacKey() {
        return hmacKey;
    }

    public void setHmacKey(HmacKey v) {
        hmacKey = v == null ? new HmacKey() : v;
    }

    /**
     * Optional path to a YAML model-descriptor file. Unset by default, in which
     * case no descriptor file is ever read and classification comes only from
     * annotations. See {@code DataPrismAutoConfiguration#dataPrismFieldMetadataResolver}
     * for how it is loaded and why every failure mode refuses startup.
     */
    public String getDescriptorFile() {
        return descriptorFile;
    }

    public void setDescriptorFile(String v) {
        descriptorFile = v;
    }

    public static class HmacKey {
        private String keyId, environmentVariable, providerReference, value;

        public String getKeyId() {
            return keyId;
        }

        public void setKeyId(String v) {
            keyId = v;
        }

        public String getEnvironmentVariable() {
            return environmentVariable;
        }

        public void setEnvironmentVariable(String v) {
            environmentVariable = v;
        }

        public String getProviderReference() {
            return providerReference;
        }

        public void setProviderReference(String v) {
            providerReference = v;
        }

        public String getValue() {
            return value;
        }

        public void setValue(String v) {
            value = v;
        }
    }
}

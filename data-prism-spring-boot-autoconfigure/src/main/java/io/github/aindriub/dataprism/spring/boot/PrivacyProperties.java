package io.github.aindriub.dataprism.spring.boot;

import java.time.Duration;

public class PrivacyProperties {
    /** Privacy profile to apply. Required. */
    private String profile;
    /** Vocabulary locale; neutral selects the locale-neutral vocabulary. */
    private String locale = "neutral";
    /** Lifetime of a privacy scope. Required in production; must be positive. */
    private Duration scopeLifetime;
    private HmacKey hmacKey = new HmacKey();
    /** Optional model descriptor file. When unset no descriptor file is read and classification comes from annotations alone. */
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
     * annotations. See {@code PrivacyEngineWiring#dataPrismFieldMetadataResolver}
     * for how it is loaded and why every failure mode refuses startup.
     */
    public String getDescriptorFile() {
        return descriptorFile;
    }

    public void setDescriptorFile(String v) {
        descriptorFile = v;
    }

    public static class HmacKey {
        /** Identifier of the HMAC key to pin for each scope. Required. */
        private String keyId;
        /** Name of the environment variable holding the key. A name only, never the key itself. */
        private String environmentVariable;
        /** Reference to a key provider. A reference only, never a literal key. */
        private String providerReference;
        /** Refused if set: a literal key is never accepted. */
        private String value;

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

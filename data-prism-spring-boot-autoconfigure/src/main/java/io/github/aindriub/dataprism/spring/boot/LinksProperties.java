package io.github.aindriub.dataprism.spring.boot;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code dataprism.links.*}: the link key only. Page links need it even while links are off; the remaining link
 * keys belong to the follow wiring.
 */
public class LinksProperties {
    /** Id of the key that seals new links. It must be a key of the keys map below. At most 16 characters of A-Z, a-z, 0-9, underscore and hyphen. Unset means no page links. */
    private String keyId;
    /** Link keys by key id, the current one and any being retired: each held for verification, only key-id signs. Set exactly one of environment-variable and provider-reference per key. */
    private Map<String, Key> keys = new LinkedHashMap<>();

    public String getKeyId() {
        return keyId;
    }

    public void setKeyId(String v) {
        keyId = v;
    }

    public Map<String, Key> getKeys() {
        return keys;
    }

    public void setKeys(Map<String, Key> v) {
        keys = v == null ? new LinkedHashMap<>() : new LinkedHashMap<>(v);
    }

    /** Whether a link key is configured at all. */
    boolean keyConfigured() {
        return keyId != null && !keyId.isBlank();
    }

    /** One link key's reference; its id is the map key. Set exactly one of the two. */
    public static class Key {
        /** Name of the environment variable holding the key. Exactly one of this and provider-reference. */
        private String environmentVariable;
        /** Reference to a key provider for the key. Exactly one of this and environment-variable. */
        private String providerReference;

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
    }
}

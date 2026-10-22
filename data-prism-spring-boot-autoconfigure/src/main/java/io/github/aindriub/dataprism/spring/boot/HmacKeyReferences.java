package io.github.aindriub.dataprism.spring.boot;

/**
 * The one place the privacy HMAC key's reference is resolved from its configuration: an environment-variable name
 * goes to {@link HmacKeyReferenceResolver#resolve}, a provider reference to
 * {@link HmacKeyReferenceResolver#resolveProviderReference}, the same split the cluster keys use.
 */
final class HmacKeyReferences {
    private HmacKeyReferences() {
    }

    static boolean isProviderReference(PrivacyProperties.HmacKey key) {
        return key.getEnvironmentVariable() == null || key.getEnvironmentVariable().isBlank();
    }

    /** The configured reference: the environment variable if one is set, otherwise the provider reference. */
    static String reference(PrivacyProperties.HmacKey key) {
        return isProviderReference(key) ? key.getProviderReference() : key.getEnvironmentVariable();
    }

    static byte[] resolve(HmacKeyReferenceResolver resolver, PrivacyProperties.HmacKey key, String keyId) {
        return resolve(resolver, isProviderReference(key), keyId, reference(key));
    }

    static byte[] resolve(HmacKeyReferenceResolver resolver, boolean provider, String keyId, String reference) {
        return provider ? resolver.resolveProviderReference(keyId, reference) : resolver.resolve(keyId, reference);
    }
}

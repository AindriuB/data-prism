package io.github.aindriub.dataprism.spring.boot;

/**
 * Application integration point for the configured HMAC key reference.
 * Implementations may resolve an environment-variable name, a secret-manager
 * reference, or another reviewed provider identifier; they must never accept
 * literal key material from configuration.
 */
@FunctionalInterface
public interface HmacKeyReferenceResolver {
    /** Resolves a reference configured as an environment-variable name (or, for a plain resolver, any reference). */
    byte[] resolve(String keyId, String reference);

    /**
     * Resolves a reference configured as a {@code provider-reference}. The default hands it to
     * {@link #resolve}, which is right for a resolver that understands both kinds of reference. A resolver that
     * reads only environment variables overrides this to refuse, so that a provider reference is never silently
     * looked up as the name of an environment variable.
     */
    default byte[] resolveProviderReference(String keyId, String reference) {
        return resolve(keyId, reference);
    }
}

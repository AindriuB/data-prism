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
     * Resolves a reference configured as a {@code provider-reference}. The default refuses with
     * {@code MISSING_KEY_PROVIDER}, naming no reference: a resolver that reads only environment variables must never
     * treat a provider reference as the name of a variable. A resolver that serves provider references overrides
     * this. (Before 0.8 such a resolver took them in {@link #resolve}; see the migration guide.)
     */
    default byte[] resolveProviderReference(String keyId, String reference) {
        throw new DataPrismConfigurationException("MISSING_KEY_PROVIDER",
                "this HmacKeyReferenceResolver does not resolve provider references");
    }
}

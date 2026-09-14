package io.github.aindriub.dataprism.spring.boot;

/**
 * Application integration point for the configured HMAC key reference.
 * Implementations may resolve an environment-variable name, a secret-manager
 * reference, or another reviewed provider identifier; they must never accept
 * literal key material from configuration.
 */
@FunctionalInterface
public interface HmacKeyReferenceResolver {
    byte[] resolve(String keyId, String reference);
}

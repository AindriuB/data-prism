package io.github.aindriub.dataprism.core;

/**
 * Supplies HMAC key material by key id.
 *
 * <p>An SPI with no production implementation in this project on purpose: a
 * generic library should not make every consumer take a dependency on one
 * organisation's secret manager. Implement it against Vault, a cloud secrets
 * service or an HSM as appropriate.
 *
 * <p>Accepted consequence of holding the key in application memory: a memory
 * disclosure exposes every scope, where a KMS-derived per-scope subkey would
 * have limited it to live ones. Recorded in docs/architecture.md.
 */
public interface SecretKeyProvider {

    /**
     * @return the raw key for {@code keyId}; callers must not retain or log it
     * @throws IllegalStateException if the key is unknown or unavailable — never
     *                               fall back to a default, which would silently
     *                               change every synthetic value
     */
    byte[] secret(String keyId);
}

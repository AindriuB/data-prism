package io.github.aindriub.dataprism.pseudonymisation;

import io.github.aindriub.dataprism.core.SecretKeyProvider;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Holds one key in memory, supplied explicitly.
 *
 * <p>For tests and the example application, where determinism has to be
 * reproducible from the source tree. Not for production: a key that lives in
 * code is a key in version control. Production wires
 * {@link EnvironmentSecretKeyProvider} or an organisation's own implementation.
 */
public final class StaticSecretKeyProvider implements SecretKeyProvider {

    private final byte[] key;

    public StaticSecretKeyProvider(byte[] key) {
        this.key = Objects.requireNonNull(key, "key").clone();
    }

    public static StaticSecretKeyProvider of(String key) {
        return new StaticSecretKeyProvider(key.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public byte[] secret(String keyId) {
        return key.clone();
    }
}

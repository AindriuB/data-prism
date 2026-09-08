package io.github.aindriub.dataprism.pseudonymisation;

import io.github.aindriub.dataprism.core.SecretKeyProvider;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Function;

/**
 * Reads key material from the environment, as {@code DATA_PRISM_HMAC_KEY_<KEYID>}.
 *
 * <p>For development and for deployments whose secret manager already projects
 * secrets into the environment. It has no default and no fallback: a missing key
 * is a startup failure, because quietly substituting one would change every
 * synthetic value in the system without anything appearing to go wrong.
 */
public final class EnvironmentSecretKeyProvider implements SecretKeyProvider {

    private static final String PREFIX = "DATA_PRISM_HMAC_KEY_";
    private static final int MIN_LENGTH = 32;

    private final Function<String, String> environment;

    public EnvironmentSecretKeyProvider() {
        this(System::getenv);
    }

    EnvironmentSecretKeyProvider(Function<String, String> environment) {
        this.environment = Objects.requireNonNull(environment, "environment");
    }

    @Override
    public byte[] secret(String keyId) {
        String name = PREFIX + Objects.requireNonNull(keyId, "keyId").toUpperCase(Locale.ROOT);
        String value = environment.apply(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is not set");
        }
        if (value.length() < MIN_LENGTH) {
            throw new IllegalStateException(name + " is shorter than " + MIN_LENGTH + " characters");
        }
        return value.getBytes(StandardCharsets.UTF_8);
    }
}

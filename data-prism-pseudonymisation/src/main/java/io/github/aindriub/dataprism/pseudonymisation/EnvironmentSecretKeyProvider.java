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
 *
 * <p>Several key ids resolve side by side, which is what a rotation needs: export
 * the new key alongside the old one and scopes pinned to either keep working. The
 * old key is retired by unsetting its variable, deliberately, once no scope still
 * depends on it. {@link MultiKeySecretKeyProvider} is the in-memory equivalent for
 * deployments that load key material some other way.
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
        String id = Objects.requireNonNull(keyId, "keyId").strip();
        if (id.isEmpty()) {
            // Otherwise this reads the bare prefix, which is a variable somebody
            // could plausibly have exported, and the scope silently gets a key
            // that was pinned to no id at all.
            throw new IllegalStateException("keyId is blank");
        }
        String name = PREFIX + id.toUpperCase(Locale.ROOT);
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

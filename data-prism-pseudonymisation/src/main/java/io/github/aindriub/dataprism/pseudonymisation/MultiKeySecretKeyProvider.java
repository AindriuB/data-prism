package io.github.aindriub.dataprism.pseudonymisation;

import io.github.aindriub.dataprism.core.SecretKeyProvider;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Holds several keys in memory at once, one per key id.
 *
 * <p>This is what makes a key rotation survivable. A scope pins its key id at
 * creation and keeps it for life, so at any moment the live scopes span every key
 * that has not yet aged out. Registering the new key does not retire the old one:
 * scopes still pinned to it keep resolving until {@link #remove(String)} is called
 * for that id, which is a deliberate act rather than a side effect of rotating.
 * Retiring a key while a scope still depends on it changes that scope's pseudonyms
 * mid-investigation, so retirement is worth making someone ask for.
 *
 * <p>Key ids match exactly; there is no normalisation and no default. An id with
 * no key registered throws, naming the id — see {@link SecretKeyProvider#secret}
 * for why substituting any other key would be worse than failing.
 *
 * <p>Not a {@code record} on purpose: the generated {@code toString} would print
 * every key held.
 */
public final class MultiKeySecretKeyProvider implements SecretKeyProvider {

    /**
     * Matches {@link EnvironmentSecretKeyProvider}'s minimum, so a key accepted by
     * one provider is accepted by the other and moving between them cannot
     * quietly weaken the HMAC.
     */
    private static final int MIN_LENGTH = 32;

    private final ConcurrentMap<String, byte[]> keys = new ConcurrentHashMap<>();

    public MultiKeySecretKeyProvider() {
    }

    /** @param keys key material by key id, copied defensively */
    public MultiKeySecretKeyProvider(Map<String, byte[]> keys) {
        Objects.requireNonNull(keys, "keys").forEach(this::add);
    }

    /**
     * Registers a key, replacing any key already held under {@code keyId}.
     *
     * @return this provider, so a rotation can be wired in one expression
     * @throws IllegalArgumentException if the id is blank or the key is too short
     */
    public MultiKeySecretKeyProvider add(String keyId, byte[] key) {
        String id = Objects.requireNonNull(keyId, "keyId").strip();
        if (id.isEmpty()) {
            throw new IllegalArgumentException("keyId is blank");
        }
        Objects.requireNonNull(key, "key");
        if (key.length < MIN_LENGTH) {
            // Names the id, never the key: an exception message travels into logs.
            throw new IllegalArgumentException(
                    "key for id '" + id + "' is shorter than " + MIN_LENGTH + " bytes");
        }
        keys.put(id, key.clone());
        return this;
    }

    public MultiKeySecretKeyProvider add(String keyId, String key) {
        return add(keyId, Objects.requireNonNull(key, "key").getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Retires a key. Every scope pinned to {@code keyId} stops resolving from here
     * on, which is the point: it is how an operator learns a scope still depended
     * on the key rather than discovering renamed pseudonyms later.
     *
     * @return whether a key was held under that id
     */
    public boolean remove(String keyId) {
        return keys.remove(Objects.requireNonNull(keyId, "keyId")) != null;
    }

    /** The ids currently resolvable, sorted. Ids are not secret; the keys are. */
    public Set<String> keyIds() {
        return new TreeSet<>(keys.keySet());
    }

    @Override
    public byte[] secret(String keyId) {
        byte[] key = keys.get(Objects.requireNonNull(keyId, "keyId"));
        if (key == null) {
            throw new IllegalStateException("no key registered for key id '" + keyId + "'");
        }
        return key.clone();
    }

    /** Ids only. Key material must never reach a log line or a debugger label. */
    @Override
    public String toString() {
        return "MultiKeySecretKeyProvider" + keyIds();
    }
}

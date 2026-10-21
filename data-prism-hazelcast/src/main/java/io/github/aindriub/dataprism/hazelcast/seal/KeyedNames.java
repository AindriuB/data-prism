package io.github.aindriub.dataprism.hazelcast.seal;

import io.github.aindriub.dataprism.core.crypto.LabelledHkdf;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Keyed pseudonymous names for map keys, lock keys and the scope token (docs/plan/design/cluster-security.md
 * 2.3.2). Every output is HMAC-SHA256 under a subkey of the name key only, as unpadded URL-safe base64, so a
 * subject id, principal, approval id or pseudonym never appears in a key. The name key is separate from the
 * cluster key and does not rotate, so two members never derive different names for one entry.
 *
 * <p>{@code map} is the full sealed-map name including the generation suffix.
 */
public final class KeyedNames {

    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final int SCOPE_TOKEN_BYTES = 16;

    private final byte[] prk;
    private final byte[] scopeSubkey;
    private final ConcurrentHashMap<String, byte[]> mapSubkeys = new ConcurrentHashMap<>();

    public KeyedNames(ClusterKeys keys) {
        this.prk = LabelledHkdf.extract(new byte[0], Objects.requireNonNull(keys, "keys").nameKeyBytes());
        this.scopeSubkey = LabelledHkdf.expand(prk, LabelledHkdf.info(SealLabels.SCOPE), 32);
    }

    /** The map key of {@code canonical} in {@code map}: 43 characters, no dot. */
    public String mapKey(String map, String canonical) {
        byte[] subkey = mapSubkeys.computeIfAbsent(Objects.requireNonNull(map, "map"),
                m -> LabelledHkdf.expand(prk, LabelledHkdf.info(SealLabels.NAME_PREFIX + m), 32));
        return B64.encodeToString(LabelledHkdf.hmac(subkey, utf8(canonical)));
    }

    /**
     * The key to lock for the entry {@code canonical} of {@code map}. It is the entry's own keyed map key, as in
     * 0.7 where the lock is taken on the key it guards: there is no separate lock subkey, so a foreign lock on the
     * entry makes {@code tryLock} time out instead of hanging a later write.
     */
    public String lockKey(String map, String canonical) {
        return mapKey(map, canonical);
    }

    /** The scope token: HMAC under the scope subkey truncated to 128 bits, 22 characters. */
    public String scopeToken(String scopeId) {
        byte[] mac = LabelledHkdf.hmac(scopeSubkey, utf8(scopeId));
        return B64.encodeToString(Arrays.copyOf(mac, SCOPE_TOKEN_BYTES));
    }

    /** {@code scopeToken + "." + mapKey}: a scope's entries share the prefix {@code scopeToken + "."}. */
    public String scoped(String map, String scopeId, String canonical) {
        return scopeToken(scopeId) + "." + mapKey(map, canonical);
    }

    private static byte[] utf8(String value) {
        return Objects.requireNonNull(value, "value").getBytes(StandardCharsets.UTF_8);
    }
}

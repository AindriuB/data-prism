package io.github.aindriub.dataprism.core.link;

import io.github.aindriub.dataprism.core.crypto.LabelledHkdf;
import io.github.aindriub.dataprism.core.spi.SecretKeyProvider;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;

/**
 * Link key derivation (sealed-links.md section 3). One link key per key id comes from a
 * {@link SecretKeyProvider}; each purpose and scope gets its own 32-byte key by HKDF-SHA-256 with a 32-zero-byte
 * salt, one expand block, and an {@code info} of the purpose label and the scope id.
 *
 * <p>Construction refuses an unresolvable key id ({@code LINK_KEY_UNRESOLVED}), key material under 32 bytes
 * ({@code LINK_KEY_WEAK}) and the pseudonymisation key's id ({@code LINK_KEY_SHARED}), each as a
 * {@link LinkConfigurationException} carrying the code. Messages start with the
 * code and name no key byte or reference; the provider's own exception is dropped, because its message may name
 * a reference.
 *
 * <p>Verification looks up exactly the key id a token names and never another. No method here logs.
 */
public final class LinkKeys {

    static final String SEAL_LABEL = "dataprism/link/v1/seal";
    static final String FINGERPRINT_LABEL = "dataprism/link/v1/fingerprint";
    static final String CALLER_LABEL = "dataprism/link/v1/caller";
    /** MAC purpose labels: the first item of every MAC input, so no two purposes can produce the same input. */
    static final String CATALOG_TAG_LABEL = "dataprism/link/v1/catalog-tag";
    static final String ATTEMPT_LABEL = "dataprism/link/v1/attempt";

    private static final int MIN_KEY_BYTES = 32;
    private static final byte[] ZERO_SALT = new byte[32];
    /**
     * Not a secret: the stand-in key so that an unknown key id still runs the same key derivation and AEAD as a
     * known one. The provider fetch (and, for a held id, a SHA-256 of the material) is not part of that: a held id
     * costs it, an unknown id does not. Key ids are public in the token header, so that difference reveals
     * nothing secret.
     */
    private static final byte[] DUMMY_KEY =
            "dataprism-link-dummy-key-0000000".getBytes(StandardCharsets.US_ASCII);

    private final SecretKeyProvider provider;
    private final String currentKeyId;
    private final Supplier<Set<String>> held;
    private final String pseudonymisationKeyId;
    /** SHA-256 of the pseudonymisation key's material; the material itself is never kept. */
    private final byte[] pseudonymisationDigest;

    /** As the long form, reporting only the current key id as held. */
    public LinkKeys(SecretKeyProvider provider, String currentKeyId, String pseudonymisationKeyId) {
        this(provider, currentKeyId, pseudonymisationKeyId, null);
    }

    /**
     * @param held the key ids this member holds, for the health report; {@code null} reports the current id only
     */
    public LinkKeys(SecretKeyProvider provider, String currentKeyId, String pseudonymisationKeyId,
                    Supplier<Set<String>> held) {
        this.provider = Objects.requireNonNull(provider, "provider");
        this.currentKeyId = Objects.requireNonNull(currentKeyId, "currentKeyId");
        if (pseudonymisationKeyId == null) {
            throw new LinkConfigurationException(
                    "LINK_KEY_SHARED", "the pseudonymisation key id setting is required");
        }
        this.pseudonymisationKeyId = pseudonymisationKeyId;
        if (!LinkSettings.validKeyId(currentKeyId)) {
            throw new LinkConfigurationException(
                    "INVALID_LINK_LIMIT", "the link key id must be 1 to 16 characters of A-Z, a-z, 0-9, _ and -");
        }
        if (currentKeyId.equals(pseudonymisationKeyId)) {
            throw new LinkConfigurationException(
                    "LINK_KEY_SHARED", "the link key id must differ from the pseudonymisation key id");
        }
        this.held = held != null ? held : () -> Set.of(currentKeyId);
        if (heldOrEmpty().contains(pseudonymisationKeyId)) {
            throw new LinkConfigurationException(
                    "LINK_KEY_SHARED", "the held key ids must not include the pseudonymisation key id");
        }
        byte[] pseudonymisationMaterial = resolve(pseudonymisationKeyId);
        if (pseudonymisationMaterial == null) {
            throw new LinkConfigurationException(
                    "LINK_KEY_UNRESOLVED", "the pseudonymisation key id setting could not be resolved");
        }
        this.pseudonymisationDigest = digest(pseudonymisationMaterial);
        for (String id : declaredHeld()) {
            Resolution resolution = resolution(id);
            if (resolution.shared) {
                throw new LinkConfigurationException(
                        "LINK_KEY_SHARED", "a link key must not share material with the pseudonymisation key");
            }
            if (resolution.material == null) {
                throw resolution.weak
                        ? new LinkConfigurationException("LINK_KEY_WEAK", "the link key must be at least 32 bytes")
                        : new LinkConfigurationException("LINK_KEY_UNRESOLVED",
                                "the link key id could not be resolved");
            }
        }
    }

    /** The key id used to issue. */
    public String currentKeyId() {
        return currentKeyId;
    }

    /** The supplied held set; a supplier that returns null or throws is treated as empty (current id only). */
    private Set<String> heldOrEmpty() {
        try {
            Set<String> ids = held.get();
            return ids != null ? ids : Set.of();
        } catch (RuntimeException failure) {
            return Set.of();
        }
    }

    /**
     * The ids this member declares it holds, resolved or not: the current id and the supplied set, without null
     * entries and without the pseudonymisation key id.
     */
    private Set<String> declaredHeld() {
        Set<String> ids = new TreeSet<>();
        for (String id : heldOrEmpty()) {
            if (id != null) {
                ids.add(id);
            }
        }
        ids.add(currentKeyId);
        ids.remove(pseudonymisationKeyId);
        return ids;
    }

    /**
     * The key ids this member verifies with, sorted: the declared ids that resolve to at least 32 bytes right now,
     * never the pseudonymisation key id. For the health endpoint and the loaded-keys gauge.
     */
    public Set<String> heldKeyIds() {
        Set<String> ids = new TreeSet<>();
        for (String id : declaredHeld()) {
            if (resolution(id).material != null) {
                ids.add(id);
            }
        }
        return ids;
    }

    /** The per-scope keys under the current key id. */
    public ScopeKeys forScope(String scopeId) {
        return forScope(scopeId, currentKeyId);
    }

    /**
     * The per-scope keys under a named key id; use the id a verified link names so that a walk begun before a
     * rotation still verifies. Throws {@link IllegalStateException} if the id is unknown.
     */
    public ScopeKeys forScope(String scopeId, String keyId) {
        Objects.requireNonNull(scopeId, "scopeId");
        byte[] master = master(keyId);
        if (master == null) {
            throw new IllegalStateException("LINK_KEY_UNRESOLVED: the link key id could not be resolved");
        }
        return new ScopeKeys(derive(master, FINGERPRINT_LABEL, scopeId));
    }

    /**
     * The limiter's counter key: {@code HMAC(fingerprintKey, label | scope | principal)}, truncated to 16 bytes and
     * hex-encoded. It holds neither the scope id nor the principal id.
     */
    public String attemptCounterKey(String scopeId, String principalId) {
        Objects.requireNonNull(principalId, "principalId");
        byte[] key = forScope(scopeId).key;
        byte[] mac = LabelledHkdf.hmac(key, LabelledHkdf.lengthPrefixed(
                ATTEMPT_LABEL.getBytes(StandardCharsets.US_ASCII),
                scopeId.getBytes(StandardCharsets.UTF_8), principalId.getBytes(StandardCharsets.UTF_8)));
        return HexFormat.of().formatHex(mac, 0, 16);
    }

    /**
     * The named key's material, or {@code null} when the id is not held, is the pseudonymisation key's, or the
     * key is unknown, unavailable or under 32 bytes. The held set is read on every call. Never falls back.
     */
    byte[] master(String keyId) {
        return lookup(keyId).material;
    }

    /**
     * As {@link #master}, telling a key this member does not hold ({@link Status#NOT_HELD}) from one it holds
     * but cannot read right now ({@link Status#UNAVAILABLE}: the provider failed, or the material is short).
     */
    Lookup lookup(String keyId) {
        if (keyId == null || keyId.equals(pseudonymisationKeyId) || !declaredHeld().contains(keyId)) {
            return new Lookup(Status.NOT_HELD, null);
        }
        Resolution resolution = resolution(keyId);
        if (resolution.shared) {
            return new Lookup(Status.NOT_HELD, null);
        }
        return resolution.material != null
                ? new Lookup(Status.HELD, resolution.material) : new Lookup(Status.UNAVAILABLE, null);
    }

    private Resolution resolution(String keyId) {
        byte[] material = resolve(keyId);
        if (material == null) {
            return new Resolution(null, false, false);
        }
        if (material.length < MIN_KEY_BYTES) {
            return new Resolution(null, true, false);
        }
        // Checked on every resolution, because the held set is live and a key added during rotation is new.
        if (MessageDigest.isEqual(digest(material), pseudonymisationDigest)) {
            return new Resolution(null, false, true);
        }
        return new Resolution(material, false, false);
    }

    private static byte[] digest(byte[] material) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(material);
        } catch (NoSuchAlgorithmException impossible) {
            // The cause names only the algorithm, never key material.
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    /** The provider's material, or {@code null} when it returns null or fails; its message is dropped. */
    private byte[] resolve(String keyId) {
        try {
            return provider.secret(keyId);
        } catch (RuntimeException failure) {
            return null;
        }
    }

    static byte[] dummyMaster() {
        return DUMMY_KEY.clone();
    }

    static byte[] derive(byte[] master, String label, String scopeId) {
        byte[] prk = LabelledHkdf.extract(ZERO_SALT, master);
        return LabelledHkdf.expand(prk, LabelledHkdf.infoText(label, scopeId), 32);
    }

    enum Status { HELD, NOT_HELD, UNAVAILABLE }

    record Lookup(Status status, byte[] material) {}

    /** {@code material} is null unless the key is usable; {@code shared} marks the pseudonymisation key's bytes. */
    private record Resolution(byte[] material, boolean weak, boolean shared) {}

    /** The keys a scope derives from one link key: fingerprints and the catalog tag. */
    public static final class ScopeKeys {
        private final byte[] key;

        private ScopeKeys(byte[] key) {
            this.key = key;
        }

        /** A 12-byte keyed fingerprint, for item ordering and duplicate collapse. */
        public byte[] fingerprint(byte[] value) {
            Objects.requireNonNull(value, "value");
            byte[] mac = LabelledHkdf.hmac(key, LabelledHkdf.lengthPrefixed(
                    FINGERPRINT_LABEL.getBytes(StandardCharsets.US_ASCII), value));
            return Arrays.copyOf(mac, LinkLayout.FINGERPRINT_BYTES);
        }

        /** The 16-byte tag over the whole visible, filtered label list, in the order given, length-prefixed. */
        public byte[] catalogTag(List<String> labels) {
            Objects.requireNonNull(labels, "labels");
            byte[][] items = new byte[labels.size() + 1][];
            items[0] = CATALOG_TAG_LABEL.getBytes(StandardCharsets.US_ASCII);
            for (int i = 1; i < items.length; i++) {
                items[i] = Objects.requireNonNull(labels.get(i - 1), "label").getBytes(StandardCharsets.UTF_8);
            }
            byte[] mac = LabelledHkdf.hmac(key, LabelledHkdf.lengthPrefixed(items));
            return Arrays.copyOf(mac, LinkLayout.CATALOG_TAG_BYTES);
        }

        @Override
        public String toString() {
            return "ScopeKeys[redacted]";
        }
    }

    @Override
    public String toString() {
        return "LinkKeys[redacted]";
    }
}

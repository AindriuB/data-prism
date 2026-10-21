package io.github.aindriub.dataprism.hazelcast.seal;

import io.github.aindriub.dataprism.core.crypto.LabelledHkdf;
import io.github.aindriub.dataprism.core.metrics.Metric;
import io.github.aindriub.dataprism.core.metrics.PrivacyMetrics;
import io.github.aindriub.dataprism.core.refusal.ClusterOperationRefusedException;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Seals and opens values as {@code dps1} envelopes (docs/plan/design/cluster-security.md 2.3.1):
 * {@code dps1.<key id>.<salt>.<nonce>.<ciphertext+tag>}, AES-256-GCM, URL-safe base64 without padding.
 *
 * <p><b>Nonces.</b> Each sealer draws a random 128-bit salt at construction and derives, per (key, map), an
 * AES subkey {@code HKDF(key, salt, info = aead label for the map)}. The nonce is a counter under that
 * subkey, shared by every map of this sealer, so no (subkey, nonce) pair repeats; the counter refuses to wrap.
 * A restart draws a new salt, so a restarted member never reuses a subkey.
 *
 * <p><b>Associated data</b> is, length-prefixed in this order: the format version, the key id, the salt, the
 * map name and the map key. A value moved to another map, key, salt or key id fails to authenticate.
 *
 * <p><b>Failure.</b> {@link #open} never throws for a bad input. An envelope that does not authenticate is
 * {@link Opened.Outcome#TAMPERED} (counted as {@code seal.tamper}, reported to the {@link TamperListener});
 * an unknown version, key id or a salt refused by the derivation limiter is {@link Opened.Outcome#UNSUPPORTED} (counted). A fault inside
 * the provider is caught at this boundary and reported the same way as a tamper, with a constant message: no
 * exception text, key, map key or input is kept. {@link #seal} refuses with
 * {@link ClusterOperationRefusedException} when it cannot seal, which is fail-closed. A closed
 * {@link ServingGate} makes both refuse.
 *
 * <p>Instances are thread-safe.
 */
public final class Sealer {

    private static final int SALT_BYTES = 16;
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int TAG_BYTES = TAG_BITS / 8;
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64_IN = Base64.getUrlDecoder();

    /** Source of {@link Cipher} instances; a test substitutes a failing one. */
    @FunctionalInterface
    interface CipherSource {
        Cipher newCipher() throws GeneralSecurityException;
    }

    private final ClusterKeys keys;
    private final PrivacyMetrics metrics;
    private final WriteKeyPolicy writeKeyPolicy;
    private final TamperListener tamperListener;
    private final ServingGate servingGate;
    private final CipherSource cipherSource;
    private final byte[] salt;
    private final AtomicLong counter;
    private final ConcurrentHashMap<String, SecretKeySpec> ownSubkeys = new ConcurrentHashMap<>();
    private final SaltCache saltCache;

    private Sealer(Builder b) {
        this.keys = b.keys;
        this.metrics = b.metrics;
        this.writeKeyPolicy = b.writeKeyPolicy;
        this.tamperListener = b.tamperListener;
        this.servingGate = b.servingGate;
        this.cipherSource = b.cipherSource;
        this.salt = b.salt != null ? b.salt.clone() : randomSalt();
        this.counter = new AtomicLong(b.initialCounter);
        this.saltCache = new SaltCache(b.saltCacheBound, b.saltDerivationsPerWindow, b.clock);
    }

    public static Builder builder(ClusterKeys keys, PrivacyMetrics metrics) {
        return new Builder(Objects.requireNonNull(keys, "keys"), Objects.requireNonNull(metrics, "metrics"));
    }

    private static byte[] randomSalt() {
        byte[] salt = new byte[SALT_BYTES];
        new SecureRandom().nextBytes(salt);
        return salt;
    }

    /** Builds a {@link Sealer}. Defaults: current write key, no listener, always serving, 1,024-entry salt cache. */
    public static final class Builder {
        private final ClusterKeys keys;
        private final PrivacyMetrics metrics;
        private WriteKeyPolicy writeKeyPolicy = WriteKeyPolicy.current();
        private TamperListener tamperListener = TamperListener.none();
        private ServingGate servingGate = ServingGate.open();
        private Clock clock = Clock.systemUTC();
        private int saltCacheBound = 1024;
        private int saltDerivationsPerWindow = 256;
        private CipherSource cipherSource = () -> Cipher.getInstance("AES/GCM/NoPadding");
        private byte[] salt;
        private long initialCounter;

        private Builder(ClusterKeys keys, PrivacyMetrics metrics) {
            this.keys = keys;
            this.metrics = metrics;
        }

        public Builder writeKeyPolicy(WriteKeyPolicy policy) {
            this.writeKeyPolicy = Objects.requireNonNull(policy, "policy");
            return this;
        }

        public Builder tamperListener(TamperListener listener) {
            this.tamperListener = Objects.requireNonNull(listener, "listener");
            return this;
        }

        public Builder servingGate(ServingGate gate) {
            this.servingGate = Objects.requireNonNull(gate, "gate");
            return this;
        }

        /** The clock the salt cache's admission window reads. */
        public Builder clock(Clock clock) {
            this.clock = Objects.requireNonNull(clock, "clock");
            return this;
        }

        public Builder saltCacheBound(int bound) {
            this.saltCacheBound = bound;
            return this;
        }

        /** At most this many subkeys are derived for unknown salts per one-minute window; default 256. */
        public Builder saltDerivationsPerWindow(int limit) {
            this.saltDerivationsPerWindow = limit;
            return this;
        }

        /** Test seam: substitute the cipher factory. */
        Builder cipherSource(CipherSource source) {
            this.cipherSource = Objects.requireNonNull(source, "source");
            return this;
        }

        /** Test seam: a fixed salt, for known-answer vectors. */
        Builder salt(byte[] salt) {
            if (salt.length != SALT_BYTES) {
                throw new IllegalArgumentException("salt must be 16 bytes");
            }
            this.salt = salt.clone();
            return this;
        }

        /** Test seam: the first nonce counter value. */
        Builder initialCounter(long first) {
            this.initialCounter = first;
            return this;
        }

        public Sealer build() {
            return new Sealer(this);
        }
    }

    /**
     * Seals {@code plaintext} for {@code map} and {@code mapKey} under the key the write-key policy chooses.
     *
     * @throws ClusterOperationRefusedException when the member is not serving, no held key may be written
     *                                          ({@code rotation.blocked}), or sealing fails
     */
    public String seal(String map, String mapKey, byte[] plaintext) {
        Objects.requireNonNull(map, "map");
        Objects.requireNonNull(mapKey, "mapKey");
        Objects.requireNonNull(plaintext, "plaintext");
        requireServing();
        ClusterKeys.Key key = chooseWriteKey();
        try {
            long n = counter.getAndIncrement();
            if (n < 0 || n == Long.MAX_VALUE) {
                throw refusal("the nonce counter is exhausted");
            }
            byte[] nonce = ByteBuffer.allocate(NONCE_BYTES).putInt(0).putLong(n).array();
            SecretKeySpec subkey = ownSubkeys.computeIfAbsent(key.id() + '\0' + map,
                    k -> new SecretKeySpec(deriveSubkey(key, salt, map), "AES"));
            Cipher cipher = cipherSource.newCipher();
            cipher.init(Cipher.ENCRYPT_MODE, subkey, new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(associatedData(key.id(), salt, map, mapKey));
            byte[] sealed = cipher.doFinal(plaintext);
            return String.join(".", SealLabels.VERSION, key.id(), B64.encodeToString(salt),
                    B64.encodeToString(nonce), B64.encodeToString(sealed));
        } catch (ClusterOperationRefusedException refused) {
            throw refused;
        } catch (GeneralSecurityException | RuntimeException fault) {
            throw refusal("sealing failed");
        }
    }

    /**
     * Opens an envelope. Never throws for a bad input; a closed serving gate is the one refusal.
     *
     * @throws ClusterOperationRefusedException when the member is not serving
     */
    public Opened open(String map, String mapKey, String envelope) {
        Objects.requireNonNull(map, "map");
        Objects.requireNonNull(mapKey, "mapKey");
        requireServing();
        try {
            return openChecked(map, mapKey, envelope);
        } catch (GeneralSecurityException | RuntimeException fault) {
            return tampered(map);
        }
    }

    private Opened openChecked(String map, String mapKey, String envelope) throws GeneralSecurityException {
        if (envelope == null) {
            return unsupported();
        }
        String[] fields = envelope.split("\\.", -1);
        if (!SealLabels.VERSION.equals(fields[0])) {
            return unsupported();
        }
        if (fields.length != 5) {
            return tampered(map);
        }
        Optional<ClusterKeys.Key> held = keys.find(fields[1]);
        if (held.isEmpty()) {
            return unsupported();
        }
        ClusterKeys.Key key = held.get();
        byte[] saltIn = decodeExact(fields[2], SALT_BYTES);
        byte[] nonce = decodeExact(fields[3], NONCE_BYTES);
        byte[] sealed = decodeAtLeast(fields[4], TAG_BYTES);
        if (saltIn == null || nonce == null || sealed == null) {
            return tampered(map);
        }
        SecretKeySpec subkey;
        String cacheKey = null;
        boolean unknownSalt = false;
        if (Arrays.equals(saltIn, salt)) {
            subkey = ownSubkeys.computeIfAbsent(key.id() + '\0' + map,
                    k -> new SecretKeySpec(deriveSubkey(key, salt, map), "AES"));
        } else {
            cacheKey = fields[2] + '\0' + key.id() + '\0' + map;
            subkey = saltCache.get(cacheKey);
            if (subkey == null) {
                if (!saltCache.tryDerive()) {
                    count(Metric.CLUSTER_SEAL_SALT_REJECTED);
                    return Opened.unsupported();
                }
                subkey = new SecretKeySpec(deriveSubkey(key, saltIn, map), "AES");
                unknownSalt = true;
            }
        }
        Cipher cipher = cipherSource.newCipher();
        cipher.init(Cipher.DECRYPT_MODE, subkey, new GCMParameterSpec(TAG_BITS, nonce));
        cipher.updateAAD(associatedData(key.id(), saltIn, map, mapKey));
        try {
            byte[] plain = cipher.doFinal(sealed);
            if (unknownSalt) {
                saltCache.put(cacheKey, subkey);
            }
            return Opened.authentic(key.id(), plain);
        } catch (AEADBadTagException badTag) {
            return tampered(map);
        }
    }

    private ClusterKeys.Key chooseWriteKey() {
        ClusterKeys.Key chosen;
        try {
            chosen = writeKeyPolicy.choose(keys);
        } catch (ClusterOperationRefusedException blocked) {
            count(Metric.CLUSTER_ROTATION_BLOCKED);
            throw blocked;
        } catch (RuntimeException fault) {
            count(Metric.CLUSTER_ROTATION_BLOCKED);
            throw refusal("no held key is acceptable");
        }
        // Seal only with the material this sealer holds: a Key from another ClusterKeys is refused.
        ClusterKeys.Key held = chosen == null ? null : keys.find(chosen.id()).orElse(null);
        if (held == null || !java.security.MessageDigest.isEqual(held.bytes(), chosen.bytes())) {
            count(Metric.CLUSTER_ROTATION_BLOCKED);
            throw refusal("no held key is acceptable");
        }
        return held;
    }

    private void requireServing() {
        boolean serving;
        try {
            serving = servingGate.serving();
        } catch (RuntimeException fault) {
            serving = false;
        }
        if (!serving) {
            throw refusal("the member is not serving");
        }
    }

    private Opened tampered(String map) {
        count(Metric.CLUSTER_SEAL_TAMPER);
        try {
            tamperListener.tamper(map);
        } catch (RuntimeException ignored) {
            // A listener fault never changes the result.
        }
        return Opened.tampered();
    }

    private Opened unsupported() {
        count(Metric.CLUSTER_SEAL_UNSUPPORTED_FORMAT);
        return Opened.unsupported();
    }

    private void count(Metric metric) {
        try {
            metrics.increment(metric);
        } catch (RuntimeException ignored) {
            // Metrics never change an outcome.
        }
    }

    private static ClusterOperationRefusedException refusal(String constantReason) {
        return new ClusterOperationRefusedException(new IllegalStateException(constantReason));
    }

    private static byte[] deriveSubkey(ClusterKeys.Key key, byte[] salt, String map) {
        byte[] prk = LabelledHkdf.extract(salt, key.bytes());
        return LabelledHkdf.expand(prk, LabelledHkdf.info(SealLabels.AEAD_PREFIX + map), 32);
    }

    private static byte[] associatedData(String keyId, byte[] salt, String map, String mapKey) {
        return LabelledHkdf.lengthPrefixed(
                SealLabels.VERSION.getBytes(StandardCharsets.UTF_8),
                keyId.getBytes(StandardCharsets.UTF_8),
                salt,
                map.getBytes(StandardCharsets.UTF_8),
                mapKey.getBytes(StandardCharsets.UTF_8));
    }

    /** Decodes canonical unpadded URL-safe base64 of exactly {@code length} bytes, or returns null. */
    private static byte[] decodeExact(String field, int length) {
        byte[] raw = decodeCanonical(field);
        return raw != null && raw.length == length ? raw : null;
    }

    private static byte[] decodeAtLeast(String field, int length) {
        byte[] raw = decodeCanonical(field);
        return raw != null && raw.length >= length ? raw : null;
    }

    private static byte[] decodeCanonical(String field) {
        try {
            byte[] raw = B64_IN.decode(field);
            return B64.encodeToString(raw).equals(field) ? raw : null;
        } catch (IllegalArgumentException notBase64) {
            return null;
        }
    }

    // Package-private seams for tests.

    int saltCacheSize() {
        return saltCache.size();
    }

    byte[] subkeyForTest(String keyId, byte[] salt, String map) {
        return deriveSubkey(keys.find(keyId).orElseThrow(), salt, map);
    }
}

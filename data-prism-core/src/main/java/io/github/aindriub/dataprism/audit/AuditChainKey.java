package io.github.aindriub.dataprism.audit;

import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * A key id plus at least 32 bytes of key material for the opt-in keyed audit chain. It resists a
 * writer who lacks the key; it does not resist anyone who holds it, including the operator and the
 * process that writes the file. The material is copied in and never rendered.
 */
public final class AuditChainKey {

    /** The smallest accepted key length, in bytes. */
    public static final int MIN_KEY_BYTES = 32;

    private static final String ALGORITHM = "HmacSHA256";

    private final String id;
    private final byte[] material;

    public AuditChainKey(String id, byte[] material) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(material, "material");
        if (id.isBlank()) {
            throw new IllegalArgumentException("key id must not be blank");
        }
        if (material.length < MIN_KEY_BYTES) {
            throw new IllegalArgumentException("audit chain key must be at least " + MIN_KEY_BYTES + " bytes");
        }
        this.id = id;
        this.material = material.clone();
    }

    public String id() {
        return id;
    }

    /** @return the hex HMAC-SHA256 of {@code data} under this key. */
    String hmacHex(byte[] data) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(material, ALGORITHM));
            return HexFormat.of().formatHex(mac.doFinal(data));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }

    @Override
    public String toString() {
        return "AuditChainKey[id=" + id + "]";
    }
}

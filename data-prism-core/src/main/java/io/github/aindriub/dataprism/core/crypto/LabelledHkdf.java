package io.github.aindriub.dataprism.core.crypto;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Objects;

/**
 * HKDF-SHA-256 (RFC 5869) over {@code javax.crypto.Mac}, with a fixed encoder for the {@code info} input.
 *
 * <p>The JDK 21 baseline has no public HKDF API, so the two steps are written out here and pinned by the RFC's
 * own test vectors. One helper serves the cluster seal (labels {@code dataprism/cluster/v1/...}) and the sealed
 * links (labels {@code dataprism/link/v1/...}), so every derived key has one labelled, unambiguous input.
 *
 * <p><b>Info encoding.</b> {@link #info(String, byte[][])} writes the label and then each context item as a
 * two-byte big-endian length followed by the bytes. The width is fixed at two bytes, so one item is at most
 * 65,535 bytes; a longer item is refused. Length prefixes mean {@code ("ab","c")} and {@code ("a","bc")}
 * encode differently. The width is pinned by known-answer vectors; changing it changes every derived key.
 *
 * <p>No method here logs, and no exception message carries an input.
 */
public final class LabelledHkdf {

    private static final String HMAC = "HmacSHA256";
    private static final int HASH_LEN = 32;
    private static final int MAX_ITEM = 0xFFFF;

    private LabelledHkdf() {}

    /**
     * RFC 5869 step 1: {@code PRK = HMAC-SHA256(salt, ikm)}. An empty salt is the RFC's default of
     * {@code HashLen} zero bytes.
     */
    public static byte[] extract(byte[] salt, byte[] ikm) {
        Objects.requireNonNull(salt, "salt");
        Objects.requireNonNull(ikm, "ikm");
        byte[] key = salt.length == 0 ? new byte[HASH_LEN] : salt;
        return hmac(key, ikm);
    }

    /** RFC 5869 step 2: {@code T(1) || T(2) || ...}, truncated to {@code length} bytes. */
    public static byte[] expand(byte[] prk, byte[] info, int length) {
        Objects.requireNonNull(prk, "prk");
        Objects.requireNonNull(info, "info");
        if (prk.length < HASH_LEN) {
            throw new IllegalArgumentException("prk is shorter than the hash length");
        }
        if (length < 1 || length > 255 * HASH_LEN) {
            throw new IllegalArgumentException("length is outside 1 to 8160");
        }
        byte[] okm = new byte[length];
        byte[] previous = new byte[0];
        int filled = 0;
        for (int counter = 1; filled < length; counter++) {
            byte[] input = new byte[previous.length + info.length + 1];
            System.arraycopy(previous, 0, input, 0, previous.length);
            System.arraycopy(info, 0, input, previous.length, info.length);
            input[input.length - 1] = (byte) counter;
            previous = hmac(prk, input);
            int take = Math.min(HASH_LEN, length - filled);
            System.arraycopy(previous, 0, okm, filled, take);
            filled += take;
        }
        return okm;
    }

    /** One 32-byte block with the default (zero) salt: the shape sealed links use. */
    public static byte[] derive(byte[] ikm, byte[] info) {
        return expand(extract(new byte[0], ikm), info, HASH_LEN);
    }

    /** The encoded {@code info}: the label, then each item, each with a two-byte big-endian length prefix. */
    public static byte[] info(String label, byte[]... items) {
        Objects.requireNonNull(label, "label");
        byte[][] all = new byte[items.length + 1][];
        all[0] = label.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(items, 0, all, 1, items.length);
        return lengthPrefixed(all);
    }

    /** As {@link #info(String, byte[][])}, with text items in UTF-8. */
    public static byte[] infoText(String label, String... items) {
        byte[][] encoded = new byte[items.length][];
        for (int i = 0; i < items.length; i++) {
            encoded[i] = Objects.requireNonNull(items[i], "item").getBytes(StandardCharsets.UTF_8);
        }
        return info(label, encoded);
    }

    /** Concatenates items, each preceded by its two-byte big-endian length. Also used for AEAD associated data. */
    public static byte[] lengthPrefixed(byte[]... items) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] item : items) {
            Objects.requireNonNull(item, "item");
            if (item.length > MAX_ITEM) {
                throw new IllegalArgumentException("an item is longer than 65535 bytes");
            }
            out.write(item.length >>> 8);
            out.write(item.length);
            out.writeBytes(item);
        }
        return out.toByteArray();
    }

    /** HMAC-SHA256, shared with the keyed-name and check-value code. The message never appears in a failure. */
    public static byte[] hmac(byte[] key, byte[] message) {
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(new SecretKeySpec(key, HMAC));
            return mac.doFinal(message);
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException("HMAC-SHA256 is unavailable");
        }
    }
}

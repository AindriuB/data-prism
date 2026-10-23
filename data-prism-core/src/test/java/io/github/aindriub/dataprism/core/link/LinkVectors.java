package io.github.aindriub.dataprism.core.link;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * An independent reading of the format for the tests: its own HKDF and its own header parse, sharing no code
 * with the main classes, so a mistake in one is not repeated in the other.
 */
final class LinkVectors {

    private LinkVectors() {}

    static byte[] hmac(byte[] key, byte[] message) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(message);
    }

    private static void prefixed(ByteArrayOutputStream out, byte[] item) {
        out.write(item.length >>> 8);
        out.write(item.length);
        out.writeBytes(item);
    }

    /** HKDF-SHA-256 with a 32-zero-byte salt, one block, and info = prefixed(label) || prefixed(scope). */
    static byte[] derive(byte[] master, String label, String scope) throws Exception {
        byte[] prk = hmac(new byte[32], master);
        ByteArrayOutputStream info = new ByteArrayOutputStream();
        prefixed(info, label.getBytes(StandardCharsets.UTF_8));
        prefixed(info, scope.getBytes(StandardCharsets.UTF_8));
        info.write(1);
        return hmac(prk, info.toByteArray());
    }

    /** Opens the raw token with the master key and returns the plaintext payload; throws if it does not verify. */
    static byte[] decrypt(byte[] master, String scope, String principal, boolean bind, byte[] raw)
            throws Exception {
        return crypt(master, scope, principal, bind, raw, null, null, null);
    }

    /**
     * The same token with its payload sealed under the same key, but with the associated data changed in one
     * place: {@code aadVersion} replaces the version byte, {@code aadScope} the scope id. Either may be null to
     * keep it, or {@link #OMIT_VERSION} / {@link #OMIT_SCOPE} to leave the item out. The key and the caller tag
     * still come from {@code scope}, so only the associated data differs.
     */
    static final int OMIT_VERSION = -1;
    static final String OMIT_SCOPE = "\u0000omit";

    static byte[] reauthenticate(byte[] master, String scope, String principal, boolean bind, byte[] raw,
                                 Integer aadVersion, String aadScope) throws Exception {
        byte[] plain = decrypt(master, scope, principal, bind, raw);
        byte[] sealed = crypt(master, scope, principal, bind, raw, plain, aadVersion, aadScope);
        int headerLength = raw.length - (plain.length + 16);
        byte[] out = Arrays.copyOf(raw, headerLength + sealed.length);
        System.arraycopy(sealed, 0, out, headerLength, sealed.length);
        return out;
    }

    /** The same token with its payload replaced, authenticated under the same header, nonce and key. */
    static byte[] reseal(byte[] master, String scope, String principal, boolean bind, byte[] raw, byte[] payload)
            throws Exception {
        byte[] sealed = crypt(master, scope, principal, bind, raw, payload, null, null);
        int headerLength = raw.length - (decrypt(master, scope, principal, bind, raw).length + 16);
        byte[] out = Arrays.copyOf(raw, headerLength + sealed.length);
        System.arraycopy(sealed, 0, out, headerLength, sealed.length);
        return out;
    }

    private static byte[] crypt(byte[] master, String scope, String principal, boolean bind, byte[] raw,
                                byte[] replacement, Integer aadVersion, String aadScope) throws Exception {
        int at = 1;
        int keyIdLength = raw[at++] & 0xFF;
        byte[] keyId = Arrays.copyOfRange(raw, at, at + keyIdLength);
        at += keyIdLength;
        byte[] expiry = Arrays.copyOfRange(raw, at, at + 8);
        at += 8;
        int targetLength = raw[at++] & 0xFF;
        byte[] target = Arrays.copyOfRange(raw, at, at + targetLength);
        at += targetLength;
        byte relation = raw[at++];
        byte[] nonce = Arrays.copyOfRange(raw, at, at + 12);
        at += 12;
        byte[] callerTag = new byte[16];
        if (bind) {
            byte[] mac = hmac(derive(master, "dataprism/link/v1/caller", scope),
                    principal.getBytes(StandardCharsets.UTF_8));
            callerTag = Arrays.copyOf(mac, 16);
        }
        ByteArrayOutputStream aad = new ByteArrayOutputStream();
        if (aadVersion == null || aadVersion != OMIT_VERSION) {
            prefixed(aad, new byte[] {aadVersion != null ? (byte) aadVersion.intValue() : raw[0]});
        }
        prefixed(aad, keyId);
        prefixed(aad, expiry);
        prefixed(aad, target);
        prefixed(aad, new byte[] {relation});
        if (!OMIT_SCOPE.equals(aadScope)) {
            prefixed(aad, (aadScope != null ? aadScope : scope).getBytes(StandardCharsets.UTF_8));
        }
        prefixed(aad, callerTag);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(replacement == null ? Cipher.DECRYPT_MODE : Cipher.ENCRYPT_MODE,
                new SecretKeySpec(derive(master, "dataprism/link/v1/seal", scope), "AES"),
                new GCMParameterSpec(128, nonce));
        cipher.updateAAD(aad.toByteArray());
        return replacement == null ? cipher.doFinal(raw, at, raw.length - at) : cipher.doFinal(replacement);
    }

    static long expiryOf(byte[] raw) {
        int keyIdLength = raw[1] & 0xFF;
        return ByteBuffer.wrap(raw, 2 + keyIdLength, 8).getLong();
    }
}

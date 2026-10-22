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
            byte[] mac = hmac(derive(master, "dataprism/link/v1/caller", scope), principal.getBytes(StandardCharsets.UTF_8));
            callerTag = Arrays.copyOf(mac, 16);
        }
        ByteArrayOutputStream aad = new ByteArrayOutputStream();
        prefixed(aad, new byte[] {raw[0]});
        prefixed(aad, keyId);
        prefixed(aad, expiry);
        prefixed(aad, target);
        prefixed(aad, new byte[] {relation});
        prefixed(aad, scope.getBytes(StandardCharsets.UTF_8));
        prefixed(aad, callerTag);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(derive(master, "dataprism/link/v1/seal", scope), "AES"),
                new GCMParameterSpec(128, nonce));
        cipher.updateAAD(aad.toByteArray());
        return cipher.doFinal(raw, at, raw.length - at);
    }

    static long expiryOf(byte[] raw) {
        int keyIdLength = raw[1] & 0xFF;
        return ByteBuffer.wrap(raw, 2 + keyIdLength, 8).getLong();
    }
}

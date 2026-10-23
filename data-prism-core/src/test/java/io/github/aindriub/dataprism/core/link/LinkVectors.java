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

    private static byte[] prefixedAll(byte[]... items) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] item : items) {
            prefixed(out, item);
        }
        return out.toByteArray();
    }

    private static byte[] ascii(String text) {
        return text.getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    /** HMAC(fingerprint key, prefixed(label) || prefixed(value)), first 12 bytes. */
    static byte[] fingerprint(byte[] master, String scope, byte[] value) throws Exception {
        byte[] key = derive(master, "dataprism/link/v1/fingerprint", scope);
        return Arrays.copyOf(hmac(key, prefixedAll(ascii("dataprism/link/v1/fingerprint"), value)), 12);
    }

    /** HMAC(fingerprint key, prefixed(label) || prefixed(each label)), first 16 bytes. */
    static byte[] catalogTag(byte[] master, String scope, java.util.List<String> labels) throws Exception {
        byte[] key = derive(master, "dataprism/link/v1/fingerprint", scope);
        ByteArrayOutputStream message = new ByteArrayOutputStream();
        prefixed(message, ascii("dataprism/link/v1/catalog-tag"));
        for (String label : labels) {
            prefixed(message, utf8(label));
        }
        return Arrays.copyOf(hmac(key, message.toByteArray()), 16);
    }

    /** Hex of HMAC(fingerprint key, prefixed(label) || prefixed(scope) || prefixed(principal)), first 16 bytes. */
    static String counterKey(byte[] master, String scope, String principal) throws Exception {
        byte[] key = derive(master, "dataprism/link/v1/fingerprint", scope);
        byte[] mac = hmac(key, prefixedAll(ascii("dataprism/link/v1/attempt"), utf8(scope), utf8(principal)));
        return java.util.HexFormat.of().formatHex(mac, 0, 16);
    }

    /**
     * Seals {@code raw}'s payload (opened under {@code payloadMaster}) again under {@code sealMaster}, with the
     * header of {@code patchedHeader} (which may differ from {@code raw}'s in its key id or expiry; the length
     * must be the same). The associated data is rebuilt from the patched header.
     */
    static byte[] rebind(byte[] payloadMaster, byte[] sealMaster, String scope, String principal, boolean bind,
                         byte[] raw, byte[] patchedHeader) throws Exception {
        byte[] plain = decrypt(payloadMaster, scope, principal, bind, raw);
        int headerLength = raw.length - (plain.length + 16);
        byte[] sealed = crypt(sealMaster, scope, principal, bind, patchedHeader, plain, null, null);
        byte[] out = Arrays.copyOf(patchedHeader, headerLength + sealed.length);
        System.arraycopy(sealed, 0, out, headerLength, sealed.length);
        return out;
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

package io.github.aindriub.dataprism.hazelcast.seal;

import io.github.aindriub.dataprism.core.crypto.LabelledHkdf;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * The key check value of docs/plan/design/cluster-security.md 4.7 (OD-215-7 (a)): the first 8 bytes of
 * {@code HMAC-SHA256(HKDF(purposeKey, label = kcv), keyId)}, with {@code keyId} in UTF-8 and an empty HKDF salt
 * (the RFC 5869 default). It is one-way and short, so it shows whether two members hold the same key under an
 * id without giving a usable fact about a key that meets the strength rule.
 */
public final class KeyCheckValue {

    /** The length of a check value. */
    public static final int LENGTH = 8;

    private KeyCheckValue() {}

    public static byte[] compute(String keyId, byte[] material) {
        byte[] derived = LabelledHkdf.derive(material, LabelledHkdf.info(SealLabels.KCV));
        byte[] mac = LabelledHkdf.hmac(derived, keyId.getBytes(StandardCharsets.UTF_8));
        return Arrays.copyOf(mac, LENGTH);
    }
}

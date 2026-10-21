package io.github.aindriub.dataprism.hazelcast.seal;

import java.util.Base64;

/** Fixed, invented test bytes. None of this is a real secret. */
final class Fixtures {

    static final String CURRENT_ID = "k1";
    static final String NEXT_ID = "k2";
    static final String PREVIOUS_ID = "k0";
    static final String NAME_ID = "n1";

    private Fixtures() {}

    static byte[] bytes(int from, int length) {
        byte[] out = new byte[length];
        for (int i = 0; i < length; i++) {
            out[i] = (byte) (from + i);
        }
        return out;
    }

    static byte[] currentKey() { return bytes(0x00, 32); }
    static byte[] nextKey() { return bytes(0x40, 32); }
    static byte[] previousKey() { return bytes(0x80, 32); }
    static byte[] nameKey() { return bytes(0x20, 32); }
    static byte[] salt() { return bytes(0xA0, 16); }

    static ClusterKeys keys() {
        return new ClusterKeys(CURRENT_ID, currentKey(), null, null, null, null, NAME_ID, nameKey());
    }

    static ClusterKeys rotatingKeys() {
        return new ClusterKeys(CURRENT_ID, currentKey(), NEXT_ID, nextKey(), PREVIOUS_ID, previousKey(),
                NAME_ID, nameKey());
    }

    static String b64(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}

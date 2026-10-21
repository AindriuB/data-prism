package io.github.aindriub.dataprism.core.crypto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LabelledHkdfTest {

    private static final HexFormat HEX = HexFormat.of();

    private static byte[] seq(int fromInclusive, int toInclusive) {
        byte[] out = new byte[toInclusive - fromInclusive + 1];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) (fromInclusive + i);
        }
        return out;
    }

    /** RFC 5869 appendix A.1. */
    @Test
    @DisplayName("RFC 5869 test case 1 (SHA-256, basic)")
    void rfcCase1() {
        byte[] ikm = HEX.parseHex("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b");
        byte[] salt = seq(0x00, 0x0c);
        byte[] info = seq(0xf0, 0xf9);

        byte[] prk = LabelledHkdf.extract(salt, ikm);
        assertThat(HEX.formatHex(prk))
                .isEqualTo("077709362c2e32df0ddc3f0dc47bba6390b6c73bb50f9c3122ec844ad7c2b3e5");
        assertThat(HEX.formatHex(LabelledHkdf.expand(prk, info, 42))).isEqualTo(
                "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865");
    }

    /** RFC 5869 appendix A.2: longer inputs, 82 bytes of output (three blocks). */
    @Test
    @DisplayName("RFC 5869 test case 2 (SHA-256, longer inputs)")
    void rfcCase2() {
        byte[] ikm = seq(0x00, 0x4f);
        byte[] salt = seq(0x60, 0xaf);
        byte[] info = seq(0xb0, 0xff);

        byte[] prk = LabelledHkdf.extract(salt, ikm);
        assertThat(HEX.formatHex(prk))
                .isEqualTo("06a6b88c5853361a06104c9ceb35b45cef760014904671014a193f40c15fc244");
        assertThat(HEX.formatHex(LabelledHkdf.expand(prk, info, 82))).isEqualTo(
                "b11e398dc80327a1c8e7f78c596a49344f012eda2d4efad8a050cc4c19afa97c"
                        + "59045a99cac7827271cb41c65e590e09da3275600c2f09b8367793a9aca3db71"
                        + "cc30c58179ec3e87c14c01d5c1f3434f1d87");
    }

    /** RFC 5869 appendix A.3: empty salt and empty info. */
    @Test
    @DisplayName("RFC 5869 test case 3 (SHA-256, empty salt and info)")
    void rfcCase3() {
        byte[] ikm = HEX.parseHex("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b");

        byte[] prk = LabelledHkdf.extract(new byte[0], ikm);
        assertThat(HEX.formatHex(prk))
                .isEqualTo("19ef24a32c717b167f33a91d6f648bdf96596776afdb6377ac434c1c293ccb04");
        assertThat(HEX.formatHex(LabelledHkdf.expand(prk, new byte[0], 42))).isEqualTo(
                "8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d9d201395faa4b61a96c8");
    }

    @Test
    @DisplayName("a changed byte of the expected OKM is detected by the comparison used in case 1")
    void changedByteFailsTheCheck() {
        byte[] ikm = HEX.parseHex("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b");
        byte[] okm = LabelledHkdf.expand(LabelledHkdf.extract(seq(0x00, 0x0c), ikm), seq(0xf0, 0xf9), 42);
        String expected = "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865";
        assertThat(HEX.formatHex(okm)).isEqualTo(expected);
        String mutated = expected.substring(0, expected.length() - 1) + "6";
        assertThatThrownBy(() -> assertThat(HEX.formatHex(okm)).isEqualTo(mutated))
                .isInstanceOf(AssertionError.class);
    }

    @Test
    @DisplayName("the info encoder prefixes each item with a two-byte big-endian length")
    void infoEncoderPinsThePrefixWidth() {
        byte[] info = LabelledHkdf.info("ab", "c".getBytes(StandardCharsets.UTF_8), new byte[0]);
        assertThat(HEX.formatHex(info)).isEqualTo("0002" + "6162" + "0001" + "63" + "0000");
        assertThat(LabelledHkdf.infoText("ab", "c", ""))
                .isEqualTo(info);
    }

    @Test
    @DisplayName("items that would run together under concatenation stay distinct")
    void lengthPrefixSeparatesItems() {
        assertThat(LabelledHkdf.infoText("l", "ab", "c")).isNotEqualTo(LabelledHkdf.infoText("l", "a", "bc"));
    }

    @Test
    @DisplayName("an over-long request, a bad PRK and an over-long item are refused")
    void refusesBadArguments() {
        byte[] prk = LabelledHkdf.extract(new byte[0], new byte[32]);
        assertThatThrownBy(() -> LabelledHkdf.expand(prk, new byte[0], 255 * 32 + 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LabelledHkdf.expand(prk, new byte[0], 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LabelledHkdf.expand(new byte[0], new byte[0], 32))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LabelledHkdf.info("l", new byte[65_536]))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("derive is extract then expand, one block, zero salt by default")
    void deriveHelper() {
        byte[] ikm = new byte[32];
        byte[] info = LabelledHkdf.infoText("dataprism/test/v1/x", "ctx");
        assertThat(LabelledHkdf.derive(ikm, info))
                .isEqualTo(LabelledHkdf.expand(LabelledHkdf.extract(new byte[0], ikm), info, 32));
    }
}

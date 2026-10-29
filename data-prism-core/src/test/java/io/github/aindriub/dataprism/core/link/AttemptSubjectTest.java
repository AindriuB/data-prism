package io.github.aindriub.dataprism.core.link;

import io.github.aindriub.dataprism.core.crypto.LabelledHkdf;
import org.junit.jupiter.api.Test;

import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;

import static io.github.aindriub.dataprism.core.link.LinkTestSupport.T0;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AttemptSubjectTest {

    private static final String SENTINEL_SCOPE = "scope-SENTINEL-41";
    private static final String SENTINEL_PRINCIPAL = "principal-SENTINEL-42";

    @Test
    void theBoundaryBetweenScopeAndPrincipalIsLengthPrefixed() {
        assertThat(new AttemptSubject("ab", "c").canonical()).isNotEqualTo(new AttemptSubject("a", "bc").canonical());
    }

    @Test
    void canonicalBeginsWithTheLengthPrefixedLabel() {
        byte[] label = LabelledHkdf.lengthPrefixed("dataprism/link/v1/attempt".getBytes(StandardCharsets.US_ASCII));
        assertThat(new AttemptSubject("case-1", "alice").canonical()).startsWith(label);
        assertThat(AttemptSubject.ATTEMPT_LABEL).isEqualTo("dataprism/link/v1/attempt");
    }

    @Test
    void canonicalTextIsLowercaseHexThatDecodesBack() {
        AttemptSubject subject = new AttemptSubject("case-1", "alice");
        assertThat(subject.canonicalText()).matches("[0-9a-f]+").hasSize(subject.canonical().length * 2);
        assertThat(HexFormat.of().parseHex(subject.canonicalText())).isEqualTo(subject.canonical());
    }

    @Test
    void decodingCanonicalAsUtf8WouldLoseBytesAndTheHexDoesNot() {
        // The label's length prefix is 0x00 0x19, and a 0x80-prefixed length (an id of 128..255 bytes) is not
        // valid UTF-8 on its own.
        AttemptSubject subject = new AttemptSubject("s".repeat(200), "p");
        byte[] canonical = subject.canonical();
        assertThatThrownBy(() -> StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(canonical))).isInstanceOf(CharacterCodingException.class);
        assertThat(new String(canonical, StandardCharsets.UTF_8).getBytes(StandardCharsets.UTF_8))
                .isNotEqualTo(canonical);
        assertThat(HexFormat.of().parseHex(subject.canonicalText())).isEqualTo(canonical);
    }

    @Test
    void badIdsThrowWithoutNamingTheValue() {
        String max = "x".repeat(65_535);
        new AttemptSubject(max, max);
        String over = "Q".repeat(65_536);
        String multiByteOver = "é".repeat(32_768);
        for (String[] bad : new String[][] {{"", "p"}, {"s", ""}, {over, "p"}, {"s", over}, {multiByteOver, "p"}}) {
            assertThatThrownBy(() -> new AttemptSubject(bad[0], bad[1]))
                    .isInstanceOf(IllegalArgumentException.class)
                    .satisfies(e -> assertThat(e.getMessage()).doesNotContain("Q").doesNotContain("é"));
        }
        assertThatThrownBy(() -> new AttemptSubject(null, "p")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AttemptSubject("s", null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AttemptSubject(SENTINEL_SCOPE, ""))
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain(SENTINEL_SCOPE));
    }

    @Test
    void ofABindingIsEmptyOutsideTheBoundsAndNeverThrows() {
        Instant end = T0.plusSeconds(60);
        assertThat(AttemptSubject.of(null)).isEmpty();
        assertThat(AttemptSubject.of(new LinkBinding("", "p", end, end))).isEmpty();
        assertThat(AttemptSubject.of(new LinkBinding("s", "", end, end))).isEmpty();
        assertThat(AttemptSubject.of(new LinkBinding("s".repeat(65_536), "p", end, end))).isEmpty();
        assertThat(AttemptSubject.of(new LinkBinding("s", "p".repeat(65_536), end, end))).isEmpty();
        assertThat(AttemptSubject.of(new LinkBinding("s".repeat(65_535), "p", end, end))).isPresent();
        assertThat(AttemptSubject.of(new LinkBinding("case-1", "alice", end, end)))
                .contains(new AttemptSubject("case-1", "alice"));
    }

    @Test
    void toStringShowsNeitherId() {
        assertThat(new AttemptSubject(SENTINEL_SCOPE, SENTINEL_PRINCIPAL).toString())
                .isEqualTo("AttemptSubject[redacted]");
    }
}

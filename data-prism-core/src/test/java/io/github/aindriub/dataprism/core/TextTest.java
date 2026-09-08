package io.github.aindriub.dataprism.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TextTest {

    @Test
    @DisplayName("the two spellings of an accented name are the same text")
    void normalisesComposition() {
        String precomposed = "Seán";   // S e U+00E1 n
        String decomposed = "Seán";  // S e a U+0301 n

        assertThat(precomposed).as("the inputs really are different bytes").isNotEqualTo(decomposed);
        assertThat(Text.sameText(precomposed, decomposed)).isTrue();
        assertThat(Text.canonical(decomposed)).isEqualTo(precomposed);
    }

    @Test
    @DisplayName("invisible marks do not change what a name is")
    void stripsInvisibleCharacters() {
        assertThat(Text.sameText("‎عبدالله‏",
                "عبدالله")).isTrue();
        assertThat(Text.sameText("Ann​Marie", "AnnMarie")).isTrue();
        assertThat(Text.sameText("﻿Seán", "Seán")).isTrue();
    }

    @Test
    @DisplayName("visible differences are still differences")
    void doesNotOverNormalise() {
        // Case, spacing and the Cyrillic Ё/Е distinction all survive. Folding any
        // of them would merge names that are genuinely different, which is the
        // opposite failure and just as bad.
        assertThat(Text.sameText("Seán", "seán")).isFalse();
        assertThat(Text.sameText("Ann Marie", "AnnMarie")).isFalse();
        assertThat(Text.sameText("Фёдоров", "Федоров")).isFalse();
        assertThat(Text.sameText("Seán", "Sean")).isFalse();
    }

    @Test
    @DisplayName("scripts that need their marks keep their letters intact")
    void preservesRealContent() {
        for (String name : new String[]{"李伟", "Владимир", "Ó Súilleabháin", "Þórsson", "Łukasz"}) {
            assertThat(Text.canonical(name)).isNotBlank();
            assertThat(Text.canonical(name).codePointCount(0, Text.canonical(name).length()))
                    .isGreaterThan(0);
        }
        // Han and Cyrillic text has nothing to normalise away.
        assertThat(Text.canonical("李伟")).isEqualTo("李伟");
        assertThat(Text.canonical("Владимир")).isEqualTo("Владимир");
    }

    @Test
    @DisplayName("null is handled rather than thrown at")
    void handlesNull() {
        assertThat(Text.canonical(null)).isNull();
        assertThat(Text.sameText(null, null)).isTrue();
        assertThat(Text.sameText(null, "x")).isFalse();
    }
}

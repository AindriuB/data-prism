package io.github.aindriub.dataprism.core;

import java.text.Normalizer;

/**
 * Unicode handling for values that must compare and hash consistently.
 *
 * <p>Names are the reason this exists. "Seán" can be a single precomposed
 * character or an "a" followed by a combining acute, and the two are the same
 * name, look identical, and are different byte sequences. Two source systems can
 * easily disagree about which form they store.
 *
 * <p>Two things break if that is not handled, and both fail quietly:
 *
 * <ul>
 *   <li>the same subject arriving in two normalisation forms would produce two
 *       different pseudonyms, so one person would read as two;
 *   <li>the output validator compares against the raw source values by exact
 *       match, so a leaked value in the other form would pass the check.
 * </ul>
 *
 * <p>Normalisation is applied where values are compared or hashed. It is never
 * applied to what is emitted: the response carries what the source held.
 */
public final class Text {

    private Text() {
    }

    /**
     * NFC, plus removal of characters that carry no meaning but change bytes:
     * zero-width joiners and non-joiners, the bidi marks that legitimately
     * appear around Arabic and Hebrew names, and the BOM.
     *
     * <p>Stripping those is safe here because this form is only ever compared or
     * hashed, never displayed. Doing it to displayed text would corrupt scripts
     * that need the marks to render correctly.
     */
    public static String canonical(String value) {
        if (value == null) {
            return null;
        }
        String normalised = Normalizer.normalize(value, Normalizer.Form.NFC);
        StringBuilder out = new StringBuilder(normalised.length());
        normalised.codePoints().forEach(cp -> {
            if (!invisible(cp)) {
                out.appendCodePoint(cp);
            }
        });
        return out.toString();
    }

    /** True when two values are the same text written differently. */
    public static boolean sameText(String a, String b) {
        if (a == null || b == null) {
            return a == null && b == null;
        }
        return canonical(a).equals(canonical(b));
    }

    private static boolean invisible(int codePoint) {
        return switch (codePoint) {
            case 0x200B, 0x200C, 0x200D, // zero-width space, non-joiner, joiner
                 0x200E, 0x200F,          // left-to-right and right-to-left marks
                 0x061C,                  // Arabic letter mark
                 0xFEFF -> true;          // byte order mark
            default -> codePoint >= 0x202A && codePoint <= 0x202E // bidi overrides
                    || codePoint >= 0x2066 && codePoint <= 0x2069; // bidi isolates
        };
    }
}

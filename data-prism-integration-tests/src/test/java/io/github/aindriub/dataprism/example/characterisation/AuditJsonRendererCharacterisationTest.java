package io.github.aindriub.dataprism.example.characterisation;

import io.github.aindriub.dataprism.audit.AuditEvent;
import io.github.aindriub.dataprism.audit.format.AuditFieldMapping;
import io.github.aindriub.dataprism.audit.format.AuditJsonRenderer;
import io.github.aindriub.dataprism.audit.format.AuditRouting;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

/**
 * Pins the exact bytes {@link AuditJsonRenderer#render} writes today (Jackson 2 streaming
 * generator, {@code ESCAPE_NON_ASCII}). Every case puts its text in {@code principalId} and
 * compares the whole line with a golden file.
 *
 * <p>Observed Jackson 2 behaviour, quoted by each test: non-ASCII is written as a six-character
 * backslash-u escape with UPPER-case hex digits; control characters use the short
 * escapes where JSON has one and backslash-u-00XX (upper-case hex) otherwise; U+007F is not escaped (it is ASCII), nor is the slash;
 * an astral code point is written as an escaped surrogate pair, also upper-case.
 *
 * <p>Source text is built from code points at run time, never from backslash-u escapes in
 * this file, which the Java compiler would translate before parsing.
 */
class AuditJsonRendererCharacterisationTest {

    private static String cp(int... codePoints) {
        StringBuilder sb = new StringBuilder();
        for (int c : codePoints) {
            sb.appendCodePoint(c);
        }
        return sb.toString();
    }

    private static String render(String principalId, Map<String, String> dispositions) {
        AuditEvent event = new AuditEvent("event-1", Instant.parse("2026-09-08T12:00:00Z"), principalId,
                "client-1", "get_entity_context", "CUSTOMER", "SUBJ-0001", "fingerprint-1", "DEFAULT",
                "case:case-1", "demonstration", "case-1", "ALLOW", Set.of("b-source", "a-source"), Set.of(),
                "correlation-1", "instance-1", 7L, "previous-hash", "event-hash", 3, dispositions, "", "", "");
        return AuditJsonRenderer.render(event, AuditFieldMapping.canonical(), AuditRouting.none());
    }

    private static String render(String principalId) {
        return render(principalId, Map.of());
    }

    @Test
    @DisplayName("plain ASCII: written as is, fields in the mapping's order, sets sorted, timestamp as Instant.toString()")
    void plainAscii() {
        Golden.assertMatches("audit-ascii.json", render("investigator-1", Map.of("customer-api:/name", "REDACT")));
    }

    @Test
    @DisplayName("Latin-1 and CJK text: every non-ASCII char is an upper-case-hex backslash-u escape")
    void nonAsciiBmp() {
        // e acute, y diaeresis (hex digits ff), CJK 'middle' U+4E2D, CJK 'text' U+6587
        Golden.assertMatches("audit-bmp.json", render("caf" + cp(0xE9) + "-" + cp(0xFF) + "-" + cp(0x4E2D, 0x6587)));
    }

    @Test
    @DisplayName("hex digit casing: letters in the escape are UPPER case (e acute is backslash-u-00E9, never 00e9)")
    void hexDigitCasing() {
        // U+00E9, U+ABCD, U+FEDC: every hex letter a-f appears
        Golden.assertMatches("audit-hexcase.json", render(cp(0xE9, 0xABCD, 0xFEDC)));
    }

    @Test
    @DisplayName("U+2028 and U+2029: escaped, so no line separator can occur in a line")
    void lineAndParagraphSeparators() {
        Golden.assertMatches("audit-separators.json", render("a" + cp(0x2028) + "b" + cp(0x2029) + "c"));
    }

    @Test
    @DisplayName("control characters U+0000 to U+001F and U+007F: short escapes for backspace, tab, LF, FF, CR; "
            + "upper-case-hex backslash-u00XX for the rest; U+007F written raw")
    void controlCharacters() {
        StringBuilder all = new StringBuilder();
        for (int c = 0; c <= 0x1F; c++) {
            all.appendCodePoint(c).append('|');
        }
        all.appendCodePoint(0x7F).append('|');
        Golden.assertMatches("audit-controls.json", render(all.toString()));
    }

    @Test
    @DisplayName("an astral code point (U+1F600): an escaped surrogate pair, two upper-case-hex backslash-u escapes")
    void surrogatePair() {
        Golden.assertMatches("audit-astral.json", render("smile-" + cp(0x1F600) + "-end"));
    }

    @Test
    @DisplayName("quote and backslash: escaped with a backslash; the slash is left alone")
    void quoteAndBackslash() {
        Golden.assertMatches("audit-quotes.json", render("a\"b\\c/d"));
    }

    @Test
    @DisplayName("a non-ASCII object key (field disposition path) is escaped the same way as a value")
    void nonAsciiKey() {
        Golden.assertMatches("audit-key.json",
                render("investigator-1", Map.of("customer-api:/na" + cp(0xEF) + "ve", "REDACT")));
    }
}

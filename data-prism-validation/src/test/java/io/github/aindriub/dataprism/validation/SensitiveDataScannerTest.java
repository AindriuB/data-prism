package io.github.aindriub.dataprism.validation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.aindriub.dataprism.annotations.DataClassification;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The detectors, and what they refuse to say about what they found.
 *
 * <p><strong>Every fixture here carries deliberately invalid check digits, and
 * says why.</strong> A valid IBAN, PPSN or card number committed to this
 * repository would itself be the leak the project exists to prevent
 * (docs/conventions.md). That is also why the detectors match on shape and treat
 * the checksum as a confidence signal rather than a gate: a scanner that
 * required valid check digits could not be tested here at all without breaking
 * the rule, and would let a mistyped account number through in production.
 */
class SensitiveDataScannerTest {

    private final SensitiveDataScanner scanner = new SensitiveDataScanner();
    private final ObjectMapper mapper = new ObjectMapper();

    /** IBAN shape, invented bank identifier, mod-97 remainder 69 rather than 1. */
    private static final String INVALID_IBAN = "IE00TEST99999999999999";

    /** Card shape on the reserved 4000 test range; the Luhn sum is 8, so the check digit is wrong. */
    private static final String INVALID_CARD = "4000000000000000";

    /** PPSN shape; the check character should be T for these digits, so A fails it. */
    private static final String INVALID_PPSN = "1234567A";

    /** Area 000, group 00 and serial 0000 are all never issued, so this SSN cannot exist. */
    private static final String INVALID_SSN = "000-00-0000";

    /** Ofcom's never-allocated drama range. */
    private static final String DRAMA_PHONE = "+44 7700 900123";

    /** alg=none and a signature that reads "not-a-signature": worthless as a token. */
    private static final String INERT_JWT =
            "eyJhbGciOiJub25lIn0.eyJzdWIiOiJub2JvZHkifQ.bm90LWEtc2lnbmF0dXJl";

    private List<SensitiveMatch> scan(String value) {
        ObjectNode response = mapper.createObjectNode();
        response.put("note", value);
        ScanReport report = scanner.scan(response);
        assertThat(report.complete()).isTrue();
        return report.matches();
    }

    @Test
    @DisplayName("an IBAN in a free-text note is found, and the failed mod-97 check is recorded")
    void findsIban() {
        assertThat(scan("please credit " + INVALID_IBAN + " today")).singleElement()
                .satisfies(match -> {
                    assertThat(match.classification()).isEqualTo(DataClassification.BANKING);
                    assertThat(match.detectionMethod()).isEqualTo("iban-shape");
                    assertThat(match.path()).isEqualTo("$.note");
                });
    }

    @Test
    @DisplayName("the mod-97 check accepts exactly one pair of check digits")
    void ibanChecksumIsReal() {
        // Proves the arithmetic runs rather than always answering "invalid",
        // without committing a valid IBAN: only the pair that validates is
        // reported as iban-mod97, and the test never records which.
        long validating = java.util.stream.IntStream.range(0, 100)
                .mapToObj(digits -> "IE%02d".formatted(digits) + INVALID_IBAN.substring(4))
                .flatMap(candidate -> scan(candidate).stream())
                .filter(match -> "iban-mod97".equals(match.detectionMethod()))
                .count();

        assertThat(validating).isEqualTo(1);
    }

    @Test
    @DisplayName("a card number is found, and the failed Luhn check is recorded")
    void findsPaymentCard() {
        assertThat(scan("card on file " + INVALID_CARD)).singleElement()
                .satisfies(match -> {
                    assertThat(match.classification()).isEqualTo(DataClassification.FINANCIAL);
                    assertThat(match.detectionMethod()).isEqualTo("card-shape");
                });
    }

    @Test
    @DisplayName("the Luhn check accepts exactly one final digit")
    void luhnIsReal() {
        long validating = java.util.stream.IntStream.range(0, 10)
                .mapToObj(digit -> INVALID_CARD.substring(0, 15) + digit)
                .flatMap(candidate -> scan(candidate).stream())
                .filter(match -> "card-luhn".equals(match.detectionMethod()))
                .count();

        assertThat(validating).isEqualTo(1);
    }

    @Test
    @DisplayName("a grouped card number is found despite the spaces")
    void findsGroupedPaymentCard() {
        assertThat(scan("4000 0000 0000 0000")).singleElement()
                .satisfies(match -> assertThat(match.classification())
                        .isEqualTo(DataClassification.FINANCIAL));
    }

    @Test
    @DisplayName("a PPSN is found, and the failed check character is recorded")
    void findsPpsn() {
        assertThat(scan("PPSN " + INVALID_PPSN)).singleElement()
                .satisfies(match -> {
                    assertThat(match.classification())
                            .isEqualTo(DataClassification.GOVERNMENT_IDENTIFIER);
                    assertThat(match.detectionMethod()).isEqualTo("ppsn-shape");
                });
    }

    @Test
    @DisplayName("the PPSN check character accepts exactly one letter")
    void ppsnCheckCharacterIsReal() {
        long validating = "ABCDEFGHIJKLMNOPQRSTUVW".chars()
                .mapToObj(letter -> INVALID_PPSN.substring(0, 7) + (char) letter)
                .flatMap(candidate -> scan(candidate).stream())
                .filter(match -> "ppsn-check-character".equals(match.detectionMethod()))
                .count();

        assertThat(validating).isEqualTo(1);
    }

    @Test
    @DisplayName("an SSN is found by shape, there being no check digit to compute")
    void findsSsn() {
        assertThat(scan("SSN " + INVALID_SSN)).singleElement()
                .satisfies(match -> {
                    assertThat(match.classification())
                            .isEqualTo(DataClassification.GOVERNMENT_IDENTIFIER);
                    assertThat(match.detectionMethod()).isEqualTo("ssn");
                });
    }

    @Test
    @DisplayName("an email address is found")
    void findsEmail() {
        assertThat(scan("write to nobody@example.com")).singleElement()
                .satisfies(match -> {
                    assertThat(match.classification()).isEqualTo(DataClassification.CONTACT);
                    assertThat(match.detectionMethod()).isEqualTo("email");
                });
    }

    @Test
    @DisplayName("an international phone number is found")
    void findsPhone() {
        assertThat(scan("ring " + DRAMA_PHONE)).singleElement()
                .satisfies(match -> assertThat(match.detectionMethod()).isEqualTo("phone-e164"));
    }

    @Test
    @DisplayName("a bare national number is not treated as a phone number")
    void doesNotOverMatchPhones() {
        // Without the leading +, every reference number of the right length
        // would refuse a response, and the platform would be unusable.
        assertThat(scan("reference 0871234567")).isEmpty();
    }

    @Test
    @DisplayName("a JWT is found")
    void findsJwt() {
        assertThat(scan("Authorization: Bearer " + INERT_JWT)).singleElement()
                .satisfies(match -> {
                    assertThat(match.classification()).isEqualTo(DataClassification.CREDENTIAL);
                    assertThat(match.detectionMethod()).isEqualTo("jwt");
                });
    }

    @Test
    @DisplayName("a key with a known issuer prefix is found")
    void findsApiKey() {
        assertThat(scan("key sk-notarealkeynotarealkey000000")).singleElement()
                .satisfies(match -> {
                    assertThat(match.classification()).isEqualTo(DataClassification.CREDENTIAL);
                    assertThat(match.detectionMethod()).isEqualTo("api-key-prefix");
                });
        assertThat(scan("AKIAEXAMPLEEXAMPLE00")).singleElement()
                .satisfies(match -> assertThat(match.detectionMethod()).isEqualTo("api-key-prefix"));
    }

    @Test
    @DisplayName("a pseudonym is not mistaken for a credential")
    void doesNotOverMatchPseudonyms() {
        // The generator emits values of exactly this shape for every subject in
        // every response. A "long opaque string" rule would refuse all of them.
        assertThat(scan("SUBJ-A1B2C3D4E5F6")).isEmpty();
        assertThat(scan("Morgan Rossi (6H10)")).isEmpty();
    }

    @Test
    @DisplayName("detection reaches values nested inside arrays and objects")
    void scansTheWholeTree() {
        ObjectNode response = mapper.createObjectNode();
        response.putObject("case").putArray("notes")
                .add("nothing here")
                .add("account " + INVALID_IBAN);

        ScanReport report = scanner.scan(response);

        assertThat(report.matches()).singleElement()
                .satisfies(match -> assertThat(match.path()).isEqualTo("$.case.notes[1]"));
    }

    @Test
    @DisplayName("a match never carries the value that produced it")
    void matchCarriesNoValue() {
        assertThat(scan("account " + INVALID_IBAN)).allSatisfy(match ->
                assertThat(match.toString()).doesNotContain(INVALID_IBAN));
    }

    @Test
    @DisplayName("a payload past the size cap reports itself incomplete rather than passing")
    void boundedScanReportsIncompleteness() {
        ObjectNode response = mapper.createObjectNode();
        response.put("padding", "x".repeat(200));
        response.put("note", "account " + INVALID_IBAN);

        // Silently stopping would be the worst outcome: the response would go out
        // reported as checked. The validator turns this into a refusal.
        ScanReport report = new SensitiveDataScanner(100).scan(response);

        assertThat(report.complete()).isFalse();
    }

    @Test
    @DisplayName("an exempted value is not reported at all")
    void exemptValuesAreSkipped() {
        ObjectNode response = mapper.createObjectNode();
        response.put("email", "person.kz48@example.invalid");

        assertThat(scanner.scan(response, "person.kz48@example.invalid"::equals).matches()).isEmpty();
    }
}

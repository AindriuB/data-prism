package io.github.aindriub.dataprism.validation;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.aindriub.dataprism.annotations.DataClassification;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds sensitive values by shape, anywhere in a response tree.
 *
 * <p>This catches what {@link RawValueLeakValidator} structurally cannot: a value
 * that was never in a classified field, so there is nothing in the source to
 * compare it against. A national identifier typed into a free-text note is the
 * case that matters.
 *
 * <p><strong>Shape is enough to refuse.</strong> Where a checksum exists it is
 * computed and recorded, but it only sharpens the reason, it never gates the
 * match. Gating on it would mean this repository's own leak fixtures — which
 * must carry invalid check digits, because a valid one committed here is itself
 * the leak — went undetected, and the tests asserting detection would pass
 * vacuously. It also means a mistyped account number still refuses, which is the
 * fail-closed answer.
 *
 * <p>The scanner reports; it does not decide. Turning matches into a refusal,
 * and exempting the values this scope emitted, is
 * {@link SensitivePatternValidator}'s job.
 */
public final class SensitiveDataScanner {

    /**
     * How much text one scan will look at. Beyond this the scan reports itself
     * incomplete and the validator refuses, so the bound costs availability on a
     * pathological payload rather than costing detection.
     */
    public static final int DEFAULT_CHARACTER_BUDGET = 256 * 1024;

    private static final int MAX_DEPTH = 16;

    /** Enough to identify the problem; a response with more is refused regardless. */
    private static final int MAX_MATCHES = 64;

    private final int characterBudget;

    public SensitiveDataScanner() {
        this(DEFAULT_CHARACTER_BUDGET);
    }

    public SensitiveDataScanner(int characterBudget) {
        if (characterBudget < 1) {
            throw new IllegalArgumentException("characterBudget must be positive");
        }
        this.characterBudget = characterBudget;
    }

    public ScanReport scan(JsonNode response) {
        return scan(response, value -> false);
    }

    /**
     * @param exempt values already known to be safe — the scope's own emitted
     *               set. It is applied here, while the text is still in hand,
     *               because a {@link SensitiveMatch} deliberately does not carry
     *               the value that produced it.
     */
    public ScanReport scan(JsonNode response, Predicate<String> exempt) {
        Objects.requireNonNull(exempt, "exempt");
        Walk walk = new Walk(exempt);
        walk.node(response, "$", 0);
        return walk.complete ? ScanReport.complete(walk.matches) : ScanReport.truncated(walk.matches);
    }

    private final class Walk {

        private final Predicate<String> exempt;
        private final List<SensitiveMatch> matches = new ArrayList<>();
        private int remaining = characterBudget;
        private boolean complete = true;

        private Walk(Predicate<String> exempt) {
            this.exempt = exempt;
        }

        private void node(JsonNode node, String path, int depth) {
            if (node == null || node.isNull() || !complete || matches.size() >= MAX_MATCHES) {
                return;
            }
            if (depth > MAX_DEPTH) {
                complete = false;
                return;
            }
            if (node.isObject()) {
                for (Map.Entry<String, JsonNode> entry : node.properties()) {
                    node(entry.getValue(), path + "." + entry.getKey(), depth + 1);
                }
                return;
            }
            if (node.isArray()) {
                for (int i = 0; i < node.size(); i++) {
                    node(node.get(i), path + "[" + i + "]", depth + 1);
                }
                return;
            }
            scalar(node.asText(), path);
        }

        private void scalar(String text, String path) {
            if (text == null || text.isBlank()) {
                return;
            }
            remaining -= text.length();
            if (remaining < 0) {
                complete = false;
                return;
            }
            if (exempt.test(text)) {
                return;
            }
            for (Detector detector : Detector.values()) {
                String method = detector.detect(text);
                if (method != null) {
                    matches.add(new SensitiveMatch(path, detector.classification, method));
                    if (matches.size() >= MAX_MATCHES) {
                        return;
                    }
                }
            }
        }
    }

    /**
     * The detectors, each a shape plus an optional check over the matched text.
     *
     * <p>Patterns are compiled from string literals in the constructor rather
     * than read from static fields: an enum constant is built before the
     * enclosing enum's static initialisers run, so a {@code static final Pattern}
     * referenced here would be null.
     */
    enum Detector {

        /**
         * Two letters, two check digits, then the country's own account format.
         * The mod-97 remainder must be 1.
         */
        IBAN(DataClassification.BANKING,
                "(?<![A-Za-z0-9])[A-Z]{2}[0-9]{2}[A-Z0-9]{11,30}(?![A-Za-z0-9])",
                "iban-shape", "iban-mod97", Detector::ibanChecksumValid),

        /**
         * 13 to 19 digits, optionally grouped by single spaces or hyphens, with
         * a valid Luhn check digit when it is a real number.
         */
        PAYMENT_CARD(DataClassification.FINANCIAL,
                "(?<![0-9A-Za-z])(?:[0-9][ -]?){12,18}[0-9](?![0-9A-Za-z])",
                "card-shape", "card-luhn", Detector::luhnValid),

        /** Irish PPSN: seven digits, a check character, and an optional second letter. */
        IRISH_PPSN(DataClassification.GOVERNMENT_IDENTIFIER,
                "(?<![A-Za-z0-9])[0-9]{7}[A-Za-z]{1,2}(?![A-Za-z0-9])",
                "ppsn-shape", "ppsn-check-character", Detector::ppsnCheckCharacterValid),

        /**
         * US SSN, hyphenated form only: nine bare digits appear in far too much
         * ordinary data for an unhyphenated detector to be usable. There is no
         * check digit to compute, so shape is all there is.
         */
        US_SSN(DataClassification.GOVERNMENT_IDENTIFIER,
                "(?<![0-9-])[0-9]{3}-[0-9]{2}-[0-9]{4}(?![0-9-])",
                "ssn", null, null),

        EMAIL(DataClassification.CONTACT,
                "(?<![A-Za-z0-9._%+-])[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}(?![A-Za-z])",
                "email", null, null),

        /**
         * E.164 and the ways people write it. A leading {@code +} is required:
         * without it every order number and reference of the right length would
         * be a phone number.
         */
        INTERNATIONAL_PHONE(DataClassification.CONTACT,
                "(?<![0-9+])\\+[0-9][0-9 ().-]{6,17}[0-9](?![0-9])",
                "phone-e164", null, null),

        /** A JWT header is a base64url-encoded object, so it always begins {@code eyJ}. */
        JWT(DataClassification.CREDENTIAL,
                "(?<![A-Za-z0-9_-])eyJ[A-Za-z0-9_-]{6,}\\.[A-Za-z0-9_-]{6,}\\.[A-Za-z0-9_-]*",
                "jwt", null, null),

        /**
         * Issuer-assigned key prefixes. Deliberately a list of known prefixes
         * rather than a general "long random string" rule, which would match
         * every pseudonym this platform emits.
         */
        API_KEY(DataClassification.CREDENTIAL,
                "(?<![A-Za-z0-9_-])(?:"
                        + "sk-[A-Za-z0-9_-]{16,}"
                        + "|(?:sk|pk|rk)_(?:live|test)_[A-Za-z0-9]{12,}"
                        + "|(?:AKIA|ASIA)[0-9A-Z]{16}"
                        + "|gh[pousr]_[A-Za-z0-9]{20,}"
                        + "|github_pat_[A-Za-z0-9_]{20,}"
                        + "|xox[abnpsr]-[A-Za-z0-9-]{10,}"
                        + "|AIza[0-9A-Za-z_-]{35}"
                        + "|glpat-[A-Za-z0-9_-]{16,}"
                        + ")",
                "api-key-prefix", null, null);

        private final DataClassification classification;
        private final Pattern pattern;
        private final String shapeMethod;
        private final String checkedMethod;
        private final Predicate<String> check;

        Detector(DataClassification classification, String regex, String shapeMethod,
                 String checkedMethod, Predicate<String> check) {
            this.classification = classification;
            this.pattern = Pattern.compile(regex);
            this.shapeMethod = shapeMethod;
            this.checkedMethod = checkedMethod;
            this.check = check;
        }

        /** @return the detection method for the first match, or null if there is none */
        String detect(String text) {
            Matcher matcher = pattern.matcher(text);
            if (!matcher.find()) {
                return null;
            }
            if (check == null) {
                return shapeMethod;
            }
            return check.test(matcher.group()) ? checkedMethod : shapeMethod;
        }

        /** ISO 13616: move the first four characters to the end, letters become 10-35, mod 97 must be 1. */
        private static boolean ibanChecksumValid(String candidate) {
            String rearranged = candidate.substring(4) + candidate.substring(0, 4);
            int remainder = 0;
            for (int i = 0; i < rearranged.length(); i++) {
                char c = rearranged.charAt(i);
                if (c >= '0' && c <= '9') {
                    remainder = (remainder * 10 + (c - '0')) % 97;
                } else {
                    remainder = (remainder * 100 + (c - 'A' + 10)) % 97;
                }
            }
            return remainder == 1;
        }

        private static boolean luhnValid(String candidate) {
            String digits = candidate.replaceAll("[^0-9]", "");
            int sum = 0;
            boolean doubling = false;
            for (int i = digits.length() - 1; i >= 0; i--) {
                int digit = digits.charAt(i) - '0';
                if (doubling) {
                    digit *= 2;
                    if (digit > 9) {
                        digit -= 9;
                    }
                }
                sum += digit;
                doubling = !doubling;
            }
            return sum % 10 == 0;
        }

        /**
         * Weighted sum of the seven digits, plus the second letter at weight 9,
         * indexes the check alphabet mod 23.
         */
        private static boolean ppsnCheckCharacterValid(String candidate) {
            String upper = candidate.toUpperCase(Locale.ROOT);
            int sum = 0;
            for (int i = 0; i < 7; i++) {
                sum += (upper.charAt(i) - '0') * (8 - i);
            }
            if (upper.length() == 9) {
                char second = upper.charAt(8);
                if (second < 'A' || second > 'W') {
                    return false;
                }
                sum += (second == 'W' ? 0 : second - 'A' + 1) * 9;
            }
            return upper.charAt(7) == "WABCDEFGHIJKLMNOPQRSTUV".charAt(sum % 23);
        }

    }
}

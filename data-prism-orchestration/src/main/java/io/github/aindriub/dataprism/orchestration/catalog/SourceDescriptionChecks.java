package io.github.aindriub.dataprism.orchestration.catalog;

import io.github.aindriub.dataprism.core.engine.SourceTree;
import io.github.aindriub.dataprism.core.spi.SourceDescription;
import io.github.aindriub.dataprism.validation.ScanReport;
import io.github.aindriub.dataprism.validation.SensitiveDataScanner;
import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The six startup rules on description text (catalog.md section 2), applied in order; the first
 * failure refuses. A refusal names the source and the rule and never the text.
 */
final class SourceDescriptionChecks {

    static final String INVALID = "INVALID_SOURCE_DESCRIPTION";
    static final String LEAKS_NAME = "SOURCE_DESCRIPTION_LEAKS_NAME";
    static final int MAX_LENGTH = 240;

    private static final String PUNCTUATION = ".,;:()-_/'\"";
    private static final Pattern LINE_BREAK = Pattern.compile("[\\n\\r\\u000B\\u000C\\u0085\\u2028\\u2029]");
    /**
     * Shapes that locate a system. Applied to NFKC-normalised, lower-cased text. Deliberately
     * broad: a false positive costs the operator a rewording, a false negative a leaked location.
     * <ul>
     * <li>a scheme, a protocol-relative {@code //}, or {@code www.};
     * <li>a dotted run of labels whose last label contains a letter (hosts, {@code api.k8s});
     * <li>an IPv4 dotted quad, with or without a port;
     * <li>an IPv6 literal (two or more colon-separated hex groups, bracketed or not);
     * <li>a name or number followed by a colon and a port of two to five digits.
     * </ul>
     */
    private static final Pattern URL_OR_HOST = Pattern.compile(
            "[a-z][a-z0-9+.-]*://|//|\\bwww\\."
                    + "|[a-z0-9_-]+(?:\\.[a-z0-9_-]+)*\\.[a-z0-9_-]*[a-z][a-z0-9_-]*(?![a-z0-9_-])"
                    + "|(?<![0-9])[0-9]{1,3}(?:\\.[0-9]{1,3}){3}(?![0-9])"
                    + "|(?<![a-z0-9])(?:[0-9a-f]{0,4}:){2,}[0-9a-f]{0,4}(?![a-z0-9])"
                    + "|[a-z0-9_-]+:[0-9]{2,5}(?![0-9])");

    private final SensitiveDataScanner scanner = new SensitiveDataScanner();

    /**
     * @param forbiddenNames names rule 5 looks for, already normalised (see {@link #normalise})
     * @throws IllegalArgumentException on the first failing rule
     */
    void check(String sourceName, SourceDescription description, List<String> forbiddenNames) {
        String alias = description.aliasDescription();
        String real = description.realDescription();
        for (int rule = 1; rule <= 3; rule++) {
            applyStructure(sourceName, rule, alias);
            applyStructure(sourceName, rule, real);
        }
        // Rule 4 (grammars, duplicates, at most 8 input keys) is enforced by the SourceDescription
        // constructor, so a description in hand has already passed it.
        if (alias != null) {
            String normalised = normalise(alias);
            for (String forbidden : forbiddenNames) {
                if (!forbidden.isEmpty() && normalised.contains(forbidden)) {
                    throw refuse(LEAKS_NAME, sourceName, 5);
                }
            }
            if (alias.equals(real)) {
                throw refuse(INVALID, sourceName, 6);
            }
        }
    }

    /** NFKC-normalises and lower-cases, so fullwidth and other compatibility forms read as plain text. */
    static String fold(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
    }

    /** Folds, then keeps only letters and digits. */
    static String normalise(String text) {
        StringBuilder out = new StringBuilder(text.length());
        fold(text).codePoints()
                .filter(Character::isLetterOrDigit)
                .forEach(out::appendCodePoint);
        return out.toString();
    }

    static IllegalArgumentException refuse(String code, String sourceName, int rule) {
        return new IllegalArgumentException(code + ": source " + sourceName + " rule " + rule);
    }

    static IllegalArgumentException refuseFailed(String sourceName) {
        return new IllegalArgumentException(INVALID + ": source " + sourceName + " description failed");
    }

    private void applyStructure(String sourceName, int rule, String text) {
        if (text == null) {
            return;
        }
        boolean ok = switch (rule) {
            case 1 -> !text.isBlank() && text.codePointCount(0, text.length()) <= MAX_LENGTH && !LINE_BREAK.matcher(text).find();
            case 2 -> text.codePoints().allMatch(SourceDescriptionChecks::printable);
            default -> keyAndLocationFree(text);
        };
        if (!ok) {
            throw refuse(INVALID, sourceName, rule);
        }
    }

    private static boolean printable(int c) {
        return Character.isLetterOrDigit(c) || c == ' ' || (c < 128 && PUNCTUATION.indexOf(c) >= 0);
    }

    private boolean keyAndLocationFree(String text) {
        String composed = Normalizer.normalize(text, Normalizer.Form.NFKC);
        String folded = composed.toLowerCase(Locale.ROOT);
        // The scanner's shapes are case-sensitive, so it sees the normalised text before folding.
        ScanReport report = scanner.scan(SourceTree.text(composed));
        return report.complete() && report.matches().isEmpty() && !URL_OR_HOST.matcher(folded).find();
    }
}

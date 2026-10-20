package io.github.aindriub.dataprism.orchestration.catalog;

import io.github.aindriub.dataprism.core.engine.SourceTree;
import io.github.aindriub.dataprism.core.spi.SourceDescription;
import io.github.aindriub.dataprism.validation.ScanReport;
import io.github.aindriub.dataprism.validation.SensitiveDataScanner;
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
    /** A scheme, a {@code www.} prefix, or a dotted run of host labels ending in a letters-only label. */
    private static final Pattern URL_OR_HOST = Pattern.compile(
            "(?i)(?:[a-z][a-z0-9+.-]*://|\\bwww\\.|[a-z0-9_-]+(?:\\.[a-z0-9_-]+)*\\.[a-z]{2,}(?![a-z0-9]))");

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

    /** Lower-cases and keeps only letters and digits. */
    static String normalise(String text) {
        StringBuilder out = new StringBuilder(text.length());
        text.toLowerCase(Locale.ROOT).codePoints()
                .filter(Character::isLetterOrDigit)
                .forEach(out::appendCodePoint);
        return out.toString();
    }

    static IllegalArgumentException refuse(String code, String sourceName, int rule) {
        return new IllegalArgumentException(code + ": source " + sourceName + " rule " + rule);
    }

    private void applyStructure(String sourceName, int rule, String text) {
        if (text == null) {
            return;
        }
        boolean ok = switch (rule) {
            case 1 -> !text.isBlank() && text.length() <= MAX_LENGTH && !LINE_BREAK.matcher(text).find();
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
        ScanReport report = scanner.scan(SourceTree.text(text));
        return report.complete() && report.matches().isEmpty() && !URL_OR_HOST.matcher(text).find();
    }
}

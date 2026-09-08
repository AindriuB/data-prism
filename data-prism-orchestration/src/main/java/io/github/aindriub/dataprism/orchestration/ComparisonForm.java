package io.github.aindriub.dataprism.orchestration;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;

/**
 * A loose form of a value, used only to compare two source values with each other.
 *
 * <p>Distinct from {@code Text.canonical}, and the distinction matters. That one
 * is deliberately conservative — it does not fold case or accents, because
 * merging two genuinely different names would be as damaging as missing a leak.
 * It is used where a mistake means either splitting one subject in two or letting
 * a leak past.
 *
 * <p>This one is aggressive on purpose. It exists so that "PATRICK MURPHY" and
 * "Patrick  Murphy" are recognised as the same value written differently rather
 * than reported as a disagreement about the data. Being wrong here downgrades a
 * finding, it does not disclose anything, so the trade runs the other way.
 *
 * <p>It never touches anything that is emitted. Both the values it compares and
 * the forms it produces stay on the trusted side; only the verdict crosses.
 */
final class ComparisonForm {

    private ComparisonForm() {
    }

    /** Case-folded, accent-stripped, whitespace-collapsed. */
    static String of(String value) {
        if (value == null) {
            return null;
        }
        String decomposed = Normalizer.normalize(value, Normalizer.Form.NFKD);
        StringBuilder out = new StringBuilder(decomposed.length());
        for (int i = 0; i < decomposed.length(); i++) {
            char c = decomposed.charAt(i);
            // Combining marks: dropping them is what makes "Seán" and "Sean"
            // compare equal, which is a formatting difference and not a
            // disagreement about who the person is.
            if (Character.getType(c) != Character.NON_SPACING_MARK) {
                out.append(c);
            }
        }
        return out.toString().toLowerCase(Locale.ROOT).trim().replaceAll("\\s+", " ");
    }

    /**
     * Whether {@code shorter} looks like an abbreviated writing of {@code longer}.
     *
     * <p>Token by token, so "P. Murphy" matches "Patrick Murphy" and "Pat Murphy"
     * does too, while "Pat Murphy" and "Pat Byrne" do not. Requiring the same
     * token count keeps it from matching a genuinely different name that happens
     * to start with the same letters.
     */
    static boolean abbreviates(String shorter, String longer) {
        if (shorter == null || longer == null || shorter.equals(longer)) {
            return false;
        }
        List<String> a = tokens(shorter);
        List<String> b = tokens(longer);
        if (a.size() != b.size() || a.isEmpty()) {
            return false;
        }
        boolean anyShortened = false;
        for (int i = 0; i < a.size(); i++) {
            String left = a.get(i);
            String right = b.get(i);
            if (left.equals(right)) {
                continue;
            }
            if (left.isEmpty() || !right.startsWith(left)) {
                return false;
            }
            anyShortened = true;
        }
        return anyShortened;
    }

    private static List<String> tokens(String comparisonForm) {
        return java.util.Arrays.stream(comparisonForm.split(" "))
                // A trailing full stop is what makes an initial an initial.
                .map(token -> token.endsWith(".") ? token.substring(0, token.length() - 1) : token)
                .filter(token -> !token.isEmpty())
                .toList();
    }
}

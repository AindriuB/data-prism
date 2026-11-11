package io.github.aindriub.dataprism.architecture.freeze;

import io.github.aindriub.dataprism.core.refusal.RefusalCodes;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Freezes the set of codes the code can emit (register section 15, "Call, admission, re-identification and
 * operator codes", and "Startup refusal codes").
 *
 * <p>The set is extracted from every module's {@code src/main} by the register's four commands: E1, every
 * upper-case literal with an underscore; E1c, a literal that begins with a code and goes on
 * ({@code "CODE: message"}); E2, the constants of every enum named {@code Code}; E1r, code-shaped tokens
 * in {@code src/main/resources}. {@code freeze/codes.txt} lists each extracted name with the surface it is
 * seen on ({@code <surface> <CODE>}, one line per surface). A name that appears in the code and not the list,
 * or the reverse, fails; so does a name with no surface, or a code-surface name that
 * {@link RefusalCodes} would replace with {@code INVALID_REFUSAL_CODE}.
 *
 * <p>Regenerate with {@code -Dfreeze.write=<directory>}: names already listed keep their surfaces, new ones
 * are written as {@code UNCLASSIFIED}, which this test refuses until someone sets the surface.
 */
class CodeSetFreezeTest {

    private static final String LIST = "codes.txt";

    /** Surfaces on which a name is a code a caller, operator or log reader sees, so it must be well formed. */
    private static final Set<String> CODE_SURFACES = Set.of("call", "admission", "reidentification", "operator",
            "startup", "violation", "audit-runtime");

    /** Surfaces that are not codes: per-source status values and the extracted names that are something else. */
    private static final Set<String> OTHER_SURFACES = Set.of("status", "not-a-code");

    private static final String NAME = "[A-Z][A-Z0-9]*(?:_[A-Z0-9]+)+";
    private static final Pattern E1 = Pattern.compile("\"(" + NAME + ")\\\\?\"");
    private static final Pattern E1C = Pattern.compile("\"(" + NAME + ")(?::|\\s)");
    /** A suffix concatenated onto an area: {@code area + "_UNRESOLVED"}, {@code ref.area() + "_WEAK"}. */
    private static final Pattern SUFFIX = Pattern.compile("\\+\\s*\"(_[A-Z][A-Z0-9_]*)\\\\?\"");
    /** A literal passed as the area of a concatenated code: {@code usableId("AREA", ...)}, {@code ref(p, "AREA", ...)}. */
    private static final Pattern AREA = Pattern.compile("\\b(?:usableId|ref)\\(\\s*(?:\\w+,\\s*)?\"(" + NAME + ")\"");
    private static final Pattern E2 = Pattern.compile("\\benum\\s+Code\\s*\\{");
    private static final Pattern E1R = Pattern.compile("(?<![A-Za-z0-9_])(" + NAME + ")(?![A-Za-z0-9_])");
    private static final Pattern COMMENTS = Pattern.compile("/\\*.*?\\*/|//[^\\n]*", Pattern.DOTALL);
    private static final Pattern CONSTANT = Pattern.compile("^\\s*(?:@\\w+\\s+)*([A-Z][A-Z0-9_]*)\\s*$");

    @Test
    void theCodesInTheCodeAreExactlyTheCodesInTheFrozenList() throws IOException {
        Set<String> extracted = extract();
        List<String> listed = FreezeLists.expected(LIST);
        write(extracted, listed);

        Set<String> frozen = new TreeSet<>();
        for (String line : listed) {
            frozen.add(codeOf(line));
        }
        Set<String> added = new TreeSet<>(extracted);
        added.removeAll(frozen);
        Set<String> removed = new TreeSet<>(frozen);
        removed.removeAll(extracted);
        assertThat(added).as("in src/main but not in freeze/" + LIST + " (add each with its surface)").isEmpty();
        assertThat(removed).as("in freeze/" + LIST + " but no longer in src/main").isEmpty();
    }

    @Test
    void everyListedNameHasASurfaceAndEveryCodeIsWellFormed() throws IOException {
        List<String> problems = new ArrayList<>();
        String previous = "";
        for (String line : FreezeLists.expected(LIST)) {
            String surface = surfaceOf(line);
            String code = codeOf(line);
            if (CODE_SURFACES.contains(surface)) {
                if (!RefusalCodes.sanitise(code).equals(code)) {
                    problems.add(code + ": not [A-Z][A-Z0-9_]{0,63}");
                }
            } else if (!OTHER_SURFACES.contains(surface)) {
                problems.add(line + ": unknown surface '" + surface + "'");
            }
            if (previous.compareTo(code) > 0) {
                problems.add(line + ": out of order");
            }
            previous = code;
        }
        assertThat(problems).isEmpty();
    }

    @Test
    void extractionSeesACodeInsideAnEscapedJsonString() {
        assertThat(javaCodes("w.write(\"{\\\"code\\\":\\\"MADE_UP_CODE\\\"}\");")).contains("MADE_UP_CODE");
    }

    @Test
    void extractionSeesAnAreaAndASuffixThatAreConcatenated() {
        String text = "usableId(\"MADE_UP_AREA\", p, id); throw new X(area + \"_UNRESOLVED\", m);"
                + " throw new X(ref.area() + \"_WEAK\", m);";
        assertThat(javaCodes(text)).contains("MADE_UP_AREA_UNRESOLVED", "MADE_UP_AREA_WEAK");
    }

    // ---- extraction ---------------------------------------------------------------------------------

    static Set<String> extract() throws IOException {
        Set<String> codes = new TreeSet<>();
        for (MainSources.Source source : MainSources.all()) {
            if (source.isJava()) {
                codes.addAll(javaCodes(source.text()));
            } else {
                collect(E1R, source.text(), codes);
            }
        }
        return codes;
    }

    /** E1, E1c, E2 and the concatenated shapes in one Java source text. */
    static Set<String> javaCodes(String text) {
        Set<String> codes = new TreeSet<>();
        collect(E1, text, codes);
        collect(E1C, text, codes);
        codes.addAll(concatenated(text));
        codes.addAll(enumCodeConstants(text));
        return codes;
    }

    /**
     * Codes built as {@code area + "_SUFFIX"}: a file that concatenates a suffix onto an area names its areas as
     * literals ({@link #AREA}); each area with each suffix is a code the file can emit.
     */
    static Set<String> concatenated(String text) {
        Set<String> areas = new TreeSet<>();
        collect(AREA, text, areas);
        Set<String> suffixes = new TreeSet<>();
        collect(SUFFIX, text, suffixes);
        Set<String> codes = new TreeSet<>();
        for (String area : areas) {
            for (String suffix : suffixes) {
                codes.add(area + suffix);
            }
        }
        return codes;
    }

    private static void collect(Pattern pattern, String text, Set<String> into) {
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            into.add(matcher.group(1));
        }
    }

    /** The constants of every {@code enum Code { ... }} in {@code text}, whatever their arguments. */
    static Set<String> enumCodeConstants(String text) {
        String stripped = COMMENTS.matcher(text).replaceAll("");
        Set<String> constants = new TreeSet<>();
        Matcher matcher = E2.matcher(stripped);
        while (matcher.find()) {
            int depth = 0;
            StringBuilder current = new StringBuilder();
            for (int i = matcher.end(); i < stripped.length(); i++) {
                char c = stripped.charAt(i);
                boolean end = depth == 0 && (c == ';' || c == '}');
                if (c == '(' || c == '{') {
                    depth++;
                } else if (c == ')' || c == '}') {
                    depth--;
                }
                if (end || (depth == 0 && c == ',')) {
                    Matcher name = CONSTANT.matcher(current);
                    if (name.matches()) {
                        constants.add(name.group(1));
                    }
                    current.setLength(0);
                    if (end) {
                        break;
                    }
                } else if (depth == 0) {
                    current.append(c);
                }
            }
        }
        return constants;
    }

    // ---- the list -----------------------------------------------------------------------------------

    private static String surfaceOf(String line) {
        return line.substring(0, line.indexOf(' '));
    }

    private static String codeOf(String line) {
        return line.substring(line.indexOf(' ') + 1);
    }

    private static void write(Set<String> extracted, List<String> listed) throws IOException {
        if (System.getProperty("freeze.write") == null) {
            return;
        }
        Map<String, List<String>> surfaces = new TreeMap<>();
        for (String line : listed) {
            surfaces.computeIfAbsent(codeOf(line), c -> new ArrayList<>()).add(surfaceOf(line));
        }
        List<String> out = new ArrayList<>();
        for (String code : extracted) {
            for (String surface : surfaces.getOrDefault(code, List.of("UNCLASSIFIED"))) {
                out.add(surface + " " + code);
            }
        }
        FreezeLists.write(LIST, out);
    }
}

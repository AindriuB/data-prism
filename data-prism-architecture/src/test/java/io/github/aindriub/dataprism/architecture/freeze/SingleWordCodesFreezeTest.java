package io.github.aindriub.dataprism.architecture.freeze;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Freezes the single-word operator codes and status values, which {@code CodeSetFreezeTest} cannot see because
 * the register's E1 rule needs an underscore (register section 3.4; task 260 amendment 2026-11-10).
 * {@code freeze/single-word-codes.txt} is kept by hand. Both directions are checked: every listed word is a
 * string literal somewhere in {@code src/main}, and every single-word upper-case literal that
 * {@code data-prism-server}'s operator code returns as a code or status is listed.
 *
 * <p>"Returned as a code or status" is read from the five shapes the server uses: {@code "status", "X"}
 * and {@code "code", "X"} in a response map, {@code new OperatorException(<status>, "X")} in the controllers,
 * {@code codeOnly(response, 401, "X")} in the security configuration, and {@code case ... -> "X"} in the error controller
 * and the error report valve. Other upper-case literals,
 * such as an audit entity type, are not codes.
 */
class SingleWordCodesFreezeTest {

    private static final String LIST = "single-word-codes.txt";
    private static final String WORD = "[A-Z][A-Z0-9]*";
    private static final Pattern STATUS = Pattern.compile("\"status\"\\s*,\\s*\"(" + WORD + ")\"");
    private static final Pattern CODE_ONLY = Pattern.compile("codeOnly\\([^,]+,[^,]+,\\s*\"(" + WORD + ")\"");
    private static final Pattern CODE_KEY = Pattern.compile("\"code\"\\s*,\\s*\"(" + WORD + ")\"");
    private static final Pattern EXCEPTION = Pattern.compile(
            "new\\s+OperatorException\\(\\s*[^,()]+,\\s*\"(" + WORD + ")\"");
    private static final Pattern CASE_ARROW = Pattern.compile("case\\s+[^\\n]*?->\\s*\"(" + WORD + ")\"");

    @Test
    void everyListedWordIsAStringLiteralInSrcMain() throws IOException {
        Set<String> missing = new TreeSet<>(FreezeLists.expected(LIST));
        for (MainSources.Source source : MainSources.all()) {
            missing.removeIf(word -> source.text().contains("\"" + word + "\""));
        }
        assertThat(missing).as("listed in freeze/" + LIST + " but in no src/main string literal").isEmpty();
    }

    @Test
    void everySingleWordCodeTheOperatorSurfaceReturnsIsListed() throws IOException {
        Set<String> returned = new TreeSet<>();
        for (MainSources.Source source : MainSources.all()) {
            String path = source.relative().toString().replace('\\', '/');
            if (!source.module().equals("data-prism-server") || !source.isJava()) {
                continue;
            }
            boolean operator = path.contains("/operator/");
            if (operator || path.endsWith("ServerSecurityConfiguration.java")) {
                collect(STATUS, source.text(), returned);
                collect(CODE_ONLY, source.text(), returned);
                collect(CODE_KEY, source.text(), returned);
                collect(EXCEPTION, source.text(), returned);
            }
            if (operator && path.contains("Error")) {
                collect(CASE_ARROW, source.text(), returned);
            }
        }
        assertThat(returned).as("returned by data-prism-server's operator surface").isNotEmpty();
        List<String> listed = FreezeLists.expected(LIST);
        Set<String> unlisted = new TreeSet<>(returned);
        unlisted.removeAll(listed);
        assertThat(unlisted).as("returned as a code or status but not in freeze/" + LIST).isEmpty();
    }

    private static void collect(Pattern pattern, String text, Set<String> into) {
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            into.add(matcher.group(1));
        }
    }
}

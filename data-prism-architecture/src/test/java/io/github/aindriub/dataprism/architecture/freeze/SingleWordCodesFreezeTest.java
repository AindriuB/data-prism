package io.github.aindriub.dataprism.architecture.freeze;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Freezes the single-word operator codes and status values, which {@code CodeSetFreezeTest} cannot see because
 * the register's E1 rule needs an underscore (register section 3.4; task 260 amendment 2026-11-10).
 * {@code freeze/single-word-codes.txt} is kept by hand. Both directions are checked: every listed word is a
 * string literal somewhere in {@code src/main}, and every single-word upper-case string literal in
 * {@code data-prism-server}'s operator package and {@code ServerSecurityConfiguration} is either listed or in
 * {@link #NOT_CODES}.
 *
 * <p>The scan does not look at how a literal is used, because the server returns codes in many shapes
 * (response-map entries, exception arguments, helper calls, switch arms, local variables) and a shape list
 * misses the next one. A new literal therefore fails until someone lists it as a code or adds it to
 * {@link #NOT_CODES} with a reason.
 */
class SingleWordCodesFreezeTest {

    private static final String LIST = "single-word-codes.txt";
    private static final Pattern LITERAL = Pattern.compile("\"([A-Z][A-Z0-9]*)\\\\?\"");

    /** Reviewed single-word upper-case literals in the scanned files that are not codes, each with its reason. */
    private static final Map<String, String> NOT_CODES = Map.of(
            "ALL", "OversightOperatorController.Target.entityType(): the audit entity-type label of an all-targets"
                    + " pause, not a response code or status");

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
            if (path.contains("/operator/") || path.endsWith("ServerSecurityConfiguration.java")) {
                Matcher matcher = LITERAL.matcher(source.text());
                while (matcher.find()) {
                    returned.add(matcher.group(1));
                }
            }
        }
        returned.removeAll(NOT_CODES.keySet());
        assertThat(returned).as("returned by data-prism-server's operator surface").isNotEmpty();
        List<String> listed = FreezeLists.expected(LIST);
        Set<String> unlisted = new TreeSet<>(returned);
        unlisted.removeAll(listed);
        assertThat(unlisted).as("single-word literal in the operator surface that is neither a listed code nor in NOT_CODES; list it in freeze/" + LIST).isEmpty();
    }
}

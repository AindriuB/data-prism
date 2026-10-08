package io.github.aindriub.dataprism.core.refusal;

import io.github.aindriub.dataprism.core.model.ScrubResult;
import java.util.Set;

/**
 * Keeps payload-supplied property names out of refusal paths.
 *
 * <p>A property name in a source payload is data, not model: it can be a map
 * keyed by email address. Anything that walks a scrubbed tree and reports a
 * path (the response validators do) sees those names, so the path has to be
 * checked against what the reviewed model or catalogue actually declared
 * before it goes into an exception, a log line or a tool error.
 *
 * <p>The declared set is the pointers {@link ScrubResult#dispositions()}
 * already reports, which are built from declared names only. A segment that
 * does not resolve to one of them is undeclared, and so is everything after
 * it, so the path stops there. Anything that cannot be parsed is treated the
 * same way: unresolvable means undeclared.
 *
 * <p>Array indices are never copied. A bracketed digit run may be the tail of a
 * payload key such as {@code email[07700900123]}, and the declared set cannot
 * tell a scalar array from a scalar, so every index renders as {@code [*]}.
 */
public final class RefusalPaths {

    /** Stands in for a property the model does not declare. */
    public static final String UNDECLARED = "<undeclared>";

    private RefusalPaths() {
    }

    /**
     * @param path     a dotted path from a scanned tree, such as {@code $.a.b[0].c}
     * @param declared JSON pointers of every declared field, array indices
     *                 collapsed to {@code *}, as in {@link ScrubResult#dispositions()}
     * @return {@code path}, indices collapsed to {@code [*]}, up to the first segment that is not declared, with that
     *         segment (and what follows it) rendered as {@link #UNDECLARED}
     */
    public static String redact(String path, Set<String> declared) {
        if (path == null || !path.startsWith("$")) {
            return UNDECLARED;
        }
        StringBuilder out = new StringBuilder("$");
        String pointer = "";
        int i = 1;
        while (i < path.length()) {
            char c = path.charAt(i);
            if (c == '[') {
                int close = path.indexOf(']', i);
                if (close < 0 || !isIndex(path, i + 1, close)) {
                    return out.append('.').append(UNDECLARED).toString();
                }
                out.append("[*]");
                pointer += "/*";
                i = close + 1;
            } else if (c == '.') {
                int end = i + 1;
                while (end < path.length() && path.charAt(end) != '.' && path.charAt(end) != '[') {
                    end++;
                }
                String name = path.substring(i + 1, end);
                String next = pointer + "/" + name.replace("~", "~0").replace("/", "~1");
                if (name.isEmpty() || !declared.contains(next)) {
                    return out.append('.').append(UNDECLARED).toString();
                }
                out.append('.').append(name);
                pointer = next;
                i = end;
            } else {
                return out.append('.').append(UNDECLARED).toString();
            }
        }
        return out.toString();
    }

    private static boolean isIndex(String s, int from, int to) {
        if (from >= to) {
            return false;
        }
        for (int k = from; k < to; k++) {
            if (!Character.isDigit(s.charAt(k))) {
                return false;
            }
        }
        return true;
    }
}

package io.github.aindriub.dataprism.orchestration.catalog;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The scope half of the effective label setting: which scopes may be shown real source names
 * (D-RM-10, catalog OD-C3 (c)).
 *
 * <p>Each pattern is an exact scope id, or a scope id prefix followed by a single trailing
 * {@code *}. A pattern is never a regular expression. The default is no pattern at all, so no
 * scope may be shown a real name and {@code EXPOSE_SOURCE_NAMES} alone changes nothing.
 */
public final class RealNamesPolicy {

    /** The message prefix of a refused pattern. The message never repeats the pattern. */
    public static final String INVALID = "INVALID_CATALOG_SETTING: ";

    private static final RealNamesPolicy NONE = new RealNamesPolicy(List.of());

    private final List<String> exact;
    private final List<String> prefixes;

    /**
     * @throws IllegalArgumentException {@code INVALID_CATALOG_SETTING: ...} for a null, blank or
     *                                  malformed pattern: more than one {@code *}, or one not at the end
     */
    public RealNamesPolicy(List<String> scopePatterns) {
        Objects.requireNonNull(scopePatterns, "scopePatterns");
        List<String> exactScopes = new ArrayList<>();
        List<String> prefixScopes = new ArrayList<>();
        for (String pattern : scopePatterns) {
            if (pattern == null || pattern.isBlank()) {
                throw new IllegalArgumentException(INVALID + "a real-names scope pattern must not be blank");
            }
            int star = pattern.indexOf('*');
            if (star < 0) {
                exactScopes.add(pattern);
            } else if (star != pattern.length() - 1) {
                throw new IllegalArgumentException(
                        INVALID + "a real-names scope pattern may have one '*', and only at the end");
            } else {
                prefixScopes.add(pattern.substring(0, star));
            }
        }
        this.exact = List.copyOf(exactScopes);
        this.prefixes = List.copyOf(prefixScopes);
    }

    /** No scope may be shown real names. */
    public static RealNamesPolicy none() {
        return NONE;
    }

    /** Whether the scope's callers may be shown real source names, if they also hold the capability. */
    public boolean permits(String scopeId) {
        if (scopeId == null) {
            return false;
        }
        return exact.contains(scopeId) || prefixes.stream().anyMatch(scopeId::startsWith);
    }
}

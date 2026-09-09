package io.github.aindriub.dataprism.security;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Tool argument names a caller may never supply: scope, principal, purpose and
 * authorisation all derive from the authenticated session, never from the
 * model. See docs/architecture.md boundary 4.
 *
 * <p>{@link #rejected(Set)} reports only which argument names were reserved,
 * never the value the caller sent for them — the value never needs to be read
 * to make this decision, and returning it would put untrusted input back on a
 * path that could log it.
 */
public final class ReservedArguments {

    public static final Set<String> NAMES = Set.of(
            "principalId", "scopeId", "scopeType", "purpose", "caseId", "profile", "capabilities");

    private ReservedArguments() {
    }

    public static Set<String> rejected(Set<String> argumentNames) {
        Objects.requireNonNull(argumentNames, "argumentNames");
        Set<String> out = new LinkedHashSet<>(argumentNames);
        out.retainAll(NAMES);
        return Set.copyOf(out);
    }
}

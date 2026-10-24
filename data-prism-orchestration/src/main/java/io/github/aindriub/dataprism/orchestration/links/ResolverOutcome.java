package io.github.aindriub.dataprism.orchestration.links;

import java.util.List;
import java.util.Objects;

/**
 * What one bounded resolver call came to: translated keys, nothing known, or a failure with a closed reason.
 * Carries no exception, no message and no input key.
 */
public sealed interface ResolverOutcome {

    /** Why a call failed. {@code INVALID_OUTPUT} is the invoker's own; the rest mirror the resolver's reasons. */
    enum Reason { UNAVAILABLE, TIMEOUT, REJECTED, TOO_MANY, INVALID_OUTPUT }

    /** One or more valid keys, at most the cap, and whether the resolver knows of others. */
    record Keys(List<String> keys, boolean more) implements ResolverOutcome {
        public Keys {
            keys = List.copyOf(Objects.requireNonNull(keys, "keys"));
        }

        /** Counts only: a key is never printed. */
        @Override
        public String toString() {
            return "Keys[count=" + keys.size() + ", more=" + more + "]";
        }
    }

    /** The resolver knows no translation. Not a failure. */
    record Empty() implements ResolverOutcome {}

    /** The call failed; the caller must not fall back to the untranslated key. */
    record Failed(Reason reason) implements ResolverOutcome {
        public Failed {
            Objects.requireNonNull(reason, "reason");
        }
    }
}

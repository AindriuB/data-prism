package io.github.aindriub.dataprism.core;

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Objects;
import java.util.Set;

/**
 * A scrubbed tree, together with the values the engine generated to build it.
 *
 * <p>The emitted set is what stops pattern detection refusing the platform's own
 * output. A synthesised email is an email, so an email detector matches it; the
 * validator can only tell that apart from a real leak if it knows which values
 * this scope produced. See docs/design-review.md §A5.
 *
 * <p>Only generated values go in. A value the profile passed through unchanged
 * came from the source, so if it looks sensitive that is a finding rather than
 * an exemption.
 */
public record ScrubResult(ObjectNode tree, Set<String> emitted) {

    public ScrubResult {
        Objects.requireNonNull(tree, "tree");
        emitted = Set.copyOf(emitted);
    }

    /**
     * Sizes only. The generated record {@code toString} would print every emitted
     * value, and this object travels through the same orchestration code that
     * logs and audits.
     */
    @Override
    public String toString() {
        return "ScrubResult[fields=" + tree.size() + ", emitted=" + emitted.size() + "]";
    }
}

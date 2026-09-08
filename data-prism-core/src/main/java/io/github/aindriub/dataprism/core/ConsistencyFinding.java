package io.github.aindriub.dataprism.core;

import io.github.aindriub.dataprism.annotations.PrivacyNamespace;

import java.util.List;

/**
 * One observation about how the sources disagree.
 *
 * <p>This is the platform's reason for existing, and it only works because of an
 * awkward fact: after pseudonymisation the disagreement is invisible. Three
 * systems holding "Patrick Murphy", "Pat Murphy" and "P. Murphy" all resolve to
 * the same synthetic name, because that is what a pseudonym keyed on the subject
 * is for. The output looks perfectly consistent and the data quality problem has
 * been erased rather than surfaced.
 *
 * <p>So correlation compares the raw values on the trusted side of the boundary,
 * before scrubbing, and emits this. The identity representation becomes
 * consistent; the underlying inconsistency becomes <em>more</em> visible.
 *
 * <p><strong>No finding carries a value.</strong> What it carries instead is
 * which sources agree with each other — {@code agreementGroups} partitions the
 * sources into sets that held the same value, without saying what any of them
 * held. That is enough for an investigator to know exactly where to look and
 * discloses nothing, and it is more useful than a count. See docs/pack.md §34.
 *
 * @param field           the field or namespace the finding is about
 * @param agreementGroups sources grouped by the value they agreed on, largest
 *                        group first. Source names are aliases unless the caller
 *                        holds the capability to see real ones
 * @param detail          a short explanation, never a value
 */
public record ConsistencyFinding(
        String field,
        PrivacyNamespace namespace,
        Kind kind,
        List<List<String>> agreementGroups,
        int distinctValues,
        String detail) {

    public ConsistencyFinding {
        agreementGroups = agreementGroups.stream().map(List::copyOf).toList();
    }

    public enum Kind {
        /**
         * The values differ once case, spacing and accents are set aside — a real
         * disagreement about what the data says.
         */
        INCONSISTENT,

        /**
         * The same value written differently: case, spacing or diacritics. Still
         * reported, because it is still a difference between the systems and
         * hiding it would be making the data look tidier than it is.
         */
        FORMATTING_ONLY,

        /**
         * One value appears to be a shortened form of another — "Pat" for
         * "Patrick", "P." for either.
         *
         * <p>This says more about the values than the other kinds do: it reveals
         * a structural relationship between two things nobody is allowed to see.
         * It is therefore only produced for namespaces where the platform already
         * emits a readable human substitute — names and organisations — and never
         * for identifiers, where "one value is a prefix of the other" would be a
         * genuine clue.
         */
        ABBREVIATION,

        /** Some sources hold the field and others do not. */
        MISSING_IN_SOME_SOURCES,

        /**
         * A source value contains something shaped like an instruction to a model.
         *
         * <p>Flagged, never removed. Rewriting it would violate the rule that the
         * platform does not alter the business truth, and would hide an attack
         * from the person best placed to notice it. The model sees it as data,
         * with a finding saying so. See docs/design-review.md §D3.
         */
        SUSPECTED_INSTRUCTION_CONTENT
    }

    public boolean disagreement() {
        return kind != Kind.SUSPECTED_INSTRUCTION_CONTENT;
    }
}

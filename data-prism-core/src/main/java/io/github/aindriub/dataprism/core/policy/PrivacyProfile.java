package io.github.aindriub.dataprism.core.policy;

import io.github.aindriub.dataprism.annotations.DataClassification;
import io.github.aindriub.dataprism.annotations.PrivacyAction;
import io.github.aindriub.dataprism.annotations.PrivacyNamespace;

import java.util.Map;
import java.util.Objects;

/**
 * A named set of rules mapping classification to action.
 *
 * <p>This is where privacy decisions actually live. The annotation on a model is
 * the author's suggestion; the profile is the operator's policy, and it can be
 * changed without touching or redeploying application code.
 */
public record PrivacyProfile(
        String name,
        UnclassifiedBehaviour unclassified,
        Map<DataClassification, ClassificationRule> classifications,
        Map<PrivacyNamespace, GeneralizationRule> generalizations) {

    public PrivacyProfile {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(unclassified, "unclassified");
        classifications = Map.copyOf(classifications);
        generalizations = generalizations == null ? Map.of() : Map.copyOf(generalizations);
    }

    /** A profile with no generalisation configured, which is the common case. */
    public PrivacyProfile(String name, UnclassifiedBehaviour unclassified,
                          Map<DataClassification, ClassificationRule> classifications) {
        this(name, unclassified, classifications, Map.of());
    }

    /**
     * What to do with a field nobody classified — one on an exposed model with no
     * annotation, a property present in the source that the model does not
     * declare, or a nested value whose type carries no classification.
     *
     * <p>Ordered from strictest to loosest. The first three are all safe in the
     * sense that no unclassified value reaches the model; they differ only in how
     * loudly they say so and whether the field keeps its place in the response.
     * The fourth is not, and is named accordingly.
     */
    public enum UnclassifiedBehaviour {

        /** Refuse the whole response. The default, and the right choice for production. */
        FAIL_REQUEST,

        /** Keep the field, replace the value, record a warning. */
        REDACT_AND_WARN,

        /**
         * Omit the field entirely and record a warning.
         *
         * <p>This is the analogue of Jackson's
         * {@code FAIL_ON_UNKNOWN_PROPERTIES=false}: that setting <em>ignores</em>
         * properties it does not recognise rather than passing them along, and
         * ignoring is what makes it safe. Use this when a source adds fields
         * faster than the models can be updated and a failed request is worse
         * than a missing field.
         */
        DROP_AND_WARN,

        /**
         * Emit the value unchanged.
         *
         * <p>This inverts the platform's guarantee. Everything else here rests on
         * the idea that an unclassified field means the decision was never made,
         * so the value must not reach the model; this setting decides in favour
         * of exposure for every field nobody looked at, including ones added to a
         * source system after the model was written and never reviewed. The
         * specification names it as the one thing the safe default must never be
         * (docs/pack.md §30).
         *
         * <p>It exists because a caller may be working with data that genuinely
         * carries nothing sensitive, and forcing them to annotate every field of
         * a large model to say so is real friction. That is a legitimate choice
         * for a specific dataset, made deliberately. It is not a default, it is
         * spelled UNSAFE in configuration so that it cannot be enabled without
         * reading it, and every field it releases is counted and warned about.
         */
        PASS_THROUGH_UNSAFE;

        /** Whether a value nobody classified can reach the model under this setting. */
        public boolean releasesUnclassifiedData() {
            return this == PASS_THROUGH_UNSAFE;
        }
    }

    /**
     * @param override take this action verbatim instead of the stricter of it and
     *                 the model author's suggestion. Needed to relax a field an
     *                 author over-classified, and rare enough to be worth stating
     *                 explicitly in the profile
     */
    public record ClassificationRule(PrivacyAction action, boolean override) {

        public ClassificationRule {
            Objects.requireNonNull(action, "action");
        }

        public static ClassificationRule of(PrivacyAction action) {
            return new ClassificationRule(action, false);
        }
    }
}

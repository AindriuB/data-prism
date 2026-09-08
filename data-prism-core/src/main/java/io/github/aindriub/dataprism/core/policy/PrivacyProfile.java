package io.github.aindriub.dataprism.core.policy;

import io.github.aindriub.dataprism.annotations.DataClassification;
import io.github.aindriub.dataprism.annotations.PrivacyAction;

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
        Map<DataClassification, ClassificationRule> classifications) {

    public PrivacyProfile {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(unclassified, "unclassified");
        classifications = Map.copyOf(classifications);
    }

    /**
     * What to do with a field on an exposed model that nobody classified.
     *
     * <p>There is deliberately no pass-through option. An unclassified field
     * means the decision was never made, and the specification is explicit that
     * the safe default may be configurable between failing and redacting but
     * never between those and exposure. See docs/pack.md §30.
     */
    public enum UnclassifiedBehaviour {

        /** Refuse the whole response. The right choice for production. */
        FAIL_REQUEST,

        /** Redact the field and record a warning. For migrating an existing model. */
        REDACT_AND_WARN
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

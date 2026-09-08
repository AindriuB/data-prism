package io.github.aindriub.dataprism.core.policy;

import io.github.aindriub.dataprism.annotations.PrivacyAction;
import io.github.aindriub.dataprism.annotations.PrivacyNamespace;

/**
 * The decision for one field, after policy and annotation have been combined.
 *
 * @param allowed false means the response must be refused; the engine does not
 *                get to choose a lesser remedy
 * @param source  which input decided it, for auditing and for explaining a
 *                surprising outcome without re-deriving the chain by hand
 */
public record EffectivePrivacyPolicy(
        PrivacyAction action,
        PrivacyNamespace namespace,
        boolean allowed,
        String profile,
        Decided source) {

    public enum Decided {
        /** A rule in the profile matched a classification on the field. */
        PROFILE_RULE,
        /** The profile had nothing to say; the model author's suggestion stood. */
        ANNOTATION,
        /** Neither had anything to say. */
        SAFE_DEFAULT,
        /** Declared non-sensitive by the model author, with a stated reason. */
        DECLARED_NON_SENSITIVE,
        /** A correlation identifier, which is never emitted. */
        IDENTIFIER,
        /** Nobody classified it. */
        UNCLASSIFIED
    }

    public static EffectivePrivacyPolicy refuse(String profile) {
        return new EffectivePrivacyPolicy(PrivacyAction.REMOVE, PrivacyNamespace.NONE,
                false, profile, Decided.UNCLASSIFIED);
    }
}

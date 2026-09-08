package io.github.aindriub.dataprism.core.policy;

import io.github.aindriub.dataprism.annotations.DataClassification;
import io.github.aindriub.dataprism.annotations.PrivacyAction;
import io.github.aindriub.dataprism.core.FieldMetadata;
import io.github.aindriub.dataprism.core.PrivacyContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Objects;

/**
 * Applies the precedence chain from docs/pack.md §29.
 *
 * <p>In S1 the chain is profile rule, then the model author's suggestion, then a
 * safe default. Authorisation and per-case rules sit above all of these and
 * arrive with S8; the ordering here already leaves room for them, and the one
 * rule that must survive their arrival is that no annotation may ever widen what
 * policy allows.
 *
 * <p>Where both a profile rule and an annotation apply, the stricter of the two
 * wins. A profile can take its action verbatim instead by setting
 * {@code override}, which exists so an over-classified field can be relaxed
 * deliberately rather than by accident.
 */
public final class ProfilePrivacyPolicyResolver implements PrivacyPolicyResolver {

    private static final Logger LOG = LoggerFactory.getLogger(ProfilePrivacyPolicyResolver.class);

    /** What an unclassified field gets when the profile says redact rather than fail. */
    private static final PrivacyAction SAFE_DEFAULT = PrivacyAction.REDACT;

    private final Map<String, PrivacyProfile> profiles;

    public ProfilePrivacyPolicyResolver(Map<String, PrivacyProfile> profiles) {
        this.profiles = Map.copyOf(Objects.requireNonNull(profiles, "profiles"));
    }

    @Override
    public EffectivePrivacyPolicy resolve(FieldMetadata field, PrivacyContext context) {
        PrivacyProfile profile = profiles.get(context.redactionProfile());
        if (profile == null) {
            // Falling back to a default profile here would mean a typo in
            // configuration silently changes what is disclosed.
            throw new IllegalStateException(
                    "no privacy profile named " + context.redactionProfile());
        }

        if (field.internalIdentifier()) {
            return new EffectivePrivacyPolicy(PrivacyAction.REMOVE, field.namespace(), true,
                    profile.name(), EffectivePrivacyPolicy.Decided.IDENTIFIER);
        }

        if (!field.declared()) {
            return unclassified(field, profile);
        }

        if (!field.sensitive()) {
            return new EffectivePrivacyPolicy(PrivacyAction.PASS_THROUGH, field.namespace(), true,
                    profile.name(), EffectivePrivacyPolicy.Decided.DECLARED_NON_SENSITIVE);
        }

        PrivacyAction fromProfile = null;
        boolean override = false;
        for (DataClassification classification : field.classifications()) {
            PrivacyProfile.ClassificationRule rule = profile.classifications().get(classification);
            if (rule == null) {
                continue;
            }
            // A field classified both PII and BANKING gets whichever rule is
            // stricter, not whichever the enum happens to list first.
            fromProfile = fromProfile == null
                    ? rule.action()
                    : ActionStrictness.stricter(fromProfile, rule.action());
            override |= rule.override();
        }

        PrivacyAction suggested = field.suggestedAction();

        if (fromProfile == null) {
            return suggested == null
                    ? new EffectivePrivacyPolicy(SAFE_DEFAULT, field.namespace(), true,
                            profile.name(), EffectivePrivacyPolicy.Decided.SAFE_DEFAULT)
                    : new EffectivePrivacyPolicy(suggested, field.namespace(), true,
                            profile.name(), EffectivePrivacyPolicy.Decided.ANNOTATION);
        }

        PrivacyAction action = (override || suggested == null)
                ? fromProfile
                : ActionStrictness.stricter(fromProfile, suggested);

        return new EffectivePrivacyPolicy(action, field.namespace(), true, profile.name(),
                EffectivePrivacyPolicy.Decided.PROFILE_RULE);
    }

    /**
     * Applies the profile's setting for anything nobody classified.
     *
     * <p>Warnings name the field but never its value. The point of the warning is
     * that someone goes and classifies the field, and a field name is not the
     * sensitive part.
     */
    private static EffectivePrivacyPolicy unclassified(FieldMetadata field, PrivacyProfile profile) {
        return switch (profile.unclassified()) {
            case FAIL_REQUEST -> EffectivePrivacyPolicy.refuse(profile.name());
            case REDACT_AND_WARN -> {
                LOG.warn("unclassified field {} redacted under profile {}",
                        field.fieldName(), profile.name());
                yield unclassifiedPolicy(SAFE_DEFAULT, field, profile);
            }
            case DROP_AND_WARN -> {
                LOG.warn("unclassified field {} dropped under profile {}",
                        field.fieldName(), profile.name());
                yield unclassifiedPolicy(PrivacyAction.REMOVE, field, profile);
            }
            case PASS_THROUGH_UNSAFE -> {
                // Deliberately WARN rather than DEBUG, and on every field rather
                // than once: this is the setting that lets unreviewed data reach
                // the model, and it should be visible in any log anyone reads.
                LOG.warn("unclassified field {} released unchanged under profile {} "
                                + "(unclassified: PASS_THROUGH_UNSAFE)",
                        field.fieldName(), profile.name());
                yield unclassifiedPolicy(PrivacyAction.PASS_THROUGH, field, profile);
            }
        };
    }

    private static EffectivePrivacyPolicy unclassifiedPolicy(PrivacyAction action,
                                                             FieldMetadata field,
                                                             PrivacyProfile profile) {
        return new EffectivePrivacyPolicy(action, field.namespace(), true, profile.name(),
                EffectivePrivacyPolicy.Decided.UNCLASSIFIED);
    }
}

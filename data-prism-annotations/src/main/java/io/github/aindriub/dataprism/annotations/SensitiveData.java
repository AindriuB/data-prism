package io.github.aindriub.dataprism.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Classifies a field as sensitive.
 *
 * <p>This annotation is metadata, not enforcement. Nothing about applying it
 * makes a value safe: the enforcement chain runs metadata to policy to
 * authorisation to transformation to validation, and the server-side policy has
 * final authority over {@link #suggestedAction()}. See docs/pack.md §95.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.RECORD_COMPONENT, ElementType.METHOD, ElementType.PARAMETER})
public @interface SensitiveData {

    /** What the value is. At least one. */
    DataClassification[] classifications();

    /**
     * The semantic identity of the value, which forms part of the
     * pseudonymisation key. Two differently-named fields sharing a namespace and
     * a subject resolve to the same synthetic value.
     */
    PrivacyNamespace namespace() default PrivacyNamespace.NONE;

    /**
     * What the model author believes should happen. A suggestion: policy may
     * choose something stricter, and never chooses something weaker.
     */
    PrivacyAction suggestedAction() default PrivacyAction.REDACT;

    SensitivityLevel sensitivity() default SensitivityLevel.CONFIDENTIAL;

    /**
     * Names the field holding the identifier of the subject this value belongs
     * to. Empty means the record's own {@link InternalIdentifier}.
     *
     * <p>Set this whenever a record describes more than one subject. Leaving it
     * empty there would give two different people the same pseudonym, which is a
     * false statement to the model rather than a leak — and harder to notice.
     */
    String subject() default "";
}

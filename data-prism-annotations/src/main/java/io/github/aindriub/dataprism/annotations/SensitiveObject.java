package io.github.aindriub.dataprism.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a type as safe to descend into when it appears nested inside an exposed
 * model.
 *
 * <p>A nested object is not a value, it is more fields — and fields nobody has
 * classified. Without an annotation saying otherwise the engine has no way to
 * tell a reviewed sub-structure from an arbitrary blob, so it treats the nested
 * type as unclassified and applies the profile's setting for that.
 *
 * <p>{@link LlmExposedModel} implies this. Use {@code @SensitiveObject} for types
 * that are only ever reached as part of a larger model and are never returned on
 * their own.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface SensitiveObject {

    /**
     * Classification applied to every field of this type that does not state its
     * own. Empty means the type is descendable but classifies nothing.
     *
     * <p>For a structured value whose parts are all the same kind of thing — an
     * address split into street, town and postcode — this says once what would
     * otherwise be said on every component.
     */
    DataClassification[] classifications() default {};

    /**
     * The action for fields covered by {@link #classifications()}.
     *
     * <p>{@code REDACT} and {@code REMOVE} are the ones that behave well here,
     * because they do not depend on telling the fields apart.
     *
     * <p>{@code SYNTHESIZE} usually does not. A synthetic value is a function of
     * subject and namespace, so applying one namespace to every field of a type
     * gives every field the same value: street, town and postcode would each come
     * back as the same synthetic address. Where a structure needs synthesis, give
     * its fields their own {@link SensitiveData} with the namespace that suits
     * each, and use this for the remainder.
     */
    PrivacyAction suggestedAction() default PrivacyAction.REDACT;

    /** Namespace for fields covered by {@link #classifications()}. */
    PrivacyNamespace namespace() default PrivacyNamespace.NONE;

    /** What to do with this type's fields that carry no annotation of their own. */
    UndeclaredFields undeclaredFields() default UndeclaredFields.PROFILE_DEFAULT;
}

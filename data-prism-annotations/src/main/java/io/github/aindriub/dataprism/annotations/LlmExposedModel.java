package io.github.aindriub.dataprism.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a type as approved for exposure through MCP.
 *
 * <p>Absence is not an oversight, it is a refusal: a type without this
 * annotation is never emitted. Every field of an annotated type must carry
 * either {@link SensitiveData} or {@link NonSensitive}, so that adding a field
 * is a decision rather than an accident — unless the type states a default for
 * the ones that do not.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface LlmExposedModel {

    /** Names the policy profile to resolve this type's fields against. */
    String profile() default "DEFAULT";

    /**
     * What to do with this type's fields that carry no annotation of their own.
     *
     * <p>Scoped to this class rather than the whole deployment, which is what
     * makes it a reasonable retrofit tool: a large legacy model can be adopted by
     * stating one decision about one type, without loosening anything else and
     * without weakening the profile every other model is read under.
     *
     * <p>A field that does carry an annotation is unaffected. This decides only
     * what silence means on this type.
     */
    UndeclaredFields undeclaredFields() default UndeclaredFields.PROFILE_DEFAULT;
}

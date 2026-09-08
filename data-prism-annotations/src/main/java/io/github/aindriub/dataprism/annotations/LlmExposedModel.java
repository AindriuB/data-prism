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
 * is a decision rather than an accident.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface LlmExposedModel {

    /** Names the policy profile to resolve this type's fields against. */
    String profile() default "DEFAULT";
}

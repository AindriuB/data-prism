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
}

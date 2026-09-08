package io.github.aindriub.dataprism.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Asserts that a field on an {@link LlmExposedModel} is safe to emit unchanged.
 *
 * <p>Fail-closed needs a way to say "this really is fine", or developers reach
 * for {@code @SensitiveData(suggestedAction = PASS_THROUGH)} instead — which is
 * indistinguishable from a genuine classification and hides the decision from
 * review.
 *
 * <p>{@link #reason()} is mandatory and is the review artefact. "Enum with three
 * values, no free text" is a reason; "not sensitive" is not.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.RECORD_COMPONENT, ElementType.METHOD, ElementType.PARAMETER})
public @interface NonSensitive {

    String reason();
}

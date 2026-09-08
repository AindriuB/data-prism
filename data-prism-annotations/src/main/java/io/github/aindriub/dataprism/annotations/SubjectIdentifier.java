package io.github.aindriub.dataprism.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a field as the identifier of one of the subjects a record describes.
 *
 * <p>{@link InternalIdentifier} is the same thing with {@code role = "self"}:
 * the record's own subject, and the default for any sensitive field that does
 * not name another.
 *
 * <p>Use this where a record describes more than one person or organisation —
 * an applicant and a guarantor, a payer and a payee. Sensitive fields then point
 * at the right one with {@link SensitiveData#subject()}. Getting this wrong does
 * not leak anything; it tells the model that two subjects are one, which is a
 * false statement it will then reason from, and far harder to notice than a leak.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.RECORD_COMPONENT, ElementType.METHOD, ElementType.PARAMETER})
public @interface SubjectIdentifier {

    /** Names the subject, e.g. "guarantor". Referenced by field name, not by role. */
    String role();
}

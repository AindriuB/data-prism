package io.github.aindriub.dataprism.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks the field holding this record's own correlation identifier.
 *
 * <p>The field name is irrelevant; the annotation carries the meaning. This is
 * the default pseudonymisation subject for every sensitive field on the same
 * record that does not name one of its own.
 *
 * <p>Where a record describes more than one subject — an applicant and a
 * guarantor, say — the default is wrong, and each sensitive field must name its
 * own subject via {@link SensitiveData#subject()}. See docs/design-review.md §A1.
 *
 * <p>All four targets are declared because javac propagates a record component's
 * annotations only to the declarations its {@code @Target} permits, and the
 * metadata resolver must find it wherever it landed.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.RECORD_COMPONENT, ElementType.METHOD, ElementType.PARAMETER})
public @interface InternalIdentifier {
}

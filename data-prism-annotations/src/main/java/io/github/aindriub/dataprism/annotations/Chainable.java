package io.github.aindriub.dataprism.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares that an identifier field holds a key that another source accepts, in the named key namespace.
 *
 * <p>Metadata only. The field stays an identifier like any other and is removed or pseudonymised exactly
 * as before; this never causes a value to be emitted. It is valid only on a field also marked
 * {@link InternalIdentifier} or {@link SubjectIdentifier}.
 *
 * <p>Targets match {@link InternalIdentifier}, for the same reason: javac propagates a record component's
 * annotations only to the declarations the {@code @Target} permits.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.RECORD_COMPONENT, ElementType.METHOD, ElementType.PARAMETER})
public @interface Chainable {

    /** The key namespace, matching {@code [a-z][a-z0-9-]{0,31}}. Checked when the model is read, not here. */
    String keyNamespace();
}

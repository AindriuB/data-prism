package io.github.aindriub.dataprism.hazelcast;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Marks a test that deliberately makes the serialisation filter reject a class, so {@link FilterRejectionGuard} stands down. */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@interface ExpectsFilterRejection {
}

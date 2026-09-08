package io.github.aindriub.dataprism.core;

/**
 * Turns a source object into a tree that is safe to show a model.
 *
 * <p>The engine works on a data tree rather than the Java object graph. Records
 * are immutable and their canonical constructors may validate, so there is no
 * way to write a scrubbed value back into one; and a tree makes unknown fields
 * visible, which is what fail-closed needs. See docs/design-review.md §A4.
 *
 * <p>The result carries the values the engine generated as well as the tree,
 * because the output validator cannot otherwise tell a synthesised email from a
 * leaked one.
 */
public interface ScrubbingEngine {

    /**
     * @throws PrivacyRefusedException if the result cannot be made safe, in which
     *                                 case nothing is returned to the caller at all
     */
    ScrubResult scrub(Object source, PrivacyContext context);
}

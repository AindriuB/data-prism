package io.github.aindriub.dataprism.annotations;

/**
 * What a type says should happen to its own fields that carry no annotation.
 *
 * <p>This exists for retrofitting. A model with two hundred fields, most of them
 * inert, is a real obstacle to adopting the platform at all: annotating every one
 * of them to say "this is fine" is hours of work that produces no information,
 * and the pressure is then to reach for a global setting that turns fail-closed
 * off everywhere.
 *
 * <p>A per-type default is the better trade. It is scoped to one class that
 * someone looked at and decided about, it sits on that class where a reviewer
 * will see it, and it is greppable — none of which is true of a global switch.
 */
public enum UndeclaredFields {

    /** Defer to the profile. The default, and what every existing model does. */
    PROFILE_DEFAULT,

    /**
     * Treat unannotated fields as declared non-sensitive.
     *
     * <p>An assertion about this type: someone has looked at it and is saying the
     * fields nobody classified are safe. That is a claim a reviewer can disagree
     * with, which is the point — unlike silence, which says nothing.
     *
     * <p>Fields added to this type later inherit it without review. That is the
     * cost, and it is why this is worth revisiting once a retrofit is done.
     */
    NON_SENSITIVE,

    /** Keep unannotated fields but replace their values. */
    REDACT,

    /** Omit unannotated fields entirely. */
    DROP
}

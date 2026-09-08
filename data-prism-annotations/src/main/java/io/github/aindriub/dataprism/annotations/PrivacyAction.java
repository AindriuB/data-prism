package io.github.aindriub.dataprism.annotations;

/**
 * What to do with a classified value on its way to the model.
 *
 * <p>S0 implements {@link #SYNTHESIZE}, {@link #REDACT} and {@link #REMOVE}. The
 * rest are declared so policy files and annotations can name them, and throw on
 * use until their slice lands, rather than silently degrading to pass-through.
 */
public enum PrivacyAction {

    /** Emit the value unchanged. Only ever reachable through server-side policy. */
    PASS_THROUGH,

    /** Replace with a fixed marker. The field stays, its value does not. */
    REDACT,

    /** Drop the field entirely. */
    REMOVE,

    /** Replace with a keyed digest. Stable, but not human-readable. */
    HASH,

    /** Replace with a deterministic synthetic value in the field's namespace. */
    SYNTHESIZE,

    /** Replace with an opaque scope-local token. */
    TOKENIZE,

    /** Replace with a coarser bucket, e.g. an amount band. */
    GENERALIZE
}

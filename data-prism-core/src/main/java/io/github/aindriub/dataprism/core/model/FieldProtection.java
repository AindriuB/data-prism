package io.github.aindriub.dataprism.core.model;

import io.github.aindriub.dataprism.core.spi.KeyNamespace;

import java.util.Objects;

/**
 * How one path of a source's output is protected, as {@code describe_source} reports it.
 *
 * <p>It names no value, no classification and no model type. The path is the field as it appears in
 * a response; an array element is written with {@code []}.
 *
 * @param path           the field as it appears in a response: {@code a}, {@code a[]}, {@code a[].b}
 * @param protection     what the scrubber does to the value
 * @param valueNamespace the privacy namespace name; only for {@link Protection#SYNTHETIC} and
 *                       {@link Protection#TOKEN}, and absent when the field has none
 * @param keyNamespace   the key namespace of a chainable field while links are enabled, else null
 */
public record FieldProtection(String path, Protection protection, String valueNamespace,
                              KeyNamespace keyNamespace) {

    /** The closed vocabulary, mapped from the effective action after profile and descriptor tightening. */
    public enum Protection {
        /** {@code PASS_THROUGH}. */
        VERBATIM,
        /** {@code SYNTHESIZE}. */
        SYNTHETIC,
        /** {@code TOKENIZE} and {@code HASH}. */
        TOKEN,
        /** {@code REDACT}. */
        MARKER,
        /** {@code GENERALIZE}. */
        BUCKET,
        /** {@code REMOVE}. */
        ABSENT
    }

    public FieldProtection {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(protection, "protection");
        if (valueNamespace != null
                && protection != Protection.SYNTHETIC && protection != Protection.TOKEN) {
            throw new IllegalArgumentException("a value namespace goes only with SYNTHETIC or TOKEN");
        }
    }
}

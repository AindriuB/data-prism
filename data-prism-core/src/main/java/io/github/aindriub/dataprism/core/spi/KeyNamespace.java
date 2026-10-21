package io.github.aindriub.dataprism.core.spi;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The name of a key space: which kind of identifier a subject key is. An opaque,
 * operator-supplied label, never a key value.
 *
 * <p>Grammar {@code [a-z][a-z0-9-]{0,31}}. Compared exactly: no trimming and no case folding.
 */
public record KeyNamespace(String value) {

    private static final Pattern GRAMMAR = Pattern.compile("[a-z][a-z0-9-]{0,31}");

    public KeyNamespace {
        Objects.requireNonNull(value, "value");
        if (!GRAMMAR.matcher(value).matches()) {
            throw new IllegalArgumentException("key namespace must match [a-z][a-z0-9-]{0,31}");
        }
    }

    @Override
    public String toString() {
        return value;
    }
}

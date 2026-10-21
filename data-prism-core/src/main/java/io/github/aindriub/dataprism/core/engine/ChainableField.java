package io.github.aindriub.dataprism.core.engine;

import io.github.aindriub.dataprism.core.spi.KeyNamespace;

import java.util.Objects;

/**
 * One path of a model that holds a key another source accepts.
 *
 * @param path         in the {@code describe_source} grammar: {@code a}, {@code a[]}, {@code a[].b}
 * @param keyNamespace the namespace of the key
 * @param element      true when the key sits in, or under, an array
 */
public record ChainableField(String path, KeyNamespace keyNamespace, boolean element) {

    public ChainableField {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(keyNamespace, "keyNamespace");
    }
}

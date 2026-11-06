package io.github.aindriub.dataprism.core.engine;

import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Objects;

/**
 * What an engine says about one record it holds a reviewed declaration for: the chainable fields, and the raw tree
 * they are declared against. Holds raw values, so it is used for the length of one call and never printed.
 */
public record ChainableView(List<ChainableField> fields, JsonNode tree) {

    public ChainableView {
        fields = List.copyOf(Objects.requireNonNull(fields, "fields"));
        Objects.requireNonNull(tree, "tree");
    }

    @Override
    public String toString() {
        return "ChainableView[redacted]";
    }
}

package io.github.aindriub.dataprism.connectors.rest;

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Objects;

/**
 * What {@link ConfiguredJsonDataSourceAdapter} fetches: the raw response tree,
 * carrying the name of the source it came from.
 *
 * <p>The name travels with the value because {@link
 * io.github.aindriub.dataprism.core.ScrubbingEngine#scrub} receives only the
 * fetched object and the privacy context — not the adapter that produced it —
 * and {@link ConfiguredJsonScrubbingEngine} needs to know which source's
 * catalogue applies before it can classify a single field. See that class for
 * why a {@code Class}-keyed resolver could not answer this question on its own.
 */
record ConfiguredJsonPayload(String sourceName, ObjectNode body) {

    ConfiguredJsonPayload {
        Objects.requireNonNull(sourceName, "sourceName");
        Objects.requireNonNull(body, "body");
    }
}

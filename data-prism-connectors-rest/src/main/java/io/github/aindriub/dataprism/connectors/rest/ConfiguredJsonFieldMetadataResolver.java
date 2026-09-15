package io.github.aindriub.dataprism.connectors.rest;

import io.github.aindriub.dataprism.core.FieldMetadata;
import io.github.aindriub.dataprism.core.FieldMetadataResolver;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The field metadata for exactly one configured JSON source, dedicated to that
 * one source rather than shared.
 *
 * <p>Every other {@link FieldMetadataResolver} in this codebase resolves
 * metadata from a Java {@code Class}, because there is a real, reviewed type to
 * key on. A configuration-driven JSON source has no such type: every response
 * this source ever produces reads into the same {@link
 * com.fasterxml.jackson.databind.node.ObjectNode}, so a resolver shared across
 * several such sources could not tell them apart by the {@code type} argument
 * alone. Rather than invent a Java type per configured source to make the
 * existing {@code Class}-keyed resolvers work, one instance of this class is
 * built per source and given only that source's own catalogue; {@code type} is
 * accepted only because the interface requires it; and it is never consulted
 * for the answer to any of the interface's methods.
 *
 * <p>This is what lets {@link io.github.aindriub.dataprism.core.JsonTreeScrubbingEngine},
 * the real engine, run unmodified over a source with no compiled model at all:
 * it asks this resolver for field metadata exactly the way it asks any other
 * one, and gets back exactly the allowlisted catalogue an operator wrote in
 * {@code fields:} — nothing more, because {@link #resolve} never returns an
 * entry that was not explicitly present there.
 */
final class ConfiguredJsonFieldMetadataResolver implements FieldMetadataResolver {

    private final List<FieldMetadata> fields;

    ConfiguredJsonFieldMetadataResolver(Map<String, FieldMetadata> fields) {
        Objects.requireNonNull(fields, "fields");
        if (fields.isEmpty()) {
            throw new IllegalArgumentException("a configured JSON source needs a non-empty catalogue");
        }
        this.fields = List.copyOf(fields.values());
    }

    @Override
    public List<FieldMetadata> resolve(Class<?> type) {
        return fields;
    }

    /** Always true: this instance exists to serve exactly one reviewed catalogue. */
    @Override
    public boolean exposed(Class<?> type) {
        return true;
    }

    /**
     * Always false: the catalogue is flat by design. A configured JSON source
     * that needs to classify a nested object has not been reviewed for one, and
     * {@link io.github.aindriub.dataprism.core.JsonTreeScrubbingEngine} already
     * refuses an undescendable nested structure rather than guess at it.
     */
    @Override
    public boolean descendable(Class<?> type) {
        return false;
    }
}

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
 * accepted only because the interface requires it; and for the root catalogue
 * it is never consulted for the answer to any of the interface's methods.
 *
 * <p>A named nested catalogue (see docs/plan/tasks/60-*.md) is different: the
 * root catalogue's own {@code fields} map may declare a field whose {@link
 * FieldMetadata#valueType()}/{@link FieldMetadata#elementType()} carries one of
 * the distinct {@code Class} tokens {@link ConfiguredJsonSources} minted for
 * that source's nested catalogues. For those tokens, and only those, {@code
 * type} genuinely selects which catalogue answers -- the nested one rather than
 * the root one -- which is exactly how {@link
 * io.github.aindriub.dataprism.core.JsonTreeScrubbingEngine} and {@link
 * io.github.aindriub.dataprism.core.SourceValues} already ask any other
 * resolver to descend into a nested Java model. Core needed no change to make
 * this work: it already keys descent on {@code Class}, never on a name.
 *
 * <p>This is what lets the real engine run unmodified over a source with no
 * compiled model at all: it asks this resolver for field metadata exactly the
 * way it asks any other one, and gets back exactly the allowlisted catalogue an
 * operator wrote in YAML — nothing more, because {@link #resolve} never returns
 * an entry that was not explicitly present there.
 */
final class ConfiguredJsonFieldMetadataResolver implements FieldMetadataResolver {

    private final List<FieldMetadata> fields;
    private final Map<Class<?>, Map<String, FieldMetadata>> nestedByToken;

    ConfiguredJsonFieldMetadataResolver(ConfiguredJsonSource source) {
        Objects.requireNonNull(source, "source");
        Map<String, FieldMetadata> rootFields = source.fields();
        if (rootFields == null || rootFields.isEmpty()) {
            throw new IllegalArgumentException("a configured JSON source needs a non-empty catalogue");
        }
        this.fields = List.copyOf(rootFields.values());
        this.nestedByToken = Map.copyOf(source.nestedCatalogueResolutions());
    }

    @Override
    public List<FieldMetadata> resolve(Class<?> type) {
        Map<String, FieldMetadata> nested = nestedByToken.get(type);
        return nested == null ? fields : List.copyOf(nested.values());
    }

    /** Always true: this instance exists to serve exactly one reviewed catalogue. */
    @Override
    public boolean exposed(Class<?> type) {
        return true;
    }

    /**
     * True only for one of this source's own minted nested-catalogue tokens.
     * Never true for the root catalogue's own type, and never true for a plain
     * {@code String.class}/{@code Object.class} leaf: a configured JSON source
     * that needs to classify a nested object does so through {@code nested:},
     * not by falling back to an unreviewed guess.
     */
    @Override
    public boolean descendable(Class<?> type) {
        return nestedByToken.containsKey(type);
    }

    /**
     * The nested catalogue for one minted token, or null if {@code token} does
     * not name one of this source's nested catalogues. Package-private: used by
     * {@link ConfiguredJsonNestedLeafShapeGuard} to walk the same catalogue the
     * engine itself would descend into, without re-deriving the token mapping
     * independently.
     */
    Map<String, FieldMetadata> nestedFields(Class<?> token) {
        return nestedByToken.get(token);
    }
}

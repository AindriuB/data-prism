package io.github.aindriub.dataprism.connectors.rest;

import io.github.aindriub.dataprism.core.FieldMetadata;

import java.util.Map;
import java.util.Objects;

/**
 * One configuration-driven JSON REST source: everything {@link RestSource}
 * already states, plus what the generic-JSON mode adds on top of it.
 *
 * <p>{@code fields} is the allowlisted field classification catalogue. It is the
 * only source of classification for this source's response — there is no
 * annotated Java model behind it, so a property that is not a key of this map is
 * unclassified by construction and the engine refuses it. See
 * {@link ConfiguredJsonFieldMetadataResolver}.
 *
 * @param transport     the reviewed transport: base URL, caller-independent path
 *                       template, timeout and TLS requirement, identical in kind
 *                       to a Java-first source
 * @param modelVersion  an explicit, operator-stated tag for the response shape
 *                       this catalogue was reviewed against. Not compared to
 *                       anything at the wire, since a REST API rarely states its
 *                       own schema version in every payload; it exists so that
 *                       reclassifying a source is a deliberate edit to a named
 *                       version rather than a silent reinterpretation of the same
 *                       name
 * @param subjectField  the field in {@code fields} carrying this source's own
 *                       correlation identifier. Must name an entry in
 *                       {@code fields} marked {@code identifier}; nothing else
 *                       about it is special-cased beyond that
 * @param fields         the allowlisted catalogue, keyed by the exact JSON
 *                       property name it classifies
 * @param nestedCatalogues the named sub-catalogues this source declared under
 *                       {@code nested-catalogues:}, keyed by name, each itself
 *                       a flat allowlisted catalogue of the kind {@code
 *                       fields} is. Exposed so a consumer -- notably task 69 --
 *                       can read the source's declared nested-catalogue names
 *                       straight off this record rather than re-parsing the
 *                       YAML. Carries no {@code Class} token: that mechanism
 *                       is this module's own business, kept off this record's
 *                       public shape entirely. {@link
 *                       ConfiguredJsonFieldMetadataResolver} rebuilds the
 *                       token index it needs for {@code resolve}/{@code
 *                       descendable} from this map together with the tokens
 *                       already written into each root field's own {@code
 *                       FieldMetadata}, rather than this record carrying a
 *                       second, {@code Class}-keyed copy of the same data --
 *                       which would otherwise make {@code equals}/{@code
 *                       hashCode} differ between two parses of identical YAML
 */
public record ConfiguredJsonSource(RestSource transport, String modelVersion,
                                    String subjectField, Map<String, FieldMetadata> fields,
                                    Map<String, Map<String, FieldMetadata>> nestedCatalogues) {

    public ConfiguredJsonSource(RestSource transport, String modelVersion,
                                String subjectField, Map<String, FieldMetadata> fields) {
        this(transport, modelVersion, subjectField, fields, Map.of());
    }

    public ConfiguredJsonSource {
        Objects.requireNonNull(transport, "transport");
        if (modelVersion == null || modelVersion.isBlank()) {
            throw new IllegalArgumentException(
                    "source " + safeName(transport) + " has no model-version");
        }
        if (fields == null || fields.isEmpty()) {
            throw new IllegalArgumentException(
                    "source " + safeName(transport) + " has no fields catalogue");
        }
        fields = Map.copyOf(fields);
        nestedCatalogues = nestedCatalogues == null ? Map.of() : Map.copyOf(nestedCatalogues);
        if (subjectField == null || subjectField.isBlank()) {
            throw new IllegalArgumentException(
                    "source " + safeName(transport) + " has no subject-json-path");
        }
        FieldMetadata subject = fields.get(subjectField);
        if (subject == null || !subject.internalIdentifier()) {
            throw new IllegalArgumentException("source " + safeName(transport)
                    + " subject-json-path '" + subjectField
                    + "' does not name a field in the catalogue marked identifier: true");
        }
        long identifierCount = fields.values().stream().filter(FieldMetadata::internalIdentifier).count();
        if (identifierCount != 1) {
            throw new IllegalArgumentException("source " + safeName(transport) + " catalogue marks "
                    + identifierCount + " fields identifier: true; exactly one is required, "
                    + "matching subject-json-path");
        }
    }

    private static String safeName(RestSource transport) {
        return transport == null ? "<unknown>" : transport.name();
    }
}

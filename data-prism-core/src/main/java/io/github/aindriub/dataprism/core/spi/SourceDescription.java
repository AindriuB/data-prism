package io.github.aindriub.dataprism.core.spi;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * What a source holds, declared by its adapter and read once at startup.
 *
 * <p>Every part is opaque operator-supplied data; nothing here names a domain. The two texts are
 * checked at startup by the catalog, not here.
 *
 * @param entityTypes      entity types the source answers; empty means not declared
 * @param aliasDescription neutral text shown under an alias; nullable, a text is then generated
 * @param realDescription  text shown under real names; nullable, the alias text is then used
 * @param inputKeys        key namespaces accepted as the subject key; at most {@value #MAX_INPUT_KEYS}
 * @param cost             advisory hint; nullable
 * @param freshness        advisory hint; nullable
 * @param namespaceTypes   which entity type a read keyed by each input namespace answers; each key must be in
 *                         {@code inputKeys} and each value in {@code entityTypes}. Used by {@code follow} to name
 *                         the type of the read. A source with no declared type is read as the sentinel type
 *                         {@value #UNDECLARED_ENTITY_TYPE}; one declared type needs no mapping; several declared
 *                         types need one entry per input namespace, and a source without it is not a link target
 */
public record SourceDescription(
        Set<String> entityTypes,
        String aliasDescription,
        String realDescription,
        List<KeyNamespace> inputKeys,
        CostHint cost,
        FreshnessHint freshness,
        Map<KeyNamespace, String> namespaceTypes) {

    public static final int MAX_INPUT_KEYS = 8;

    /** The entity type a followed read carries when the source declares no entity type. */
    public static final String UNDECLARED_ENTITY_TYPE = "entity";

    private static final Pattern ENTITY_TYPE = Pattern.compile("[A-Za-z][A-Za-z0-9_-]{0,63}");

    /** Messages never carry description text or declared values. */
    public SourceDescription {
        Objects.requireNonNull(entityTypes, "entityTypes");
        Objects.requireNonNull(inputKeys, "inputKeys");
        for (String type : entityTypes) {
            if (type == null || !ENTITY_TYPE.matcher(type).matches()) {
                throw new IllegalArgumentException("entity type must match [A-Za-z][A-Za-z0-9_-]{0,63}");
            }
        }
        entityTypes = Set.copyOf(entityTypes);
        if (inputKeys.size() > MAX_INPUT_KEYS) {
            throw new IllegalArgumentException("at most " + MAX_INPUT_KEYS + " input keys");
        }
        Set<KeyNamespace> seen = new HashSet<>();
        for (KeyNamespace key : inputKeys) {
            if (key == null || !seen.add(key)) {
                throw new IllegalArgumentException("input keys must be non-null and without duplicates");
            }
        }
        inputKeys = List.copyOf(inputKeys);
        Objects.requireNonNull(namespaceTypes, "namespaceTypes");
        for (Map.Entry<KeyNamespace, String> mapped : namespaceTypes.entrySet()) {
            if (mapped.getKey() == null || !inputKeys.contains(mapped.getKey())) {
                throw new IllegalArgumentException("a mapped namespace must be one of the input keys");
            }
            if (mapped.getValue() == null || !entityTypes.contains(mapped.getValue())) {
                throw new IllegalArgumentException("a mapped type must be one of the entity types");
            }
        }
        namespaceTypes = Map.copyOf(namespaceTypes);
    }

    /** Declares no namespace-to-type mapping. */
    public SourceDescription(Set<String> entityTypes, String aliasDescription, String realDescription,
                             List<KeyNamespace> inputKeys, CostHint cost, FreshnessHint freshness) {
        this(entityTypes, aliasDescription, realDescription, inputKeys, cost, freshness, Map.of());
    }

    /** Declares nothing: the catalog generates a neutral description. */
    public static SourceDescription undeclared() {
        return new SourceDescription(Set.of(), null, null, List.of(), null, null, Map.of());
    }
}

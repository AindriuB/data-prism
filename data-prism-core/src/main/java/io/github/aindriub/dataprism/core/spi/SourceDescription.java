package io.github.aindriub.dataprism.core.spi;

import java.util.HashSet;
import java.util.List;
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
 */
public record SourceDescription(
        Set<String> entityTypes,
        String aliasDescription,
        String realDescription,
        List<KeyNamespace> inputKeys,
        CostHint cost,
        FreshnessHint freshness) {

    public static final int MAX_INPUT_KEYS = 8;

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
    }

    /** Declares nothing: the catalog generates a neutral description. */
    public static SourceDescription undeclared() {
        return new SourceDescription(Set.of(), null, null, List.of(), null, null);
    }
}

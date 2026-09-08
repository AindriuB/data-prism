package io.github.aindriub.dataprism.pseudonymisation.vocabulary;

import io.github.aindriub.dataprism.core.Text;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * One locale's word lists.
 *
 * <p>Entries are stored in canonical form, so a set written with combining
 * accents and one written with precomposed characters produce the same
 * vocabulary and therefore the same pseudonyms.
 *
 * @param localeTag BCP 47, or {@code "und"} for a set not tied to a language
 * @param script    the ISO 15924 script the entries are written in. Recorded so
 *                  a set can be checked for mixing scripts: a first name in
 *                  Latin beside a surname in Cyrillic reads as a data error to a
 *                  model, and mixed-script text is also where homoglyph
 *                  confusion lives
 */
public record NameSet(
        String id,
        int version,
        String localeTag,
        String script,
        Map<PoolKind, List<String>> pools) {

    public NameSet {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(localeTag, "localeTag");
        Objects.requireNonNull(script, "script");

        Map<PoolKind, List<String>> copy = new EnumMap<>(PoolKind.class);
        pools.forEach((kind, entries) -> copy.put(kind, entries.stream()
                .map(Text::canonical)
                .filter(e -> !e.isBlank())
                .distinct()
                .toList()));
        pools = Map.copyOf(copy);

        for (PoolKind required : List.of(PoolKind.FIRST_NAME, PoolKind.LAST_NAME)) {
            List<String> entries = pools.get(required);
            if (entries == null || entries.isEmpty()) {
                throw new IllegalArgumentException(
                        "name set " + id + " has no " + required.key());
            }
        }
    }

    public List<String> pool(PoolKind kind) {
        return pools.getOrDefault(kind, List.of());
    }

    /** This set with {@code other}'s entries appended, duplicates dropped. */
    public NameSet extendedWith(NameSet other) {
        Map<PoolKind, List<String>> merged = new EnumMap<>(PoolKind.class);
        for (PoolKind kind : PoolKind.values()) {
            List<String> combined = java.util.stream.Stream
                    .concat(pool(kind).stream(), other.pool(kind).stream())
                    .distinct()
                    .toList();
            if (!combined.isEmpty()) {
                merged.put(kind, combined);
            }
        }
        // The version moves because the entries did. Growing a pool shifts every
        // index derived from it, so an extension is a new vocabulary rather than
        // a tweak to an existing one.
        return new NameSet(id, version + 1, localeTag, script, merged);
    }
}

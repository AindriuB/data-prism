package io.github.aindriub.dataprism.quickstart.extension.identity;

import io.github.aindriub.dataprism.core.IdentityResolver;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A worked example for {@code docs/developer-guide/custom-identity-resolver.md}:
 * a deterministic cross-reference between a canonical subject and the keys
 * three unrelated sources happen to use for it, for the case where {@code
 * PassThroughIdentityResolver} does not apply because those keys genuinely
 * differ from source to source.
 *
 * <p>The mapping below is a fixed table, not a computation — every canonical
 * id, every source key and every association between them is looked up, never
 * guessed at. Nothing here does anything probabilistic: two records with a
 * similar name, or the same date of birth, are not treated as the same
 * subject unless this table already says so.
 *
 * <p><strong>This class is not registered by {@code
 * QuickstartExtensionAutoConfiguration}.</strong> The quickstart's one source
 * already keys on the subject id it is asked for, so its {@code
 * PassThroughIdentityResolver} choice stays correct; this class exists only
 * to be compiled, unit tested and read.
 */
// --8<-- [start:resolver]
public final class MappedIdentityResolver implements IdentityResolver {

    /** canonicalId -> (sourceName -> that source's own key for the subject). */
    private final Map<String, Map<String, String>> keysByCanonicalId;

    /** sourceName -> (that source's key -> canonicalId). Built once, from the same rows. */
    private final Map<String, Map<String, String>> canonicalIdBySourceKey;

    public MappedIdentityResolver(Map<String, Map<String, String>> keysByCanonicalId) {
        this.keysByCanonicalId = Map.copyOf(Objects.requireNonNull(keysByCanonicalId, "keysByCanonicalId"));
        this.canonicalIdBySourceKey = invert(this.keysByCanonicalId);
    }

    private static Map<String, Map<String, String>> invert(Map<String, Map<String, String>> byCanonicalId) {
        Map<String, Map<String, String>> bySource = new HashMap<>();
        byCanonicalId.forEach((canonicalId, sourceKeys) -> sourceKeys.forEach((sourceName, key) ->
                bySource.computeIfAbsent(sourceName, unused -> new HashMap<>()).put(key, canonicalId)));
        return bySource;
    }

    /**
     * @throws IllegalArgumentException if no row in the table has this source
     *     name and key together — an unknown key is refused rather than
     *     treated as its own, unrelated subject, so a typo or a source the
     *     table has not caught up with fails loudly instead of silently
     *     fragmenting one subject's history across two canonical ids.
     */
    @Override
    public CanonicalId resolve(SourceRef ref) {
        Objects.requireNonNull(ref, "ref");
        Map<String, String> keysForSource = canonicalIdBySourceKey.get(ref.sourceName());
        String canonicalId = keysForSource == null ? null : keysForSource.get(ref.key());
        if (canonicalId == null) {
            throw new IllegalArgumentException(
                    "no subject known for source '" + ref.sourceName() + "' key '" + ref.key() + "'");
        }
        return new CanonicalId(canonicalId);
    }

    /**
     * A source absent from the subject's row is omitted from the result, not
     * padded with a guess — the same contract {@link IdentityResolver#expand}
     * documents, so the orchestrator never queries a source that does not
     * know this subject.
     */
    @Override
    public List<SourceRef> expand(CanonicalId id, List<String> sourceNames) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(sourceNames, "sourceNames");
        Map<String, String> keysForSubject = keysByCanonicalId.getOrDefault(id.value(), Map.of());
        return sourceNames.stream()
                .filter(keysForSubject::containsKey)
                .map(sourceName -> new SourceRef(sourceName, keysForSubject.get(sourceName)))
                .toList();
    }
}
// --8<-- [end:resolver]

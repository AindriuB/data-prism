package io.github.aindriub.dataprism.pseudonymisation.vocabulary;

import java.util.List;

/**
 * The word lists a generator draws from, with an identity that changes whenever
 * the contents do.
 *
 * <p>That identity matters more than it looks. A synthetic value is chosen by
 * {@code digest mod pool.size()}, so adding one name to a pool shifts the
 * selection for a large share of subjects — quietly, and for everyone at once.
 * Under a scheme where a pseudonym is supposed to be stable for the life of an
 * investigation, that is indistinguishable from corruption.
 *
 * <p>So a vocabulary is content-addressed and pinned by the scope, exactly as
 * the signing key is. Editing a pool produces a new id, and a scope pinned to
 * the old one keeps resolving against the old pool rather than silently drifting.
 */
public interface Vocabulary {

    /**
     * Stable identity of these exact contents, e.g. {@code western-v2#3f9a1c0e}.
     * The suffix is a digest of every entry in every pool.
     */
    String id();

    String localeTag();

    /** ISO 15924, e.g. Latn, Arab, Hans, Cyrl. Decides how names are assembled. */
    String script();

    List<String> pool(PoolKind kind);
}

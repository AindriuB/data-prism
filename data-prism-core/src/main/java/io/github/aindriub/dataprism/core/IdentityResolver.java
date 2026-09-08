package io.github.aindriub.dataprism.core;

import java.util.List;

/**
 * Maps between a canonical subject and the keys each source knows it by.
 *
 * <p>The specification's worked example has three systems all returning
 * {@code customerId = 123}. Real ones do not, and that is the whole reason
 * correlation is difficult. This interface is where that difficulty is put:
 * V1 requires a key the sources already share, and anything cleverer — a
 * probabilistic match on name and date of birth, a master data service — is an
 * implementation of this and not a change to the platform.
 *
 * <p>{@link #expand} is on the critical path rather than an extra. The
 * orchestrator cannot even build its fan-out without knowing what to ask each
 * source for; a source keyed by its own reference gets that reference, not the
 * canonical id.
 *
 * <p>See docs/design-review.md §A3 for why this is an SPI rather than a matcher.
 */
public interface IdentityResolver {

    /** The canonical subject a source record belongs to. */
    CanonicalId resolve(SourceRef ref);

    /**
     * The per-source keys to fetch for a subject. A source absent from the result
     * is not queried, which is how a subject known to only some systems avoids
     * pointless calls and misleading "no data" findings.
     */
    List<SourceRef> expand(CanonicalId id, List<String> sourceNames);

    /** The platform-wide identity of a subject. Never exposed; pseudonymised first. */
    record CanonicalId(String value) {
        public CanonicalId {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("canonical id must not be blank");
            }
        }
    }

    /** One source's own key for a subject. */
    record SourceRef(String sourceName, String key) {
        public SourceRef {
            if (sourceName == null || sourceName.isBlank() || key == null || key.isBlank()) {
                throw new IllegalArgumentException("sourceName and key must not be blank");
            }
        }
    }
}

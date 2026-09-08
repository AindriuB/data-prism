package io.github.aindriub.dataprism.core;

import java.util.Objects;

/**
 * Pins how synthetic values are derived.
 *
 * <p>Every part is an input to the result, so changing any of them changes every
 * synthetic value it produces. A scope captures one of these at creation and
 * keeps it for its whole life; that is what lets a key be rotated, or a name
 * pool widened, without corrupting an investigation already in flight.
 *
 * @param vocabularyId the exact word lists in use. This is here rather than in
 *                     the generator because a name is chosen by
 *                     {@code digest mod pool size}: adding a single entry to a
 *                     pool shifts the choice for a large share of subjects, all
 *                     at once and with nothing appearing to fail. Pinning it
 *                     turns that from silent drift into a refusal
 */
public record PseudonymisationVersion(
        String algorithm, String version, String keyId, String vocabularyId) {

    /** The base algorithm, not yet pinned to a vocabulary. */
    public static final PseudonymisationVersion HMAC_SHA256_V1 =
            new PseudonymisationVersion("HmacSHA256", "v1", "dev", "");

    public PseudonymisationVersion {
        Objects.requireNonNull(algorithm, "algorithm");
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(keyId, "keyId");
        Objects.requireNonNull(vocabularyId, "vocabularyId");
    }

    public PseudonymisationVersion withVocabulary(String vocabularyId) {
        return new PseudonymisationVersion(algorithm, version, keyId, vocabularyId);
    }

    public PseudonymisationVersion withKey(String keyId) {
        return new PseudonymisationVersion(algorithm, version, keyId, vocabularyId);
    }
}

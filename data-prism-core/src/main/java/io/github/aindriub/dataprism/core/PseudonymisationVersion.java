package io.github.aindriub.dataprism.core;

import javax.crypto.Mac;
import java.security.GeneralSecurityException;
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

    /**
     * The widest offset any generator reads from a digest plus the four bytes it
     * reads there ({@code HmacSyntheticGenerator.address} reads bytes 20-23). An
     * algorithm whose MAC output is shorter than this throws deep inside a
     * generator instead of here, on whichever request happens to need those
     * bytes.
     */
    private static final int MINIMUM_MAC_LENGTH = 24;

    public PseudonymisationVersion {
        Objects.requireNonNull(algorithm, "algorithm");
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(keyId, "keyId");
        Objects.requireNonNull(vocabularyId, "vocabularyId");
        int macLength;
        try {
            macLength = Mac.getInstance(algorithm).getMacLength();
        } catch (GeneralSecurityException e) {
            throw new InvalidAlgorithmException("pseudonymisation.algorithm-unavailable", algorithm);
        }
        if (macLength < MINIMUM_MAC_LENGTH) {
            throw new InvalidAlgorithmException("pseudonymisation.algorithm-digest-too-short", algorithm);
        }
    }

    public PseudonymisationVersion withVocabulary(String vocabularyId) {
        return new PseudonymisationVersion(algorithm, version, keyId, vocabularyId);
    }

    public PseudonymisationVersion withKey(String keyId) {
        return new PseudonymisationVersion(algorithm, version, keyId, vocabularyId);
    }

    /**
     * Thrown when {@code algorithm} cannot back this record: either no provider
     * offers it, or its MAC output is narrower than {@link #MINIMUM_MAC_LENGTH},
     * which every bundled generator relies on. Never carries a key or key id —
     * the algorithm name is the only thing worth logging here.
     */
    public static final class InvalidAlgorithmException extends IllegalArgumentException {

        private final String code;
        private final String algorithm;

        InvalidAlgorithmException(String code, String algorithm) {
            super(code + ": algorithm '" + algorithm + "' cannot back a PseudonymisationVersion");
            this.code = code;
            this.algorithm = algorithm;
        }

        public String code() {
            return code;
        }

        public String algorithm() {
            return algorithm;
        }
    }
}

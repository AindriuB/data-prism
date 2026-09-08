package io.github.aindriub.dataprism.core;

import java.util.Objects;

/**
 * Pins how synthetic values are derived.
 *
 * <p>All three parts are inputs to the pseudonymisation key, so changing any of
 * them changes every synthetic value it produces. A scope captures one of these
 * at creation and keeps it for its whole life; that is what lets a key be
 * rotated without corrupting an investigation already in flight. See
 * docs/design-review.md §B4.
 */
public record PseudonymisationVersion(String algorithm, String version, String keyId) {

    public static final PseudonymisationVersion HMAC_SHA256_V1 =
            new PseudonymisationVersion("HmacSHA256", "v1", "dev");

    public PseudonymisationVersion {
        Objects.requireNonNull(algorithm, "algorithm");
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(keyId, "keyId");
    }
}

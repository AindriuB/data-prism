package io.github.aindriub.dataprism.core.link;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The link settings the codec needs (bound from configuration in tasks 254 and 255). Validated here, so a bad
 * value cannot reach the codec. Every message starts with its code and names no value.
 *
 * @param keyId     the link key id (a {@code SecretKeyProvider} id), at most 16 characters of {@code [A-Za-z0-9_-]}
 * @param lifetime  the link lifetime; empty means {@code scope}: up to the scope's current expiry
 * @param maxLength the longest accepted or issued link, in characters, 616 to 4096
 * @param bindCaller whether the caller tag is bound into the associated data
 * @param clockSkew the tolerance for link expiry and issue time, 0 to 30 seconds
 */
public record LinkSettings(
        String keyId,
        Optional<Duration> lifetime,
        int maxLength,
        boolean bindCaller,
        Duration clockSkew) {

    private static final Pattern KEY_ID = Pattern.compile("[A-Za-z0-9_-]{1," + LinkLayout.MAX_KEY_ID + "}");
    private static final Duration MAX_SKEW = Duration.ofSeconds(30);

    public LinkSettings {
        Objects.requireNonNull(keyId, "keyId");
        Objects.requireNonNull(lifetime, "lifetime");
        Objects.requireNonNull(clockSkew, "clockSkew");
        if (!validKeyId(keyId)) {
            throw new IllegalArgumentException(
                    "INVALID_LINK_LIMIT: the link key id must be 1 to 16 characters of A-Z, a-z, 0-9, _ and -");
        }
        if (lifetime.isPresent() && (lifetime.get().isZero() || lifetime.get().isNegative())) {
            throw new IllegalArgumentException("INVALID_LINK_LIFETIME: the lifetime must be positive or scope");
        }
        if (maxLength < LinkLayout.MIN_MAX_LENGTH || maxLength > LinkLayout.MAX_MAX_LENGTH) {
            throw new IllegalArgumentException(
                    "INVALID_LINK_LIMIT: max-length must be 616 to 4096 characters");
        }
        if (clockSkew.isNegative() || clockSkew.compareTo(MAX_SKEW) > 0) {
            throw new IllegalArgumentException("INVALID_LINK_LIMIT: clock-skew must be 0 to 30 seconds");
        }
    }

    static boolean validKeyId(String keyId) {
        return keyId != null && KEY_ID.matcher(keyId).matches();
    }
}

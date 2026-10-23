package io.github.aindriub.dataprism.core.link;

import io.github.aindriub.dataprism.core.crypto.LabelledHkdf;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;

/**
 * Whom the failed-attempt limiter counts: a principal within a scope. Each id is 1 to
 * {@value LinkBinding#MAX_ID_BYTES} UTF-8 bytes. Every limiter keys its own map from {@link #canonical()} under a
 * key that does not rotate with the link key (sealed-links.md section 4). {@code toString} shows no value.
 */
public record AttemptSubject(String scopeId, String principalId) {

    /** The first item of {@link #canonical()}: no other MAC input in the section 3 label table starts with it. */
    static final String ATTEMPT_LABEL = "dataprism/link/v1/attempt";

    public AttemptSubject {
        if (!validId(scopeId) || !validId(principalId)) {
            throw new IllegalArgumentException("a scope id and a principal id must each be 1 to "
                    + LinkBinding.MAX_ID_BYTES + " UTF-8 bytes");
        }
    }

    /** The subject of a binding; empty for a null binding or an id outside the bounds. Never throws. */
    public static Optional<AttemptSubject> of(LinkBinding binding) {
        if (binding == null || !binding.withinBounds()) {
            return Optional.empty();
        }
        return Optional.of(new AttemptSubject(binding.scopeId(), binding.principalId()));
    }

    private static boolean validId(String id) {
        return id != null && !id.isEmpty()
                && id.getBytes(StandardCharsets.UTF_8).length <= LinkBinding.MAX_ID_BYTES;
    }

    /** {@code lengthPrefixed(label, scopeId, principalId)}: the input an implementation keys under its own key. */
    public byte[] canonical() {
        return LabelledHkdf.lengthPrefixed(ATTEMPT_LABEL.getBytes(StandardCharsets.US_ASCII),
                scopeId.getBytes(StandardCharsets.UTF_8), principalId.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * {@link #canonical()} as lowercase hex, which is lossless, for {@code String}-keyed APIs. Never decode
     * {@code canonical()} as UTF-8 to get a string. It is a MAC input only and is never logged.
     */
    public String canonicalText() {
        return HexFormat.of().formatHex(canonical());
    }

    @Override
    public String toString() {
        return "AttemptSubject[redacted]";
    }
}

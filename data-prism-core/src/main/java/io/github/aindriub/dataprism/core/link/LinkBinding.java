package io.github.aindriub.dataprism.core.link;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Objects;

/**
 * What the authenticated session says about a link, never what the token says: the scope id, the principal id,
 * the scope's expiry and the caller token's expiry. {@code toString} shows no value.
 */
public record LinkBinding(String scopeId, String principalId, Instant scopeExpiry, Instant tokenExpiry) {

    /**
     * The most UTF-8 bytes a scope id or principal id may have: the associated data prefixes each item with 2
     * bytes.
     */
    static final int MAX_ID_BYTES = 65535;

    public LinkBinding {
        Objects.requireNonNull(scopeId, "scopeId");
        Objects.requireNonNull(principalId, "principalId");
        Objects.requireNonNull(scopeExpiry, "scopeExpiry");
        Objects.requireNonNull(tokenExpiry, "tokenExpiry");
    }

    /**
     * Whether both ids are non-empty and fit the associated-data item limit. The codec refuses or omits otherwise;
     * it never throws.
     */
    boolean withinBounds() {
        return !scopeId.isEmpty() && !principalId.isEmpty()
                && utf8Length(scopeId) <= MAX_ID_BYTES && utf8Length(principalId) <= MAX_ID_BYTES;
    }

    private static int utf8Length(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }

    @Override
    public String toString() {
        return "LinkBinding[redacted]";
    }
}

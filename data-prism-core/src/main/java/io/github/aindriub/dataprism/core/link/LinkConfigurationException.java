package io.github.aindriub.dataprism.core.link;

import java.util.Objects;

/**
 * A link setting or link key that is refused at construction. {@link #code()} is the stable machine-readable code
 * ({@code LINK_KEY_UNRESOLVED}, {@code LINK_KEY_WEAK}, {@code LINK_KEY_SHARED}, {@code INVALID_LINK_LIMIT} or
 * {@code INVALID_LINK_LIFETIME}); the message starts with {@code CODE: } and names no value, key byte or
 * reference. The cause of a provider failure is never attached.
 */
public final class LinkConfigurationException extends IllegalArgumentException {

    private static final long serialVersionUID = 1L;

    private final String code;

    LinkConfigurationException(String code, String message) {
        super(Objects.requireNonNull(code, "code") + ": " + Objects.requireNonNull(message, "message"));
        this.code = code;
    }

    /** The stable refusal code. */
    public String code() {
        return code;
    }
}

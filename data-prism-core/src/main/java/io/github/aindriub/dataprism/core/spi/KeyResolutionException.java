package io.github.aindriub.dataprism.core.spi;

import java.util.Objects;

/**
 * A resolver's failure. Carries a closed reason and nothing else: the message is a constant, there is no cause,
 * and the key is never in it.
 */
public final class KeyResolutionException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Why the resolver failed. */
    public enum Reason { UNAVAILABLE, TIMEOUT, REJECTED, TOO_MANY }

    private final Reason reason;

    public KeyResolutionException(Reason reason) {
        super("key resolution failed", null, false, false);
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    public Reason reason() {
        return reason;
    }
}

package io.github.aindriub.dataprism.core.correlation;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;

/**
 * A caller-supplied correlation id that has passed {@link CorrelationIdPolicy}.
 *
 * <p>There is no public constructor: holding one proves the value was validated.
 */
public final class ExternalCorrelationId {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final String value;
    private final String traceparent; // null in opaque mode

    ExternalCorrelationId(String value, String traceparent) {
        this.value = Objects.requireNonNull(value, "value");
        this.traceparent = traceparent;
    }

    /** Opaque mode: the header value. Traceparent mode: the 32-hex trace-id. */
    public String value() {
        return value;
    }

    /** The validated inbound traceparent; present only in traceparent mode. */
    public Optional<String> traceparent() {
        return Optional.ofNullable(traceparent);
    }

    /** Same trace-id and flags, a new non-zero parent-id; empty in opaque mode. */
    public Optional<String> childTraceparent() {
        if (traceparent == null) {
            return Optional.empty();
        }
        String[] parts = traceparent.split("-");
        String parentId;
        do {
            byte[] bytes = new byte[8];
            RANDOM.nextBytes(bytes);
            parentId = HexFormat.of().formatHex(bytes);
        } while (parentId.equals("0".repeat(16)) || parentId.equals(parts[2]));
        return Optional.of(parts[0] + "-" + parts[1] + "-" + parentId + "-" + parts[3]);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof ExternalCorrelationId other
                && value.equals(other.value)
                && Objects.equals(traceparent, other.traceparent);
    }

    @Override
    public int hashCode() {
        return Objects.hash(value, traceparent);
    }

    @Override
    public String toString() {
        return "ExternalCorrelationId[" + value + "]";
    }
}

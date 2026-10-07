package io.github.aindriub.dataprism.core.correlation;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The outcome of reading a correlation id from an inbound request. A rejected
 * outcome deliberately carries no copy of the rejected text.
 */
public final class InboundCorrelation {

    private enum State { ABSENT, REJECTED, PRESENT }

    private static final InboundCorrelation ABSENT = new InboundCorrelation(State.ABSENT, null);
    private static final InboundCorrelation REJECTED = new InboundCorrelation(State.REJECTED, null);

    private final State state;
    private final ExternalCorrelationId id;

    private InboundCorrelation(State state, ExternalCorrelationId id) {
        this.state = state;
        this.id = id;
    }

    public static InboundCorrelation absent() {
        return ABSENT;
    }

    public static InboundCorrelation rejected() {
        return REJECTED;
    }

    public static InboundCorrelation present(ExternalCorrelationId id) {
        return new InboundCorrelation(State.PRESENT, Objects.requireNonNull(id, "id"));
    }

    public static InboundCorrelation resolve(List<String> headerValues, CorrelationIdPolicy policy) {
        if (headerValues == null || headerValues.isEmpty()) {
            return ABSENT;
        }
        if (headerValues.size() > 1) {
            return REJECTED;
        }
        return policy.validate(headerValues.get(0)).map(InboundCorrelation::present).orElse(REJECTED);
    }

    public boolean isAbsent() {
        return state == State.ABSENT;
    }

    public boolean isRejected() {
        return state == State.REJECTED;
    }

    public boolean isPresent() {
        return state == State.PRESENT;
    }

    public Optional<ExternalCorrelationId> id() {
        return Optional.ofNullable(id);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof InboundCorrelation other && state == other.state && Objects.equals(id, other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(state, id);
    }

    @Override
    public String toString() {
        return "InboundCorrelation[" + state + "]";
    }
}

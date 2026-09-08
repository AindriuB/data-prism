package io.github.aindriub.dataprism.orchestration;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stops asking a source that is already failing.
 *
 * <p>Deliberately small, and deliberately not a dependency. A library that pulls
 * in a resilience framework forces it on every consumer along with its
 * configuration model and its version conflicts; this is roughly eighty lines and
 * covers the case that matters — a source that is down should cost one timeout
 * per cooldown rather than one per request. An organisation that wants windowed
 * failure rates, bulkhead metrics or half-open concurrency should decorate
 * {@code DataSourceAdapter} with its own implementation, which is why the
 * orchestrator takes the breaker rather than constructing one.
 *
 * <p>The states are the usual three. Closed passes calls through. A run of
 * failures opens it for a cooldown, during which nothing is called. After the
 * cooldown one trial is allowed: success closes it, failure re-opens it.
 */
public final class SourceCircuitBreaker {

    private final int failureThreshold;
    private final Duration cooldown;
    private final Clock clock;
    private final Map<String, State> states = new ConcurrentHashMap<>();

    public SourceCircuitBreaker(int failureThreshold, Duration cooldown, Clock clock) {
        if (failureThreshold <= 0) {
            throw new IllegalArgumentException("failureThreshold must be positive");
        }
        this.failureThreshold = failureThreshold;
        this.cooldown = Objects.requireNonNull(cooldown, "cooldown");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** A breaker that never opens, for tests and for deployments that want none. */
    public static SourceCircuitBreaker disabled() {
        return new SourceCircuitBreaker(Integer.MAX_VALUE, Duration.ZERO, Clock.systemUTC());
    }

    /** Whether this source may be called now. */
    public boolean allows(String sourceName) {
        State state = states.get(sourceName);
        if (state == null || state.openedAt == null) {
            return true;
        }
        if (Instant.now(clock).isBefore(state.openedAt.plus(cooldown))) {
            return false;
        }
        // Cooldown elapsed: let exactly one call through to find out whether the
        // source has recovered, rather than releasing the whole request at it.
        return state.trialTaken.compareAndSet(false, true);
    }

    public void record(SourceOutcome outcome) {
        State state = states.computeIfAbsent(outcome.sourceName(), name -> new State());
        synchronized (state) {
            if (outcome.failure()) {
                state.consecutiveFailures++;
                if (state.consecutiveFailures >= failureThreshold) {
                    state.openedAt = Instant.now(clock);
                    state.trialTaken.set(false);
                }
            } else if (outcome.status() != SourceOutcome.Status.CIRCUIT_OPEN) {
                // NO_DATA is an answer, not a failure: a source that legitimately
                // holds nothing for this subject must not trip its own breaker.
                state.consecutiveFailures = 0;
                state.openedAt = null;
                state.trialTaken.set(false);
            }
        }
    }

    public boolean open(String sourceName) {
        return !allows(sourceName);
    }

    private static final class State {
        private int consecutiveFailures;
        private volatile Instant openedAt;
        private final java.util.concurrent.atomic.AtomicBoolean trialTaken =
                new java.util.concurrent.atomic.AtomicBoolean();
    }
}

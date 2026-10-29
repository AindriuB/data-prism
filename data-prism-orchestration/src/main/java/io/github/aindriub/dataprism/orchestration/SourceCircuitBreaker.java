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

    /**
     * What {@link #admit} decided.
     *
     * @param allowed whether the source may be called now
     * @param trial   0 unless this admission is the single half-open trial; then a number no
     *                other admission of that source carries, which is what proves a later
     *                {@link #releaseTrial} is made by the holder and not by someone else
     */
    public record Admission(boolean allowed, long trial) {
        static final Admission CLOSED = new Admission(true, 0);
        static final Admission DENIED = new Admission(false, 0);

        /** Whether this admission holds the half-open trial. */
        public boolean holdsTrial() {
            return trial != 0;
        }
    }

    /** Whether this source may be called now. Takes the half-open trial where there is one; see {@link #admit}. */
    public boolean allows(String sourceName) {
        return admit(sourceName).allowed();
    }

    /**
     * As {@link #allows}, saying which kind of yes it was. After the cooldown one call is let
     * through to find out whether the source has recovered, rather than releasing the whole
     * request at it; that call holds the trial until an outcome is {@link #record recorded}.
     * A caller that then does not call the source must hand the trial back with
     * {@link #releaseTrial}, or no one can ever try the source again.
     */
    public Admission admit(String sourceName) {
        State state = states.get(sourceName);
        if (state == null) {
            return Admission.CLOSED;
        }
        synchronized (state) {
            Instant opened = state.openedAt;
            if (opened == null) {
                return Admission.CLOSED;
            }
            if (Instant.now(clock).isBefore(opened.plus(cooldown)) || state.trialTaken.get()) {
                return Admission.DENIED;
            }
            state.trialTaken.set(true);
            return new Admission(true, ++state.trialCount);
        }
    }

    /**
     * Hands back a trial this caller took with {@link #admit} and did not use: no failure is
     * recorded and the breaker stays open, so the next caller after the cooldown takes the trial.
     * Does nothing unless {@code admission} is the trial currently held, so it cannot end a
     * trial that belongs to another caller, nor one already ended by a recorded outcome, and it
     * does nothing for a closed breaker.
     */
    public void releaseTrial(String sourceName, Admission admission) {
        if (!admission.holdsTrial()) {
            return;
        }
        State state = states.get(sourceName);
        if (state == null) {
            return;
        }
        synchronized (state) {
            if (state.trialCount == admission.trial()) {
                state.trialTaken.set(false);
            }
        }
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
        /** Numbers the trials, so a release can be matched to the trial it was taken for. */
        private long trialCount;
        private final java.util.concurrent.atomic.AtomicBoolean trialTaken =
                new java.util.concurrent.atomic.AtomicBoolean();
    }
}

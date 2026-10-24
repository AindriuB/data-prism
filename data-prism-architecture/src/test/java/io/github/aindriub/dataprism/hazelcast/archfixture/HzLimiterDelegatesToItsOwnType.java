package io.github.aindriub.dataprism.hazelcast.archfixture;

import io.github.aindriub.dataprism.core.link.AttemptSubject;
import io.github.aindriub.dataprism.core.link.LinkAttemptLimiter;

/**
 * Negative fixture for {@code LinkArchitectureTest}: a Hazelcast limiter that holds another instance of its own
 * type and delegates to it. The call's owner is the class itself, so only the field check can fail the bypass rule.
 * Imported explicitly, never by a package scan.
 */
public final class HzLimiterDelegatesToItsOwnType implements LinkAttemptLimiter {

    private final HzLimiterDelegatesToItsOwnType other;

    public HzLimiterDelegatesToItsOwnType(HzLimiterDelegatesToItsOwnType other) {
        this.other = other;
    }

    @Override
    public LockState lockState(AttemptSubject subject) {
        return other.lockState(subject);
    }

    @Override
    public Admission recordFailure(AttemptSubject subject) {
        return other.recordFailure(subject);
    }
}

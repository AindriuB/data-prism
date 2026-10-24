package io.github.aindriub.dataprism.hazelcast.archfixture;

import io.github.aindriub.dataprism.core.link.AttemptSubject;
import io.github.aindriub.dataprism.core.link.LinkAttemptLimiter;

/**
 * Negative fixture for {@code LinkArchitectureTest}: a Hazelcast limiter that calls another limiter it holds. The
 * Hazelcast exemption covers a limiter's calls on itself only, so the bypass rule must fail on this class.
 * Imported explicitly, never by a package scan.
 */
public final class HzLimiterDelegatesToAnother implements LinkAttemptLimiter {

    private final LinkAttemptLimiter other;

    public HzLimiterDelegatesToAnother(LinkAttemptLimiter other) {
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

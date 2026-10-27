package io.github.aindriub.dataprism.hazelcast.archfixture;

import io.github.aindriub.dataprism.core.link.AttemptSubject;
import io.github.aindriub.dataprism.core.link.LinkAttemptLimiter;

/**
 * Positive fixture for {@code LinkArchitectureTest}: a limiter in a package under {@code dataprism.hazelcast},
 * the one place outside {@code core.link} that may implement {@link LinkAttemptLimiter} (task 264 will) and call
 * its methods. It is a test class, imported explicitly by the architecture test and never by a package scan.
 *
 * <p>It is sanctioned to implement the limiter, but no call is exempt from the bypass rule until task 264's
 * allow-list exists: its own {@code lockState} and {@code recordFailure} calls are violations there.
 */
public final class LinkLimiterFloorFixture implements LinkAttemptLimiter {

    @Override
    public LockState lockState(AttemptSubject subject) {
        return LockState.UNAVAILABLE;
    }

    @Override
    public Admission recordFailure(AttemptSubject subject) {
        return recordFailureAgain(subject);
    }

    private Admission recordFailureAgain(AttemptSubject subject) {
        return lockState(subject) == LockState.UNAVAILABLE ? Admission.UNAVAILABLE : Admission.OVER_LIMIT;
    }
}

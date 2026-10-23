package io.github.aindriub.dataprism.hazelcast.archfixture;

import io.github.aindriub.dataprism.core.link.AttemptSubject;
import io.github.aindriub.dataprism.core.link.LinkAttemptLimiter;
import io.github.aindriub.dataprism.core.link.LinkCodec;

import java.util.Set;

/**
 * Negative fixture for {@code LinkArchitectureTest}: a Hazelcast limiter that opens a token. The Hazelcast exemption
 * covers the limiter's own methods only, so the bypass rule must fail on this class. Imported explicitly, never by a
 * package scan.
 */
public final class HzLimiterOpensTokens implements LinkAttemptLimiter {

    private final LinkCodec codec;

    public HzLimiterOpensTokens(LinkCodec codec) {
        this.codec = codec;
    }

    @Override
    public LockState lockState(AttemptSubject subject) {
        return LockState.UNAVAILABLE;
    }

    @Override
    public Admission recordFailure(AttemptSubject subject) {
        codec.open("token", null, Set.of());
        return Admission.UNAVAILABLE;
    }
}

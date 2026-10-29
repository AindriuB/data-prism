package io.github.aindriub.dataprism.core.spi;

import java.time.Instant;

/**
 * What a resolver is told besides the key: the privacy scope, who is asking, why, and when to give up. No raw
 * record and no other key.
 */
public record ResolutionContext(String scopeId, String principalId, String purpose, Instant deadline) {

    /** Shows only the deadline: the scope, principal and purpose identify who asked and are never printed. */
    @Override
    public String toString() {
        return "ResolutionContext[deadline=" + deadline + "]";
    }
}

package io.github.aindriub.dataprism.oversight;

import java.time.Duration;
import java.time.Instant;

/** Per-caller request counter. Any {@link RuntimeException} means "unavailable"; callers fail closed. */
public interface CallerRateLimiter {

    /** @return whether this call is within the limit. A refused acquire is not counted. */
    boolean tryAcquire(String principalId, int limit, Duration window, Instant now);
}

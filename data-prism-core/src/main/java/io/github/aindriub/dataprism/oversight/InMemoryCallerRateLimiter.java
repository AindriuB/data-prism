package io.github.aindriub.dataprism.oversight;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Per-instance fixed-window counter, windows aligned to the epoch. */
public final class InMemoryCallerRateLimiter implements CallerRateLimiter {

    private record Window(long index, int count) { }

    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    @Override
    public boolean tryAcquire(String principalId, int limit, Duration window, Instant now) {
        long index = Math.floorDiv(now.toEpochMilli(), Math.max(1L, window.toMillis()));
        boolean[] admitted = {false};
        windows.compute(principalId, (k, current) -> {
            int count = current != null && current.index() == index ? current.count() : 0;
            if (count >= limit) {
                admitted[0] = false;
                return current != null && current.index() == index ? current : new Window(index, count);
            }
            admitted[0] = true;
            return new Window(index, count + 1);
        });
        return admitted[0];
    }
}

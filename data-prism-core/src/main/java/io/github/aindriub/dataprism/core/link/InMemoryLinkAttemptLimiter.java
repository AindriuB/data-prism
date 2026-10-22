package io.github.aindriub.dataprism.core.link;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A per-process {@link LinkAttemptLimiter}. Each counter key has a window start and a count. The window is
 * fixed: the first failure sets its start and later failures never move it. Whether the window has ended is
 * decided by comparing the stored start with the injected clock, never by an entry expiring. An entry past its
 * window is dropped on the next access of that key, and every {@value #SWEEP_EVERY} recordings the whole map is
 * swept, so memory follows the keys active in one window.
 */
public final class InMemoryLinkAttemptLimiter implements LinkAttemptLimiter {

    private static final int SWEEP_EVERY = 256;

    private record Entry(Instant windowStart, int count) {}

    private final int limit;
    private final Duration window;
    private final Clock clock;
    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();
    private final AtomicLong recordings = new AtomicLong();

    public InMemoryLinkAttemptLimiter(int limit, Duration window, Clock clock) {
        if (limit < 1) {
            throw new IllegalArgumentException("INVALID_LINK_ATTEMPT_LIMIT: the limit must be at least 1");
        }
        Objects.requireNonNull(window, "window");
        if (window.isZero() || window.isNegative()) {
            throw new IllegalArgumentException("INVALID_LINK_ATTEMPT_WINDOW: the window must be positive");
        }
        if (window.compareTo(Duration.ofDays(1)) > 0) {
            throw new IllegalArgumentException("INVALID_LINK_ATTEMPT_WINDOW: the window must be at most one day");
        }
        this.limit = limit;
        this.window = window;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    private boolean ended(Entry entry, Instant now) {
        return !now.isBefore(LinkLayout.plus(entry.windowStart(), window));
    }

    @Override
    public boolean locked(String counterKey) {
        Objects.requireNonNull(counterKey, "counterKey");
        Instant now = clock.instant();
        boolean[] locked = new boolean[1];
        entries.computeIfPresent(counterKey, (key, entry) -> {
            if (ended(entry, now)) {
                return null;
            }
            locked[0] = entry.count() >= limit;
            return entry;
        });
        return locked[0];
    }

    @Override
    public void recordFailure(String counterKey) {
        Objects.requireNonNull(counterKey, "counterKey");
        Instant now = clock.instant();
        entries.compute(counterKey, (key, entry) ->
                entry == null || ended(entry, now)
                        ? new Entry(now, 1)
                        : new Entry(entry.windowStart(), entry.count() == Integer.MAX_VALUE
                                ? entry.count() : entry.count() + 1));
        if (recordings.incrementAndGet() % SWEEP_EVERY == 0) {
            sweep();
        }
    }

    /** Drops every entry whose window has ended. */
    public void sweep() {
        Instant now = clock.instant();
        entries.entrySet().removeIf(e -> ended(e.getValue(), now));
    }

    /** The number of entries held, for the memory-bound test. */
    int size() {
        return entries.size();
    }

    @Override
    public String toString() {
        return "InMemoryLinkAttemptLimiter[limit=" + limit + "]";
    }
}

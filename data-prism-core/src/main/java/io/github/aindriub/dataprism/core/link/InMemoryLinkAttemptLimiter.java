package io.github.aindriub.dataprism.core.link;

import io.github.aindriub.dataprism.core.crypto.LabelledHkdf;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * A per-process {@link LinkAttemptLimiter}.
 *
 * <p>Each subject has an entry under {@code HMAC-SHA256(k, subject.canonical())} truncated to 16 bytes, where
 * {@code k} is 32 random bytes drawn per instance; no map key holds an id. An entry has a window start and a count.
 * The window is fixed, and whether it has ended is decided by comparing the stored start with the injected clock,
 * never by an entry expiring.
 *
 * <p>At most {@code maxEntries} entries are held. Slots are reserved with a non-blocking compare-and-set, no live
 * entry is ever evicted, and a subject that cannot be stored is answered {@link Admission#CEILING}.
 *
 * <p>Memory follows the keys active in one window through an expiry queue that holds exactly one marker per entry.
 * One examiner at a time (a {@code tryLock}, never a wait) looks at a bounded number of markers per call, and
 * decides inside the map's atomic update whether the entry's window has ended. There is no full sweep, no
 * {@code removeIf}, no background thread, and no queue or lock call inside a map lambda.
 */
public final class InMemoryLinkAttemptLimiter implements LinkAttemptLimiter {

    static final int DEFAULT_MAX_ENTRIES = 100_000;
    /** Heads examined by every call before its own work. */
    static final int PRE_CHECK_HEADS = 2;
    /** Further heads examined by the ceiling drain. */
    static final int DRAIN_HEADS = 16;

    /** The two points where the test constructor's probe runs, never inside a map lambda. */
    enum Stage { RESERVED, STORED }

    /** One expiry marker: an entry's key, its id and the window start it was appended with. */
    record Marker(String mapKey, long entryId, Instant windowStart) {}

    /** The last call on the calling thread, for the bound tests. */
    record CallStats(int queueOperations, int preCheckRemoved, int drainRemoved, int insertAttempts) {}

    private record Entry(long id, Instant windowStart, int count) {}

    private static final class Stats {
        int queueOperations;
        int preCheckRemoved;
        int drainRemoved;
        int insertAttempts;
    }

    /** What one insert attempt did, filled in by the map lambda and read after {@code compute} returns. */
    private static final class Attempt {
        boolean reserved;
        boolean created;
        boolean full;
        Entry result;
    }

    private enum Action { STALE, REOPENED, REMOVED, LIVE }

    private final int limit;
    private final Duration window;
    private final Clock clock;
    private final int maxEntries;
    private final byte[] hmacKey;
    private final Consumer<Stage> probe;
    private final Queue<Marker> queue;
    private final ReentrantLock examination = new ReentrantLock();
    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();
    private final AtomicInteger entryCount = new AtomicInteger();
    private final AtomicLong ids = new AtomicLong();
    private final ThreadLocal<Stats> stats = ThreadLocal.withInitial(Stats::new);

    public InMemoryLinkAttemptLimiter(int limit, Duration window, Clock clock) {
        this(limit, window, clock, DEFAULT_MAX_ENTRIES);
    }

    public InMemoryLinkAttemptLimiter(int limit, Duration window, Clock clock, int maxEntries) {
        this(limit, window, clock, maxEntries, randomKey(), stage -> { }, ConcurrentLinkedQueue::new);
    }

    InMemoryLinkAttemptLimiter(int limit, Duration window, Clock clock, int maxEntries, byte[] hmacKey,
            Consumer<Stage> probe, Supplier<Queue<Marker>> queueFactory) {
        if (limit < 1) {
            throw new LinkConfigurationException("INVALID_LINK_LIMIT", "the limit must be at least 1");
        }
        Objects.requireNonNull(window, "window");
        if (window.isZero() || window.isNegative()) {
            throw new LinkConfigurationException("INVALID_LINK_LIMIT", "the window must be positive");
        }
        if (maxEntries < 1) {
            throw new LinkConfigurationException("INVALID_LINK_LIMIT", "the entry ceiling must be at least 1");
        }
        this.limit = limit;
        this.window = window;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.maxEntries = maxEntries;
        this.hmacKey = Objects.requireNonNull(hmacKey, "hmacKey").clone();
        this.probe = Objects.requireNonNull(probe, "probe");
        this.queue = Objects.requireNonNull(queueFactory, "queueFactory").get();
    }

    private static byte[] randomKey() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return key;
    }

    private String mapKey(AttemptSubject subject) {
        byte[] mac = LabelledHkdf.hmac(hmacKey, subject.canonical());
        return HexFormat.of().formatHex(mac, 0, 16);
    }

    private boolean ended(Entry entry, Instant now) {
        return !now.isBefore(LinkLayout.plus(entry.windowStart(), window));
    }

    private boolean tryReserve() {
        int current;
        do {
            current = entryCount.get();
            if (current >= maxEntries) {
                return false;
            }
        } while (!entryCount.compareAndSet(current, current + 1));
        return true;
    }

    @Override
    public LockState lockState(AttemptSubject subject) {
        try {
            Objects.requireNonNull(subject, "subject");
            Stats call = freshStats();
            Instant now = clock.instant();
            String key = mapKey(subject);
            examine(PRE_CHECK_HEADS, false, now, call);
            Entry entry = entries.get(key);
            return entry != null && !ended(entry, now) && entry.count() >= limit
                    ? LockState.LOCKED : LockState.UNLOCKED;
        } catch (RuntimeException failure) {
            return LockState.UNAVAILABLE;
        }
    }

    @Override
    public Admission recordFailure(AttemptSubject subject) {
        try {
            Objects.requireNonNull(subject, "subject");
            return record(subject);
        } catch (RuntimeException failure) {
            return Admission.UNAVAILABLE;
        }
    }

    private Admission record(AttemptSubject subject) {
        Stats call = freshStats();
        Instant now = clock.instant();
        String key = mapKey(subject);
        examine(PRE_CHECK_HEADS, false, now, call);
        Attempt attempt = new Attempt();
        try {
            for (int round = 1; round <= 2; round++) {
                call.insertAttempts++;
                if (!attempt.reserved && !entries.containsKey(key)) {
                    attempt.reserved = tryReserve();
                    if (attempt.reserved) {
                        probe.accept(Stage.RESERVED);
                    }
                }
                attempt.created = false;
                attempt.full = false;
                entries.compute(key, (k, existing) -> {
                    if (existing == null) {
                        if (!attempt.reserved && !tryReserve()) {
                            attempt.full = true;
                            return null;
                        }
                        attempt.reserved = false;
                        attempt.created = true;
                        attempt.result = new Entry(ids.incrementAndGet(), now, 1);
                        return attempt.result;
                    }
                    attempt.result = ended(existing, now)
                            ? new Entry(existing.id(), now, 1)
                            : new Entry(existing.id(), existing.windowStart(), existing.count() == Integer.MAX_VALUE
                                    ? existing.count() : existing.count() + 1);
                    return attempt.result;
                });
                if (!attempt.full) {
                    break;
                }
                if (round == 1) {
                    examine(DRAIN_HEADS, true, now, call);
                }
            }
        } finally {
            if (attempt.reserved) {
                attempt.reserved = false;
                entryCount.decrementAndGet();
            }
        }
        if (attempt.full) {
            return Admission.CEILING;
        }
        Entry stored = attempt.result;
        if (attempt.created && !appendMarker(key, stored, call)) {
            return Admission.UNAVAILABLE;
        }
        return stored.count() <= limit ? Admission.WITHIN_LIMIT : Admission.OVER_LIMIT;
    }

    /**
     * Appends the new entry's marker. If that fails in any way the entry is rolled back by id, so no entry exists
     * without a marker, and the slot is released only if the rollback actually removed it.
     */
    private boolean appendMarker(String key, Entry stored, Stats call) {
        boolean appended;
        try {
            probe.accept(Stage.STORED);
            call.queueOperations++;
            appended = queue.offer(new Marker(key, stored.id(), stored.windowStart()));
        } catch (RuntimeException failure) {
            appended = false;
        }
        if (!appended) {
            boolean[] removed = new boolean[1];
            entries.computeIfPresent(key, (k, current) -> {
                if (current.id() == stored.id()) {
                    removed[0] = true;
                    return null;
                }
                return current;
            });
            if (removed[0]) {
                entryCount.decrementAndGet();
            }
        }
        return appended;
    }

    /**
     * Examines up to {@code maxHeads} markers at the head of the queue, under the examination lock if it is free;
     * a call that does not get the lock skips examination. The lock is released in a {@code finally}.
     */
    private void examine(int maxHeads, boolean drain, Instant now, Stats call) {
        if (!examination.tryLock()) {
            return;
        }
        try {
            for (int i = 0; i < maxHeads; i++) {
                call.queueOperations++;
                Marker head = queue.peek();
                if (head == null || !examineHead(head, drain, now, call)) {
                    return;
                }
            }
        } finally {
            examination.unlock();
        }
    }

    /** Decides one head inside the map's atomic update; returns whether to go on to the next head. */
    private boolean examineHead(Marker head, boolean drain, Instant now, Stats call) {
        Action[] action = {Action.STALE};
        Instant[] storedStart = new Instant[1];
        entries.computeIfPresent(head.mapKey(), (k, entry) -> {
            if (entry.id() != head.entryId()) {
                return entry;
            }
            if (!entry.windowStart().equals(head.windowStart())) {
                action[0] = Action.REOPENED;
                storedStart[0] = entry.windowStart();
                return entry;
            }
            if (ended(entry, now)) {
                action[0] = Action.REMOVED;
                return null;
            }
            action[0] = Action.LIVE;
            return entry;
        });
        switch (action[0]) {
            case REMOVED -> {
                entryCount.decrementAndGet();
                if (drain) {
                    call.drainRemoved++;
                } else {
                    call.preCheckRemoved++;
                }
                call.queueOperations++;
                queue.poll();
                // The ceiling drain stops as soon as one slot is freed.
                return !drain;
            }
            case STALE -> {
                call.queueOperations++;
                queue.poll();
                return true;
            }
            case REOPENED -> {
                return reappend(new Marker(head.mapKey(), head.entryId(), storedStart[0]), call);
            }
            default -> {
                if (drain || head.windowStart().isAfter(now)) {
                    return reappend(head, call);
                }
                return false;
            }
        }
    }

    /** Offers the new marker before polling the old one; a refused offer stops the examination, marker kept. */
    private boolean reappend(Marker marker, Stats call) {
        boolean offered;
        call.queueOperations++;
        try {
            offered = queue.offer(marker);
        } catch (RuntimeException failure) {
            offered = false;
        }
        if (!offered) {
            return false;
        }
        call.queueOperations++;
        queue.poll();
        return true;
    }

    private Stats freshStats() {
        Stats call = stats.get();
        call.queueOperations = 0;
        call.preCheckRemoved = 0;
        call.drainRemoved = 0;
        call.insertAttempts = 0;
        return call;
    }

    @Override
    public String toString() {
        return "InMemoryLinkAttemptLimiter[limit=" + limit + "]";
    }

    // Package-private seams for the tests; none is used in production.

    /** The number of entries held. */
    int size() {
        return entries.size();
    }

    /** The reserved-slot count, which must equal {@link #size()} when no call is in flight. */
    int entryCount() {
        return entryCount.get();
    }

    List<Marker> queueSnapshot() {
        return new ArrayList<>(queue);
    }

    int queueSize() {
        return queue.size();
    }

    /** The lock a test thread may hold to make other calls skip examination. */
    ReentrantLock examinationLock() {
        return examination;
    }

    /** The counters of the last call on the calling thread. */
    CallStats lastCall() {
        Stats call = stats.get();
        return new CallStats(call.queueOperations, call.preCheckRemoved, call.drainRemoved, call.insertAttempts);
    }

    /** The stored count of a subject, or 0 when it has no entry. */
    int countOf(AttemptSubject subject) {
        Entry entry = entries.get(mapKey(subject));
        return entry == null ? 0 : entry.count();
    }

    /** The map key of a subject, for tests that append a marker by hand. */
    String mapKeyOf(AttemptSubject subject) {
        return mapKey(subject);
    }

    /** The id of a subject's entry, or -1 when it has none. */
    long entryIdOf(AttemptSubject subject) {
        Entry entry = entries.get(mapKey(subject));
        return entry == null ? -1 : entry.id();
    }

    /** The map's keys, for the no-leak test. */
    List<String> mapKeys() {
        return new ArrayList<>(entries.keySet());
    }

    /** Asserts exactly one marker per stored entry id. Call it single-threaded. */
    void checkInvariant() {
        List<Marker> markers = queueSnapshot();
        for (Entry entry : entries.values()) {
            long found = markers.stream().filter(m -> m.entryId() == entry.id()).count();
            if (found != 1) {
                throw new AssertionError("entry " + entry.id() + " has " + found + " markers");
            }
        }
    }
}

package io.github.aindriub.dataprism.hazelcast.seal;

import javax.crypto.spec.SecretKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A bounded LRU cache of AEAD subkeys derived for other members' salts, keyed by (salt, key id, map).
 *
 * <p>An attacker who sprays distinct salts makes every read cost one HKDF. The cache therefore never grows past
 * its bound, and once it is full a new salt is admitted only by evicting the least recently used entry, at most
 * {@code bound / 8} (at least one) times per interval. Past that the admission is refused, which the sealer
 * counts and reports as an unsupported result. A restart of a few members is a few salts, far under that.
 * The interval comes from an injected clock; nothing sleeps.
 */
final class SaltCache {

    static final Duration EVICTION_INTERVAL = Duration.ofMinutes(1);

    private final int bound;
    private final Clock clock;
    private final LinkedHashMap<String, SecretKeySpec> entries;
    private final int evictionBudget;
    private Instant windowStart;
    private int evictionsLeft;

    SaltCache(int bound, Clock clock) {
        if (bound < 1) {
            throw new IllegalArgumentException("salt cache bound must be at least 1");
        }
        this.bound = bound;
        this.clock = clock;
        this.evictionBudget = Math.max(1, bound / 8);
        this.evictionsLeft = evictionBudget;
        this.windowStart = clock.instant();
        this.entries = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, SecretKeySpec> eldest) {
                return size() > SaltCache.this.bound;
            }
        };
    }

    synchronized SecretKeySpec get(String key) {
        return entries.get(key);
    }

    /** Whether a new entry may be derived and stored now. Spends one eviction when the cache is full. */
    synchronized boolean reserve() {
        if (entries.size() < bound) {
            return true;
        }
        Instant now = clock.instant();
        if (!now.isBefore(windowStart.plus(EVICTION_INTERVAL))) {
            windowStart = now;
            evictionsLeft = evictionBudget;
        }
        if (evictionsLeft > 0) {
            evictionsLeft--;
            return true;
        }
        return false;
    }

    synchronized void put(String key, SecretKeySpec subkey) {
        entries.put(key, subkey);
    }

    synchronized int size() {
        return entries.size();
    }
}

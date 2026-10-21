package io.github.aindriub.dataprism.hazelcast.seal;

import javax.crypto.spec.SecretKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A bounded LRU cache of AEAD subkeys for other members' salts, keyed by (salt, key id, map), plus a limiter on
 * how many subkeys may be derived for unknown salts per window.
 *
 * <p>Only a salt whose envelope authenticated is admitted ({@link #put}), so forged salts never enter the cache
 * and never evict a real peer's salt. Each unknown salt still costs one HKDF before it can be tried, so
 * {@link #tryDerive} allows at most a fixed number of such derivations per one-minute window, read from an
 * injected clock; beyond it the sealer refuses without deriving. The window also resets when the clock steps
 * back. Nothing sleeps.
 */
final class SaltCache {

    static final Duration WINDOW = Duration.ofMinutes(1);

    private final int bound;
    private final int derivationsPerWindow;
    private final Clock clock;
    private final LinkedHashMap<String, SecretKeySpec> entries;
    private Instant windowStart;
    private int derivationsLeft;

    SaltCache(int bound, int derivationsPerWindow, Clock clock) {
        if (bound < 1 || derivationsPerWindow < 1) {
            throw new IllegalArgumentException("salt cache bound and derivation limit must be at least 1");
        }
        this.bound = bound;
        this.derivationsPerWindow = derivationsPerWindow;
        this.clock = clock;
        this.derivationsLeft = derivationsPerWindow;
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

    /** Spends one derivation if the window allows it. */
    synchronized boolean tryDerive() {
        Instant now = clock.instant();
        if (now.isBefore(windowStart) || !now.isBefore(windowStart.plus(WINDOW))) {
            windowStart = now;
            derivationsLeft = derivationsPerWindow;
        }
        if (derivationsLeft > 0) {
            derivationsLeft--;
            return true;
        }
        return false;
    }

    /** Admits a subkey; call only after the envelope it opened has authenticated. */
    synchronized void put(String key, SecretKeySpec subkey) {
        entries.put(key, subkey);
    }

    synchronized int size() {
        return entries.size();
    }
}

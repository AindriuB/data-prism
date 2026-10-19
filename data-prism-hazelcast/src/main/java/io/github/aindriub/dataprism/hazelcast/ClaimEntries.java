package io.github.aindriub.dataprism.hazelcast;

import com.hazelcast.map.EntryProcessor;
import com.hazelcast.map.ExtendedMapEntry;

import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * The value of one id claim and the processors that change it.
 *
 * <p>The value is the approval's entry key, a NUL, and the claim's expiry in epoch milliseconds as its last writer
 * saw it. The processors never read a clock: the caller passes the time, so a re-execution gives the same result.
 * Both act only when the claim still names the caller's entry key, so a stale caller cannot touch the claim of a
 * later approval that re-used the id after the first one's scope ended.
 */
final class ClaimEntries {

    private static final char SEP = 0;

    private ClaimEntries() {
    }

    static String value(String entryKey, long expiresAtMillis) {
        return entryKey + SEP + expiresAtMillis;
    }

    private static boolean names(String value, String entryKey) {
        return value != null && value.startsWith(entryKey + SEP) && value.indexOf(SEP, entryKey.length() + 1) < 0;
    }

    /**
     * Gives the claim a lifetime of at least {@code requiredTtlMillis} from {@code nowMillis}. It only extends: a
     * claim that already lives longer is left alone, and a claim of another approval is never touched.
     */
    record Extend(String entryKey, long requiredTtlMillis, long nowMillis)
            implements EntryProcessor<String, String, Boolean> {
        @Override
        public Boolean process(Map.Entry<String, String> entry) {
            String value = entry.getValue();
            if (!names(value, entryKey)) {
                return false;
            }
            long stored;
            try {
                stored = Long.parseLong(value.substring(entryKey.length() + 1));
            } catch (NumberFormatException unreadable) {
                return false;
            }
            if (requiredTtlMillis <= stored - nowMillis) {
                return false;
            }
            ((ExtendedMapEntry<String, String>) entry).setValue(value(entryKey, nowMillis + requiredTtlMillis),
                    requiredTtlMillis, TimeUnit.MILLISECONDS);
            return true;
        }
    }

    /** Removes the claim only if it still names {@code entryKey}. */
    record Release(String entryKey) implements EntryProcessor<String, String, Boolean> {
        @Override
        public Boolean process(Map.Entry<String, String> entry) {
            if (!names(entry.getValue(), entryKey)) {
                return false;
            }
            entry.setValue(null);
            return true;
        }
    }
}

package io.github.aindriub.dataprism.hazelcast;

import com.hazelcast.map.EntryProcessor;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The value of one pending-approval entry: the ids a requester has pending, each with its expiry.
 *
 * <p>The entry has no TTL: any lifetime could drop a live id and let the cap be passed, which fails open. Retention
 * is bounded by {@link Sweep}, which removes only ids that are past their expiry by a fixed slack. Stored as a string
 * like every other value in the cluster: alternating id and expiry, NUL-separated. Approval ids hold no NUL (the approval keys already rely on that). An entry that cannot be read throws, so the call that
 * met it is refused rather than counted as empty.
 */
final class PendingSet {

    private static final String SEP = "\0";

    private PendingSet() {
    }

    static Map<String, Instant> decode(String raw) {
        Map<String, Instant> ids = new LinkedHashMap<>();
        if (raw == null || raw.isEmpty()) {
            return ids;
        }
        String[] parts = raw.split(SEP, -1);
        if (parts.length % 2 != 0) {
            throw new IllegalStateException("unreadable pending entry");
        }
        for (int i = 0; i < parts.length; i += 2) {
            ids.put(parts[i], Instant.parse(parts[i + 1]));
        }
        return ids;
    }

    static String encode(Map<String, Instant> ids) {
        StringBuilder out = new StringBuilder();
        ids.forEach((id, expiresAt) -> {
            if (out.length() > 0) {
                out.append(SEP);
            }
            out.append(id).append(SEP).append(expiresAt);
        });
        return out.toString();
    }

    /** Every id from both, each with the later expiry. */
    static String union(String left, String right) {
        Map<String, Instant> merged = decode(left);
        decode(right).forEach((id, expiresAt) -> merged.merge(id, expiresAt,
                (a, b) -> a.isAfter(b) ? a : b));
        return encode(merged);
    }

    /**
     * Adds {@code id} unless {@code cap} live ids are already there. Live means {@code createdAt} is before the
     * id's expiry; expired ids are pruned first. Returns whether the id is now recorded.
     */
    record Reserve(String id, Instant createdAt, Instant expiresAt, int cap)
            implements EntryProcessor<String, String, Boolean> {
        @Override
        public Boolean process(Map.Entry<String, String> entry) {
            Map<String, Instant> ids = decode(entry.getValue());
            ids.values().removeIf(expiry -> !createdAt.isBefore(expiry));
            if (!ids.containsKey(id) && ids.size() >= cap) {
                return false;
            }
            ids.put(id, expiresAt);
            entry.setValue(encode(ids));
            return true;
        }
    }

    /** Removes {@code id}; the entry goes when it holds no other. */
    record Release(String id) implements EntryProcessor<String, String, Boolean> {
        @Override
        public Boolean process(Map.Entry<String, String> entry) {
            if (entry.getValue() == null) {
                return false;
            }
            Map<String, Instant> ids = decode(entry.getValue());
            if (ids.remove(id) == null) {
                return false;
            }
            entry.setValue(ids.isEmpty() ? null : encode(ids));
            return true;
        }
    }

    /**
     * Removes every id whose expiry plus {@code slackMillis} is not after {@code now}, and the entry when none is
     * left. {@code now} is the sweep's, passed in: the processor reads no clock, so running it again, or on a
     * backup, changes nothing more. An id that is not past that point is never removed, and an entry that cannot be
     * read is left as it is, so the cap keeps refusing rather than guessing. Returns how many ids it removed.
     */
    record Sweep(Instant now, long slackMillis) implements EntryProcessor<String, String, Integer> {
        @Override
        public Integer process(Map.Entry<String, String> entry) {
            Map<String, Instant> ids;
            try {
                ids = decode(entry.getValue());
            } catch (RuntimeException unreadable) {
                return 0;
            }
            int before = ids.size();
            ids.values().removeIf(expiry -> !expiry.plusMillis(slackMillis).isAfter(now));
            int removed = before - ids.size();
            if (removed > 0) {
                entry.setValue(ids.isEmpty() ? null : encode(ids));
            }
            return removed;
        }
    }
}

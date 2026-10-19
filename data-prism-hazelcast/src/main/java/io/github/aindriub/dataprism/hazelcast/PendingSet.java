package io.github.aindriub.dataprism.hazelcast;

import com.hazelcast.map.EntryProcessor;
import com.hazelcast.map.ExtendedMapEntry;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * The value of one pending-approval entry: the ids a requester has pending, each with its expiry.
 *
 * <p>The entry has a TTL, set by the processor on every write, so a requester whose approvals all expired
 * undecided leaves nothing behind. Stored as a string like every other value in the cluster: alternating id and expiry, NUL-separated. Approval
 * ids hold no NUL (the approval keys already rely on that). An entry that cannot be read throws, so the call that
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
    record Reserve(String id, Instant createdAt, Instant expiresAt, int cap, long slackMillis)
            implements EntryProcessor<String, String, Boolean> {
        @Override
        public Boolean process(Map.Entry<String, String> entry) {
            Map<String, Instant> ids = decode(entry.getValue());
            ids.values().removeIf(expiry -> !createdAt.isBefore(expiry));
            if (!ids.containsKey(id) && ids.size() >= cap) {
                return false;
            }
            ids.put(id, expiresAt);
            write(entry, ids, createdAt, slackMillis);
            return true;
        }
    }

    /**
     * Writes the ids with a lifetime of the longest remaining approval, measured from {@code reference}, plus
     * {@code slackMillis}, so the entry cannot outlive its approvals by more than the slack, nor expire before one.
     */
    private static void write(Map.Entry<String, String> entry, Map<String, Instant> ids, Instant reference,
                              long slackMillis) {
        Instant latest = ids.values().stream().max(Instant::compareTo).orElse(reference);
        long ttl = Math.max(1L, Duration.between(reference, latest).toMillis()) + slackMillis;
        ((ExtendedMapEntry<String, String>) entry).setValue(encode(ids), ttl, TimeUnit.MILLISECONDS);
    }

    /**
     * Removes {@code id}; the entry goes when it holds no other. {@code reference} is the creation time of the
     * request that leaves, from which the lifetime of what remains is measured, as in {@link Reserve}.
     */
    record Release(String id, Instant reference, long slackMillis) implements EntryProcessor<String, String, Boolean> {
        @Override
        public Boolean process(Map.Entry<String, String> entry) {
            if (entry.getValue() == null) {
                return false;
            }
            Map<String, Instant> ids = decode(entry.getValue());
            if (ids.remove(id) == null) {
                return false;
            }
            if (ids.isEmpty()) {
                entry.setValue(null);
            } else {
                write(entry, ids, reference, slackMillis);
            }
            return true;
        }
    }
}

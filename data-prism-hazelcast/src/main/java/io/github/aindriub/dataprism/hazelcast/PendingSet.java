package io.github.aindriub.dataprism.hazelcast;

import com.hazelcast.map.EntryProcessor;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The value of one pending-approval entry: the ids a requester has pending, each with its expiry and the scope of the
 * approval entry that owns it.
 *
 * <p>The owner matters because an id claim can be taken over (see {@link ClaimEntries}): a paused creator that lost
 * its claim must not release a slot that the new owner of the id now relies on. {@link Reserve} by a different owner
 * takes the slot over without changing the count, and {@link Release} removes a member only for the owner it names.
 * The owner is the scope, which with the id is the approval's entry key; a null scope is the empty string.
 *
 * <p>The entry has no TTL: any lifetime could drop a live id and let the cap be passed, which fails open. Retention
 * is bounded by {@link Sweep}, which removes only ids that are past their expiry by a fixed slack. Stored as a string
 * like every other value in the cluster: id, expiry and owner in turn, NUL-separated. Approval ids and scopes hold no
 * NUL (the approval keys already rely on that). An entry that cannot be read throws, so the call that met it is
 * refused rather than counted as empty.
 */
final class PendingSet {

    private static final String SEP = "\0";

    private PendingSet() {
    }

    /** One pending approval: when it expires and which scope's entry owns the slot. */
    record Member(Instant expiresAt, String owner) {
    }

    static String ownerOf(String scopeId) {
        return scopeId == null ? "" : scopeId;
    }

    static Map<String, Member> decode(String raw) {
        Map<String, Member> ids = new LinkedHashMap<>();
        if (raw == null || raw.isEmpty()) {
            return ids;
        }
        String[] parts = raw.split(SEP, -1);
        if (parts.length % 3 != 0) {
            throw new IllegalStateException("unreadable pending entry");
        }
        for (int i = 0; i < parts.length; i += 3) {
            ids.put(parts[i], new Member(Instant.parse(parts[i + 1]), parts[i + 2]));
        }
        return ids;
    }

    static String encode(Map<String, Member> ids) {
        StringBuilder out = new StringBuilder();
        ids.forEach((id, member) -> {
            if (out.length() > 0) {
                out.append(SEP);
            }
            out.append(id).append(SEP).append(member.expiresAt()).append(SEP).append(member.owner());
        });
        return out.toString();
    }

    /** Owner of a member whose two sides disagreed after a heal: no one can release it, so it only over-counts. */
    static final String CONFLICT = "\u0001conflict";

    /**
     * Every id from both. For an id on both sides the later expiry wins; if the sides name different owners the
     * member keeps no owner ({@link #CONFLICT}), so nobody's release can remove it early. The result does not depend
     * on the order of the arguments, and the count is the union's.
     */
    static String union(String left, String right) {
        Map<String, Member> merged = decode(left);
        decode(right).forEach((id, member) -> merged.merge(id, member, (a, b) -> new Member(
                a.expiresAt().isAfter(b.expiresAt()) ? a.expiresAt() : b.expiresAt(),
                a.owner().equals(b.owner()) ? a.owner() : CONFLICT)));
        return encode(merged);
    }

    /**
     * Adds {@code id} for {@code owner} unless {@code cap} live ids are already there. Live means {@code createdAt}
     * is before the id's expiry; expired ids are pruned first. An id that is already there is taken over by
     * {@code owner} with the count unchanged, and keeps the later of the two expiries, so a creator that reserves late
     * can never shorten the slot of the approval that took its id over. The latest writer is the owner: it is the
     * only one that can release, and a stale writer that wrote last leaves a slot nobody releases, which only
     * over-counts. Returns whether the id is now recorded.
     */
    record Reserve(String id, String owner, Instant createdAt, Instant expiresAt, int cap)
            implements EntryProcessor<String, String, Boolean> {
        @Override
        public Boolean process(Map.Entry<String, String> entry) {
            Map<String, Member> ids = decode(entry.getValue());
            ids.values().removeIf(member -> !createdAt.isBefore(member.expiresAt()));
            if (!ids.containsKey(id) && ids.size() >= cap) {
                return false;
            }
            Member present = ids.get(id);
            Instant expiry = present != null && present.expiresAt().isAfter(expiresAt) ? present.expiresAt() : expiresAt;
            ids.put(id, new Member(expiry, owner));
            entry.setValue(encode(ids));
            return true;
        }
    }

    /**
     * Removes {@code id} only if {@code owner} still owns it; the entry goes when it holds no other. A caller whose
     * slot was taken over leaves the new owner's slot alone.
     */
    record Release(String id, String owner) implements EntryProcessor<String, String, Boolean> {
        @Override
        public Boolean process(Map.Entry<String, String> entry) {
            if (entry.getValue() == null) {
                return false;
            }
            Map<String, Member> ids = decode(entry.getValue());
            Member member = ids.get(id);
            if (member == null || !member.owner().equals(owner)) {
                return false;
            }
            ids.remove(id);
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
            Map<String, Member> ids;
            try {
                ids = decode(entry.getValue());
            } catch (RuntimeException unreadable) {
                return 0;
            }
            int before = ids.size();
            ids.values().removeIf(member -> !member.expiresAt().plusMillis(slackMillis).isAfter(now));
            int removed = before - ids.size();
            if (removed > 0) {
                entry.setValue(ids.isEmpty() ? null : encode(ids));
            }
            return removed;
        }
    }
}

package io.github.aindriub.dataprism.hazelcast;

import com.hazelcast.map.IMap;

/**
 * The id claims: who may create an approval with a given id, and when a claim is stale.
 *
 * <p><b>A claim is valid if and only if the approval entry it names still exists.</b> A claim has no lifetime of its
 * own and is derived from no clock: lifetimes compared across members break under clock skew, and a claim that
 * expires while its approval lives would let the id be created again. The claim's value is the approval's entry key.
 *
 * <p><b>Creating.</b> The creator {@code putIfAbsent}s the claim, then inserts the entry, then reads the claim again
 * and keeps the entry only if the claim still names it. A claim that names an entry which does not exist is stale
 * (its approval expired, its scope ended, or its creator died or is paused before the insert). It cannot be told
 * apart from a creator that is about to insert, and the ID lock cannot settle that, because a lock is only a bounded
 * wait: a paused creator loses it when its lease runs out. So safety does not rest on any lock or clock but on
 * compare-and-set and an order of steps:
 * <ol>
 *   <li>The taker marks the claim: {@code replace(id, K, K + NUL + "~" + NUL + new)}. A marked claim is held by
 *       the taker; nobody else takes it over or claims it.</li>
 *   <li>Only after marking, the taker checks that the entry {@code K} is absent. If it is present, the taker puts
 *       the claim back and refuses.</li>
 *   <li>The taker finishes with {@code replace(id, marked, new)} and then inserts its own entry.</li>
 * </ol>
 * The previous creator inserts {@code K} and only then reads the claim. If it reads before the mark, its entry
 * exists before the taker's check at step 2, so the taker refuses. If it reads after the mark, the claim no longer
 * names {@code K}, so the previous creator removes its entry and refuses. Either way at most one of the two returns
 * success. Every change to a claim is a compare-and-set, and a claim leaves {@code K} while {@code K} exists only to
 * be put back, so no entry that was returned as created ever loses its claim. A race where both back off
 * refuses both, which fails closed.
 *
 * <p><b>Sweeping.</b> A claim whose entry is absent is removed by the same marking steps (mark, check absent, remove),
 * so the sweep never removes a claim whose entry exists, and a creator that inserts at that moment loses its claim
 * check and refuses. A marked claim left behind by a member that died is resolved the same way.
 */
final class ClaimEntries {

    private static final char SEP = 0;

    private ClaimEntries() {
    }

    /** Whether the claim is held by a taker or a sweep that has not finished. */
    static boolean marked(String value) {
        return value.indexOf(SEP, value.indexOf(SEP) + 1) >= 0;
    }

    /** The entry key a claim names. Keys hold exactly one NUL, between scope and id. */
    static String named(String value) {
        int second = value.indexOf(SEP, value.indexOf(SEP) + 1);
        return second < 0 ? value : value.substring(0, second);
    }

    /** {@code intent} is the entry key the taker will name, or empty when the claim is only being swept. */
    private static String marker(String named, String intent) {
        return named + SEP + "~" + SEP + intent;
    }

    /**
     * Claims {@code id} for {@code entryKey}, taking over a stale claim. Returns false if the id is claimed by a
     * live approval, or by someone who got there first; the caller refuses it as a duplicate.
     */
    static boolean claim(IMap<String, String> claims, IMap<String, String> approvals, String id, String entryKey) {
        String held = claims.putIfAbsent(id, entryKey);
        if (held == null) {
            return true;
        }
        if (marked(held) || approvals.containsKey(held)) {
            return false;
        }
        String marker = marker(held, entryKey);
        if (!claims.replace(id, held, marker)) {
            return false;
        }
        if (approvals.containsKey(held)) {
            claims.replace(id, marker, held);
            return false;
        }
        return claims.replace(id, marker, entryKey);
    }

    /** Removes the claim only if it still names {@code entryKey} and no taker holds it. */
    static void release(IMap<String, String> claims, String id, String entryKey) {
        claims.remove(id, entryKey);
    }

    /**
     * One pass over the claims this member owns; returns how many stale claims it removed. An id whose lock is held
     * right now is skipped: its creator is running, and a claim that is only waiting for its insert must not be taken
     * for a stale one and make that creator refuse. The lease is the configured lock timeout's, like every lock. The lock only keeps the sweep off a normally running creator.
     * Safety does not rest on it (a creator whose lease ran out is handled by the claim check after its insert).
     */
    static int sweep(IMap<String, String> claims, IMap<String, String> approvals, java.time.Duration lockTimeout) {
        int removed = 0;
        for (String id : claims.localKeySet()) {
            if (!ClusterLocks.tryAcquireNow(claims, id, lockTimeout)) {
                continue;
            }
            try {
                removed += sweepOne(claims, approvals, id);
            } finally {
                ClusterLocks.release(claims, id);
            }
        }
        return removed;
    }

    private static int sweepOne(IMap<String, String> claims, IMap<String, String> approvals, String id) {
        String value = claims.get(id);
        if (value == null) {
            return 0;
        }
        String named = named(value);
        if (approvals.containsKey(named)) {
            if (marked(value)) {
                claims.replace(id, value, named);
            }
            return 0;
        }
        String marker = value;
        if (!marked(value)) {
            marker = marker(named, "");
            if (!claims.replace(id, value, marker)) {
                return 0;
            }
        }
        if (approvals.containsKey(named)) {
            claims.replace(id, marker, named);
            return 0;
        }
        return claims.remove(id, marker) ? 1 : 0;
    }
}

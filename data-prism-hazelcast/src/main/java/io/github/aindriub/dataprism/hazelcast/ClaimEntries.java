package io.github.aindriub.dataprism.hazelcast;

import com.hazelcast.map.IMap;

import java.util.Optional;

/**
 * The id claims: who may create an approval with a given id, and when a claim is stale.
 *
 * <p><b>A claim is valid if and only if the approval entry it names still exists.</b> A claim has no lifetime of its
 * own and is derived from no clock: lifetimes compared across members break under clock skew, and a claim that
 * expires while its approval lives would let the id be created again. The claim's value is the approval's entry key
 * and the random creation token of that approval ({@code entryKey NUL token}), so a claim from one creation is never
 * mistaken for the claim of a later creation of the same id in the same scope. Every change to a claim, every
 * release and every sweep compares the whole value, token included. A claim in any other form (the bare entry key
 * that earlier versions wrote) is unreadable: it is neither taken over nor released nor swept.
 *
 * <p><b>Creating.</b> The creator {@code putIfAbsent}s the claim, then inserts the entry, then reads the claim again
 * and keeps the entry only if the claim still names it. A claim that names an entry which does not exist is stale
 * (its approval expired, its scope ended, or its creator died or is paused before the insert). It cannot be told
 * apart from a creator that is about to insert, and the ID lock cannot settle that, because a lock is only a bounded
 * wait: a paused creator loses it when its lease runs out. So safety does not rest on any lock or clock but on
 * compare-and-set and an order of steps:
 * <ol>
 *   <li>The taker marks the claim: {@code replace(id, K, K + NUL + "~" + NUL + new)}, where {@code K} and {@code new} are
 *       each an entry key with its token. A marked claim is held by
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

    private static final String SEP = "\0";

    private ClaimEntries() {
    }

    /** A readable claim: the entry key and token it names, and whether a taker or a sweep holds it. */
    record Named(String entryKey, String token, boolean marked) {
        /** The unmarked value naming the same creation. */
        String plain() {
            return value(entryKey, token);
        }
    }

    /** The claim value for one creation of an approval. */
    static String value(String entryKey, String token) {
        return entryKey + SEP + token;
    }

    /**
     * The creation a claim names, or empty if the value is not in the current form. An entry key holds exactly one
     * NUL, between scope and id; the token follows; a marked claim continues with {@code NUL ~ NUL} and then either
     * nothing (a sweep) or the entry key and token of the taker.
     */
    static Optional<Named> read(String value) {
        if (value == null) {
            return Optional.empty();
        }
        String[] parts = value.split(SEP, -1);
        if (parts.length == 3 && !parts[2].isEmpty()) {
            return Optional.of(new Named(parts[0] + SEP + parts[1], parts[2], false));
        }
        boolean sweepMarker = parts.length == 5 && parts[4].isEmpty();
        boolean takeMarker = parts.length == 7 && !parts[6].isEmpty();
        if ((sweepMarker || takeMarker) && parts[3].equals("~") && !parts[2].isEmpty()) {
            return Optional.of(new Named(parts[0] + SEP + parts[1], parts[2], true));
        }
        return Optional.empty();
    }

    /** {@code intent} is the value the taker will write, or empty when the claim is only being swept. */
    private static String marker(String held, String intent) {
        return held + SEP + "~" + SEP + intent;
    }

    /**
     * Claims {@code id} for the creation {@code (entryKey, token)}, taking over a stale claim. Returns false if the
     * id is claimed by a live approval, by a claim that cannot be read, or by someone who got there first; the
     * caller refuses it as a duplicate. The caller holds the id's lock, which only bounds the wait.
     */
    static boolean claim(IMap<String, String> claims, IMap<String, String> approvals, String id, String entryKey,
                         String token) {
        String mine = value(entryKey, token);
        String held = claims.putIfAbsent(id, mine);
        if (held == null) {
            return true;
        }
        Optional<Named> current = read(held);
        if (current.isEmpty() || current.get().marked() || approvals.containsKey(current.get().entryKey())) {
            return false;
        }
        String marker = marker(held, mine);
        if (!claims.replace(id, held, marker)) {
            return false;
        }
        if (approvals.containsKey(current.get().entryKey())) {
            claims.replace(id, marker, held);
            return false;
        }
        return claims.replace(id, marker, mine);
    }

    /**
     * Removes the claim only if it still names this creation and no taker holds it. The caller holds the id's lock
     * so the call cannot wait unboundedly.
     */
    static void release(IMap<String, String> claims, String id, String entryKey, String token) {
        claims.remove(id, value(entryKey, token));
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

    /**
     * A claim in the form earlier versions wrote (a bare entry key, or one marked with {@code NUL ~ NUL}) is removed
     * once its entry is absent, by compare-and-set on the whole value. Entries of that form are unreadable and refused,
     * and no new creator takes such a claim over, so nothing races with this. Any other unreadable value is left.
     */
    private static int sweepLegacy(IMap<String, String> claims, IMap<String, String> approvals, String id,
                                   String value) {
        if (value == null) {
            return 0;
        }
        String[] parts = value.split(SEP, -1);
        boolean legacy = parts.length == 2 || (parts.length >= 4 && parts[2].equals("~"));
        if (!legacy || approvals.containsKey(parts[0] + SEP + parts[1])) {
            return 0;
        }
        return claims.remove(id, value) ? 1 : 0;
    }

    private static int sweepOne(IMap<String, String> claims, IMap<String, String> approvals, String id) {
        String value = claims.get(id);
        Optional<Named> named = read(value);
        if (named.isEmpty()) {
            return sweepLegacy(claims, approvals, id, value);
        }
        String plain = named.get().plain();
        if (approvals.containsKey(named.get().entryKey())) {
            if (named.get().marked()) {
                claims.replace(id, value, plain);
            }
            return 0;
        }
        String marker = value;
        if (!named.get().marked()) {
            marker = marker(value, "");
            if (!claims.replace(id, value, marker)) {
                return 0;
            }
        }
        if (approvals.containsKey(named.get().entryKey())) {
            claims.replace(id, marker, plain);
            return 0;
        }
        return claims.remove(id, marker) ? 1 : 0;
    }
}

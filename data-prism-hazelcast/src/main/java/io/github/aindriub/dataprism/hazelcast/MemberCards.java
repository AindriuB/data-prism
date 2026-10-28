package io.github.aindriub.dataprism.hazelcast;

import com.hazelcast.cluster.Member;
import com.hazelcast.cluster.MembershipEvent;
import com.hazelcast.cluster.MembershipListener;
import com.hazelcast.map.IMap;
import io.github.aindriub.dataprism.core.metrics.Metric;
import io.github.aindriub.dataprism.core.metrics.PrivacyMetrics;
import io.github.aindriub.dataprism.hazelcast.ClusterSecuritySettings.UnverifiedMemberAction;
import io.github.aindriub.dataprism.hazelcast.seal.ClusterKeys;
import io.github.aindriub.dataprism.hazelcast.seal.KeyCheckValue;
import io.github.aindriub.dataprism.hazelcast.seal.Opened;
import io.github.aindriub.dataprism.hazelcast.seal.Sealer;
import org.slf4j.Logger;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Member cards, the verified-member detection of docs/plan/design/cluster-security.md 2.4 (d2).
 *
 * <p>Every member that holds the cluster key publishes a card in {@code dataprism.membercard.s1}: its UUID, a format
 * version, the cluster key ids it accepts, a key check value for each configured purpose key, the highest pause
 * sequence it has accepted and a lease end. The card is a standard {@code dps1} envelope under the cluster key, bound
 * to its member by the map key (the keyed name of the member UUID), so a reader needs only the live member UUID to
 * find and open it, and a card moved to another member's key does not open. The reader enforces the lease after
 * opening. A card is refreshed once half its lease has been spent.
 *
 * <p>A joined member is <em>verified</em> if a card that authenticates, names that UUID and is inside its lease
 * exists for it. A card for a UUID that is not in the live member list is ignored. A member without a valid card
 * is counted as unverified at once; after the grace period, under {@code refuse}, the status says not serving.
 * This is detection and fail-closed, not prevention: Data Prism cannot evict a member.
 *
 * <p>The cards use a sealer with no serving gate, so a member that is not serving can still prove itself.
 *
 * <p>Nothing here logs or exposes a key, a key id's material, an address or a member UUID.
 */
public final class MemberCards {

    /** The card format this build writes and reads. */
    static final int FORMAT_VERSION = 1;

    private static final String MAGIC = "dpcard";
    private static final int MAX_LISTED = 64;

    /** What a card says. */
    record Card(UUID member, int version, List<String> acceptedKeyIds, Map<String, byte[]> keyCheckValues,
                long highestPauseSequence, Instant leaseEnd) {

        Card {
            acceptedKeyIds = List.copyOf(acceptedKeyIds);
            Map<String, byte[]> copy = new LinkedHashMap<>();
            keyCheckValues.forEach((id, value) -> copy.put(id, value.clone()));
            keyCheckValues = Collections.unmodifiableMap(copy);
        }

        byte[] encode() {
            try {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                DataOutputStream out = new DataOutputStream(bytes);
                out.writeUTF(MAGIC);
                out.writeInt(version);
                out.writeUTF(member.toString());
                out.writeLong(leaseEnd.toEpochMilli());
                out.writeLong(highestPauseSequence);
                out.writeInt(acceptedKeyIds.size());
                for (String id : acceptedKeyIds) {
                    out.writeUTF(id);
                }
                out.writeInt(keyCheckValues.size());
                for (Map.Entry<String, byte[]> entry : keyCheckValues.entrySet()) {
                    out.writeUTF(entry.getKey());
                    out.writeInt(entry.getValue().length);
                    out.write(entry.getValue());
                }
                out.flush();
                return bytes.toByteArray();
            } catch (IOException impossible) {
                throw new IllegalStateException("a member card could not be encoded");
            }
        }

        /** The card in {@code plain}, or null for anything that is not one this build can read. */
        static Card decode(byte[] plain) {
            try {
                DataInputStream in = new DataInputStream(new ByteArrayInputStream(plain));
                if (!MAGIC.equals(in.readUTF())) {
                    return null;
                }
                int version = in.readInt();
                UUID member = UUID.fromString(in.readUTF());
                Instant leaseEnd = Instant.ofEpochMilli(in.readLong());
                long pause = in.readLong();
                int accepted = in.readInt();
                if (accepted < 0 || accepted > MAX_LISTED) {
                    return null;
                }
                List<String> ids = new ArrayList<>();
                for (int i = 0; i < accepted; i++) {
                    ids.add(in.readUTF());
                }
                int checks = in.readInt();
                if (checks < 0 || checks > MAX_LISTED) {
                    return null;
                }
                Map<String, byte[]> values = new LinkedHashMap<>();
                for (int i = 0; i < checks; i++) {
                    String id = in.readUTF();
                    int length = in.readInt();
                    if (length != KeyCheckValue.LENGTH) {
                        return null;
                    }
                    byte[] value = new byte[length];
                    in.readFully(value);
                    values.put(id, value);
                }
                return in.available() == 0 ? new Card(member, version, ids, values, pause, leaseEnd) : null;
            } catch (IOException | RuntimeException unreadable) {
                return null;
            }
        }
    }

    private final PrivacyCluster cluster;
    private final ClusterKeys keys;
    private final ClusterSecuritySettings settings;
    private final Sealer sealer;
    private final ClusterSentinel sentinel;
    private final Clock clock;
    private final PrivacyMetrics metrics;
    private final Duration tickInterval;
    /** Where the status changes are logged; a seam for tests. */
    volatile Logger log;

    /** Key id to key check value, for every purpose key this member holds. */
    private final Map<String, byte[]> ownCheckValues = new LinkedHashMap<>();
    private volatile LongSupplier pauseSequence = () -> 0L;

    private final Object lock = new Object();
    private boolean started;
    private boolean closed;
    private Instant publishedAt;
    private UUID listenerId;
    private ScheduledExecutorService executor;

    /** The cards found at the latest look, by member UUID. */
    private volatile Map<UUID, Card> verified = Map.of();
    private volatile ClusterSecurityStatus status = ClusterSecurityStatus.serving(0, 0);
    /** When each member without a valid card was first seen without one. */
    private final Map<UUID, Instant> unverifiedSince = new HashMap<>();
    /** The stored text of a card that failed to open, so it is judged and counted once, not at every look. */
    private final Map<UUID, String> rejected = new HashMap<>();
    /** The (member, key id) pairs whose key check value disagreement was already counted. */
    private final Set<String> mismatchesCounted = new HashSet<>();
    private ClusterSecurityStatus lastLogged;

    MemberCards(PrivacyCluster cluster, ClusterKeys keys, ClusterSecuritySettings settings, Sealer sealer,
                ClusterSentinel sentinel, PrivacyCluster.Environment environment) {
        this.cluster = Objects.requireNonNull(cluster, "cluster");
        this.keys = Objects.requireNonNull(keys, "keys");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.sealer = Objects.requireNonNull(sealer, "sealer");
        this.sentinel = Objects.requireNonNull(sentinel, "sentinel");
        this.clock = environment.clock();
        this.metrics = environment.metrics();
        this.tickInterval = environment.tickInterval();
        this.log = environment.log();
        for (ClusterKeys.Key held : keys.held()) {
            ownCheckValues.put(held.id(), KeyCheckValue.compute(held.id(), held.material()));
        }
        ownCheckValues.put(keys.nameKeyId(), KeyCheckValue.compute(keys.nameKeyId(), keys.nameKeyMaterial()));
    }

    // ---- the contract of tasks 228 and 229 ---------------------------------------------------------------

    /** The live members that had a valid card at the latest look. */
    public Set<UUID> verifiedMembers() {
        return Set.copyOf(verified.keySet());
    }

    /** The joined members that had no valid card at the latest look. */
    public int unverifiedCount() {
        return status.unverifiedCount();
    }

    /** The cluster key ids that every verified member lists as accepted; empty when no member is verified. */
    public Set<String> acceptedKeyIdsOfAllVerified() {
        Set<String> common = null;
        for (Card card : verified.values()) {
            if (common == null) {
                common = new LinkedHashSet<>(card.acceptedKeyIds());
            } else {
                common.retainAll(card.acceptedKeyIds());
            }
        }
        return common == null ? Set.of() : Set.copyOf(common);
    }

    /** The highest pause sequence any verified member reports; zero when none does. */
    public long highestReportedPauseSequence() {
        return verified.values().stream().mapToLong(Card::highestPauseSequence).max().orElse(0L);
    }

    /** Where this member's card takes the highest pause sequence it has accepted from; zero until task 228 sets it. */
    public void reportPauseSequence(LongSupplier sequence) {
        this.pauseSequence = Objects.requireNonNull(sequence, "sequence");
    }

    /**
     * Adds a purpose key (a link key, say) whose key check value this member's card carries. A member's cards are
     * published when the member starts, so a key added after that is carried by the card published straight away.
     */
    public void addPurposeKey(String id, byte[] material) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(material, "material");
        synchronized (lock) {
            ownCheckValues.put(id, KeyCheckValue.compute(id, material));
            if (started && !closed) {
                publish(clock.instant());
            }
        }
    }

    /**
     * Registers the {@code members.unverified} gauge with {@code registry}. The member registers it with the metrics
     * it was started with; an embedder that starts a member without metrics, or the health contributor of task 286,
     * registers it with its own.
     */
    public void registerGauges(PrivacyMetrics registry) {
        Objects.requireNonNull(registry, "registry").gauge(Metric.CLUSTER_MEMBERS_UNVERIFIED,
                () -> status.unverifiedCount());
    }

    /** Whether this member serves, and why not. */
    public ClusterSecurityStatus status() {
        return status;
    }

    // ---- lifecycle -----------------------------------------------------------------------------------------

    /**
     * Publishes this member's card, starts watching membership and refreshing the card, and registers the
     * {@code members.unverified} gauge. The sentinel has been checked before this runs.
     */
    void start() {
        synchronized (lock) {
            if (started || closed) {
                return;
            }
            started = true;
            executor = Executors.newSingleThreadScheduledExecutor(task -> {
                Thread thread = new Thread(task, "dataprism-member-cards");
                thread.setDaemon(true);
                return thread;
            });
            publish(clock.instant());
            listenerId = cluster.instance().getCluster().addMembershipListener(new MembershipListener() {
                @Override
                public void memberAdded(MembershipEvent event) {
                    lookSoon();
                }

                @Override
                public void memberRemoved(MembershipEvent event) {
                    lookSoon();
                }
            });
            registerGauges(metrics);
            look(clock.instant());
            executor.scheduleWithFixedDelay(this::tick, tickInterval.toMillis(), tickInterval.toMillis(),
                    TimeUnit.MILLISECONDS);
        }
    }

    void close() {
        synchronized (lock) {
            closed = true;
            if (listenerId != null) {
                try {
                    cluster.instance().getCluster().removeMembershipListener(listenerId);
                } catch (RuntimeException ignored) {
                    // The instance may already be gone.
                }
                listenerId = null;
            }
            if (executor != null) {
                executor.shutdownNow();
            }
        }
    }

    /** A membership event: look at the cluster again, off the listener thread. */
    private void lookSoon() {
        try {
            executor.execute(() -> {
                synchronized (lock) {
                    if (!closed) {
                        look(clock.instant());
                    }
                }
            });
        } catch (RejectedExecutionException closing) {
            // Closing; nothing to look at.
        }
    }

    /**
     * One pass of the schedule: refresh this member's card if half its lease is spent, then look at every member's.
     * The schedule runs it; a test with an injected clock runs it by hand.
     */
    void tick() {
        synchronized (lock) {
            if (!started || closed) {
                return;
            }
            try {
                Instant now = clock.instant();
                if (publishedAt == null || !now.isBefore(publishedAt.plus(settings.pauseLease().dividedBy(2)))) {
                    publish(now);
                }
                look(now);
            } catch (RuntimeException unreadable) {
                // Keep the last status: the cluster call failing is the cluster's state, not a verdict on a member.
                log.debug("member cards could not be refreshed: {}", unreadable.getClass().getSimpleName());
            }
        }
    }

    /** Writes this member's card now, whatever its age. For tests that move the clock. */
    void publishNow() {
        synchronized (lock) {
            publish(clock.instant());
        }
    }

    // ---- the card of this member ------------------------------------------------------------------------

    private IMap<String, String> map() {
        return cluster.instance().getMap(PrivacyCluster.MEMBER_CARD_MAP);
    }

    private String keyOf(UUID member) {
        return cluster.keyedNames().mapKey(PrivacyCluster.MEMBER_CARD_MAP, member.toString());
    }

    private void publish(Instant now) {
        UUID self = cluster.instance().getCluster().getLocalMember().getUuid();
        Card card = new Card(self, FORMAT_VERSION, keys.held().stream().map(ClusterKeys.Key::id).toList(),
                ownCheckValues, pauseSequence.getAsLong(), now.plus(settings.pauseLease()));
        String key = keyOf(self);
        String sealed = sealer.seal(PrivacyCluster.MEMBER_CARD_MAP, key, card.encode());
        map().set(key, sealed, settings.pauseLease().toMillis(), TimeUnit.MILLISECONDS);
        publishedAt = now;
    }

    // ---- the cards of the members ------------------------------------------------------------------------

    private void look(Instant now) {
        List<Member> members = new ArrayList<>(cluster.instance().getCluster().getMembers());
        IMap<String, String> cards = map();
        Map<UUID, Card> found = new LinkedHashMap<>();
        Set<UUID> live = new HashSet<>();
        boolean keyCheckMismatch = false;
        for (Member member : members) {
            UUID uuid = member.getUuid();
            live.add(uuid);
            Card card = valid(uuid, cards.get(keyOf(uuid)), now);
            if (card == null) {
                unverifiedSince.putIfAbsent(uuid, now);
                continue;
            }
            unverifiedSince.remove(uuid);
            found.put(uuid, card);
            keyCheckMismatch |= disagrees(uuid, card);
        }
        unverifiedSince.keySet().retainAll(live);
        rejected.keySet().retainAll(live);
        mismatchesCounted.removeIf(pair -> live.stream().noneMatch(uuid -> pair.startsWith(uuid + "/")));
        verified = Map.copyOf(found);

        boolean sentinelMismatch = sentinelMismatch();
        int unverified = members.size() - found.size();
        boolean overGrace = unverifiedSince.values().stream()
                .anyMatch(since -> !now.isBefore(since.plus(settings.unverifiedMemberGrace())));
        ClusterSecurityStatus next;
        if (sentinelMismatch) {
            next = ClusterSecurityStatus.notServing(ClusterSecurityStatus.Reason.SENTINEL_MISMATCH,
                    members.size(), unverified);
        } else if (keyCheckMismatch) {
            next = ClusterSecurityStatus.notServing(ClusterSecurityStatus.Reason.KEY_CHECK_MISMATCH,
                    members.size(), unverified);
        } else if (overGrace && settings.unverifiedMemberAction() == UnverifiedMemberAction.REFUSE) {
            next = ClusterSecurityStatus.notServing(ClusterSecurityStatus.Reason.UNVERIFIED_MEMBER,
                    members.size(), unverified);
        } else {
            next = ClusterSecurityStatus.serving(members.size(), unverified);
        }
        status = next;
        report(next, overGrace);
    }

    /** The card in {@code stored} if it is one of {@code member}'s, inside its lease; otherwise null. */
    private Card valid(UUID member, String stored, Instant now) {
        if (stored == null) {
            rejected.remove(member);
            return null;
        }
        if (stored.equals(rejected.get(member))) {
            return null;
        }
        Opened opened = sealer.open(PrivacyCluster.MEMBER_CARD_MAP, keyOf(member), stored);
        if (opened.outcome() != Opened.Outcome.AUTHENTIC) {
            // The sealer has counted it; remember the text so the same garbage is not counted at every look.
            rejected.put(member, stored);
            return null;
        }
        Card card = Card.decode(opened.plaintext());
        if (card == null || card.version() != FORMAT_VERSION || !card.member().equals(member)) {
            rejected.put(member, stored);
            return null;
        }
        rejected.remove(member);
        return now.isBefore(card.leaseEnd()) ? card : null;
    }

    /** Whether the card holds a key check value that differs from this member's for a key id both hold. */
    private boolean disagrees(UUID member, Card card) {
        boolean differs = false;
        for (Map.Entry<String, byte[]> entry : card.keyCheckValues().entrySet()) {
            byte[] own = ownCheckValues.get(entry.getKey());
            if (own != null && !MessageDigest.isEqual(own, entry.getValue())) {
                differs = true;
                if (mismatchesCounted.add(member + "/" + entry.getKey())) {
                    try {
                        metrics.increment(Metric.CLUSTER_SEAL_TAMPER);
                    } catch (RuntimeException ignored) {
                        // Metrics never change an outcome.
                    }
                }
            }
        }
        return differs;
    }

    private boolean sentinelMismatch() {
        try {
            ClusterSentinel.Outcome outcome = sentinel.read();
            if (outcome == ClusterSentinel.Outcome.ABSENT) {
                // Deleted by someone: write it back under the current key, then judge what is there.
                outcome = sentinel.ensure();
            }
            return outcome == ClusterSentinel.Outcome.MISMATCH;
        } catch (RuntimeException unreadable) {
            return false;
        }
    }

    /** One line per change of state, by constant text: no member, key or address. */
    private void report(ClusterSecurityStatus next, boolean overGrace) {
        ClusterSecurityStatus previous = lastLogged;
        lastLogged = next;
        if (next.equals(previous) || (previous == null && next.serving() && next.unverifiedCount() == 0)) {
            return;
        }
        if (next.unverifiedCount() > 0 || !next.serving()) {
            log.warn("cluster.members.unverified: {} joined member(s) have no valid member card (grace over: {}); {}",
                    next.unverifiedCount(), overGrace, next.text());
        } else {
            log.info("cluster.members.unverified: every joined member has a valid member card; {}", next.text());
        }
    }

    /** The key check value of this member's key under {@code id}, for tests; null if it holds no such key. */
    byte[] ownCheckValue(String id) {
        byte[] value = ownCheckValues.get(id);
        return value == null ? null : value.clone();
    }

    /** Seals {@code card} for {@code member} the way a member does, for tests that plant cards. */
    String sealForTest(UUID member, Card card) {
        return sealer.seal(PrivacyCluster.MEMBER_CARD_MAP, keyOf(member), card.encode());
    }

    /** The map key of {@code member}'s card, for tests. */
    String keyForTest(UUID member) {
        return keyOf(member);
    }
}

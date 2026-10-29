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
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
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

    private static final String DECLARATIONS_CONTEXT_KEY = "dataprism.cluster.card.declarations";

    /**
     * What the one card of a member says, shared by every wrapper of its instance. The card describes the member, not
     * a wrapper: it carries the key check values of all live wrappers and the highest pause sequence any of them has
     * reported, so a refresh by either wrapper writes the same content. Kept in the instance's user context.
     */
    static final class Declarations {
        final Set<MemberCards> live = ConcurrentHashMap.newKeySet();
        /** Changes whenever the declared content changes; a write that started under an older value is stale. */
        final AtomicLong generation = new AtomicLong();
        /** The highest pause sequence ever reported by any wrapper: it does not go down when a reporter closes. */
        final AtomicLong highestPause = new AtomicLong();

        Map<String, byte[]> checks() {
            Map<String, byte[]> union = new LinkedHashMap<>();
            for (MemberCards wrapper : live) {
                union.putAll(wrapper.declaredChecks());
            }
            return union;
        }

        long pause() {
            long highest = highestPause.get();
            for (MemberCards wrapper : live) {
                highest = Math.max(highest, wrapper.pauseSequence.getAsLong());
            }
            final long candidate = highest;
            return highestPause.accumulateAndGet(candidate, Math::max);
        }
    }

    @SuppressWarnings("unchecked")
    private Declarations declarations() {
        return (Declarations) cluster.instance().getUserContext().computeIfAbsent(DECLARATIONS_CONTEXT_KEY,
                key -> new Declarations());
    }

    private Map<String, byte[]> declaredChecks() {
        synchronized (state) {
            Map<String, byte[]> copy = new LinkedHashMap<>();
            ownCheckValues.forEach((id, value) -> copy.put(id, value.clone()));
            return copy;
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
    /** Runs at the start of every look; a seam for tests. */
    volatile Runnable lookFault = () -> { };
    /** Runs in a card write after the card is built and before it is written; a seam for tests. */
    volatile Runnable afterCardBuilt = () -> { };

    /** The members whose rejected card text is remembered, for tests. */
    Set<UUID> rejectedMembers() {
        synchronized (state) {
            return Set.copyOf(rejected.keySet());
        }
    }

    /** How many (member, key) disagreements are remembered as counted, for tests. */
    int mismatchesCountedSize() {
        synchronized (state) {
            return mismatchesCounted.size();
        }
    }

    int sentinelWritesStarted() {
        return sentinel.writesStarted();
    }

    boolean sentinelWriteOutstanding() {
        return sentinel.writeOutstanding();
    }

    /** Key id to key check value, for every purpose key this member holds. */
    private final Map<String, byte[]> ownCheckValues = new LinkedHashMap<>();
    private volatile LongSupplier pauseSequence = () -> 0L;

    /**
     * Guards the in-memory judgement only. No cluster call is made while it is held: a member that locks a card key
     * must not be able to stall the refresh, the status or {@link #close()}.
     */
    private final Object state = new Object();
    private final AtomicBoolean started = new AtomicBoolean();
    private volatile boolean running;
    private volatile boolean closed;
    private Instant publishedAt;
    private UUID listenerId;
    private ScheduledExecutorService executor;
    private final Duration writeTimeout;
    /** When the latest look finished, and when the latest card write succeeded, on the injected clock. */
    private volatile Instant lastLookAt;
    private volatile Instant lastPublishAt;
    /** The first look of an instance judges the members already there: they get no grace. */
    private boolean firstLook = true;
    private final AtomicInteger writes = new AtomicInteger();
    /** True from the start of a card write until the owner has completed it, however long the caller waited. */
    private final AtomicBoolean inFlight = new AtomicBoolean();

    /** How many card writes were started, for tests. */
    int writesStarted() {
        return writes.get();
    }

    /** Whether a card write has been started and has not completed at the owner, for tests. */
    boolean writeOutstanding() {
        return inFlight.get();
    }

    /** The cards found at the latest look, by member UUID. */
    private volatile Map<UUID, Card> verified = Map.of();
    /** Not serving until a look has judged the cluster serving: an unstarted wrapper is not a serving owner. */
    private volatile ClusterSecurityStatus status =
            ClusterSecurityStatus.notServing(ClusterSecurityStatus.Reason.CARDS_UNAVAILABLE, 0, 0);
    /** When each member without a valid card was first seen without one. */
    private Instant unverifiedSince;
    /** The first look of an unbroken run of looks that found no unverified member, while the clock is running. */
    private Instant clearSince;
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
        this.writeTimeout = environment.cardWriteTimeout();
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
        synchronized (state) {
            ownCheckValues.put(id, KeyCheckValue.compute(id, material));
            // Due at the next tick even if the write below fails.
            publishedAt = null;
        }
        declarations().generation.incrementAndGet();
        if (running && !closed) {
            try {
                publish(clock.instant());
            } catch (RuntimeException failed) {
                log.debug("member card could not be published: {}", failed.getClass().getSimpleName());
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
        ClusterSecurityStatus current = status;
        if (current.serving() && running) {
            // Unknown is not serving: a look or a card write that has not finished within a lease means this member
            // can neither see the others nor prove itself.
            Instant now = clock.instant();
            if (lapsed(lastLookAt, now) || lapsed(lastPublishAt, now)) {
                return ClusterSecurityStatus.notServing(ClusterSecurityStatus.Reason.CARDS_UNAVAILABLE,
                        current.memberCount(), current.unverifiedCount());
            }
        }
        return current;
    }

    private boolean lapsed(Instant at, Instant now) {
        return at == null || !now.isBefore(at.plus(settings.pauseLease()));
    }

    // ---- lifecycle -----------------------------------------------------------------------------------------

    /**
     * Publishes this member's card, starts watching membership and refreshing the card, and registers the
     * {@code members.unverified} gauge. The sentinel has been checked before this runs.
     */
    void start() {
        if (closed || !started.compareAndSet(false, true)) {
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "dataprism-member-cards");
            thread.setDaemon(true);
            return thread;
        });
        declarations().live.add(this);
        publish(clock.instant(), startupWriteBound());
        UUID id = cluster.instance().getCluster().addMembershipListener(new MembershipListener() {
            @Override
            public void memberAdded(MembershipEvent event) {
                lookSoon();
            }

            @Override
            public void memberRemoved(MembershipEvent event) {
                lookSoon();
            }
        });
        synchronized (state) {
            listenerId = id;
        }
        registerGauges(metrics);
        look(clock.instant());
        running = true;
        executor.scheduleWithFixedDelay(this::tick, tickInterval.toMillis(), tickInterval.toMillis(),
                TimeUnit.MILLISECONDS);
    }

    void close() {
        closed = true;
        running = false;
        UUID id;
        synchronized (state) {
            id = listenerId;
            listenerId = null;
            status = ClusterSecurityStatus.notServing(ClusterSecurityStatus.Reason.CARDS_UNAVAILABLE,
                    status.memberCount(), status.unverifiedCount());
        }
        if (id != null) {
            try {
                cluster.instance().getCluster().removeMembershipListener(id);
            } catch (RuntimeException ignored) {
                // The instance may already be gone.
            }
        }
        try {
            Declarations declarations = declarations();
            declarations.live.remove(this);
            declarations.generation.incrementAndGet();
        } catch (RuntimeException ignored) {
            // The instance may already be gone.
        }
        if (executor != null) {
            // Interrupts a card write that is waiting on a key somebody else has locked.
            executor.shutdownNow();
        }
    }

    /** A membership event: look at the cluster again, off the listener thread. */
    private void lookSoon() {
        try {
            executor.execute(() -> {
                if (!closed) {
                    look(clock.instant());
                }
            });
        } catch (RejectedExecutionException closing) {
            // Closing; nothing to look at.
        }
    }

    /**
     * One pass of the schedule: refresh this member's card if half its lease is spent, then look at every member's.
     * The schedule runs it; a test with an injected clock runs it by hand. Neither step can run longer than the
     * write timeout plus the reads, and a step that fails leaves the member not serving, never with its old status.
     */
    void tick() {
        if (!running || closed) {
            return;
        }
        Instant now = clock.instant();
        boolean due;
        synchronized (state) {
            due = publishedAt == null || !now.isBefore(publishedAt.plus(settings.pauseLease().dividedBy(2)));
        }
        if (due) {
            try {
                publish(now);
            } catch (RuntimeException unwritten) {
                // The old card stays until its lease ends; once a lease has gone by without a write, status() closes.
                log.debug("member card could not be refreshed: {}", unwritten.getClass().getSimpleName());
            }
        }
        look(now);
    }

    /** Writes this member's card now, whatever its age. For tests that move the clock. */
    void publishNow() {
        publish(clock.instant());
    }

    // ---- the card of this member ------------------------------------------------------------------------

    /** Any value can be stored under a card key by a member without the key, so values are read as objects. */
    private IMap<String, Object> map() {
        return cluster.instance().getMap(PrivacyCluster.MEMBER_CARD_MAP);
    }

    private String keyOf(UUID member) {
        return cluster.keyedNames().mapKey(PrivacyCluster.MEMBER_CARD_MAP, member.toString());
    }

    /** How long the first write of this member may take as it starts; see {@link ClusterSentinel#startupBound()}. */
    Duration startupWriteBound() {
        return sentinel.startupBound();
    }

    private void publish(Instant now) {
        publish(now, writeTimeout);
    }

    /** Writes the card, waiting at most {@code bound} for a key somebody else may have locked. */
    private void publish(Instant now, Duration bound) {
        UUID self = cluster.instance().getCluster().getLocalMember().getUuid();
        Declarations declarations = declarations();
        long generation = declarations.generation.get();
        Map<String, byte[]> checks = declarations.checks();
        if (checks.isEmpty()) {
            // Not yet joined (a card written before start): at least this wrapper's own.
            checks = declaredChecks();
        }
        Card card = new Card(self, FORMAT_VERSION, keys.held().stream().map(ClusterKeys.Key::id).toList(),
                checks, declarations.pause(), now.plus(settings.pauseLease()));
        afterCardBuilt.run();
        String key = keyOf(self);
        String sealed = sealer.seal(PrivacyCluster.MEMBER_CARD_MAP, key, card.encode());
        // A write to a locked key stays queued at the owner after the caller stopped waiting: start no second one.
        if (!inFlight.compareAndSet(false, true)) {
            throw new IllegalStateException("a member card write is still outstanding");
        }
        CompletionStage<Void> write;
        try {
            write = map().setAsync(key, sealed, settings.pauseLease().toMillis(), TimeUnit.MILLISECONDS);
        } catch (RuntimeException notStarted) {
            inFlight.set(false);
            throw notStarted;
        }
        writes.incrementAndGet();
        write.whenComplete((done, failure) -> inFlight.set(false));
        ClusterSentinel.bounded(write, bound);
        synchronized (state) {
            lastPublishAt = now;
            // A key added while this card was being written is not in it, and this write may have replaced a newer
            // card: the next tick must publish again.
            publishedAt = generation == declarations.generation.get() ? now : null;
        }
    }

    // ---- the cards of the members ------------------------------------------------------------------------

    /** What one look read from the cluster, before anything is judged. */
    private record Snapshot(List<UUID> members, Map<UUID, Object> stored, ClusterSentinel.Outcome sentinel,
                            Map<String, byte[]> declared) {
    }

    /**
     * Reads every member's card and the sentinel, then judges them. Any failure to read leaves the member not
     * serving until a look succeeds: the last status is never kept on a failure.
     */
    private void look(Instant now) {
        try {
            lookFault.run();
            Snapshot snapshot = read();
            synchronized (state) {
                judge(snapshot, now);
            }
        } catch (RuntimeException unreadable) {
            log.debug("member cards could not be read: {}", unreadable.getClass().getSimpleName());
            synchronized (state) {
                ClusterSecurityStatus previous = status;
                status = ClusterSecurityStatus.notServing(ClusterSecurityStatus.Reason.CARDS_UNAVAILABLE,
                        previous.memberCount(), previous.unverifiedCount());
            }
        }
    }

    /** No lock is held: the reads and the write of an absent sentinel go to the cluster. */
    private Snapshot read() {
        List<UUID> members = cluster.instance().getCluster().getMembers().stream().map(Member::getUuid).toList();
        IMap<String, Object> cards = map();
        Map<UUID, Object> stored = new LinkedHashMap<>();
        for (UUID member : members) {
            stored.put(member, cards.get(keyOf(member)));
        }
        ClusterSentinel.Outcome outcome = sentinel.read();
        if (outcome == ClusterSentinel.Outcome.ABSENT) {
            // Deleted by someone: write it back under the current key, then judge what is there.
            outcome = sentinel.ensure();
        }
        // Read here, with no lock held: it takes the state lock of every wrapper of the instance in turn.
        return new Snapshot(members, stored, outcome, declarations().checks());
    }

    private void judge(Snapshot snapshot, Instant now) {
        if (closed) {
            return;
        }
        Map<UUID, Card> found = new LinkedHashMap<>();
        Set<UUID> live = new HashSet<>(snapshot.members());
        boolean keyCheckMismatch = false;
        boolean anyMissing = false;
        for (UUID uuid : snapshot.members()) {
            Card card = valid(uuid, snapshot.stored().get(uuid), now);
            if (card == null) {
                anyMissing = true;
                continue;
            }
            found.put(uuid, card);
            keyCheckMismatch |= disagrees(uuid, card, snapshot.declared());
        }
        // One clock for the cluster as this instance sees it, not one per member UUID (OD-W6-8). It starts when the
        // view goes from no unverified member to one, and returns to null only after this instance has seen none,
        // look after look, for at least the grace (OD-W6-9): a brief absence, or a rejoin with a fresh UUID, leaves
        // the running clock alone. Members already there before this instance has served once get no grace
        // (OD-W6-7): the clock starts already lapsed. A failed look touches neither value.
        if (anyMissing) {
            clearSince = null;
            if (unverifiedSince == null) {
                unverifiedSince = firstLook ? Instant.MIN : now;
            }
        } else if (unverifiedSince != null) {
            if (clearSince == null) {
                clearSince = now;
            }
            if (!now.isBefore(clearSince.plus(settings.unverifiedMemberGrace()))) {
                unverifiedSince = null;
                clearSince = null;
            }
        }
        // Nothing is remembered about a member that has left: an outsider with a fresh UUID each time must not grow
        // these without bound.
        rejected.keySet().retainAll(live);
        mismatchesCounted.removeIf(pair -> live.stream().noneMatch(uuid -> pair.startsWith(uuid + "/")));
        verified = Map.copyOf(found);

        boolean sentinelMismatch = snapshot.sentinel() == ClusterSentinel.Outcome.MISMATCH;
        int members = snapshot.members().size();
        int unverified = members - found.size();
        boolean overGrace = anyMissing && unverifiedSince != null
                && !now.isBefore(unverifiedSince.plus(settings.unverifiedMemberGrace()));
        ClusterSecurityStatus next;
        if (sentinelMismatch) {
            next = ClusterSecurityStatus.notServing(ClusterSecurityStatus.Reason.SENTINEL_MISMATCH,
                    members, unverified);
        } else if (keyCheckMismatch) {
            next = ClusterSecurityStatus.notServing(ClusterSecurityStatus.Reason.KEY_CHECK_MISMATCH,
                    members, unverified);
        } else if (overGrace && settings.unverifiedMemberAction() == UnverifiedMemberAction.REFUSE) {
            next = ClusterSecurityStatus.notServing(ClusterSecurityStatus.Reason.UNVERIFIED_MEMBER,
                    members, unverified);
        } else {
            next = ClusterSecurityStatus.serving(members, unverified);
        }
        status = next;
        if (next.serving()) {
            firstLook = false;
        }
        lastLookAt = now;
        report(next, overGrace);
    }

    /** The card in {@code stored} if it is one of {@code member}'s, inside its lease; otherwise null. */
    private Card valid(UUID member, Object value, Instant now) {
        if (!(value instanceof String stored)) {
            // Absent, or something a member without the key stored that is not even text: no card.
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
        // The reader's clock may run ahead of the writer's by up to the skew tolerance.
        return now.isBefore(card.leaseEnd().plus(settings.clockSkewTolerance())) ? card : null;
    }

    /** Whether the card holds a key check value that differs from this member's for a key id both hold. */
    private boolean disagrees(UUID member, Card card, Map<String, byte[]> declared) {
        boolean differs = false;
        for (Map.Entry<String, byte[]> entry : card.keyCheckValues().entrySet()) {
            byte[] own = declared.get(entry.getKey());
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

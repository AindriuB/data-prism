package io.github.aindriub.dataprism.hazelcast;

import com.hazelcast.cluster.Member;
import com.hazelcast.cluster.MembershipEvent;
import com.hazelcast.cluster.MembershipListener;
import com.hazelcast.core.HazelcastInstance;
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
import java.util.concurrent.CompletableFuture;
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
 * sequence it has accepted, a per-instance card sequence, the digest of the pause document it holds (or a marker for
 * no document) and a lease end. A card is written when that digest changes and at half a lease, never two at once
 * for an instance; the instance tracker in {@link Declarations} reads the cards of the other members for the pause
 * document's freshness gate. The card is a standard {@code dps1} envelope under the cluster key, bound
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
    static final int FORMAT_VERSION = 2;

    private static final String MAGIC = "dpcard";
    private static final int MAX_LISTED = 64;

    /**
     * What a card says. Card v2 adds the per-instance {@code cardSequence} and the {@code pauseDigest} of the
     * instance's main pause document; a null digest is the distinct no-document marker (R7).
     */
    record Card(UUID member, int version, List<String> acceptedKeyIds, Map<String, byte[]> keyCheckValues,
                long highestPauseSequence, long cardSequence, PauseDocument.Digest pauseDigest, Instant leaseEnd) {

        Card {
            acceptedKeyIds = List.copyOf(acceptedKeyIds);
            Map<String, byte[]> copy = new LinkedHashMap<>();
            keyCheckValues.forEach((id, value) -> copy.put(id, value.clone()));
            keyCheckValues = Collections.unmodifiableMap(copy);
        }

        /** A card with sequence zero and no document: for tests that plant a card of a peer. */
        Card(UUID member, int version, List<String> acceptedKeyIds, Map<String, byte[]> keyCheckValues,
             long highestPauseSequence, Instant leaseEnd) {
            this(member, version, acceptedKeyIds, keyCheckValues, highestPauseSequence, 0L, null, leaseEnd);
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
                out.writeLong(cardSequence);
                if (pauseDigest == null) {
                    out.writeByte(0);
                } else {
                    out.writeByte(1);
                    out.write(pauseDigest.bytes());
                }
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
                long cardSequence = in.readLong();
                int presence = in.readByte();
                PauseDocument.Digest digest = null;
                if (presence == 1) {
                    byte[] bytes = new byte[PauseDocument.Digest.LENGTH];
                    in.readFully(bytes);
                    digest = new PauseDocument.Digest(bytes);
                } else if (presence != 0) {
                    return null;
                }
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
                return in.available() == 0
                        ? new Card(member, version, ids, values, pause, cardSequence, digest, leaseEnd) : null;
            } catch (IOException | RuntimeException unreadable) {
                return null;
            }
        }
    }

    private static final String DECLARATIONS_CONTEXT_KEY = "dataprism.cluster.card.declarations";

    /**
     * The key in the Hazelcast instance's user context under which a test puts the instance nano clock (a
     * {@link LongSupplier}) before the first {@code PrivacyCluster} over the instance is created. Absent, the instance
     * nano clock is {@link System#nanoTime()}.
     */
    static final String NANO_CLOCK_CONTEXT_KEY = "dataprism.cluster.card.nanoclock";

    /** The settings the instance's first wrapper records and every later wrapper must equal (R7). */
    record InstanceSettings(Duration pauseLease, Duration clockSkewTolerance, Set<String> clusterKeyIds) {
        InstanceSettings {
            clusterKeyIds = Set.copyOf(clusterKeyIds);
        }
    }

    /** The instance clocks: the first wrapper's wall clock and the instance nano clock, with the recorded settings. */
    record Clocks(Clock wall, LongSupplier nanos, InstanceSettings settings) {
    }

    /** The highest card sequence sighted from one member, when it was first seen and whether it was in its lease. */
    private record Sighting(long sequence, long firstSeenNanos, boolean inLease) {
    }

    private static final class Recorded {
        final Clocks clocks;
        final MemberCards owner;
        final long deadlineNanos;
        boolean confirmed;

        Recorded(Clocks clocks, MemberCards owner, long deadlineNanos) {
            this.clocks = clocks;
            this.owner = owner;
            this.deadlineNanos = deadlineNanos;
        }
    }

    /**
     * What the one card of a member says, shared by every wrapper of its instance, and everything else that is per
     * instance in 228: the card-write protocol (R7: flag, mark, sequence), the recorded settings and clocks, and the
     * instance tracker behind the pause gate. Kept in the instance's user context.
     *
     * <p>Lock order: the pause document's monitor, then a wrapper's {@code state}, then this monitor, never the
     * reverse. This monitor is a leaf: no other monitor of that order is taken and no Hazelcast call is made while it
     * is held.
     */
    static final class Declarations {
        final Set<MemberCards> live = ConcurrentHashMap.newKeySet();
        /** Changes whenever the declared content changes; a write that started under an older value is stale. */
        final AtomicLong generation = new AtomicLong();
        /** The highest pause sequence ever reported by any wrapper: it does not go down when a reporter closes. */
        final AtomicLong highestPause = new AtomicLong();
        /** Orders the member-list reads of the looks of every wrapper of the instance. */
        final AtomicLong memberListReads = new AtomicLong();
        /** The digest of the instance's main document, or null (the no-document marker); immutable. */
        volatile PauseDocument.Digest reportedDigest;
        /** Runs in the stamping loop of a successful card write, for each wrapper; a test seam. */
        volatile Runnable stampingFault = () -> { };
        /** Runs first inside the atomic step of a completion (X1, X2); a test seam for an Error inside it. */
        volatile Runnable stepFault = () -> { };

        // ---- the card-write protocol, guarded by this monitor ----
        private long cardSequence;
        private boolean flag;
        /** The card sequence of the write that holds the flag: only that write clears it. */
        private long flagOwner;
        private boolean mark;
        private boolean hasBuild;
        private long lastBuildNanos;

        // ---- the recorded settings and clocks, guarded by this monitor ----
        private Recorded recorded;

        // ---- the instance tracker, guarded by this monitor ----
        private final Map<UUID, Long> tracked = new HashMap<>();
        private final Map<UUID, Sighting> sightings = new HashMap<>();
        private boolean hasLook;
        private long latestToken;
        private Set<UUID> latestMembers = Set.of();
        private long latestLookNanos;
        private Map<UUID, PauseDocument.Digest> latestDigests = Map.of();
        private int latestUnverified;
        private boolean latestOffending;
        private boolean hasRestart;
        private long restartNanos;

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

        // ---- the instance clocks and settings ----

        /** The instance clocks, or null until a start has passed. */
        synchronized Clocks clocks() {
            return recorded == null ? null : recorded.clocks;
        }

        long nanoNow() {
            Clocks clocks = clocks();
            return clocks == null ? System.nanoTime() : clocks.nanos().getAsLong();
        }

        /**
         * Compares against the recorded settings, or records {@code clocks} as tentative, in one step. Returns true
         * when this call recorded. A start that finds a tentative record waits for it to be confirmed or cleared, at
         * most until the recording start's budget has gone by.
         */
        synchronized boolean compareOrRecord(MemberCards wrapper, Clocks clocks, Duration budget) {
            while (true) {
                if (recorded == null) {
                    recorded = new Recorded(clocks, wrapper, System.nanoTime() + budget.toNanos());
                    return true;
                }
                if (recorded.confirmed) {
                    if (!recorded.clocks.settings().equals(clocks.settings())) {
                        throw new PrivacyClusterRefusal(PrivacyClusterRefusal.Code.CLUSTER_SETTINGS_MISMATCH,
                                "the pause lease, the clock skew tolerance or the cluster keys differ from the "
                                        + "instance's first member card wrapper");
                    }
                    return false;
                }
                long remaining = recorded.deadlineNanos - System.nanoTime();
                if (remaining <= 0) {
                    throw new IllegalStateException("an instance settings record did not finish in time");
                }
                try {
                    TimeUnit.NANOSECONDS.timedWait(this, remaining);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("an instance settings record did not finish in time");
                }
            }
        }

        /** The recording start completed: its record stands, late or not. */
        synchronized void confirm(MemberCards wrapper) {
            if (recorded != null && recorded.owner == wrapper) {
                recorded.confirmed = true;
                notifyAll();
            }
        }

        /** The recording start did not complete: its tentative record is cleared. */
        synchronized void abandon(MemberCards wrapper) {
            if (recorded != null && recorded.owner == wrapper && !recorded.confirmed) {
                recorded = null;
                notifyAll();
            }
        }

        // ---- the card-write protocol ----

        /** W1 or nothing: takes the flag, clears the mark and takes the next card sequence; -1 if the flag is held. */
        synchronized long beginWrite() {
            if (flag) {
                return -1;
            }
            flag = true;
            mark = false;
            flagOwner = ++cardSequence;
            return flagOwner;
        }

        /** T6: waits for the flag under one deadline, then takes it (W1). */
        long beginWriteWaiting(Duration timeout) {
            long deadline = System.nanoTime() + timeout.toNanos();
            synchronized (this) {
                while (flag) {
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0) {
                        throw new IllegalStateException("a member card write did not finish in time");
                    }
                    try {
                        TimeUnit.NANOSECONDS.timedWait(this, remaining);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("a member card write was interrupted");
                    }
                }
                flag = true;
                mark = false;
                flagOwner = ++cardSequence;
                return flagOwner;
            }
        }

        /** T2: a write is outstanding or about to be: remember a change may be unpublished. */
        synchronized void markPending() {
            mark = true;
        }

        synchronized boolean marked() {
            return mark;
        }

        /** The latest card sequence taken by a write of this instance, for tests. */
        synchronized long cardSequenceForTest() {
            return cardSequence;
        }

        synchronized boolean writeOutstanding() {
            return flag;
        }

        /**
         * X4: a throw before the card reached Hazelcast. Clears the flag only if this write still owns it: a
         * completion that was attached before the throw may already have cleared it, and another write taken it.
         */
        synchronized void failedBeforeWrite(long sequence) {
            mark = true;
            if (flag && flagOwner == sequence) {
                flag = false;
            }
            notifyAll();
        }

        /** X2: the write failed. One atomic step. */
        void failed(long sequence) {
            synchronized (this) {
                try {
                    stepFault.run();
                    mark = true;
                    if (flagOwner == sequence) {
                        flag = false;
                    }
                    notifyAll();
                } catch (Throwable abnormal) {
                    mark = true;
                    flag = false;
                    throw abnormal;
                }
            }
        }

        /** X1: the write succeeded. The atomic step, then the stamping loop outside the monitor. */
        void succeeded(long sequence, long generationAtBuild, PauseDocument.Digest digestAtBuild, long buildNanos,
                       Map<MemberCards, Instant> readings) {
            synchronized (this) {
                try {
                    stepFault.run();
                    if (reportedDigest != digestAtBuild || generation.get() != generationAtBuild) {
                        mark = true;
                    }
                    if (flagOwner == sequence) {
                        flag = false;
                    }
                    if (!hasBuild || buildNanos - lastBuildNanos > 0) {
                        hasBuild = true;
                        lastBuildNanos = buildNanos;
                    }
                    notifyAll();
                } catch (Throwable abnormal) {
                    mark = true;
                    flag = false;
                    throw abnormal;
                }
            }
            try {
                for (Map.Entry<MemberCards, Instant> entry : readings.entrySet()) {
                    if (live.contains(entry.getKey())) {
                        stampingFault.run();
                        entry.getKey().stamp(entry.getValue());
                    }
                }
            } catch (Throwable abnormal) {
                synchronized (this) {
                    mark = true;
                }
                throw abnormal;
            }
        }

        /** The age of the last successful build on the instance nano clock, or -1 if there was none. */
        synchronized long buildAgeNanos() {
            Clocks clocks = recorded == null ? null : recorded.clocks;
            if (!hasBuild || clocks == null) {
                return -1;
            }
            return clocks.nanos().getAsLong() - lastBuildNanos;
        }

        /** A change of the main document's reported digest: sets the mark and wakes the schedule (T5). */
        void report(PauseDocument.Digest digest) {
            reportedDigest = digest;
            synchronized (this) {
                mark = true;
            }
            // Outside the monitor: the first live wrapper whose executor accepts the pass gets it. If none does, or
            // there is none, the mark stays for the next pass or the next start.
            for (MemberCards wrapper : live) {
                if (wrapper.submitPass()) {
                    return;
                }
            }
        }

        // ---- the instance tracker ----

        private boolean validCard(UUID member, Card card, long nowNanos, Instant nowWall, Clocks clocks) {
            if (card == null) {
                return false;
            }
            Sighting sighting = sightings.get(member);
            return sighting != null && sighting.sequence() == card.cardSequence() && sighting.inLease()
                    && nowWall.isBefore(card.leaseEnd().plus(clocks.settings().clockSkewTolerance()))
                    && nowNanos - sighting.firstSeenNanos() < clocks.settings().pauseLease().toNanos();
        }

        /** Feeds one wrapper's completed look (R7). A wrapper that is not live feeds nothing. */
        synchronized void feedLook(MemberCards from, UUID self, List<UUID> members, long token,
                                   Map<UUID, Card> opened) {
            if (!live.contains(from) || recorded == null) {
                return;
            }
            Clocks clocks = recorded.clocks;
            long nowNanos = clocks.nanos().getAsLong();
            Instant nowWall = clocks.wall().instant();
            Set<UUID> memberSet = new LinkedHashSet<>(members);
            boolean counted = !hasLook || token > latestToken;
            Set<UUID> sighted = counted ? memberSet : latestMembers;
            for (UUID member : sighted) {
                Card card = opened.get(member);
                if (card == null) {
                    continue;
                }
                Sighting known = sightings.get(member);
                if (known == null || card.cardSequence() > known.sequence()) {
                    sightings.put(member, new Sighting(card.cardSequence(), nowNanos,
                            nowWall.isBefore(card.leaseEnd().plus(clocks.settings().clockSkewTolerance()))));
                }
            }
            if (!counted) {
                return;
            }
            boolean offending = hasLook
                    && nowNanos - latestLookNanos > clocks.settings().pauseLease().dividedBy(2).toNanos();
            int valid = 0;
            Map<UUID, Boolean> validity = new HashMap<>();
            for (UUID member : memberSet) {
                Card card = opened.get(member);
                boolean ok = validCard(member, card, nowNanos, nowWall, clocks);
                validity.put(member, ok);
                if (!ok) {
                    offending = true;
                    continue;
                }
                valid++;
                if (!member.equals(self)) {
                    Long recordedSequence = tracked.get(member);
                    if (recordedSequence == null || card.cardSequence() > recordedSequence) {
                        tracked.put(member, card.cardSequence());
                    }
                }
                if (card.pauseDigest() != null && !card.pauseDigest().equals(PauseDocument.Digest.EMPTY)) {
                    offending = true;
                }
            }
            tracked.keySet().retainAll(memberSet);
            sightings.keySet().retainAll(memberSet);
            Map<UUID, PauseDocument.Digest> digests = new HashMap<>();
            for (UUID member : tracked.keySet()) {
                if (!validity.get(member)) {
                    digests.put(member, PauseDocument.Digest.NONE);
                } else {
                    PauseDocument.Digest digest = opened.get(member).pauseDigest();
                    if (digest != null) {
                        digests.put(member, digest);
                    }
                }
            }
            latestDigests = digests;
            latestUnverified = memberSet.size() - valid;
            latestMembers = memberSet;
            latestToken = token;
            latestLookNanos = nowNanos;
            hasLook = true;
            latestOffending = offending;
            if (offending) {
                hasRestart = false;
            } else if (!hasRestart) {
                hasRestart = true;
                restartNanos = nowNanos;
            }
        }

        /** One consistent snapshot of the tracker against the member list just read (R7, Peers.snapshot()). */
        synchronized PauseDocument.PeerView snapshot(UUID self, List<UUID> current) {
            Set<UUID> currentSet = new HashSet<>(current);
            boolean alone = current.stream().noneMatch(member -> !member.equals(self));
            boolean oldest = current.isEmpty() || current.get(0).equals(self);
            if (recorded == null) {
                return new PauseDocument.PeerView(Map.of(), 0, false, Long.MAX_VALUE, -1, alone, oldest);
            }
            long now = recorded.clocks.nanos().getAsLong();
            boolean completed = hasLook && latestMembers.equals(currentSet);
            long lookAge = hasLook ? now - latestLookNanos : Long.MAX_VALUE;
            long cleanAge = hasLook && !latestOffending && hasRestart ? now - restartNanos : -1;
            return new PauseDocument.PeerView(Map.copyOf(latestDigests), latestUnverified, completed, lookAge,
                    cleanAge, alone, oldest);
        }
    }

    @SuppressWarnings("unchecked")
    static Declarations declarations(HazelcastInstance instance) {
        return (Declarations) instance.getUserContext().computeIfAbsent(DECLARATIONS_CONTEXT_KEY,
                key -> new Declarations());
    }

    /** The declarations of {@code instance} if there are any; creates nothing. */
    static Declarations declarationsIfAny(HazelcastInstance instance) {
        return (Declarations) instance.getUserContext().get(DECLARATIONS_CONTEXT_KEY);
    }

    private Declarations declarations() {
        return declarations(cluster.instance());
    }

    /** The Peers hooks of the pause gate for a keyed member: the instance tracker and the live member list. */
    static PauseDocument.Peers peers(PrivacyCluster cluster) {
        return () -> {
            HazelcastInstance instance = cluster.instance();
            UUID self = instance.getCluster().getLocalMember().getUuid();
            List<UUID> members = instance.getCluster().getMembers().stream().map(Member::getUuid).toList();
            return declarations(instance).snapshot(self, members);
        };
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
    /**
     * Called with the name of each step of a card write that holds the flag ("flag", "digest", "checks", "build",
     * "seal", "setAsync"), before the step runs; a seam for tests that throw at, or change something between, steps.
     */
    volatile java.util.function.Consumer<String> writeStage = stage -> { };
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

    /** Whether this wrapper's executor is stopped (or was never made), for tests. */
    boolean executorStoppedForTest() {
        return executor == null || executor.isShutdown();
    }

    /** Whether this wrapper holds a membership listener, for tests. */
    boolean listenerRegisteredForTest() {
        synchronized (state) {
            return listenerId != null;
        }
    }

    /** The reading of this wrapper's own clock at the card build it last relies on, for tests; null if none. */
    Instant lastPublishAtForTest() {
        return lastPublishAt;
    }

    /** How many card writes were started, for tests. */
    int writesStarted() {
        return writes.get();
    }

    /**
     * Whether a card write of this wrapper's instance has been started and has not completed at the owner, for
     * tests. The flag is per instance (R7).
     */
    boolean writeOutstanding() {
        Declarations declarations = declarationsIfAny(cluster.instance());
        return declarations != null && declarations.writeOutstanding();
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
        }
        // The generation moves first (T1), so a write that began earlier sets the mark when it completes. The mark,
        // not a reset of the stamp, makes the next pass due even if the write below fails (invariant I5: the stamps
        // never move backwards).
        declarations().generation.incrementAndGet();
        if (running && !closed) {
            try {
                publishOrMark(writeTimeout);
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
     * {@code members.unverified} gauge. The sentinel has been checked before this runs. The order is fixed (R7): the
     * nano clock key; the settings compare-or-record; joining {@code Declarations.live}; the startup publish; the
     * first look; then confirming a record this start made. A start that does not complete leaves the live set,
     * clears its tentative record and stops what it created.
     */
    void start() {
        if (closed || !started.compareAndSet(false, true)) {
            return;
        }
        Declarations declarations = declarations();
        LongSupplier nanoClock = nanoClockOf(cluster.instance());
        boolean recorder = false;
        boolean completed = false;
        try {
            Set<String> keyIds = new LinkedHashSet<>();
            keys.held().forEach(held -> keyIds.add(held.id()));
            Clocks clocks = new Clocks(clock, nanoClock,
                    new InstanceSettings(settings.pauseLease(), settings.clockSkewTolerance(), keyIds));
            recorder = declarations.compareOrRecord(this, clocks, startupWriteBound().plus(settings.pauseLease()));
            ScheduledExecutorService created = Executors.newSingleThreadScheduledExecutor(task -> {
                Thread thread = new Thread(task, "dataprism-member-cards");
                thread.setDaemon(true);
                return thread;
            });
            executor = created;
            join(declarations);
            publishOrMark(startupWriteBound());
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
            created.scheduleWithFixedDelay(this::tick, tickInterval.toMillis(), tickInterval.toMillis(),
                    TimeUnit.MILLISECONDS);
            if (recorder) {
                declarations.confirm(this);
            }
            completed = true;
        } finally {
            if (!completed) {
                abandonStart(declarations, recorder);
            }
        }
    }

    /** The instance nano clock: the context key's value, or {@link System#nanoTime()}; anything else is an error. */
    private static LongSupplier nanoClockOf(HazelcastInstance instance) {
        Object value = instance.getUserContext().get(NANO_CLOCK_CONTEXT_KEY);
        if (value == null) {
            return System::nanoTime;
        }
        if (value instanceof LongSupplier supplier) {
            return supplier;
        }
        throw new IllegalStateException("the nano clock in the instance context is not a LongSupplier");
    }

    /** Joins the live set and takes a liveness stamp from the last successful build, if it is under a lease old. */
    private void join(Declarations declarations) {
        long age;
        declarations.live.add(this);
        age = declarations.buildAgeNanos();
        if (age >= 0 && age < settings.pauseLease().toNanos()) {
            // Seeded: the joiner is no less served than the build it relies on. Older, it stays null: not serving.
            Instant seeded = clock.instant().minusNanos(age);
            stamp(seeded);
        }
    }

    /** A start that did not complete: leave the live set, clear the record, stop the executor and the listener. */
    private void abandonStart(Declarations declarations, boolean recorder) {
        UUID id;
        synchronized (state) {
            id = listenerId;
            listenerId = null;
        }
        declarations.live.remove(this);
        if (recorder) {
            declarations.abandon(this);
        }
        if (executor != null) {
            executor.shutdownNow();
        }
        if (id != null) {
            try {
                cluster.instance().getCluster().removeMembershipListener(id);
            } catch (RuntimeException ignored) {
                // The instance may already be gone.
            }
        }
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
            // Looked up, never created: closing a wrapper that never started creates nothing (T7).
            Declarations declarations = declarationsIfAny(cluster.instance());
            if (declarations != null) {
                declarations.live.remove(this);
                declarations.generation.incrementAndGet();
            }
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

    /** Submits a schedule pass to this wrapper's executor (T5); a rejection leaves the mark for the next pass. */
    boolean submitPass() {
        ScheduledExecutorService current = executor;
        if (current == null || closed) {
            return false;
        }
        try {
            current.execute(this::tick);
            return true;
        } catch (RejectedExecutionException closing) {
            // The mark stays set.
            return false;
        }
    }

    /**
     * One pass of the schedule: write this member's card if it is due, then look at every member's. The schedule runs
     * it; a test with an injected clock runs it by hand. A step that fails leaves the member not serving, never with
     * its old status.
     *
     * <p>A card is due when the pending mark is set, or when half a lease has passed since the instance's last
     * successful build on the instance nano clock, or since this wrapper's own {@code publishedAt} on its own clock.
     * A pass that finds the flag held writes nothing (T3, T4): the outstanding write refreshes the card, and the mark,
     * if set, stays for the next pass.
     */
    void tick() {
        if (!running || closed) {
            return;
        }
        Instant now = clock.instant();
        Declarations declarations = declarations();
        boolean due;
        synchronized (state) {
            due = publishedAt == null || !now.isBefore(publishedAt.plus(settings.pauseLease().dividedBy(2)));
        }
        long buildAge = declarations.buildAgeNanos();
        due |= buildAge >= settings.pauseLease().dividedBy(2).toNanos();
        due |= declarations.marked();
        if (due) {
            try {
                long sequence = declarations.beginWrite();
                if (sequence >= 0) {
                    writeCard(declarations, sequence, writeTimeout);
                }
            } catch (RuntimeException unwritten) {
                // The old card stays until its lease ends; once a lease has gone by without a write, status() closes.
                log.debug("member card could not be refreshed: {}", unwritten.getClass().getSimpleName());
            }
        }
        look(now);
    }

    /**
     * Writes this member's card now, whatever its age. For tests that move the clock. If a card write is outstanding
     * it waits, under one deadline of the write timeout, for the flag to clear, then writes (T6); either deadline
     * throws.
     */
    void publishNow() {
        Declarations declarations = declarations();
        long sequence = declarations.beginWriteWaiting(writeTimeout);
        writeCard(declarations, sequence, writeTimeout);
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

    /** T1 or T2: writes the card if no write of the instance is outstanding, otherwise sets the mark. */
    private void publishOrMark(Duration bound) {
        Declarations declarations = declarations();
        long sequence = declarations.beginWrite();
        if (sequence < 0) {
            declarations.markPending();
            return;
        }
        writeCard(declarations, sequence, bound);
    }

    /** A liveness stamp: this wrapper's card state as of a card built at {@code reading}; never moves backwards. */
    void stamp(Instant reading) {
        synchronized (state) {
            if (lastPublishAt == null || reading.isAfter(lastPublishAt)) {
                lastPublishAt = reading;
            }
            if (publishedAt == null || reading.isAfter(publishedAt)) {
                publishedAt = reading;
            }
        }
    }

    /**
     * W2 and W3 of the card-write protocol, for a write that holds the flag (W1 took it and the next card
     * sequence). Any throw before the card reaches Hazelcast clears the flag and sets the mark (X4); after that the
     * completion does (X1, X2).
     */
    private void writeCard(Declarations declarations, long sequence, Duration bound) {
        boolean attached = false;
        try {
            writeStage.accept("flag");
            long generation = declarations.generation.get();
            writeStage.accept("digest");
            PauseDocument.Digest digest = declarations.reportedDigest;
            writeStage.accept("checks");
            Map<String, byte[]> checks = declarations.checks();
            if (checks.isEmpty()) {
                // Not yet joined (a card written before start): at least this wrapper's own.
                checks = declaredChecks();
            }
            Map<MemberCards, Instant> readings = new LinkedHashMap<>();
            for (MemberCards wrapper : declarations.live) {
                readings.put(wrapper, wrapper.clock.instant());
            }
            writeStage.accept("readings");
            long buildNanos = declarations.nanoNow();
            // The lease and the writer's stamp both use this wrapper's own reading from W2, not a second one.
            Instant build = readings.getOrDefault(this, clock.instant());
            UUID self = cluster.instance().getCluster().getLocalMember().getUuid();
            Card card = new Card(self, FORMAT_VERSION, keys.held().stream().map(ClusterKeys.Key::id).toList(),
                    checks, declarations.pause(), sequence, digest, build.plus(settings.pauseLease()));
            writeStage.accept("build");
            afterCardBuilt.run();
            String key = keyOf(self);
            writeStage.accept("seal");
            String sealed = sealer.seal(PrivacyCluster.MEMBER_CARD_MAP, key, card.encode());
            CompletableFuture<Void> finished = new CompletableFuture<>();
            writeStage.accept("setAsync");
            CompletionStage<Void> write = map().setAsync(key, sealed, settings.pauseLease().toMillis(),
                    TimeUnit.MILLISECONDS);
            write.whenComplete((done, failure) -> {
                try {
                    if (failure != null) {
                        declarations.failed(sequence);
                    } else {
                        declarations.succeeded(sequence, generation, digest, buildNanos, readings);
                    }
                } finally {
                    if (failure != null) {
                        finished.completeExceptionally(failure);
                    } else {
                        finished.complete(null);
                    }
                }
            });
            attached = true;
            writes.incrementAndGet();
            ClusterSentinel.afterWriteStarted.accept(key);
            ClusterSentinel.bounded(finished, bound);
            stamp(build);
        } finally {
            if (!attached) {
                declarations.failedBeforeWrite(sequence);
            }
        }
    }

    // ---- the cards of the members ------------------------------------------------------------------------

    /** What one look read from the cluster, before anything is judged. */
    private record Snapshot(List<UUID> members, UUID self, long readToken, Map<UUID, Object> stored,
                            ClusterSentinel.Outcome sentinel, Map<String, byte[]> declared) {
    }

    /**
     * Reads every member's card and the sentinel, then judges them. Any failure to read leaves the member not
     * serving until a look succeeds: the last status is never kept on a failure.
     */
    private void look(Instant now) {
        try {
            lookFault.run();
            Snapshot snapshot = read();
            Map<UUID, Card> opened;
            synchronized (state) {
                opened = judge(snapshot, now);
            }
            if (opened != null) {
                // After `judge` has released the wrapper's state (lock order); a wrapper that is not live feeds nothing.
                declarations().feedLook(this, snapshot.self(), snapshot.members(), snapshot.readToken(), opened);
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
        Declarations declarations = declarations();
        // The ticket is taken before the member list is read: a later ticket never belongs to an older list.
        long token = declarations.memberListReads.incrementAndGet();
        List<UUID> members = cluster.instance().getCluster().getMembers().stream().map(Member::getUuid).toList();
        UUID self = cluster.instance().getCluster().getLocalMember().getUuid();
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
        return new Snapshot(members, self, token, stored, outcome, declarations.checks());
    }

    private Map<UUID, Card> judge(Snapshot snapshot, Instant now) {
        if (closed) {
            return null;
        }
        Map<UUID, Card> found = new LinkedHashMap<>();
        Map<UUID, Card> opened = new LinkedHashMap<>();
        Set<UUID> live = new HashSet<>(snapshot.members());
        boolean keyCheckMismatch = false;
        boolean anyMissing = false;
        for (UUID uuid : snapshot.members()) {
            Card read = openCard(uuid, snapshot.stored().get(uuid));
            if (read != null) {
                opened.put(uuid, read);
            }
            // The reader's clock may run ahead of the writer's by up to the skew tolerance.
            Card card = read != null && now.isBefore(read.leaseEnd().plus(settings.clockSkewTolerance())) ? read : null;
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
        return opened;
    }

    /** The card in {@code stored} if it opens and is one of {@code member}'s, in its lease or not; otherwise null. */
    private Card openCard(UUID member, Object value) {
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
        return card;
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

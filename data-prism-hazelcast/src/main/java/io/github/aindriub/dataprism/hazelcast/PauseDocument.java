package io.github.aindriub.dataprism.hazelcast;

import com.hazelcast.cluster.Member;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.core.LifecycleEvent;
import com.hazelcast.map.IMap;
import io.github.aindriub.dataprism.core.metrics.Metric;
import io.github.aindriub.dataprism.core.metrics.PrivacyMetrics;
import io.github.aindriub.dataprism.core.refusal.ClusterOperationRefusedException;
import io.github.aindriub.dataprism.hazelcast.seal.Opened;
import io.github.aindriub.dataprism.hazelcast.seal.Sealer;
import io.github.aindriub.dataprism.hazelcast.seal.ServingGate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * The pause state of the cluster as one sealed, observed-remove document under a freshness gate
 * (docs/plan/design/cluster-security.md 2.3.3, OD-3, OD-4, OD-14, OD-15).
 *
 * <p><b>Pause state is an observed-remove set.</b> The document holds <i>elements</i> (each a pause of one target
 * under a fresh 128-bit id) and <i>tombstones</i> (ids). A target is paused while some element for it has no
 * tombstone. A pause always adds a new element; a resume tombstones the ids it observed, and only those, so a pause
 * that lands while a resume is built stays in force. Accepting a document merges it into what this instance holds by
 * union, so content only ever grows (F0). The sequence and the refresh counter order writes; they are never evidence
 * of freshness.
 *
 * <p><b>Freshness</b> is one gate (F1 to F5). It passes when the stored document is authentic and wholly merged, every
 * tracked peer shows a valid card whose digest this member has held or merged (Covered), and, for a member that has
 * not yet been fresh in this process life, no member is unverified. A pass resets the lease. Only a pass does: this
 * member's own writes never do, when it was lapsed as they began. A document that is absent, unauthentic or not fresh
 * makes every read refuse.
 *
 * <p><b>Writes</b> are {@code replace(name, exactOldBytes, newBytes)}, or {@code putIfAbsent} when the document is
 * absent, under the key's bounded lock, after a read under that lock, computed from {@code held ⊔ stored}. A lost
 * race re-reads and tries again, {@value #MAX_ATTEMPTS} times, then fails closed.
 */
final class PauseDocument {

    /** The canonical name the document's map key is derived from. */
    static final String CANONICAL = "pause-document";
    /** How many times a write that lost its race reads again and retries before it refuses. */
    static final int MAX_ATTEMPTS = 10;
    /** A pause of anything but everything is refused when the merged base already holds this many elements less one. */
    static final int PAUSE_CAP = 8_176;
    /** The most elements, and the most tombstones, a document may hold: encode and decode alike. */
    static final int FORMAT_BOUND = 65_536;

    static final byte FORMAT = 2;
    private static final String FENCE_KEY = "all";
    private static final int MAX_ENTRIES = 1 << 20;
    private static final int MEMO_SIZE = 16;
    private static final int RING_SIZE = 256;
    private static final long NANOS_PER_SECOND = TimeUnit.SECONDS.toNanos(1);

    /** The two clocks and the tick: injected so a test moves time itself. */
    record Environment(LongSupplier nanos, Clock wall, Duration tickInterval) {

        Environment {
            Objects.requireNonNull(nanos, "nanos");
            Objects.requireNonNull(wall, "wall");
        }

        /** The system clocks, ticking at a sixth of the lease (at least 100 ms). */
        static Environment system(Duration lease) {
            Duration tick = lease.dividedBy(6);
            return new Environment(System::nanoTime, Clock.systemUTC(),
                    tick.compareTo(Duration.ofMillis(100)) < 0 ? Duration.ofMillis(100) : tick);
        }
    }

    /** What a pause names. */
    enum Kind { ALL, TOOL, SCOPE }

    /** One target: everything, one tool, or one scope. The name of {@code ALL} is empty. */
    record Target(Kind kind, String name) {

        static final Target ALL = new Target(Kind.ALL, "");

        Target {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(name, "name");
            if (kind == Kind.ALL && !name.isEmpty()) {
                throw new IllegalArgumentException("the target of everything has no name");
            }
        }

        static Target tool(String name) {
            return new Target(Kind.TOOL, name);
        }

        static Target scope(String name) {
            return new Target(Kind.SCOPE, name);
        }
    }

    /** The id of one element: 128 random bits. */
    record Id(long high, long low) implements Comparable<Id> {

        static Id random(SecureRandom random) {
            return new Id(random.nextLong(), random.nextLong());
        }

        @Override
        public int compareTo(Id other) {
            int byHigh = Long.compareUnsigned(high, other.high);
            return byHigh != 0 ? byHigh : Long.compareUnsigned(low, other.low);
        }
    }

    /** What the document says is paused, derived from its elements and tombstones. */
    record Pauses(boolean all, Set<String> tools, Set<String> scopes) {

        static final Pauses NONE = new Pauses(false, Set.of(), Set.of());

        Pauses {
            tools = Set.copyOf(tools);
            scopes = Set.copyOf(scopes);
        }
    }

    /**
     * A pause digest: 16 bytes, value-compared. {@link #NONE} stands for "no valid card" in the peer digests; it is
     * never a real digest. The no-document marker of a card is a null digest, never one of these.
     */
    record Digest(byte[] bytes) {

        static final int LENGTH = 16;
        /** Digest(empty): the digest of a document with no elements and no tombstones. */
        static final Digest EMPTY = Doc.empty(0, 0, 0, "").digest();
        /** A peer with no valid card at the latest look. */
        static final Digest NONE = new Digest(new byte[0]);

        Digest {
            bytes = bytes.clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Digest digest && Arrays.equals(bytes, digest.bytes);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(bytes);
        }
    }

    /**
     * One document, as sealed in the cluster. The maps and sets are owned by the document and never changed after
     * construction; a caller hands over collections it no longer touches.
     */
    record Doc(long sequence, long refresh, long issuedAtMillis, String issuer, Map<Id, Target> elements,
               Set<Id> tombstones) {

        static Doc empty(long sequence, long refresh, long issuedAtMillis, String issuer) {
            return new Doc(sequence, refresh, issuedAtMillis, issuer, Map.of(), Set.of());
        }

        int compareOrder(Doc other) {
            int bySequence = Long.compare(sequence, other.sequence);
            return bySequence != 0 ? bySequence : Long.compare(refresh, other.refresh);
        }

        /** Whether every element and every tombstone of {@code other} is also here. */
        boolean covers(Doc other) {
            return (other.elements.size() <= elements.size() && elements.keySet().containsAll(other.elements.keySet()))
                    && (other.tombstones.size() <= tombstones.size() && tombstones.containsAll(other.tombstones));
        }

        /** Whether an id of {@code other} names a different target here. */
        boolean conflictsWith(Doc other) {
            Map<Id, Target> small = elements.size() <= other.elements.size() ? elements : other.elements;
            Map<Id, Target> large = small == elements ? other.elements : elements;
            for (Map.Entry<Id, Target> entry : small.entrySet()) {
                Target there = large.get(entry.getKey());
                if (there != null && !there.equals(entry.getValue())) {
                    return true;
                }
            }
            return false;
        }

        /**
         * The union of the contents with the metadata of R2: the order is the lexicographic maximum, and the issued
         * time and issuer are those of the maximum-order document; on an order tie the later issued time wins, then
         * the greater issuer as encoded bytes, so merge is commutative.
         */
        Doc merge(Doc other) {
            boolean thisWins = metaCompare(other) >= 0;
            if (covers(other) && thisWins) {
                return this;
            }
            if (other.covers(this) && !thisWins) {
                return other;
            }
            Map<Id, Target> mergedElements = new HashMap<>(elements);
            mergedElements.putAll(other.elements);
            Set<Id> mergedTombstones = new HashSet<>(tombstones);
            mergedTombstones.addAll(other.tombstones);
            Doc winner = thisWins ? this : other;
            return new Doc(winner.sequence, winner.refresh, winner.issuedAtMillis, winner.issuer, mergedElements,
                    mergedTombstones);
        }

        private int metaCompare(Doc other) {
            int byOrder = compareOrder(other);
            if (byOrder != 0) {
                return byOrder;
            }
            int byIssued = Long.compare(issuedAtMillis, other.issuedAtMillis);
            if (byIssued != 0) {
                return byIssued;
            }
            return Arrays.compareUnsigned(issuer.getBytes(StandardCharsets.UTF_8),
                    other.issuer.getBytes(StandardCharsets.UTF_8));
        }

        /** Digest(x): the first 16 bytes of SHA-256 over the counted, sorted element ids and tombstone ids. */
        Digest digest() {
            try {
                MessageDigest sha = MessageDigest.getInstance("SHA-256");
                sha.update(intBytes(elements.size()));
                for (Id id : new TreeSet<>(elements.keySet())) {
                    sha.update(idBytes(id));
                }
                sha.update(intBytes(tombstones.size()));
                for (Id id : new TreeSet<>(tombstones)) {
                    sha.update(idBytes(id));
                }
                return new Digest(Arrays.copyOf(sha.digest(), Digest.LENGTH));
            } catch (NoSuchAlgorithmException impossible) {
                throw new IllegalStateException("SHA-256 is unavailable");
            }
        }

        private static byte[] intBytes(int value) {
            return new byte[]{(byte) (value >>> 24), (byte) (value >>> 16), (byte) (value >>> 8), (byte) value};
        }

        private static byte[] idBytes(Id id) {
            byte[] bytes = new byte[16];
            for (int i = 0; i < 8; i++) {
                bytes[i] = (byte) (id.high() >>> (56 - 8 * i));
                bytes[8 + i] = (byte) (id.low() >>> (56 - 8 * i));
            }
            return bytes;
        }

        Doc withHeader(long newSequence, long newRefresh, long newIssuedAt, String newIssuer) {
            return new Doc(newSequence, newRefresh, newIssuedAt, newIssuer, elements, tombstones);
        }

        /** The ids of the elements for {@code target} that have no tombstone: its live ids. */
        Set<Id> live(Target target) {
            Set<Id> ids = new HashSet<>();
            for (Map.Entry<Id, Target> entry : elements.entrySet()) {
                if (entry.getValue().equals(target) && !tombstones.contains(entry.getKey())) {
                    ids.add(entry.getKey());
                }
            }
            return ids;
        }

        /** What is paused: a target is paused while some element for it has no tombstone. */
        Pauses pauses() {
            boolean all = false;
            Set<String> tools = new HashSet<>();
            Set<String> scopes = new HashSet<>();
            for (Map.Entry<Id, Target> entry : elements.entrySet()) {
                if (tombstones.contains(entry.getKey())) {
                    continue;
                }
                Target target = entry.getValue();
                switch (target.kind()) {
                    case ALL -> all = true;
                    case TOOL -> tools.add(target.name());
                    case SCOPE -> scopes.add(target.name());
                }
            }
            return new Pauses(all, tools, scopes);
        }

        /**
         * The sealed plaintext. Refused, never truncated, above the format bound.
         *
         * @throws IllegalStateException if there are more than {@link #FORMAT_BOUND} elements or tombstones
         */
        byte[] encode() {
            if (elements.size() > FORMAT_BOUND || tombstones.size() > FORMAT_BOUND) {
                throw new IllegalStateException("the pause document is over the format bound");
            }
            try {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                DataOutputStream out = new DataOutputStream(bytes);
                out.writeByte(FORMAT);
                out.writeLong(sequence);
                out.writeLong(refresh);
                out.writeLong(issuedAtMillis);
                writeText(out, issuer);
                out.writeInt(elements.size());
                for (Id id : new TreeSet<>(elements.keySet())) {
                    Target target = elements.get(id);
                    out.writeLong(id.high());
                    out.writeLong(id.low());
                    out.writeByte(target.kind().ordinal());
                    writeText(out, target.name());
                }
                out.writeInt(tombstones.size());
                for (Id id : new TreeSet<>(tombstones)) {
                    out.writeLong(id.high());
                    out.writeLong(id.low());
                }
                return bytes.toByteArray();
            } catch (IOException impossible) {
                throw new IllegalStateException("the pause document could not be encoded");
            }
        }

        /**
         * The document in {@code plaintext}, or null if it is not exactly one well-formed document: an unknown
         * format tag, a duplicate id with a different target, a count above the guard or the format bound.
         */
        static Doc decode(byte[] plaintext) {
            try {
                DataInputStream in = new DataInputStream(new ByteArrayInputStream(plaintext));
                if (in.readByte() != FORMAT) {
                    return null;
                }
                long sequence = in.readLong();
                long refresh = in.readLong();
                long issuedAt = in.readLong();
                String issuer = readText(in);
                int elementCount = readCount(in);
                Map<Id, Target> elements = new HashMap<>();
                for (int i = 0; i < elementCount; i++) {
                    Id id = new Id(in.readLong(), in.readLong());
                    int kind = in.readByte();
                    if (kind < 0 || kind >= Kind.values().length) {
                        return null;
                    }
                    Target target = new Target(Kind.values()[kind], readText(in));
                    Target existing = elements.put(id, target);
                    if (existing != null && !existing.equals(target)) {
                        return null;
                    }
                }
                int tombstoneCount = readCount(in);
                Set<Id> tombstones = new HashSet<>();
                for (int i = 0; i < tombstoneCount; i++) {
                    tombstones.add(new Id(in.readLong(), in.readLong()));
                }
                if (in.available() != 0 || sequence < 0 || refresh < 0) {
                    return null;
                }
                return new Doc(sequence, refresh, issuedAt, issuer, elements, tombstones);
            } catch (IOException | RuntimeException malformed) {
                return null;
            }
        }

        private static int readCount(DataInputStream in) throws IOException {
            int count = in.readInt();
            if (count < 0 || count > MAX_ENTRIES || count > FORMAT_BOUND || (long) count * 16 > in.available()) {
                throw new IOException("bad count");
            }
            return count;
        }

        private static void writeText(DataOutputStream out, String text) throws IOException {
            byte[] utf8 = text.getBytes(StandardCharsets.UTF_8);
            out.writeInt(utf8.length);
            out.write(utf8);
        }

        private static String readText(DataInputStream in) throws IOException {
            int length = in.readInt();
            if (length < 0 || length > in.available()) {
                throw new IOException("bad length");
            }
            byte[] utf8 = new byte[length];
            in.readFully(utf8);
            return new String(utf8, StandardCharsets.UTF_8);
        }
    }

    /** What a look at the stored value found. */
    enum Status {
        /** No entry. */
        ABSENT,
        /** An entry that is not an authentic, well-formed document, or one that names a held id as another target. */
        UNREADABLE,
        /** An authentic document, merged into what is held. */
        AUTHENTIC,
        /** An authentic first document older than the cold-start gate allows: not accepted. */
        GATED
    }

    /**
     * What a look found.
     *
     * @param stored the authentic document read (merged, unless {@code GATED}), else null
     * @param fresh  whether this member is fresh as the look leaves it, after the gate ran at it
     * @param passed whether the gate passed at this look
     * @param peers  the one snapshot of the peers this look and the decision that follows it read
     */
    record Look(Status status, Doc stored, boolean fresh, boolean passed, PeerView peers) {
    }

    /** What a commit did; the refusals are the guard clauses of the write. */
    enum Outcome {
        WRITTEN,
        /** Another writer changed the entry first. */
        LOST_RACE,
        /** The document written would drop an element or a tombstone of the stored one. */
        DROPS_STORED,
        /** The document written would hold an element that is neither in the base nor the one fresh pause. */
        ADDS_ELEMENT,
        /** The document written would hold a tombstone that is neither in the base nor one the change may add. */
        ADDS_TOMBSTONE,
        /** The document written is not above the stored one and is not its refresh. */
        ORDER,
        /** The document written is over the format bound. */
        OVERSIZE
    }

    /**
     * One consistent snapshot of what the cards say, with one read of Hazelcast's member list (R7).
     *
     * @param peerDigests      for every tracked peer, the digest its valid card showed at the latest completed look,
     *                         or {@link Digest#NONE}; a valid no-document card and an untracked member are absent
     * @param unverifiedCount  the listed members without an R7-valid card at that look
     * @param lookCompleted    whether a look has completed whose member set equals the list this snapshot read
     * @param lookAgeNanos     the time since that look on the instance nano clock; ignored while not completed
     * @param cleanRunAgeNanos the age of the restart look, or -1 for none
     * @param alone            whether no other member is in the member list
     * @param oldest           whether this member is first in the member list
     */
    record PeerView(Map<UUID, Digest> peerDigests, int unverifiedCount, boolean lookCompleted, long lookAgeNanos,
                    long cleanRunAgeNanos, boolean alone, boolean oldest) {

        boolean isAlone() {
            return alone;
        }

        boolean isOldest() {
            return oldest;
        }
    }

    /** What the cards say about the other members; replaced by tests. */
    interface Peers {
        /** One snapshot of the tracker, made with no JVM monitor held. */
        PeerView snapshot();
    }

    /**
     * What a caller brings: its metrics, its lock timeout, its serving status and a sealer that counts into those
     * metrics. The shared document has none of its own: each operator call and tick uses the calling wrapper's.
     */
    record Dependencies(PrivacyMetrics metrics, Duration lockTimeout, Supplier<ClusterSecurityStatus> status,
                        Sealer sealer) {

        static Dependencies of(PrivacyCluster cluster, PrivacyMetrics metrics, Duration lockTimeout) {
            PrivacyMetrics safe = FailSafeMetrics.wrap(Objects.requireNonNull(metrics, "metrics"));
            return new Dependencies(safe, ClusterLocks.checked(lockTimeout), cluster::securityStatus,
                    IdentityMaps.sealerFor(cluster, safe, ServingGate.open()));
        }
    }

    private static final class Memo {
        final Doc doc;
        /** Whether the document was merged into the held one: held only grows, so it stays true. */
        boolean merged;
        /** Whether this document was counted as a conflict. */
        boolean counted;
        Digest digest;

        Memo(Doc doc) {
            this.doc = doc;
        }
    }

    private record Action(Doc base, Doc next, boolean freshBefore, boolean recovery) {
    }

    private enum Op { PAUSE, RESUME, END_SCOPE }

    private final PrivacyCluster cluster;
    /**
     * How the document reaches the Hazelcast instance: the caller decides. The pause state of a member that is not
     * serving must stay readable and writable, so {@code HazelcastOversightState} passes the ungated accessor;
     * ending a scope needs a serving member and passes the gated one. This class never names either, so it is not
     * one of the few that may call {@code PrivacyCluster.instance()}.
     */
    private final Supplier<HazelcastInstance> instances;
    private final String key;
    private final LongSupplier nanos;
    private final Clock wall;
    private final Duration lease;
    private final Duration skew;
    private final Duration tickInterval;
    /** Whether this is the instance's one main document, the only digest reporter (R7). */
    private final boolean main;
    private final SecureRandom random = new SecureRandom();
    private final long createdNanos;

    private final Object monitor = new Object();
    /** The union of every authentic document accepted; null before the first. */
    private Doc held;
    private Digest heldDigest;
    /** What is paused according to {@link #held}; recomputed when the content changes. */
    private Pauses pauses = Pauses.NONE;
    /** When the gate last passed, on the monotonic clock, and whether it ever has. */
    private long receivedAt;
    private boolean everPassed;
    private boolean established;
    /** The last 256 distinct unpinned digests, oldest first. */
    private final LinkedHashSet<Digest> ring = new LinkedHashSet<>();
    private final Set<Digest> pinned = new HashSet<>();
    /** The sealed text of the stored document last read, and when it was first read, for the refresh. */
    private String storedSealed;
    private long storedSeenSince;
    /** The document held when a merge began. */
    private Doc mergeBase;
    private static final int WARN_MISMATCH = 0;
    private static final int WARN_RECOVERY = 1;
    private static final int WARN_MAINTENANCE = 2;
    private static final int WARN_LAPSE = 3;
    private static final int WARN_REGAIN = 4;
    private final long[] lastWarn = {Long.MIN_VALUE, Long.MIN_VALUE, Long.MIN_VALUE, Long.MIN_VALUE, Long.MIN_VALUE};
    /** Whether the member was fresh at the last judgement; null before the first. */
    private Boolean lastFresh;
    private final Map<String, Memo> memo = new LinkedHashMap<>(MEMO_SIZE, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Memo> eldest) {
            return size() > MEMO_SIZE;
        }
    };

    private ScheduledExecutorService executor;
    private UUID lifecycleListener;
    private boolean closed;
    private int users;
    private volatile Dependencies tickDependencies;
    /** The dependencies of every user still retained, oldest first; ticks use the latest of them. */
    private final List<Dependencies> retained = new ArrayList<>();

    /** Runs after a write has read the stored value and sealed its next one, before it commits; a test seam. */
    volatile Runnable beforeCommit = () -> { };
    volatile Peers peers;
    /** Decodes an authentic plaintext; a test seam that counts decodes. */
    volatile Function<byte[], Doc> decoder = Doc::decode;
    volatile Logger log = LoggerFactory.getLogger(PauseDocument.class);

    PauseDocument(PrivacyCluster cluster, Supplier<HazelcastInstance> instances, Environment environment,
                  boolean main, Peers peers) {
        this.cluster = Objects.requireNonNull(cluster, "cluster");
        this.instances = Objects.requireNonNull(instances, "instances");
        this.main = main;
        this.nanos = environment.nanos();
        this.wall = environment.wall();
        this.tickInterval = environment.tickInterval();
        ClusterSecuritySettings settings = cluster.securitySettings();
        this.lease = settings.pauseLease();
        this.skew = settings.clockSkewTolerance();
        this.createdNanos = nanos.getAsLong();
        this.key = cluster.keyedNames().mapKey(PrivacyCluster.OVERSIGHT_MAP, CANONICAL);
        this.peers = peers;
    }

    // ---- reads ----------------------------------------------------------------------------------------

    /**
     * Looks at the cluster and answers what is paused.
     *
     * @throws IllegalStateException if the document is absent, unauthentic or not fresh (the caller sees
     *                               {@code OVERSIGHT_UNAVAILABLE})
     */
    Pauses current(Dependencies deps) {
        Look look = look(deps);
        synchronized (monitor) {
            // A stored document that is absent, unauthentic or gated is unavailable, whatever the lease says (R6).
            if (look.status() != Status.AUTHENTIC || !freshLocked()) {
                throw unavailable();
            }
            return pauses;
        }
    }

    /** For tests: the dependencies ticks use now. */
    Dependencies tickDependenciesForTest() {
        return tickDependencies;
    }

    /** For tests: reads with the dependencies of the latest user. */
    Pauses current() {
        return current(tickDependencies);
    }

    /** For tests: ticks with the dependencies of the latest user. */
    void tick() {
        tick(tickDependencies);
    }

    /** For tests: reconciles with the dependencies of the latest user. */
    void reconcileAfterMerge() {
        reconcileAfterMerge(tickDependencies);
    }

    /** For tests: the dependencies that {@link #current()} and {@link #tick()} use, without starting the lease. */
    void useDependencies(Dependencies deps) {
        tickDependencies = deps;
    }

    /** Whether the gate has passed within the last lease, by the nano clock. */
    boolean fresh() {
        synchronized (monitor) {
            return freshLocked();
        }
    }

    /** Whether the document has accepted anything. */
    boolean hasAccepted() {
        synchronized (monitor) {
            return held != null;
        }
    }

    /** The time since the last passing gate, in whole seconds; -1 if the gate has never passed. */
    long ageSeconds() {
        synchronized (monitor) {
            return !everPassed ? -1 : Math.max(0, (nanos.getAsLong() - receivedAt) / NANOS_PER_SECOND);
        }
    }

    /** The highest sequence accepted; zero before the first. */
    long acceptedSequence() {
        synchronized (monitor) {
            return held == null ? 0 : held.sequence();
        }
    }

    /** The highest (sequence, refresh) accepted as a pair, for tests; null before the first. */
    long[] acceptedOrder() {
        synchronized (monitor) {
            return held == null ? null : new long[]{held.sequence(), held.refresh()};
        }
    }

    /** The union of the documents accepted, for tests; null before the first. */
    Doc accepted() {
        synchronized (monitor) {
            return held;
        }
    }

    /** Whether the gate has ever passed in this process life, for tests. */
    boolean established() {
        synchronized (monitor) {
            return established;
        }
    }

    /** Whether {@code digest} is in Seen, for tests. */
    boolean seen(Digest digest) {
        synchronized (monitor) {
            return ring.contains(digest) || pinned.contains(digest);
        }
    }

    /** How many digests are pinned, for tests. */
    int pinnedCount() {
        synchronized (monitor) {
            return pinned.size();
        }
    }

    /** Digest(held), or null before the first document, for tests. */
    Digest heldDigest() {
        synchronized (monitor) {
            return heldDigest;
        }
    }

    private boolean freshLocked() {
        return everPassed && nanos.getAsLong() - receivedAt < lease.toNanos();
    }

    static IllegalStateException unavailable() {
        return new IllegalStateException("the pause document is absent, unauthentic or not fresh");
    }

    private IMap<String, Object> map() {
        return instances.get().getMap(PrivacyCluster.OVERSIGHT_MAP);
    }

    private Look look(Dependencies deps) {
        Doc heldBefore = accepted();
        return judge(map().get(key), deps, peers.snapshot(), heldBefore);
    }

    /**
     * Judges a stored value: merges what is authentic into what is held, then runs the gate (renewal point 1, R6).
     * {@code peerView} is the one snapshot of the peers this judgement reads; it was taken with no monitor held.
     * {@code heldBefore} is what was held before the map was read: a conflict is judged against it, so a read that
     * straddles an accepted write is not counted as one (R15).
     */
    private Look judge(Object stored, Dependencies deps, PeerView peerView, Doc heldBefore) {
        Look look = judgeStored(stored, deps, peerView, heldBefore);
        trackFreshness();
        return look;
    }

    /** One WARN, rate-limited, whenever the member lapses or regains freshness; no pause content. */
    private void trackFreshness() {
        boolean lapsed = false;
        boolean regained = false;
        synchronized (monitor) {
            boolean now = freshLocked();
            if (lastFresh != null && lastFresh != now) {
                lapsed = !now;
                regained = now;
            }
            lastFresh = now;
        }
        if (lapsed && shouldWarn(WARN_LAPSE)) {
            log.warn("PAUSE_DOCUMENT_LAPSED: the pause document is not fresh; reads refuse until the gate passes");
        }
        if (regained && shouldWarn(WARN_REGAIN)) {
            log.warn("PAUSE_DOCUMENT_FRESH: the pause document is fresh again");
        }
    }

    private Look judgeStored(Object stored, Dependencies deps, PeerView peerView, Doc heldBefore) {
        if (stored == null) {
            return noDocument(Status.ABSENT, peerView);
        }
        Memo entry = open(stored, deps);
        if (entry == null) {
            return noDocument(Status.UNREADABLE, peerView);
        }
        String sealed = (String) stored;
        Doc doc = entry.doc;
        boolean mismatch = false;
        Look result = null;
        synchronized (monitor) {
            long now = nanos.getAsLong();
            if (!sealed.equals(storedSealed)) {
                storedSealed = sealed;
                storedSeenSince = now;
            }
            if (held == null) {
                if (tooOldForColdStart(doc)) {
                    gate(peerView, false, null);
                    return new Look(Status.GATED, doc, freshLocked(), false, peerView);
                }
                held = doc;
                entry.merged = true;
                entry.digest = doc.digest();
                heldChanged(entry.digest, true, true);
                boolean passed = gate(peerView, true, null);
                return new Look(Status.AUTHENTIC, doc, freshLocked(), passed, peerView);
            }
            if (!entry.merged && held.conflictsWith(doc)) {
                mismatch = true;
            } else if (entry.merged) {
                // Held only grows and already holds this document: nothing to merge, so no containment work (R17).
                if (heldBefore != null && !entry.counted && (heldBefore.elements().size() > doc.elements().size()
                        || heldBefore.tombstones().size() > doc.tombstones().size())) {
                    entry.counted = true;
                    deps.metrics().increment(Metric.CLUSTER_PAUSE_CONFLICT);
                }
                boolean passed = gate(peerView, true, null);
                result = new Look(Status.AUTHENTIC, doc, freshLocked(), passed, peerView);
            } else {
                int order = doc.compareOrder(heldBefore == null ? held : heldBefore);
                if (!entry.counted && heldBefore != null
                        && (!doc.covers(heldBefore) || (order <= 0 && !heldBefore.covers(doc)))) {
                    entry.counted = true;
                    deps.metrics().increment(Metric.CLUSTER_PAUSE_CONFLICT);
                }
                Doc merged = held.merge(doc);
                boolean grew = merged.elements().size() != held.elements().size()
                        || merged.tombstones().size() != held.tombstones().size();
                boolean sequenceMoved = merged.sequence() != held.sequence();
                boolean anyChange = merged != held;
                held = merged;
                entry.merged = true;
                if (grew) {
                    heldChanged(held.digest(), true, sequenceMoved);
                } else if (anyChange) {
                    heldChanged(heldDigest, false, sequenceMoved);
                }
                if (entry.digest == null) {
                    entry.digest = doc.digest();
                }
                appendSeen(entry.digest);
                boolean passed = gate(peerView, true, null);
                result = new Look(Status.AUTHENTIC, doc, freshLocked(), passed, peerView);
            }
        }
        if (mismatch) {
            deps.metrics().increment(Metric.CLUSTER_SEAL_TAMPER);
            warnMismatch();
            return noDocument(Status.UNREADABLE, peerView);
        }
        return result;
    }

    private Look noDocument(Status status, PeerView peerView) {
        synchronized (monitor) {
            gate(peerView, false, null);
            return new Look(status, null, freshLocked(), false, peerView);
        }
    }

    /**
     * Held changed (R2): record its digest and target view, append the digest to Seen and, for the main document,
     * tell the cards. The monitor is held; the notice only sets a mark and wakes the schedule.
     */
    private void heldChanged(Digest digest, boolean contentGrew, boolean sequenceMoved) {
        heldDigest = digest;
        if (contentGrew) {
            pauses = held.pauses();
        }
        appendSeen(digest);
        if (main && (contentGrew || sequenceMoved)) {
            report(digest);
        }
    }

    private void report(Digest digest) {
        HazelcastInstance instance;
        try {
            instance = instances.get();
        } catch (RuntimeException unreachable) {
            return;
        }
        MemberCards.Declarations declarations = MemberCards.declarationsIfAny(instance);
        if (declarations != null) {
            declarations.report(digest);
        }
    }

    // ---- Seen and the gate ----------------------------------------------------------------------------

    private void appendSeen(Digest digest) {
        if (pinned.contains(digest)) {
            return;
        }
        ring.remove(digest);
        ring.add(digest);
        evict();
    }

    private void evict() {
        Iterator<Digest> oldest = ring.iterator();
        while (ring.size() > RING_SIZE && oldest.hasNext()) {
            oldest.next();
            oldest.remove();
        }
    }

    /** Pins the Seen values that peers show now, and unpins the ones they no longer show (R6). */
    private void updatePins(PeerView peerView) {
        Set<Digest> shown = new HashSet<>();
        for (Digest digest : peerView.peerDigests().values()) {
            if (!digest.equals(Digest.NONE)) {
                shown.add(digest);
            }
        }
        for (Digest digest : shown) {
            if (ring.remove(digest)) {
                pinned.add(digest);
            }
        }
        List<Digest> unpinned = new ArrayList<>();
        Iterator<Digest> pins = pinned.iterator();
        while (pins.hasNext()) {
            Digest digest = pins.next();
            if (!shown.contains(digest)) {
                pins.remove();
                unpinned.add(digest);
            }
        }
        for (Digest digest : unpinned) {
            // Back at the head, as if just appended: it survives 256 further changes.
            ring.remove(digest);
            ring.add(digest);
        }
        evict();
    }

    /**
     * The one freshness gate (R6), the only code that sets {@code receivedAt}. The monitor is held.
     *
     * @param storedOk     clause 1: the stored document is authentic and wholly merged
     * @param freshAtWrite null for a plain judgement; for a post-write renewal point, whether this member was fresh
     *                     when the write began (clause 4)
     */
    private boolean gate(PeerView peerView, boolean storedOk, Boolean freshAtWrite) {
        updatePins(peerView);
        if (!storedOk) {
            return false;
        }
        if (freshAtWrite != null && !freshAtWrite) {
            return false;
        }
        if (peerStateUnknown(peerView)) {
            return false;
        }
        for (Digest digest : peerView.peerDigests().values()) {
            if (digest.equals(Digest.NONE) || !(ring.contains(digest) || pinned.contains(digest))) {
                return false;
            }
        }
        if (!established && peerView.unverifiedCount() != 0) {
            return false;
        }
        receivedAt = nanos.getAsLong();
        everPassed = true;
        established = true;
        return true;
    }

    /** R6, peer state unknown: no look, a stale look, or a look of another member set; a lone member is exempt. */
    private boolean peerStateUnknown(PeerView peerView) {
        if (peerView.isAlone()) {
            return false;
        }
        return !peerView.lookCompleted() || peerView.lookAgeNanos() > lease.toNanos();
    }

    /** The document in a stored value, memoised by its exact text, or null (counted) if it is not authentic. */
    private Memo open(Object stored, Dependencies deps) {
        if (!(stored instanceof String envelope)) {
            deps.metrics().increment(Metric.CLUSTER_SEAL_TAMPER);
            return null;
        }
        synchronized (monitor) {
            Memo known = memo.get(envelope);
            if (known != null) {
                return known;
            }
        }
        Opened opened = deps.sealer().open(PrivacyCluster.OVERSIGHT_MAP, key, envelope);
        if (opened.outcome() != Opened.Outcome.AUTHENTIC) {
            // The sealer has counted it.
            return null;
        }
        Doc doc = decoder.apply(opened.plaintext());
        if (doc == null) {
            deps.metrics().increment(Metric.CLUSTER_SEAL_TAMPER);
            return null;
        }
        synchronized (monitor) {
            Memo known = memo.get(envelope);
            if (known != null) {
                return known;
            }
            Memo created = new Memo(doc);
            memo.put(envelope, created);
            return created;
        }
    }

    /** The wall-clock gate, used for the first acceptance after a cold start and for nothing else. */
    private boolean tooOldForColdStart(Doc doc) {
        Instant oldest = wall.instant().minus(lease).minus(skew);
        return Instant.ofEpochMilli(doc.issuedAtMillis()).isBefore(oldest);
    }

    // ---- operator writes ------------------------------------------------------------------------------

    /**
     * Pauses {@code target}: adds one fresh element for it, always, whether or not it is already paused. Allowed
     * whenever the cluster can be written.
     *
     * @throws ClusterOperationRefusedException if the operating cap is reached, the write loses its race
     *                                          {@value #MAX_ATTEMPTS} times, or the document would be over the
     *                                          format bound
     */
    void pause(Target target, Dependencies deps) {
        change(Op.PAUSE, Objects.requireNonNull(target, "target"), 0L, deps);
    }

    /**
     * Resumes {@code target}: tombstones the ids live for it in the first base this call reads, and no others, so a
     * pause that lands meanwhile stays in force. The preconditions of R13 hold on every read.
     */
    void resume(Target target, Dependencies deps) {
        change(Op.RESUME, Objects.requireNonNull(target, "target"), 0L, deps);
    }

    /** Ends a scope's pause: as {@link #resume} for the scope. Waits for the lock until {@code deadlineNanos}. */
    void endScope(String scopeId, long deadlineNanos, Dependencies deps) {
        change(Op.END_SCOPE, Target.scope(Objects.requireNonNull(scopeId, "scopeId")), deadlineNanos, deps);
    }

    private void change(Op op, Target target, long deadlineNanos, Dependencies deps) {
        IMap<String, Object> map = map();
        // The lock is taken before the read, so writers queue for it instead of racing; correctness still rests on
        // the compare-and-set, which is what a writer that holds an expired lease, or a split-brain, falls back on.
        if (deadlineNanos == 0L) {
            ClusterLocks.acquire(map, key, deps.lockTimeout());
        } else {
            ClusterLocks.acquireUntil(map, key, deadlineNanos, deps.lockTimeout());
        }
        try {
            Set<Id> observed = null;
            String issuer = issuer();
            for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
                Doc heldBefore = accepted();
                Object stored = map.get(key);
                // The snapshot is taken under the oversight lock, as part of the decision it serves.
                Look look = judge(stored, deps, peers.snapshot(), heldBefore);
                if (op != Op.PAUSE) {
                    refuseUnless(look, deps);
                }
                Doc base;
                Doc next;
                Id fresh = null;
                Set<Id> allowed = Set.of();
                RuntimeException refusal = null;
                synchronized (monitor) {
                    Doc storedDoc = look.stored();
                    long top = Math.max(held == null ? 0 : held.sequence(), storedDoc == null ? 0 : storedDoc.sequence());
                    if (op == Op.PAUSE) {
                        if (look.status() == Status.GATED) {
                            base = held == null ? storedDoc : held.merge(storedDoc);
                        } else {
                            base = held;
                        }
                        int size = base == null ? 0 : base.elements().size();
                        int cap = target.kind() == Kind.ALL ? FORMAT_BOUND : PAUSE_CAP;
                        if (size + 1 > cap) {
                            refusal = ClusterRefusals.refused(new IllegalStateException(
                                    "the pause document is at its operating cap"));
                        }
                        if (refusal != null) {
                            next = null;
                        } else {
                            fresh = Id.random(random);
                            Map<Id, Target> elements = new HashMap<>(base == null ? Map.of() : base.elements());
                            elements.put(fresh, target);
                            next = new Doc(top + 1, 0, wall.millis(), issuer, elements,
                                    base == null ? Set.of() : base.tombstones());
                        }
                    } else {
                        base = held;
                        Set<Id> liveNow = base.live(target);
                        if (observed == null) {
                            observed = liveNow;
                        }
                        Set<Id> tombstoned = new HashSet<>(observed);
                        tombstoned.retainAll(liveNow);
                        if (tombstoned.isEmpty()) {
                            return;
                        }
                        Set<Id> tombstones = new HashSet<>(base.tombstones());
                        tombstones.addAll(tombstoned);
                        allowed = tombstoned;
                        next = new Doc(top + 1, 0, wall.millis(), issuer, base.elements(), tombstones);
                    }
                }
                if (refusal != null) {
                    throw refusal;
                }
                // Over UNREADABLE or GATED the exact stored bytes are replaced, with the gated content unioned in.
                Outcome outcome = commit(stored, look.stored(), base, next, fresh, allowed, look.fresh(), deps);
                switch (outcome) {
                    case WRITTEN -> {
                        return;
                    }
                    case LOST_RACE -> { }
                    default -> throw ClusterRefusals.refused(new IllegalStateException(
                            "the pause document write was refused: " + outcome));
                }
            }
        } finally {
            ClusterLocks.release(map, key);
        }
        throw ClusterRefusals.refused(new IllegalStateException("the pause document write lost its race"));
    }

    /** R13's preconditions, in R13's order and with its codes. Throws; returns only if all hold. */
    private void refuseUnless(Look look, Dependencies deps) {
        synchronized (monitor) {
            if (held == null) {
                throw unavailable();
            }
        }
        if (!deps.status().get().serving()) {
            throw ClusterRefusals.refused(new IllegalStateException("the member is not serving"));
        }
        PeerView peerView = look.peers();
        if (peerView.unverifiedCount() > 0) {
            throw ClusterRefusals.refused(new IllegalStateException("a member is unverified"));
        }
        if (peerStateUnknown(peerView)) {
            throw ClusterRefusals.refused(new IllegalStateException("the state of the other members is unknown"));
        }
        if (look.status() != Status.AUTHENTIC) {
            throw unavailable();
        }
        if (!look.passed()) {
            throw unavailable();
        }
    }

    // ---- the commit -----------------------------------------------------------------------------------

    /**
     * Checks {@code next} against the guard clauses and, if they hold, seals it and writes it over exactly
     * {@code storedRaw} ({@code putIfAbsent} when that is null), then accepts it and runs the gate as a post-write
     * renewal point (R6, clause 4). The caller holds the key's lock.
     *
     * @param storedRaw   the value read, to be replaced; null if there was none
     * @param storedDoc   the authentic document read, else null
     * @param base        what {@code next} was computed from
     * @param fresh       the one element a pause adds, else null
     * @param tombstones  the tombstones the change may add beyond the base (a resume's observed ids still live)
     * @param freshBefore whether this member was fresh when the write began, as it stood after the judging read
     */
    Outcome commit(Object storedRaw, Doc storedDoc, Doc base, Doc next, Id fresh, Set<Id> tombstones,
                   boolean freshBefore, Dependencies deps) {
        Outcome refused = guard(storedDoc, base, next, fresh, tombstones);
        if (refused != null) {
            return refused;
        }
        String sealed = deps.sealer().seal(PrivacyCluster.OVERSIGHT_MAP, key, next.encode());
        beforeCommit.run();
        IMap<String, Object> map = map();
        boolean written = storedRaw == null ? map.putIfAbsent(key, sealed) == null : map.replace(key, storedRaw, sealed);
        // A retried invocation that had already applied finds its own bytes: the nonce is used for nothing else.
        if (!written && !sealed.equals(map.get(key))) {
            return Outcome.LOST_RACE;
        }
        acceptWritten(next, sealed, freshBefore);
        assertFence(deps);
        return Outcome.WRITTEN;
    }

    private static Outcome guard(Doc stored, Doc base, Doc next, Id fresh, Set<Id> tombstones) {
        if (next.elements().size() > FORMAT_BOUND || next.tombstones().size() > FORMAT_BOUND) {
            return Outcome.OVERSIZE;
        }
        if (stored != null && !next.covers(stored)) {
            return Outcome.DROPS_STORED;
        }
        for (Map.Entry<Id, Target> entry : next.elements().entrySet()) {
            Id id = entry.getKey();
            boolean inBase = base != null && entry.getValue().equals(base.elements().get(id));
            if (!inBase && !id.equals(fresh)) {
                return Outcome.ADDS_ELEMENT;
            }
        }
        for (Id id : next.tombstones()) {
            if (!(base != null && base.tombstones().contains(id)) && !tombstones.contains(id)) {
                return Outcome.ADDS_TOMBSTONE;
            }
        }
        if (stored != null) {
            boolean above = next.compareOrder(stored) > 0;
            boolean refresh = next.sequence() == stored.sequence() && next.refresh() == stored.refresh() + 1
                    && next.elements().equals(stored.elements()) && next.tombstones().equals(stored.tombstones());
            if (!above && !refresh) {
                return Outcome.ORDER;
            }
        }
        return null;
    }

    /**
     * Merges a document this member wrote into what it holds and runs the gate as a post-write renewal point. Neither
     * the commit nor this takes a renew argument: a member that was lapsed when the write began never renews from it.
     */
    private void acceptWritten(Doc next, String sealed, boolean freshBefore) {
        PeerView peerView = peers.snapshot();
        synchronized (monitor) {
            Memo written = new Memo(next);
            written.merged = true;
            written.digest = next.digest();
            Doc before = held;
            held = held == null ? next : held.merge(next);
            memo.put(sealed, written);
            storedSealed = sealed;
            storedSeenSince = nanos.getAsLong();
            if (before != held) {
                boolean grew = before == null || held.elements().size() != before.elements().size()
                        || held.tombstones().size() != before.tombstones().size();
                heldChanged(grew ? held.digest() : heldDigest, grew, before == null || held.sequence() != before.sequence());
            }
            appendSeen(written.digest);
            gate(peerView, true, freshBefore);
        }
    }

    /**
     * Keeps {@code all} = TRUE in the 0.7 map, so a 0.7 member that joins sees everything paused. Best effort: a
     * write that fails leaves only 0.7 members, which must not be there, without the fence. A member without keys is
     * solo by construction and refuses any member that joins, so it writes none.
     */
    private void assertFence(Dependencies deps) {
        if (cluster.memberCards().isEmpty()) {
            return;
        }
        try {
            instances.get().<String, Object>getMap(PrivacyCluster.OVERSIGHT_FENCE_MAP)
                    .tryPut(FENCE_KEY, Boolean.TRUE, deps.lockTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (RuntimeException ignored) {
            // See above.
        }
    }

    private String issuer() {
        try {
            return instances.get().getCluster().getLocalMember().getUuid().toString();
        } catch (RuntimeException unknown) {
            return "unknown";
        }
    }

    // ---- maintenance ----------------------------------------------------------------------------------

    /**
     * One pass of the lease, by the ticker or by a test, in the order of R6: judge (with the gate); then decide and
     * write, if it writes; then accept the write (a post-write renewal point). Writes are a union repair or restore
     * (R9), the first document (R8) and the refresh (R10). Never throws.
     */
    void tick(Dependencies deps) {
        attempt("tick", () -> maintain(deps));
    }

    private void maintain(Dependencies deps) {
        Look first = look(deps);
        if (decide(first) == null) {
            return;
        }
        IMap<String, Object> map = map();
        ClusterLocks.acquire(map, key, deps.lockTimeout());
        try {
            for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
                Doc heldBefore = accepted();
                Object stored = map.get(key);
                // R8 judges the member list under the lock, in the same decision as the write.
                Look look = judge(stored, deps, peers.snapshot(), heldBefore);
                Action action = decide(look);
                if (action == null) {
                    return;
                }
                Outcome outcome = commit(look.status() == Status.AUTHENTIC ? stored : null, look.stored(),
                        action.base(), action.next(), null, Set.of(), action.freshBefore(), deps);
                if (outcome == Outcome.WRITTEN && action.recovery()) {
                    warnRecovery();
                }
                if (outcome != Outcome.LOST_RACE) {
                    return;
                }
            }
        } finally {
            ClusterLocks.release(map, key);
        }
    }

    /** What the tick would write for {@code look}, or null for nothing. */
    private Action decide(Look look) {
        PeerView peerView = look.peers();
        String issuer = look.status() == Status.ABSENT || look.status() == Status.AUTHENTIC ? issuer() : null;
        synchronized (monitor) {
            long now = nanos.getAsLong();
            switch (look.status()) {
                case UNREADABLE, GATED -> {
                    // Never overwritten by a tick; only an operator pause replaces such a value (R9).
                    return null;
                }
                case ABSENT -> {
                    if (held != null) {
                        // pushHeld: putIfAbsent(held) at held.sequence + 1, whether fresh or lapsed (R9).
                        return new Action(held, new Doc(held.sequence() + 1, 0, wall.millis(), issuer, held.elements(),
                                held.tombstones()), look.fresh(), true);
                    }
                    return firstStart(peerView, now, issuer);
                }
                default -> {
                    Doc stored = look.stored();
                    if (!stored.covers(held)) {
                        // pushHeld: held ⊔ stored at max + 1, over the exact stored bytes (R9).
                        long top = Math.max(held.sequence(), stored.sequence());
                        return new Action(held, new Doc(top + 1, 0, wall.millis(), issuer, held.elements(),
                                held.tombstones()), look.fresh(), true);
                    }
                    if (look.fresh() && stored.elements().equals(held.elements())
                            && stored.tombstones().equals(held.tombstones())
                            && now - storedSeenSince >= lease.dividedBy(2).toNanos()) {
                        // R10: a refresh, from a member that is fresh, once half a lease has passed.
                        return new Action(stored, new Doc(stored.sequence(), stored.refresh() + 1, wall.millis(),
                                issuer, stored.elements(), stored.tombstones()), look.fresh(), false);
                    }
                    return null;
                }
            }
        }
    }

    /** R8: the first Doc(1), for a member with no document over an ABSENT one. The monitor is held. */
    private Action firstStart(PeerView peerView, long now, String issuer) {
        boolean alone = peerView.isAlone();
        if (!alone && !(peerView.isOldest() && now - createdNanos >= lease.toNanos())) {
            return null;
        }
        if (peerView.unverifiedCount() != 0) {
            return null;
        }
        if (!alone) {
            if (!peerView.lookCompleted() || peerView.lookAgeNanos() > lease.dividedBy(2).toNanos()) {
                return null;
            }
            long clean = peerView.cleanRunAgeNanos();
            if (clean < 0 || clean - peerView.lookAgeNanos() < lease.toNanos()) {
                return null;
            }
        }
        return new Action(null, Doc.empty(1, 0, wall.millis(), issuer), false, false);
    }

    /** Notes the document held now as the side of a heal that a higher cluster document would hide. */
    void beginMerge() {
        synchronized (monitor) {
            mergeBase = held;
        }
    }

    /**
     * Reconciles with the cluster after this member's side of a split healed (OD-14): writes
     * {@code mergeBase ⊔ held ⊔ stored} at one sequence above the held and stored documents, only if the stored one
     * lacks something of it. A write is a post-write renewal point; no write leaves only the ordinary judgement.
     */
    void reconcileAfterMerge(Dependencies deps) {
        attempt("reconcile after a merge", () -> {
            Doc began;
            synchronized (monitor) {
                began = mergeBase;
                mergeBase = null;
            }
            IMap<String, Object> map = map();
            ClusterLocks.acquire(map, key, deps.lockTimeout());
            try {
                String issuer = issuer();
                for (int tries = 0; tries < MAX_ATTEMPTS; tries++) {
                    Doc heldBefore = accepted();
                    Object stored = map.get(key);
                    Look look = judge(stored, deps, peers.snapshot(), heldBefore);
                    if (look.status() == Status.UNREADABLE || look.status() == Status.GATED) {
                        return;
                    }
                    Doc base;
                    Doc next;
                    synchronized (monitor) {
                        if (held == null && began == null) {
                            return;
                        }
                        base = held == null ? began : (began == null ? held : held.merge(began));
                        Doc other = look.stored();
                        if (other != null && other.covers(base)) {
                            return;
                        }
                        long top = Math.max(held == null ? 0 : held.sequence(), other == null ? 0 : other.sequence());
                        next = new Doc(top + 1, 0, wall.millis(), issuer, base.elements(), base.tombstones());
                    }
                    if (commit(look.status() == Status.AUTHENTIC ? stored : null, look.stored(), base, next, null,
                            Set.of(), look.fresh(), deps) != Outcome.LOST_RACE) {
                        return;
                    }
                }
            } finally {
                ClusterLocks.release(map, key);
            }
        });
    }

    /** Empties the held content, keeping its order, as if a merge had hidden it; for tests of {@link #beginMerge}. */
    void forgetContentForTest() {
        synchronized (monitor) {
            if (held != null) {
                held = new Doc(held.sequence(), held.refresh(), held.issuedAtMillis(), held.issuer(), Map.of(),
                        Set.of());
                pauses = held.pauses();
                heldDigest = held.digest();
            }
        }
    }

    private void warnMismatch() {
        if (shouldWarn(WARN_MISMATCH)) {
            log.warn("a pause document was refused: it names a held id as another target");
        }
    }

    private void warnRecovery() {
        if (shouldWarn(WARN_RECOVERY)) {
            log.warn("a pause document was put back from the content this member holds");
        }
    }

    private boolean shouldWarn(int kind) {
        long now = nanos.getAsLong();
        synchronized (monitor) {
            long last = lastWarn[kind];
            if (last != Long.MIN_VALUE && now - last < lease.toNanos()) {
                return false;
            }
            lastWarn[kind] = now;
            return true;
        }
    }

    /**
     * Runs one maintenance step and never throws: the next tick, or the next read, tries again, and meanwhile the
     * document is judged by what was accepted. A failure is logged, rate-limited, by stable code and cause class only.
     */
    private void attempt(String step, Runnable body) {
        try {
            body.run();
        } catch (RuntimeException failed) {
            if (shouldWarn(WARN_MAINTENANCE)) {
                log.warn("PAUSE_DOCUMENT_MAINTENANCE_FAILED: the pause document {} did not complete; cause {}", step,
                        failed.getClass().getSimpleName());
            }
        }
    }
    // ---- lifecycle ------------------------------------------------------------------------------------

    /**
     * Registers a user of this document, with the dependencies its ticks use. The first user starts the lease: a
     * first pass, the merge listener, and a ticker if the environment has an interval. The ticker stops when the last
     * user releases; the document itself lives on, so a later user finds what it held.
     */
    void retain(Dependencies deps) {
        ScheduledExecutorService started;
        synchronized (monitor) {
            users++;
            retained.add(deps);
            tickDependencies = deps;
            if (closed || executor != null) {
                return;
            }
            started = Executors.newSingleThreadScheduledExecutor(task -> {
                Thread thread = new Thread(task, "dataprism-pause-document");
                thread.setDaemon(true);
                return thread;
            });
            executor = started;
        }
        try {
            start(deps, started);
        } catch (RuntimeException | Error failed) {
            // Undo this user: a failed start leaves no ticker and no listener behind.
            release(deps);
            throw failed;
        }
    }

    private void start(Dependencies deps, ScheduledExecutorService started) {
        tick(deps);
        // A first document written by the tick renews nobody (R8). The next judgement does, so make it now: a lone
        // member is then established before any other member joins.
        attempt("first judgement", () -> look(deps));
        HazelcastInstance instance = instances.get();
        UUID listener = instance.getLifecycleService().addLifecycleListener(event -> {
            if (event.getState() == LifecycleEvent.LifecycleState.MERGING) {
                beginMerge();
            } else if (event.getState() == LifecycleEvent.LifecycleState.MERGED) {
                Dependencies current = tickDependencies;
                submit(() -> reconcileAfterMerge(current));
            }
        });
        ScheduledExecutorService running;
        boolean superseded;
        synchronized (monitor) {
            // A release may have stopped this executor while the listener was being added: do not keep the listener.
            superseded = closed || executor != started;
            if (!superseded) {
                lifecycleListener = listener;
            }
            running = executor;
        }
        if (superseded) {
            try {
                instance.getLifecycleService().removeLifecycleListener(listener);
            } catch (RuntimeException gone) {
                // The instance may already be shut down.
            }
            return;
        }
        if (running != null && tickInterval != null && !tickInterval.isZero() && !tickInterval.isNegative()) {
            long millis = tickInterval.toMillis();
            try {
                running.scheduleWithFixedDelay(this::tickWhileRunning, millis, millis, TimeUnit.MILLISECONDS);
            } catch (RejectedExecutionException closedDown) {
                // Released already.
            }
        }
    }

    /** A user is done: the last release stops the ticker and the merge listener. The document stays. */
    void release(Dependencies deps) {
        boolean last;
        synchronized (monitor) {
            retained.remove(deps);
            if (users > 0) {
                users--;
            }
            last = users == 0;
            if (!retained.isEmpty()) {
                // Hand the ticks to a user that is still live, not the one that has just gone.
                tickDependencies = retained.get(retained.size() - 1);
            }
        }
        if (last) {
            stopTicking();
        }
    }

    private void tickWhileRunning() {
        boolean running;
        try {
            running = instances.get().getLifecycleService().isRunning();
        } catch (RuntimeException gone) {
            running = false;
        }
        if (running) {
            Dependencies deps = tickDependencies;
            if (deps != null) {
                tick(deps);
            }
        } else {
            stopTicking();
        }
    }

    private void submit(Runnable task) {
        ScheduledExecutorService running;
        synchronized (monitor) {
            running = executor;
        }
        if (running != null) {
            try {
                running.execute(task);
            } catch (RejectedExecutionException closedDown) {
                // Closed.
            }
        }
    }

    /** Stops the ticker and the merge listener; the document stays in the cluster. Safe to call twice. */
    void close() {
        synchronized (monitor) {
            closed = true;
            users = 0;
            retained.clear();
        }
        stopTicking();
    }

    private void stopTicking() {
        ScheduledExecutorService stopping;
        UUID listener;
        synchronized (monitor) {
            stopping = executor;
            executor = null;
            listener = lifecycleListener;
            lifecycleListener = null;
        }
        if (stopping != null) {
            stopping.shutdownNow();
        }
        if (listener != null) {
            try {
                instances.get().getLifecycleService().removeLifecycleListener(listener);
            } catch (RuntimeException gone) {
                // The instance may already be shut down.
            }
        }
    }
}

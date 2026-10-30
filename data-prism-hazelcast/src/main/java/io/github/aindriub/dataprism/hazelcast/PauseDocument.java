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
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
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
 * The pause state of the cluster as one sealed, sequenced, leased document (docs/plan/design/cluster-security.md
 * 2.3.3, OD-3, OD-4, OD-14, OD-15). It sits under one keyed name in {@link PrivacyCluster#OVERSIGHT_MAP}; scope ids,
 * tool names and the flag for everything live only in its sealed plaintext.
 *
 * <p><b>Pause state is an observed-remove set.</b> The document holds <i>elements</i> (each a pause of one target
 * under a fresh 128-bit id) and <i>tombstones</i> (ids). A target is paused while some element for it has no
 * tombstone. A pause always adds a new element; a resume tombstones the ids it observed, and only those, so a pause
 * that lands while a resume is being built stays in force. Documents are merged by union on every acceptance, so
 * nothing a member holds is lost to a document that is merely newer, and the merge is commutative, associative and
 * idempotent. The sequence and the refresh counter order documents for freshness; they never decide content.
 *
 * <p><b>Order and memory.</b> Documents are ordered by (sequence, refresh). An instance keeps the union of every
 * authentic document it accepted, with the highest order seen. A document of lower order never resets the lease, so
 * a replayed older document is not fresh; its content is still merged, and a member whose cluster document lacks
 * something it holds writes the union back.
 *
 * <p><b>Writes</b> are {@code replace(name, exactOldBytes, newBytes)}, or {@code putIfAbsent} when the document is
 * absent, under the key's bounded lock, after a read under that lock. A lost race re-reads and tries again,
 * {@value #MAX_ATTEMPTS} times, then fails closed. No time bound is added to the write itself beyond the lock wait.
 *
 * <p><b>Freshness</b> is measured from the local monotonic receipt of a document of higher order, for one lease. The
 * wall clock gates only the first acceptance after a cold start. A document that is absent, unauthentic, behind what
 * the instance has accepted, lacking something it holds, or older than its lease is not fresh, and every read then
 * refuses.
 *
 * <p><b>Residual</b>, stated plainly: a captured pre-pause document, replayed to an instance that has not yet seen
 * the pause and not older than the cold-start gate, lifts the pause for at most the lease.
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

        /** The union of the contents, under this header. */
        Doc union(Doc other) {
            if (covers(other)) {
                return this;
            }
            Map<Id, Target> mergedElements = new HashMap<>(elements);
            mergedElements.putAll(other.elements);
            Set<Id> mergedTombstones = new HashSet<>(tombstones);
            mergedTombstones.addAll(other.tombstones);
            return new Doc(sequence, refresh, issuedAtMillis, issuer, mergedElements, mergedTombstones);
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
        /** A document of higher order than any accepted: merged and accepted. */
        ACCEPTED,
        /** A document of the order already accepted that lacks nothing held. */
        CURRENT,
        /** A document of lower order than one accepted, or one that lacks something held: merged, not fresh. */
        BEHIND,
        /** A first document older than the cold-start gate allows: refused. */
        GATED
    }

    /**
     * What a look found.
     *
     * @param stored    the authentic document read, else null
     * @param lacks     whether the stored document lacks an element or tombstone held
     * @param sameBytes whether the stored text is the one the held order came from
     */
    record Look(Status status, Doc stored, boolean lacks, boolean sameBytes) {
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

    /** What the cards say about the other members; replaced by tests. */
    interface Peers {
        /** The highest pause sequence any verified member reports, this member's own card included. */
        long highestReportedSequence();

        /** How many joined members have no valid card. */
        int unverifiedCount();

        /** Whether this member is alone or the oldest member of the cluster. */
        boolean isOldest();
    }

    private static final class Memo {
        final Doc doc;
        /** Whether the document was merged into the held one: held only grows, so it stays true. */
        boolean merged;
        /** Whether this document was counted as a conflict. */
        boolean counted;

        Memo(Doc doc) {
            this.doc = doc;
        }
    }

    private record Action(Doc base, Doc next, boolean recovery) {
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
    private final Sealer sealer;
    private final String key;
    private final PrivacyMetrics metrics;
    private final LongSupplier nanos;
    private final Clock wall;
    private final Duration lease;
    private final Duration skew;
    private final Duration lockTimeout;
    private final Duration tickInterval;
    private final SecureRandom random = new SecureRandom();

    private final Object monitor = new Object();
    /** The union of every authentic document accepted, under the highest order seen; null before the first. */
    private Doc held;
    /** The sealed text the held order came from. */
    private String heldSealed;
    /** What is paused according to {@link #held}; recomputed when the content changes. */
    private Pauses view = Pauses.NONE;
    /** When the held order was last raised, on the monotonic clock. */
    private long receivedAt;
    /** Whether the latest look found the cluster at least as far on as {@link #held}. */
    private boolean lookOk;
    /** The document held when a merge began. */
    private Doc mergeBase;
    /** Where the wait for a first start began or was last restarted, on the monotonic clock. */
    private long quietSince;
    private long lastMismatchWarn = Long.MIN_VALUE;
    private long lastRecoveryWarn = Long.MIN_VALUE;
    private final Map<String, Memo> memo = new LinkedHashMap<>(MEMO_SIZE, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Memo> eldest) {
            return size() > MEMO_SIZE;
        }
    };

    private ScheduledExecutorService executor;
    private UUID lifecycleListener;
    private boolean closed;

    /** Runs after a write has read the stored value and sealed its next one, before it commits; a test seam. */
    volatile Runnable beforeCommit = () -> { };
    volatile Peers peers;
    /** Decodes an authentic plaintext; a test seam that counts decodes. */
    volatile Function<byte[], Doc> decoder = Doc::decode;
    volatile Logger log = LoggerFactory.getLogger(PauseDocument.class);

    PauseDocument(PrivacyCluster cluster, Supplier<HazelcastInstance> instances, PrivacyMetrics metrics,
                  Duration lockTimeout, Environment environment) {
        this.cluster = Objects.requireNonNull(cluster, "cluster");
        this.instances = Objects.requireNonNull(instances, "instances");
        this.metrics = FailSafeMetrics.wrap(Objects.requireNonNull(metrics, "metrics"));
        this.lockTimeout = ClusterLocks.checked(lockTimeout);
        this.nanos = environment.nanos();
        this.wall = environment.wall();
        this.tickInterval = environment.tickInterval();
        ClusterSecuritySettings settings = cluster.securitySettings();
        this.lease = settings.pauseLease();
        this.skew = settings.clockSkewTolerance();
        this.quietSince = nanos.getAsLong();
        // The ungated sealer: a member that is not serving must still read and write the pause document.
        this.sealer = IdentityMaps.sealerFor(cluster, this.metrics, ServingGate.open());
        this.key = cluster.keyedNames().mapKey(PrivacyCluster.OVERSIGHT_MAP, CANONICAL);
        this.peers = new Peers() {
            @Override
            public long highestReportedSequence() {
                return cluster.memberCards().map(MemberCards::highestReportedPauseSequence).orElse(0L);
            }

            @Override
            public int unverifiedCount() {
                return cluster.memberCards().map(MemberCards::unverifiedCount).orElse(0);
            }

            @Override
            public boolean isOldest() {
                try {
                    var hazelcast = instances.get().getCluster();
                    Iterator<Member> oldest = hazelcast.getMembers().iterator();
                    return !oldest.hasNext()
                            || oldest.next().getUuid().equals(hazelcast.getLocalMember().getUuid());
                } catch (RuntimeException unknown) {
                    return false;
                }
            }
        };
    }

    // ---- reads ----------------------------------------------------------------------------------------

    /**
     * Looks at the cluster and answers what is paused.
     *
     * @throws IllegalStateException if the document is absent, unauthentic or not fresh (the caller sees
     *                               {@code OVERSIGHT_UNAVAILABLE})
     */
    Pauses current() {
        Look look = look();
        if (look.status() == Status.ABSENT) {
            boolean cold;
            synchronized (monitor) {
                cold = held == null;
            }
            if (cold) {
                // The first start, if this member may make it; every other gap waits for a tick or an operator.
                attempt(() -> maintain(look));
                look();
            }
        }
        synchronized (monitor) {
            if (!freshLocked()) {
                throw unavailable();
            }
            return view;
        }
    }

    /** Whether the pause document is present, authentic and inside its lease, as of the latest look. */
    boolean fresh() {
        synchronized (monitor) {
            return freshLocked();
        }
    }

    /** The age of the accepted document from its local receipt, in whole seconds; -1 if none was accepted. */
    long ageSeconds() {
        synchronized (monitor) {
            return held == null ? -1 : Math.max(0, (nanos.getAsLong() - receivedAt) / NANOS_PER_SECOND);
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

    /** Registers {@code pause.fresh} and {@code pause.age_seconds} with the metrics this document counts into. */
    void registerGauges() {
        metrics.gauge(Metric.CLUSTER_PAUSE_FRESH, () -> fresh() ? 1 : 0);
        metrics.gauge(Metric.CLUSTER_PAUSE_AGE_SECONDS, () -> {
            long age = ageSeconds();
            return age < 0 ? Double.NaN : age;
        });
    }

    private boolean freshLocked() {
        return held != null && lookOk && nanos.getAsLong() - receivedAt < lease.toNanos();
    }

    private static IllegalStateException unavailable() {
        return new IllegalStateException("the pause document is absent, unauthentic or not fresh");
    }

    private IMap<String, Object> map() {
        return instances.get().getMap(PrivacyCluster.OVERSIGHT_MAP);
    }

    private Look look() {
        return judge(map().get(key));
    }

    /**
     * Judges a stored value against what this instance has accepted, and merges what it finds into what it holds.
     * A document of higher order raises the held order and resets the lease; one of lower or equal order never does,
     * but its content is still merged.
     */
    private Look judge(Object stored) {
        if (stored == null) {
            synchronized (monitor) {
                lookOk = false;
            }
            return new Look(Status.ABSENT, null, false, false);
        }
        Memo entry = open(stored);
        if (entry == null) {
            synchronized (monitor) {
                lookOk = false;
            }
            return new Look(Status.UNREADABLE, null, false, false);
        }
        String sealed = (String) stored;
        Doc doc = entry.doc;
        long now = nanos.getAsLong();
        boolean mismatch = false;
        Look result = null;
        synchronized (monitor) {
            if (held == null) {
                if (tooOldForColdStart(doc)) {
                    lookOk = false;
                    return new Look(Status.GATED, doc, false, false);
                }
                held = doc;
                heldSealed = sealed;
                entry.merged = true;
                receivedAt = now;
                lookOk = true;
                view = held.pauses();
                return new Look(Status.ACCEPTED, doc, false, true);
            }
            if (!entry.merged && held.conflictsWith(doc)) {
                lookOk = false;
                mismatch = true;
            } else {
                int order = doc.compareOrder(held);
                boolean lacks;
                boolean brings;
                if (entry.merged) {
                    // Held only grows and already holds this document, so it differs only if it holds more.
                    lacks = held.elements().size() != doc.elements().size()
                            || held.tombstones().size() != doc.tombstones().size();
                    brings = false;
                } else {
                    lacks = !doc.covers(held);
                    brings = !held.covers(doc);
                }
                if ((lacks || (order <= 0 && brings)) && !entry.counted) {
                    entry.counted = true;
                    metrics.increment(Metric.CLUSTER_PAUSE_CONFLICT);
                }
                boolean sameBytes = order == 0 && sealed.equals(heldSealed);
                if (brings) {
                    held = held.union(doc);
                    view = held.pauses();
                }
                entry.merged = true;
                if (order > 0) {
                    held = held.withHeader(doc.sequence(), doc.refresh(), doc.issuedAtMillis(), doc.issuer());
                    heldSealed = sealed;
                    receivedAt = now;
                }
                lookOk = order >= 0 && !lacks;
                Status status = order > 0 ? Status.ACCEPTED : (order == 0 && !lacks ? Status.CURRENT : Status.BEHIND);
                result = new Look(status, doc, lacks, sameBytes);
            }
        }
        if (mismatch) {
            metrics.increment(Metric.CLUSTER_SEAL_TAMPER);
            warnMismatch();
            return new Look(Status.UNREADABLE, null, false, false);
        }
        return result;
    }

    /** The document in a stored value, memoised by its exact text, or null (counted) if it is not authentic. */
    private Memo open(Object stored) {
        if (!(stored instanceof String envelope)) {
            metrics.increment(Metric.CLUSTER_SEAL_TAMPER);
            return null;
        }
        synchronized (monitor) {
            Memo known = memo.get(envelope);
            if (known != null) {
                return known;
            }
        }
        Opened opened = sealer.open(PrivacyCluster.OVERSIGHT_MAP, key, envelope);
        if (opened.outcome() != Opened.Outcome.AUTHENTIC) {
            // The sealer has counted it.
            return null;
        }
        Doc doc = decoder.apply(opened.plaintext());
        if (doc == null) {
            metrics.increment(Metric.CLUSTER_SEAL_TAMPER);
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
     * whenever the cluster can be written; it re-creates a document that is absent or unauthentic and keeps what
     * this member holds.
     *
     * @throws ClusterOperationRefusedException if the operating cap is reached, the write loses its race
     *                                          {@value #MAX_ATTEMPTS} times, or the document would be over the
     *                                          format bound
     */
    void pause(Target target) {
        change(Op.PAUSE, Objects.requireNonNull(target, "target"), 0L);
    }

    /**
     * Resumes {@code target}: tombstones the ids live for it in the first base this call reads, and no others, so a
     * pause that lands meanwhile stays in force. Needs this member to serve, no unverified member, no peer card above
     * the document, and a fresh document (OD-215-16 (a)).
     *
     * @throws ClusterOperationRefusedException if the member is not serving or a member is unverified
     * @throws IllegalStateException            if the document is absent, unauthentic, behind a peer or not fresh
     */
    void resume(Target target) {
        change(Op.RESUME, Objects.requireNonNull(target, "target"), 0L);
    }

    /**
     * Ends a scope's pause: as {@link #resume} for the scope, except that an absent document is a no-op success.
     * Waits for the lock until {@code deadlineNanos} ({@link System#nanoTime()} units).
     */
    void endScope(String scopeId, long deadlineNanos) {
        change(Op.END_SCOPE, Target.scope(Objects.requireNonNull(scopeId, "scopeId")), deadlineNanos);
    }

    private void change(Op op, Target target, long deadlineNanos) {
        if (op != Op.PAUSE && !cluster.securityStatus().serving()) {
            throw new ClusterOperationRefusedException(new IllegalStateException("the member is not serving"));
        }
        IMap<String, Object> map = map();
        // The lock is taken before the read, so writers queue for it instead of racing; correctness still rests on
        // the compare-and-set, which is what a writer that holds an expired lease, or a split-brain, falls back on.
        if (deadlineNanos == 0L) {
            ClusterLocks.acquire(map, key, lockTimeout);
        } else {
            ClusterLocks.acquireUntil(map, key, deadlineNanos, lockTimeout);
        }
        try {
            Set<Id> observed = null;
            for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
                Object stored = map.get(key);
                Look look = judge(stored);
                if (op == Op.END_SCOPE && look.status() == Status.ABSENT) {
                    return;
                }
                int unverified = op == Op.PAUSE ? 0 : peers.unverifiedCount();
                long highest = op == Op.PAUSE ? 0 : peers.highestReportedSequence();
                Doc base;
                Doc next;
                Id fresh = null;
                Set<Id> allowed = Set.of();
                RuntimeException refusal = null;
                synchronized (monitor) {
                    Doc storedDoc = look.stored();
                    long top = Math.max(held == null ? 0 : held.sequence(), storedDoc == null ? 0 : storedDoc.sequence());
                    if (op == Op.PAUSE) {
                        base = look.status() == Status.GATED ? storedDoc : held;
                        int size = base == null ? 0 : base.elements().size();
                        int cap = target.kind() == Kind.ALL ? FORMAT_BOUND : PAUSE_CAP;
                        if (size + 1 > cap) {
                            refusal = ClusterRefusals.refused(new IllegalStateException(
                                    "the pause document is at its operating cap"));
                        }
                        fresh = Id.random(random);
                        Map<Id, Target> elements = new HashMap<>(base == null ? Map.of() : base.elements());
                        elements.put(fresh, target);
                        next = new Doc(top + 1, 0, wall.millis(), issuer(), elements,
                                base == null ? Set.of() : base.tombstones());
                    } else {
                        if (look.status() == Status.UNREADABLE || look.status() == Status.GATED || held == null) {
                            throw unavailable();
                        }
                        if (unverified > 0) {
                            throw ClusterRefusals.refused(new IllegalStateException("a member is unverified"));
                        }
                        if (highest > held.sequence()) {
                            throw unavailable();
                        }
                        if (!freshLocked()) {
                            throw unavailable();
                        }
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
                        next = new Doc(top + 1, 0, wall.millis(), issuer(), base.elements(), tombstones);
                    }
                }
                if (refusal != null) {
                    throw refusal;
                }
                Outcome outcome = commit(stored, look.stored(), base, next, fresh, allowed);
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

    // ---- the commit -----------------------------------------------------------------------------------

    /**
     * Checks {@code next} against the guard clauses and, if they hold, seals it and writes it over exactly
     * {@code storedRaw} ({@code putIfAbsent} when that is null), then accepts it. The caller holds the key's lock.
     *
     * @param storedRaw  the value read, to be replaced; null if there was none
     * @param storedDoc  the authentic document read, else null
     * @param base       what {@code next} was computed from
     * @param fresh      the one element a pause adds, else null
     * @param tombstones the tombstones the change may add beyond the base (a resume's observed ids still live)
     */
    Outcome commit(Object storedRaw, Doc storedDoc, Doc base, Doc next, Id fresh, Set<Id> tombstones) {
        Outcome refused = guard(storedDoc, base, next, fresh, tombstones);
        if (refused != null) {
            return refused;
        }
        String sealed = sealer.seal(PrivacyCluster.OVERSIGHT_MAP, key, next.encode());
        beforeCommit.run();
        IMap<String, Object> map = map();
        boolean written = storedRaw == null ? map.putIfAbsent(key, sealed) == null : map.replace(key, storedRaw, sealed);
        // A retried invocation that had already applied finds its own bytes: the nonce is used for nothing else.
        if (!written && !sealed.equals(map.get(key))) {
            return Outcome.LOST_RACE;
        }
        acceptWritten(next, sealed);
        assertFence();
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

    private void acceptWritten(Doc next, String sealed) {
        long now = nanos.getAsLong();
        synchronized (monitor) {
            Memo written = new Memo(next);
            if (held == null) {
                held = next;
                heldSealed = sealed;
                receivedAt = now;
            } else {
                Doc merged = held.union(next);
                if (next.compareOrder(held) >= 0) {
                    held = merged.withHeader(next.sequence(), next.refresh(), next.issuedAtMillis(), next.issuer());
                    heldSealed = sealed;
                    receivedAt = now;
                } else {
                    held = merged;
                }
            }
            written.merged = true;
            memo.put(sealed, written);
            lookOk = next.covers(held);
            view = held.pauses();
        }
    }

    /**
     * Keeps {@code all} = TRUE in the 0.7 map, so a 0.7 member that joins sees everything paused. Best effort: a
     * write that fails leaves only 0.7 members, which must not be there, without the fence. A member without keys is
     * solo by construction and refuses any member that joins, so it writes none.
     */
    private void assertFence() {
        if (cluster.memberCards().isEmpty()) {
            return;
        }
        try {
            instances.get().<String, Object>getMap(PrivacyCluster.OVERSIGHT_FENCE_MAP)
                    .tryPut(FENCE_KEY, Boolean.TRUE, lockTimeout.toMillis(), TimeUnit.MILLISECONDS);
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
     * One pass of the lease, by the ticker or by a test: refresh the document when half the lease is spent, put it
     * back when it has gone from the cluster (OD-15), write the first one on a first start, write a union back when
     * the cluster document lacks something this member holds, and recover a lapsed lease over the document this
     * member last accepted. Never throws.
     */
    void tick() {
        attempt(() -> maintain(look()));
    }

    private void maintain(Look first) {
        sampleCards();
        if (decide(first) == null) {
            return;
        }
        IMap<String, Object> map = map();
        ClusterLocks.acquire(map, key, lockTimeout);
        try {
            for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
                Object stored = map.get(key);
                Look look = judge(stored);
                Action action = decide(look);
                if (action == null) {
                    return;
                }
                Outcome outcome = commit(stored, look.stored(), action.base(), action.next(), null, Set.of());
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

    /** A look at the cards while no document is held: a card above zero restarts the wait for a first start. */
    private void sampleCards() {
        long highest = peers.highestReportedSequence();
        synchronized (monitor) {
            if (held == null && highest > 0) {
                quietSince = nanos.getAsLong();
            }
        }
    }

    /** What the tick would write for {@code look}, or null for nothing. */
    private Action decide(Look look) {
        long highest = peers.highestReportedSequence();
        int unverified = peers.unverifiedCount();
        boolean oldest = look.status() == Status.ABSENT && peers.isOldest();
        boolean serving = look.sameBytes() && look.status() == Status.CURRENT && cluster.securityStatus().serving();
        synchronized (monitor) {
            long now = nanos.getAsLong();
            switch (look.status()) {
                case UNREADABLE, GATED -> {
                    return null;
                }
                case ABSENT -> {
                    if (held != null) {
                        // OD-15 (i): only from a document accepted within the last lease, and only if no verified
                        // member reports a higher sequence.
                        if (now - receivedAt >= lease.toNanos() || highest > held.sequence()) {
                            return null;
                        }
                        return new Action(held, successor(held), false);
                    }
                    if (highest > 0) {
                        quietSince = now;
                        return null;
                    }
                    if (!oldest || unverified != 0 || now - quietSince < lease.toNanos()) {
                        return null;
                    }
                    return new Action(null, Doc.empty(1, 0, wall.millis(), issuer()), false);
                }
                case BEHIND -> {
                    if (now - receivedAt >= lease.toNanos() || highest > held.sequence()) {
                        return null;
                    }
                    return new Action(held, unionWrite(look), false);
                }
                default -> {
                    if (look.lacks()) {
                        return new Action(held, unionWrite(look), false);
                    }
                    long age = now - receivedAt;
                    if (age >= lease.toNanos()) {
                        if (serving && unverified == 0 && highest <= held.sequence()) {
                            return new Action(held, successor(held), true);
                        }
                        return null;
                    }
                    if (freshLocked() && age >= lease.dividedBy(2).toNanos()) {
                        return new Action(held, successor(held), false);
                    }
                    return null;
                }
            }
        }
    }

    /** The held content at one sequence above the held and the stored documents; the monitor is held. */
    private Doc unionWrite(Look look) {
        long top = Math.max(held.sequence(), look.stored() == null ? 0 : look.stored().sequence());
        return new Doc(top + 1, 0, wall.millis(), issuer(), held.elements(), held.tombstones());
    }

    private Doc successor(Doc doc) {
        return new Doc(doc.sequence(), doc.refresh() + 1, wall.millis(), issuer(), doc.elements(), doc.tombstones());
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
     * lacks something of it.
     */
    void reconcileAfterMerge() {
        attempt(() -> {
            Doc began;
            synchronized (monitor) {
                began = mergeBase;
                mergeBase = null;
            }
            IMap<String, Object> map = map();
            ClusterLocks.acquire(map, key, lockTimeout);
            try {
                for (int tries = 0; tries < MAX_ATTEMPTS; tries++) {
                    Object stored = map.get(key);
                    Look look = judge(stored);
                    if (look.status() == Status.UNREADABLE || look.status() == Status.GATED) {
                        return;
                    }
                    Doc base;
                    Doc next;
                    synchronized (monitor) {
                        if (held == null && began == null) {
                            return;
                        }
                        base = held == null ? began : (began == null ? held : held.union(began));
                        Doc other = look.stored();
                        if (other != null && other.covers(base)) {
                            return;
                        }
                        long top = Math.max(held == null ? 0 : held.sequence(), other == null ? 0 : other.sequence());
                        next = new Doc(top + 1, 0, wall.millis(), issuer(), base.elements(), base.tombstones());
                    }
                    if (commit(stored, look.stored(), base, next, null, Set.of()) != Outcome.LOST_RACE) {
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
                view = held.pauses();
            }
        }
    }

    private void warnMismatch() {
        if (shouldWarn(true)) {
            log.warn("a pause document was refused: it names a held id as another target");
        }
    }

    private void warnRecovery() {
        if (shouldWarn(false)) {
            log.warn("the pause lease had lapsed and was renewed over the document this member last accepted");
        }
    }

    private boolean shouldWarn(boolean mismatch) {
        long now = nanos.getAsLong();
        synchronized (monitor) {
            long last = mismatch ? lastMismatchWarn : lastRecoveryWarn;
            if (last != Long.MIN_VALUE && now - last < lease.toNanos()) {
                return false;
            }
            if (mismatch) {
                lastMismatchWarn = now;
            } else {
                lastRecoveryWarn = now;
            }
            return true;
        }
    }

    private static void attempt(Runnable step) {
        try {
            step.run();
        } catch (RuntimeException failed) {
            // Never throws from maintenance: the next tick, or the next read, tries again, and meanwhile the
            // document is judged by what was accepted.
        }
    }

    // ---- lifecycle ------------------------------------------------------------------------------------

    /** Starts the lease: a first pass, the merge listener, and a ticker if the environment has an interval. */
    void start() {
        synchronized (monitor) {
            if (closed || executor != null) {
                return;
            }
            executor = Executors.newSingleThreadScheduledExecutor(task -> {
                Thread thread = new Thread(task, "dataprism-pause-document");
                thread.setDaemon(true);
                return thread;
            });
        }
        attempt(this::tick);
        HazelcastInstance instance = instances.get();
        lifecycleListener = instance.getLifecycleService().addLifecycleListener(event -> {
            if (event.getState() == LifecycleEvent.LifecycleState.MERGING) {
                beginMerge();
            } else if (event.getState() == LifecycleEvent.LifecycleState.MERGED) {
                submit(this::reconcileAfterMerge);
            }
        });
        if (tickInterval != null && !tickInterval.isZero() && !tickInterval.isNegative()) {
            long millis = tickInterval.toMillis();
            executor.scheduleWithFixedDelay(this::tickWhileRunning, millis, millis, TimeUnit.MILLISECONDS);
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
            tick();
        } else {
            close();
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
        ScheduledExecutorService stopping;
        UUID listener;
        synchronized (monitor) {
            closed = true;
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

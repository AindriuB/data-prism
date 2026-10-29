package io.github.aindriub.dataprism.hazelcast;

import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.core.LifecycleEvent;
import com.hazelcast.map.IMap;
import io.github.aindriub.dataprism.core.metrics.Metric;
import io.github.aindriub.dataprism.core.metrics.PrivacyMetrics;
import io.github.aindriub.dataprism.core.refusal.ClusterOperationRefusedException;
import io.github.aindriub.dataprism.hazelcast.seal.Opened;
import io.github.aindriub.dataprism.hazelcast.seal.Sealer;
import io.github.aindriub.dataprism.hazelcast.seal.ServingGate;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.function.UnaryOperator;

/**
 * The pause state of the cluster as one sealed, sequenced, leased document (docs/plan/design/cluster-security.md
 * 2.3.3, OD-3, OD-4, OD-14, OD-15). It sits under one keyed name in {@link PrivacyCluster#OVERSIGHT_MAP}; scope ids,
 * tool names and the flag for everything live only in its sealed plaintext.
 *
 * <p><b>Order and memory.</b> Documents are ordered by (sequence, refresh). An instance keeps the highest document it
 * accepted and never accepts a lower one, so a deleted entry cannot lift a pause it has seen, and a replayed older
 * document is refused by every instance that saw a newer one.
 *
 * <p><b>Writes</b> are {@code replace(name, exactOldBytes, newBytes)}, or {@code putIfAbsent} when the document is
 * absent, under the key's bounded lock. A lost race re-reads and tries again, {@value #MAX_ATTEMPTS} times, then
 * fails closed. No time bound is added to the write itself beyond the lock wait: a write that races a join
 * migration can take up to Hazelcast's backup timeout, as {@code ClusterSentinel.startupBound()} allows for, and
 * refusing earlier would refuse a healthy pause for nothing a retry could mend.
 *
 * <p><b>Freshness</b> is measured from the local monotonic receipt of a document of higher order, for one lease. The
 * wall clock gates only the first acceptance after a cold start. A document that is absent, unauthentic, behind what
 * the instance has accepted, or older than its lease is not fresh, and every read then refuses.
 *
 * <p><b>Residual</b>, stated plainly: a captured pre-pause document, replayed to an instance that has not yet seen
 * the pause and not older than the cold-start gate, lifts the pause for at most the lease.
 */
final class PauseDocument {

    /** The canonical name the document's map key is derived from. */
    static final String CANONICAL = "pause-document";
    /** How many times a write that lost its race reads again and retries before it refuses. */
    static final int MAX_ATTEMPTS = 10;

    private static final byte FORMAT = 1;
    private static final String FENCE_KEY = "all";
    private static final int MAX_ENTRIES = 1 << 20;
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

    /** What the document says is paused. */
    record Pauses(boolean all, Set<String> tools, Set<String> scopes) {

        static final Pauses NONE = new Pauses(false, Set.of(), Set.of());

        Pauses {
            tools = Set.copyOf(tools);
            scopes = Set.copyOf(scopes);
        }

        Pauses withAll(boolean value) {
            return new Pauses(value, tools, scopes);
        }

        Pauses withTool(String tool, boolean paused) {
            return new Pauses(all, change(tools, tool, paused), scopes);
        }

        Pauses withScope(String scope, boolean paused) {
            return new Pauses(all, tools, change(scopes, scope, paused));
        }

        Pauses union(Pauses other) {
            Set<String> mergedTools = new TreeSet<>(tools);
            mergedTools.addAll(other.tools);
            Set<String> mergedScopes = new TreeSet<>(scopes);
            mergedScopes.addAll(other.scopes);
            return new Pauses(all || other.all, mergedTools, mergedScopes);
        }

        /** Whether every pause of {@code other} is also a pause here. */
        boolean covers(Pauses other) {
            return (all || !other.all) && tools.containsAll(other.tools) && scopes.containsAll(other.scopes);
        }

        private static Set<String> change(Set<String> set, String member, boolean present) {
            Set<String> changed = new TreeSet<>(set);
            if (present) {
                changed.add(Objects.requireNonNull(member, "name"));
            } else {
                changed.remove(member);
            }
            return changed;
        }
    }

    /** One document, as sealed in the cluster. */
    record Doc(long sequence, long refresh, long issuedAtMillis, String issuer, Pauses pauses) {

        int compareOrder(Doc other) {
            int bySequence = Long.compare(sequence, other.sequence);
            return bySequence != 0 ? bySequence : Long.compare(refresh, other.refresh);
        }

        byte[] encode() {
            try {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                DataOutputStream out = new DataOutputStream(bytes);
                out.writeByte(FORMAT);
                out.writeLong(sequence);
                out.writeLong(refresh);
                out.writeLong(issuedAtMillis);
                writeText(out, issuer);
                out.writeBoolean(pauses.all());
                writeAll(out, pauses.tools());
                writeAll(out, pauses.scopes());
                return bytes.toByteArray();
            } catch (IOException impossible) {
                throw new IllegalStateException("the pause document could not be encoded");
            }
        }

        /** The document in {@code plaintext}, or null if it is not exactly one well-formed document. */
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
                boolean all = in.readBoolean();
                Set<String> tools = readAll(in);
                Set<String> scopes = readAll(in);
                if (in.available() != 0 || sequence < 0 || refresh < 0) {
                    return null;
                }
                return new Doc(sequence, refresh, issuedAt, issuer, new Pauses(all, tools, scopes));
            } catch (IOException | RuntimeException malformed) {
                return null;
            }
        }

        private static void writeAll(DataOutputStream out, Collection<String> values) throws IOException {
            out.writeInt(values.size());
            for (String value : new TreeSet<>(values)) {
                writeText(out, value);
            }
        }

        private static Set<String> readAll(DataInputStream in) throws IOException {
            int count = in.readInt();
            if (count < 0 || count > MAX_ENTRIES || count > in.available()) {
                throw new IOException("bad count");
            }
            Set<String> values = new TreeSet<>();
            for (int i = 0; i < count; i++) {
                values.add(readText(in));
            }
            return values;
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
        /** An entry that is not an authentic, well-formed document. */
        UNREADABLE,
        /** A document of higher order than any accepted: accepted. */
        ACCEPTED,
        /** The document already accepted. */
        CURRENT,
        /** A document of lower order than one accepted: refused. */
        BEHIND,
        /** A first document older than the cold-start gate allows: refused. */
        GATED,
        /** Equal order, different pauses: the union is adopted at the next sequence and is yet to be written. */
        CONFLICT
    }

    private record Look(Status status, Doc stored) {
    }

    /** What the cards say about the other members; replaced by tests. */
    interface Peers {
        /** The highest pause sequence any verified member reports. */
        long highestReportedSequence();

        /** Whether a verified member other than this one is present. */
        boolean othersVerified();
    }

    private final PrivacyCluster cluster;
    private final Sealer sealer;
    private final String key;
    private final PrivacyMetrics metrics;
    private final LongSupplier nanos;
    private final Clock wall;
    private final Duration lease;
    private final Duration skew;
    private final Duration lockTimeout;
    private final Duration tickInterval;

    private final Object monitor = new Object();
    /** The highest document accepted, or null before the first. */
    private Doc held;
    /** When {@link #held} was last accepted, on the monotonic clock. */
    private long receivedAt;
    /** Whether the latest look found the cluster at least as far on as {@link #held}. */
    private boolean lookOk;
    /** The document held when a merge began: the side of a heal that a higher cluster document would hide. */
    private Doc mergeBase;

    private ScheduledExecutorService executor;
    private UUID lifecycleListener;
    private boolean closed;

    /** Runs after a write has read the stored value and sealed its next one, before it commits; a test seam. */
    volatile Runnable beforeCommit = () -> { };
    volatile Peers peers;

    PauseDocument(PrivacyCluster cluster, PrivacyMetrics metrics, Duration lockTimeout, Environment environment) {
        this.cluster = Objects.requireNonNull(cluster, "cluster");
        this.metrics = FailSafeMetrics.wrap(Objects.requireNonNull(metrics, "metrics"));
        this.lockTimeout = ClusterLocks.checked(lockTimeout);
        this.nanos = environment.nanos();
        this.wall = environment.wall();
        this.tickInterval = environment.tickInterval();
        ClusterSecuritySettings settings = cluster.securitySettings();
        this.lease = settings.pauseLease();
        this.skew = settings.clockSkewTolerance();
        // The ungated sealer: a member that is not serving must still read and write the pause document.
        this.sealer = IdentityMaps.sealerFor(cluster, this.metrics, ServingGate.open());
        this.key = cluster.keyedNames().mapKey(PrivacyCluster.OVERSIGHT_MAP, CANONICAL);
        this.peers = new Peers() {
            @Override
            public long highestReportedSequence() {
                return cluster.memberCards().map(MemberCards::highestReportedPauseSequence).orElse(0L);
            }

            @Override
            public boolean othersVerified() {
                var cards = cluster.memberCards();
                if (cards.isEmpty()) {
                    return false;
                }
                UUID self = cluster.instance().getCluster().getLocalMember().getUuid();
                return cards.get().verifiedMembers().stream().anyMatch(member -> !member.equals(self));
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
        Status status = look().status();
        if (status == Status.ABSENT) {
            startIfAlone();
            look();
        } else if (status == Status.CONFLICT) {
            attempt(this::pushHeld);
        }
        synchronized (monitor) {
            if (!freshLocked()) {
                throw unavailable();
            }
            return held.pauses();
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
        return cluster.instance().getMap(PrivacyCluster.OVERSIGHT_MAP);
    }

    private Look look() {
        return judge(map().get(key));
    }

    /** Judges a stored value against what this instance has accepted, and updates what it has accepted. */
    private Look judge(Object stored) {
        if (stored == null) {
            synchronized (monitor) {
                lookOk = false;
            }
            return new Look(Status.ABSENT, null);
        }
        Doc doc = open(stored);
        if (doc == null) {
            synchronized (monitor) {
                lookOk = false;
            }
            return new Look(Status.UNREADABLE, null);
        }
        long now = nanos.getAsLong();
        synchronized (monitor) {
            if (held == null) {
                if (tooOldForColdStart(doc)) {
                    lookOk = false;
                    return new Look(Status.GATED, doc);
                }
                held = doc;
                receivedAt = now;
                lookOk = true;
                return new Look(Status.ACCEPTED, doc);
            }
            int order = doc.compareOrder(held);
            if (order > 0) {
                held = doc;
                receivedAt = now;
                lookOk = true;
                return new Look(Status.ACCEPTED, doc);
            }
            if (order < 0) {
                lookOk = false;
                return new Look(Status.BEHIND, doc);
            }
            if (doc.pauses().equals(held.pauses())) {
                lookOk = true;
                return new Look(Status.CURRENT, doc);
            }
            // The same order with different pauses can only come from a split-brain merge or a key holder behaving
            // badly: adopt the union one sequence up, count it, and write it back when the caller gets to it.
            metrics.increment(Metric.CLUSTER_PAUSE_CONFLICT);
            held = new Doc(held.sequence() + 1, 0, wall.millis(), issuer(), held.pauses().union(doc.pauses()));
            receivedAt = now;
            lookOk = false;
            return new Look(Status.CONFLICT, doc);
        }
    }

    /** The document in a stored value, or null (counted as a tamper) if it is not an authentic document. */
    private Doc open(Object stored) {
        if (!(stored instanceof String envelope)) {
            metrics.increment(Metric.CLUSTER_SEAL_TAMPER);
            return null;
        }
        Opened opened = sealer.open(PrivacyCluster.OVERSIGHT_MAP, key, envelope);
        if (opened.outcome() != Opened.Outcome.AUTHENTIC) {
            // The sealer has counted it.
            return null;
        }
        Doc doc = Doc.decode(opened.plaintext());
        if (doc == null) {
            metrics.increment(Metric.CLUSTER_SEAL_TAMPER);
        }
        return doc;
    }

    /** The wall-clock gate, used for the first acceptance after a cold start and for nothing else. */
    private boolean tooOldForColdStart(Doc doc) {
        Instant oldest = wall.instant().minus(lease).minus(skew);
        return Instant.ofEpochMilli(doc.issuedAtMillis()).isBefore(oldest);
    }

    // ---- writes ---------------------------------------------------------------------------------------

    /**
     * Pauses or resumes. A pause is allowed whenever the cluster can be written, and re-creates a document that is
     * absent or unauthentic; a resume needs this member to serve and the document to be fresh (OD-215-16 (a)).
     *
     * @throws ClusterOperationRefusedException if a resume is asked of a member that is not serving
     * @throws IllegalStateException            if a resume is asked while the document is not fresh
     */
    void update(UnaryOperator<Pauses> change, boolean resume) {
        if (resume && !cluster.securityStatus().serving()) {
            throw new ClusterOperationRefusedException(new IllegalStateException("the member is not serving"));
        }
        IMap<String, Object> map = map();
        // The lock is taken before the read, so writers queue for it instead of racing; correctness still rests on
        // the compare-and-set, which is what a writer that holds an expired lease, or a split-brain, falls back on.
        ClusterLocks.acquire(map, key, lockTimeout);
        try {
            for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
                Object stored = map.get(key);
                Look look = judge(stored);
                Doc next;
                synchronized (monitor) {
                    if (resume && !freshLocked()) {
                        throw unavailable();
                    }
                    Doc top = highest(held, look.stored());
                    long sequence = Math.max(top == null ? 0 : top.sequence(), peers.highestReportedSequence()) + 1;
                    next = new Doc(sequence, 0, wall.millis(), issuer(),
                            change.apply(top == null ? Pauses.NONE : top.pauses()));
                }
                if (commit(stored, next)) {
                    return;
                }
            }
        } finally {
            ClusterLocks.release(map, key);
        }
        throw ClusterRefusals.refused(new IllegalStateException("the pause document write lost its race"));
    }

    /** Seals {@code next} and writes it over exactly {@code stored}; accepts it on success. */
    private boolean commit(Object stored, Doc next) {
        String sealed = sealer.seal(PrivacyCluster.OVERSIGHT_MAP, key, next.encode());
        beforeCommit.run();
        IMap<String, Object> map = map();
        ClusterLocks.acquire(map, key, lockTimeout);
        try {
            boolean written = stored == null ? map.putIfAbsent(key, sealed) == null : map.replace(key, stored, sealed);
            // A retried invocation that had already applied finds its own bytes: the nonce is used for nothing else.
            if (!written && !sealed.equals(map.get(key))) {
                return false;
            }
        } finally {
            ClusterLocks.release(map, key);
        }
        synchronized (monitor) {
            if (held == null || next.compareOrder(held) >= 0) {
                held = next;
            }
            receivedAt = nanos.getAsLong();
            lookOk = true;
        }
        assertFence();
        return true;
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
            cluster.instance().<String, Object>getMap(PrivacyCluster.OVERSIGHT_FENCE_MAP)
                    .tryPut(FENCE_KEY, Boolean.TRUE, lockTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (RuntimeException ignored) {
            // See above.
        }
    }

    private static Doc highest(Doc a, Doc b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return b.compareOrder(a) > 0 ? b : a;
    }

    private String issuer() {
        try {
            return cluster.instance().getCluster().getLocalMember().getUuid().toString();
        } catch (RuntimeException unknown) {
            return "unknown";
        }
    }

    // ---- maintenance ----------------------------------------------------------------------------------

    /**
     * One pass of the lease: refresh the document when half the lease is spent, put it back when it has gone from
     * the cluster (OD-15), write the first one on a first start, and push a union that is yet to be written. Never
     * throws.
     */
    void tick() {
        attempt(() -> {
            Object stored = map().get(key);
            Look look = judge(stored);
            switch (look.status()) {
                case ABSENT -> {
                    if (!restore(null, null)) {
                        startIfAlone();
                    }
                }
                case BEHIND -> restore(stored, look.stored());
                case CONFLICT -> pushHeld();
                case ACCEPTED, CURRENT -> refreshIfDue(stored);
                default -> { }
            }
        });
    }

    private void refreshIfDue(Object stored) {
        Doc doc;
        synchronized (monitor) {
            if (!freshLocked() || nanos.getAsLong() - receivedAt < lease.dividedBy(2).toNanos()) {
                return;
            }
            doc = held;
        }
        commit(stored, successor(doc));
    }

    /**
     * OD-15: put the document back, with {@code putIfAbsent} or over what is stored, only from a document accepted
     * within the last lease, and only if no verified member reports a higher sequence. A stored document that is
     * behind ours (a replay, or the side a merge kept) may hold a pause we lack: it is written back as a union, so
     * that repairing the cluster never drops a pause from either.
     *
     * @param behind the stored document when it is behind the one held, else null
     */
    private boolean restore(Object stored, Doc behind) {
        Doc doc;
        synchronized (monitor) {
            if (held == null || nanos.getAsLong() - receivedAt >= lease.toNanos()) {
                return false;
            }
            doc = held;
        }
        if (peers.highestReportedSequence() > doc.sequence()) {
            return false;
        }
        if (behind != null && !doc.pauses().covers(behind.pauses())) {
            commit(stored, new Doc(doc.sequence() + 1, 0, wall.millis(), issuer(),
                    doc.pauses().union(behind.pauses())));
        } else {
            commit(stored, successor(doc));
        }
        return true;
    }

    /** First start: a member that finds no document and no other verified member writes the first one. */
    private void startIfAlone() {
        synchronized (monitor) {
            if (held != null) {
                return;
            }
        }
        if (peers.othersVerified()) {
            return;
        }
        commit(null, new Doc(1, 0, wall.millis(), issuer(), Pauses.NONE));
    }

    private void pushHeld() {
        Object stored = map().get(key);
        Doc doc;
        synchronized (monitor) {
            doc = held;
        }
        if (doc != null) {
            commit(stored, new Doc(doc.sequence(), doc.refresh(), wall.millis(), issuer(), doc.pauses()));
        }
    }

    private Doc successor(Doc doc) {
        return new Doc(doc.sequence(), doc.refresh() + 1, wall.millis(), issuer(), doc.pauses());
    }

    /** Notes the document held now as the side of a heal that a higher cluster document would hide. */
    void beginMerge() {
        synchronized (monitor) {
            mergeBase = held;
        }
    }

    /**
     * Reconciles with the cluster after this member's side of a split healed (OD-14): a pause that the side held
     * and the cluster document lacks is written back as a union at one sequence above both.
     */
    void reconcileAfterMerge() {
        attempt(() -> {
            Doc base;
            synchronized (monitor) {
                base = mergeBase != null ? mergeBase : held;
                mergeBase = null;
            }
            if (base == null) {
                return;
            }
            for (int tries = 0; tries < MAX_ATTEMPTS; tries++) {
                Object stored = map().get(key);
                Look look = judge(stored);
                if (look.status() == Status.CONFLICT) {
                    pushHeld();
                    return;
                }
                if (look.status() == Status.UNREADABLE || look.status() == Status.GATED) {
                    return;
                }
                Doc other = look.stored();
                if (other != null && other.pauses().covers(base.pauses())) {
                    return;
                }
                Pauses union = other == null ? base.pauses() : base.pauses().union(other.pauses());
                long top = Math.max(base.sequence(), other == null ? 0 : other.sequence());
                synchronized (monitor) {
                    top = Math.max(top, held == null ? 0 : held.sequence());
                }
                top = Math.max(top, peers.highestReportedSequence());
                if (commit(stored, new Doc(top + 1, 0, wall.millis(), issuer(), union))) {
                    return;
                }
            }
        });
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
        HazelcastInstance instance = cluster.instance();
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
            running = cluster.instance().getLifecycleService().isRunning();
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
                cluster.instance().getLifecycleService().removeLifecycleListener(listener);
            } catch (RuntimeException gone) {
                // The instance may already be shut down.
            }
        }
    }
}

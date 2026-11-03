package io.github.aindriub.dataprism.hazelcast;

import com.hazelcast.cluster.InitialMembershipEvent;
import com.hazelcast.cluster.InitialMembershipListener;
import com.hazelcast.cluster.Member;
import com.hazelcast.cluster.MembershipEvent;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.map.IMap;
import io.github.aindriub.dataprism.core.metrics.Metric;
import io.github.aindriub.dataprism.core.metrics.PrivacyMetrics;
import io.github.aindriub.dataprism.oversight.OversightSnapshot;
import io.github.aindriub.dataprism.oversight.OversightState;

import java.time.Clock;
import java.time.Duration;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

/**
 * Pause state shared across the cluster, so pausing on one instance pauses all.
 *
 * <p>The state is one sealed {@link PauseDocument} under a freshness gate (docs/plan/design/cluster-security.md
 * 2.3.3). There is one main document per Hazelcast instance, kept in the instance's user context and shared by every
 * wrapper of it. A document that is absent, unauthentic or not fresh means admission cannot be evaluated: every read
 * throws, which callers fail closed on ({@code OVERSIGHT_UNAVAILABLE}). A delete of the document is a visible denial
 * of service, never a lifted pause.
 *
 * <p>Fails closed: every method goes to the cluster and lets its exception escape. A caller that cannot learn
 * whether it is paused must treat itself as paused, and cannot do that if this class invents an answer.
 *
 * <p>A pause is accepted whether or not this member is serving, and re-creates a document that is absent. It always
 * adds a new element, so pausing a paused target twice is two pauses, and a resume lifts only the pauses it saw: a
 * pause that lands while the resume is built stays in force. A resume is refused while the member is not serving or a
 * member is unverified or peer state is unknown ({@code CLUSTER_OPERATION_REFUSED}), and while the document is
 * absent, unauthentic or the gate does not pass ({@code OVERSIGHT_UNAVAILABLE}). The document is reached through {@code instance()}, so the
 * serving gate does not stop it. A scope pause written by a 0.7 member after construction is still found, by its raw key, and keeps that
 * scope paused. Every write waits for the key's lock at most the lock timeout and then fails with
 * {@code CLUSTER_LOCK_TIMEOUT}.
 *
 * <p>The shared document is ticked while at least one {@code HazelcastOversightState} of the instance is open;
 * the ticker stops with the last {@link #close()} or when the Hazelcast instance stops, and the document stays.
 */
public final class HazelcastOversightState implements OversightState, AutoCloseable {

    private static final String LEGACY_PAUSED_SUFFIX = "\0paused";
    private static final String MAIN_DOCUMENT_CONTEXT_KEY = "dataprism.cluster.pause.main";
    private static final String GAUGE_SLOT_CONTEXT_KEY = "dataprism.cluster.pause.gauges";
    private static final String KEYLESS_REFUSED_CONTEXT_KEY = "dataprism.cluster.pause.keyless.refused";

    /** Where the pause gauges read the instance's document; claimed once per instance. */
    private record GaugeSlot(PauseDocument document, HazelcastInstance instance) {
    }

    private final PrivacyCluster cluster;
    private final PauseDocument document;
    private final PauseDocument.Dependencies dependencies;
    private final AtomicBoolean closed = new AtomicBoolean();

    /** With the default lock timeout of two seconds. */
    public HazelcastOversightState(PrivacyCluster cluster) {
        this(cluster, ClusterLocks.DEFAULT_TIMEOUT);
    }

    /**
     * @param lockTimeout how long a write waits for its key's lock before it is refused, {@code PT100MS} to
     *                    {@code PT30S}
     * @throws IllegalArgumentException if the timeout is outside that range
     */
    public HazelcastOversightState(PrivacyCluster cluster, Duration lockTimeout) {
        this(cluster, lockTimeout, PrivacyMetrics.none());
    }

    /** As above, counting into {@code metrics} and registering the {@code pause.fresh} and age gauges with it. */
    public HazelcastOversightState(PrivacyCluster cluster, Duration lockTimeout, PrivacyMetrics metrics) {
        this(cluster, lockTimeout, metrics,
                PauseDocument.Environment.system(Objects.requireNonNull(cluster, "cluster")
                        .securitySettings().pauseLease()));
    }

    /** As above, with the two clocks a keyless member's document uses; a test seam. */
    HazelcastOversightState(PrivacyCluster cluster, Duration lockTimeout, PrivacyMetrics metrics, Clock wall,
                            LongSupplier nanos) {
        this(cluster, lockTimeout, metrics, new PauseDocument.Environment(nanos, wall,
                PauseDocument.Environment.system(Objects.requireNonNull(cluster, "cluster")
                        .securitySettings().pauseLease()).tickInterval()));
    }

    /** As above, with the clocks and the tick a test injects. A keyed member's document ignores the clocks. */
    HazelcastOversightState(PrivacyCluster cluster, Duration lockTimeout, PrivacyMetrics metrics,
                            PauseDocument.Environment environment) {
        this.cluster = Objects.requireNonNull(cluster, "cluster");
        this.dependencies = PauseDocument.Dependencies.of(cluster, metrics, lockTimeout);
        refuseLegacyEntries();
        this.document = mainDocument(cluster, environment);
        if (document != null) {
            cluster.memberCards().ifPresent(cards -> cards.reportPauseSequence(document::acceptedSequence));
            registerGauges(metrics, dependencies.metrics());
            document.retain(dependencies);
        }
    }

    /**
     * The instance's one main document, made by the first state to ask. A keyed member's document uses the instance
     * clocks the first wrapper recorded; a keyless member's, the clocks of the first state. A keyed member whose
     * start has not passed has no instance clocks, and every read then refuses.
     */
    private static PauseDocument mainDocument(PrivacyCluster cluster, PauseDocument.Environment environment) {
        HazelcastInstance instance = cluster.instance();
        Object known = instance.getUserContext().get(MAIN_DOCUMENT_CONTEXT_KEY);
        if (known != null) {
            return (PauseDocument) known;
        }
        PauseDocument.Environment clocks = environment;
        PauseDocument.Peers peers;
        if (cluster.memberCards().isPresent()) {
            MemberCards.Clocks recorded = MemberCards.declarations(instance).clocks();
            if (recorded == null) {
                return null;
            }
            clocks = new PauseDocument.Environment(recorded.nanos(), recorded.wall(), environment.tickInterval());
            peers = MemberCards.peers(cluster);
        } else {
            peers = lonePeers(cluster, instance);
        }
        PauseDocument created = new PauseDocument(cluster, cluster::instance, clocks, true, peers);
        Object winner = instance.getUserContext().putIfAbsent(MAIN_DOCUMENT_CONTEXT_KEY, created);
        return winner == null ? created : (PauseDocument) winner;
    }

    /**
     * The Peers of a member without cluster keys: nothing is tracked, no member is unverified, and peer state is
     * known only while it is the only member. Once refused (a member joined it), it stays refused for the life of the
     * instance (IR-54): the mark lives in the instance's user context.
     */
    static PauseDocument.Peers lonePeers(PrivacyCluster cluster, HazelcastInstance instance) {
        AtomicBoolean refused = (AtomicBoolean) instance.getUserContext()
                .computeIfAbsent(KEYLESS_REFUSED_CONTEXT_KEY, key -> {
                    AtomicBoolean mark = new AtomicBoolean();
                    instance.getCluster().addMembershipListener(new InitialMembershipListener() {
                        @Override
                        public void init(InitialMembershipEvent event) {
                            if (event.getMembers().size() > 1) {
                                mark.set(true);
                            }
                        }

                        @Override
                        public void memberAdded(MembershipEvent event) {
                            mark.set(true);
                        }

                        @Override
                        public void memberRemoved(MembershipEvent event) {
                        }
                    });
                    return mark;
                });
        return () -> {
            if (cluster.isRefused()) {
                refused.set(true);
            }
            Set<Member> members = instance.getCluster().getMembers();
            UUID self = instance.getCluster().getLocalMember().getUuid();
            boolean other = members.stream().anyMatch(member -> !member.getUuid().equals(self));
            boolean alone = !other && !refused.get();
            if (!alone) {
                return new PauseDocument.PeerView(Map.of(), 0, false, Long.MAX_VALUE, -1, false, false);
            }
            boolean oldest = true;
            return new PauseDocument.PeerView(Map.of(), 0, true, 0, -1, alone, oldest);
        };
    }

    /**
     * Registers {@code pause.fresh} and {@code pause.age_seconds} once per instance, with the first state that has a
     * real registry. The slot is claimed atomically; nothing is ever unregistered, and the suppliers read the document
     * through the slot, never through a state.
     */
    private void registerGauges(PrivacyMetrics supplied, PrivacyMetrics metrics) {
        if (supplied.getClass() == PrivacyMetrics.none().getClass()) {
            return;
        }
        HazelcastInstance instance = cluster.instance();
        boolean[] mine = {false};
        GaugeSlot slot = (GaugeSlot) instance.getUserContext().computeIfAbsent(GAUGE_SLOT_CONTEXT_KEY, key -> {
            mine[0] = true;
            return new GaugeSlot(document, instance);
        });
        if (!mine[0]) {
            return;
        }
        metrics.gauge(Metric.CLUSTER_PAUSE_FRESH, () -> live(slot) && slot.document().fresh() ? 1 : 0);
        metrics.gauge(Metric.CLUSTER_PAUSE_AGE_SECONDS, () -> {
            if (!live(slot)) {
                return Double.NaN;
            }
            long age = slot.document().ageSeconds();
            return age < 0 ? Double.NaN : age;
        });
    }

    private static boolean live(GaugeSlot slot) {
        try {
            return slot.instance().getLifecycleService().isRunning();
        } catch (RuntimeException gone) {
            return false;
        }
    }

    /** The pause document, for tests; null for a keyed member whose start has not passed. */
    PauseDocument document() {
        return document;
    }

    private PauseDocument doc() {
        if (document == null) {
            throw PauseDocument.unavailable();
        }
        return document;
    }

    /**
     * Whether this member serves, with the pause document's freshness and age added. A member whose pause document
     * is not fresh reports {@code PAUSE_NOT_FRESH}. Carries no pause content.
     */
    public ClusterSecurityStatus securityStatus() {
        return cluster.securityStatus().withPause(document != null && document.fresh(),
                document == null ? -1 : document.ageSeconds());
    }

    @Override
    public boolean allPaused() {
        return doc().current(dependencies).all();
    }

    @Override
    public boolean toolPaused(String tool) {
        return doc().current(dependencies).tools().contains(tool);
    }

    @Override
    public boolean scopePaused(String scopeId) {
        if (doc().current(dependencies).scopes().contains(scopeId)) {
            return true;
        }
        // A 0.7 pause written after construction is keyed by the raw scope id, NUL and "paused". It is matched
        // exactly, one lookup, so that scope stays paused (fail closed) though this member cannot resume it.
        return flags().containsKey(scopeId + LEGACY_PAUSED_SUFFIX);
    }

    @Override
    public void pauseAll() {
        doc().pause(PauseDocument.Target.ALL, dependencies);
    }

    @Override
    public void resumeAll() {
        doc().resume(PauseDocument.Target.ALL, dependencies);
    }

    @Override
    public void pauseTool(String tool) {
        Objects.requireNonNull(tool, "tool");
        doc().pause(PauseDocument.Target.tool(tool), dependencies);
    }

    @Override
    public void resumeTool(String tool) {
        Objects.requireNonNull(tool, "tool");
        doc().resume(PauseDocument.Target.tool(tool), dependencies);
    }

    @Override
    public void pauseScope(String scopeId) {
        Objects.requireNonNull(scopeId, "scopeId");
        doc().pause(PauseDocument.Target.scope(scopeId), dependencies);
    }

    @Override
    public void resumeScope(String scopeId) {
        Objects.requireNonNull(scopeId, "scopeId");
        doc().resume(PauseDocument.Target.scope(scopeId), dependencies);
    }

    @Override
    public OversightSnapshot snapshot() {
        PauseDocument.Pauses pauses = doc().current(dependencies);
        Set<String> scopes = new HashSet<>(pauses.scopes());
        for (String key : flags().keySet()) {
            if (key.endsWith(LEGACY_PAUSED_SUFFIX)) {
                scopes.add(key.substring(0, key.length() - LEGACY_PAUSED_SUFFIX.length()));
            }
        }
        return new OversightSnapshot(pauses.all(), pauses.tools(), scopes);
    }

    /**
     * Stops using the shared document. The last state of the instance to close stops the ticker and the merge watch;
     * the document and its content stay, so a later state finds them. Safe to call twice.
     */
    @Override
    public void close() {
        if (closed.compareAndSet(false, true) && document != null) {
            document.release();
        }
    }

    /**
     * A scope pause written before the keyed names of task 223 is keyed by the raw scope id, NUL and {@code paused}.
     * No current key holds a NUL, and this member would neither see such a pause nor remove it, so a paused scope
     * would silently resume. Refuses instead. A pause written after construction cannot be refused, so
     * {@link #scopePaused} also looks for the legacy key of the scope asked about and treats it as paused. Only this
     * map is scanned, here at construction and by the cluster's membership watch afterwards; raw NUL keys in the
     * other maps are not looked for, so upgrading from 0.7 or from before 223 needs the cluster state wiped by a
     * full restart.
     */
    private void refuseLegacyEntries() {
        for (String key : flags().keySet()) {
            if (key.indexOf('\0') >= 0) {
                throw new LegacyOversightStateException();
            }
        }
    }

    private IMap<String, Object> flags() {
        return cluster.instance().getMap(PrivacyCluster.OVERSIGHT_MAP);
    }
}

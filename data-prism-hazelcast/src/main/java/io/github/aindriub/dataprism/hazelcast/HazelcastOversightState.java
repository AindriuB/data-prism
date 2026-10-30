package io.github.aindriub.dataprism.hazelcast;

import com.hazelcast.map.IMap;
import io.github.aindriub.dataprism.core.metrics.PrivacyMetrics;
import io.github.aindriub.dataprism.oversight.OversightSnapshot;
import io.github.aindriub.dataprism.oversight.OversightState;

import java.time.Duration;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Pause state shared across the cluster, so pausing on one instance pauses all.
 *
 * <p>The state is one sealed, sequenced, leased {@link PauseDocument} (docs/plan/design/cluster-security.md 2.3.3).
 * A document that is absent, unauthentic, behind what this instance has accepted or past its lease means admission
 * cannot be evaluated: every read throws, which callers fail closed on ({@code OVERSIGHT_UNAVAILABLE}). A delete of
 * the document is a visible denial of service, never a lifted pause.
 *
 * <p>Fails closed: every method goes to the cluster and lets its exception escape. A caller that cannot learn
 * whether it is paused must treat itself as paused, and cannot do that if this class invents an answer.
 *
 * <p>A pause is accepted whether or not this member is serving, and re-creates a document that is absent. It always
 * adds a new element, so pausing a paused target twice is two pauses, and a resume lifts only the pauses it saw: a
 * pause that lands while the resume is built stays in force. A resume is refused while the member is not serving or a
 * member is unverified ({@code CLUSTER_OPERATION_REFUSED}), and while the document is not fresh or a peer reports a
 * higher sequence ({@code OVERSIGHT_UNAVAILABLE}). The document is reached through {@code instance()}, so the serving gate does not
 * stop it. A scope pause written by a 0.7 member after construction is still found, by its raw key, and keeps that
 * scope paused. Every write waits for the key's lock at most the lock timeout and then fails with
 * {@code CLUSTER_LOCK_TIMEOUT}.
 *
 * <p>Keeps a background task that refreshes the lease and watches for a split-brain merge; it stops with
 * {@link #close()} or when the Hazelcast instance stops.
 */
public final class HazelcastOversightState implements OversightState, AutoCloseable {

    private static final String LEGACY_PAUSED_SUFFIX = "\0paused";

    private final PrivacyCluster cluster;
    private final PauseDocument document;

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

    /** As above, with the clocks and the tick a test injects. */
    HazelcastOversightState(PrivacyCluster cluster, Duration lockTimeout, PrivacyMetrics metrics,
                            PauseDocument.Environment environment) {
        this.cluster = Objects.requireNonNull(cluster, "cluster");
        this.document = new PauseDocument(cluster, cluster::instance, metrics, ClusterLocks.checked(lockTimeout),
                environment);
        refuseLegacyEntries();
        cluster.memberCards().ifPresent(cards -> cards.reportPauseSequence(document::acceptedSequence));
        document.registerGauges();
        document.start();
    }

    /** The pause document, for tests. */
    PauseDocument document() {
        return document;
    }

    /**
     * Whether this member serves, with the pause document's freshness and age added. A member whose pause document
     * is not fresh reports {@code PAUSE_NOT_FRESH}. Carries no pause content.
     */
    public ClusterSecurityStatus securityStatus() {
        return cluster.securityStatus().withPause(document.fresh(), document.ageSeconds());
    }

    @Override
    public boolean allPaused() {
        return document.current().all();
    }

    @Override
    public boolean toolPaused(String tool) {
        return document.current().tools().contains(tool);
    }

    @Override
    public boolean scopePaused(String scopeId) {
        if (document.current().scopes().contains(scopeId)) {
            return true;
        }
        // A 0.7 pause written after construction is keyed by the raw scope id, NUL and "paused". It is matched
        // exactly, one lookup, so that scope stays paused (fail closed) though this member cannot resume it.
        return flags().containsKey(scopeId + LEGACY_PAUSED_SUFFIX);
    }

    @Override
    public void pauseAll() {
        document.pause(PauseDocument.Target.ALL);
    }

    @Override
    public void resumeAll() {
        document.resume(PauseDocument.Target.ALL);
    }

    @Override
    public void pauseTool(String tool) {
        Objects.requireNonNull(tool, "tool");
        document.pause(PauseDocument.Target.tool(tool));
    }

    @Override
    public void resumeTool(String tool) {
        Objects.requireNonNull(tool, "tool");
        document.resume(PauseDocument.Target.tool(tool));
    }

    @Override
    public void pauseScope(String scopeId) {
        Objects.requireNonNull(scopeId, "scopeId");
        document.pause(PauseDocument.Target.scope(scopeId));
    }

    @Override
    public void resumeScope(String scopeId) {
        Objects.requireNonNull(scopeId, "scopeId");
        document.resume(PauseDocument.Target.scope(scopeId));
    }

    @Override
    public OversightSnapshot snapshot() {
        PauseDocument.Pauses pauses = document.current();
        Set<String> scopes = new HashSet<>(pauses.scopes());
        for (String key : flags().keySet()) {
            if (key.endsWith(LEGACY_PAUSED_SUFFIX)) {
                scopes.add(key.substring(0, key.length() - LEGACY_PAUSED_SUFFIX.length()));
            }
        }
        return new OversightSnapshot(pauses.all(), pauses.tools(), scopes);
    }

    /** Stops the lease refresh and the merge watch. The document stays in the cluster. */
    @Override
    public void close() {
        document.close();
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

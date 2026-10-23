package io.github.aindriub.dataprism.hazelcast;

import com.hazelcast.cluster.InitialMembershipEvent;
import com.hazelcast.cluster.InitialMembershipListener;
import com.hazelcast.cluster.MembershipEvent;
import com.hazelcast.cluster.MembershipListener;
import com.hazelcast.config.ClassFilter;
import com.hazelcast.config.Config;
import com.hazelcast.config.EvictionConfig;
import com.hazelcast.config.EvictionPolicy;
import com.hazelcast.config.IndexConfig;
import com.hazelcast.config.IndexType;
import com.hazelcast.config.JavaSerializationFilterConfig;
import com.hazelcast.config.MapConfig;
import com.hazelcast.config.MaxSizePolicy;
import com.hazelcast.config.RestEndpointGroup;
import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.spi.properties.ClusterProperty;
import com.hazelcast.spi.properties.HazelcastProperties;
import io.github.aindriub.dataprism.core.metrics.PrivacyMetrics;
import io.github.aindriub.dataprism.hazelcast.PrivacyClusterRefusal.Code;
import io.github.aindriub.dataprism.hazelcast.seal.ClusterKeys;
import io.github.aindriub.dataprism.hazelcast.seal.KeyedNames;
import io.github.aindriub.dataprism.hazelcast.seal.SealedWrites;
import io.github.aindriub.dataprism.hazelcast.seal.Sealer;

import java.net.InetAddress;
import java.security.SecureRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The embedded member and the maps it holds.
 *
 * <p>Embedded rather than a separate cluster. The usual objection to embedding is
 * that members in an autoscaled deployment rebalance partitions on every scale
 * event, which sounds ruinous for a map that decides what a subject is called.
 * It is not, because of a property designed in from the start: a synthetic value
 * is a pure function of scope, subject, namespace and key, so losing a partition
 * costs a recomputed HMAC and never a different answer. Rebalancing produces
 * cache misses, not renamed people.
 *
 * <p>What that argument does <em>not</em> cover is the re-identification index,
 * which is a store rather than a cache: nothing can recompute a subject id from a
 * pseudonym. An embedded cluster scaled to zero loses it. It is therefore off by
 * default, and a deployment that turns it on has to decide about persistence and
 * accept that its durability is the cluster's durability.
 *
 * <p>Three settings here are security rather than tuning. No {@code MapStore}, so
 * nothing is written through to a database nobody audited. No persistence, so the
 * maps do not survive on disk by accident. A TTL on every entry, so a scope that
 * is never explicitly ended still expires.
 *
 * <p>Membership is explicit and enforced. Auto-detection, multicast and phone-home
 * are always off, and a member refuses to start under the Hazelcast default cluster
 * name {@code dev} or a blank one, so it cannot join an unrelated cluster by
 * accident. A {@code Config} that enables TLS or Hazelcast security is refused,
 * because the open-source distribution has no member TLS engine and no member
 * authentication: both are Enterprise features and are not provided here. Network
 * isolation of the cluster port is therefore the deployer's responsibility. The advanced
 * network config is refused too, because it bypasses the join and TLS checks. A
 * {@link ClusterMembership} requires an interface for {@code tcp-ip} and {@code kubernetes}, and the member
 * listens only there. Jet, user-code deployment and phone-home are forced off, a Java serialisation allowlist is
 * set, and the Management Center trusted interfaces are set so that no client matches. A member that finds state
 * from before 0.8 refuses to start. {@link #using} checks a supplied instance for the same things it cannot change
 * and never shuts down an instance it did not start.
 */
public final class PrivacyCluster implements AutoCloseable {

    /**
     * Synthetic values, recomputable. Losing this map costs CPU and nothing else. Every map name carries the
     * generation suffix {@code .s1}; the 0.7 names (no suffix) hold nothing 0.8 uses.
     */
    public static final String IDENTITY_MAP = "dataprism.identity.s1";

    /** Pseudonym to subject. Not recomputable, and the most sensitive object here. */
    public static final String REIDENTIFICATION_MAP = "dataprism.reidentification.s1";

    /** Per-subject read counts, shared so the budget means what it says. */
    public static final String BUDGET_MAP = "dataprism.budget.s1";

    /** Pause flags: global, per tool, per scope. No TTL and no eviction, because losing one reopens a paused path. */
    public static final String OVERSIGHT_MAP = "dataprism.oversight.s1";

    /** Approval requests, keyed scope first. Each entry lives until the request's expiry. */
    public static final String APPROVAL_MAP = "dataprism.approval.s1";

    /**
     * Claims on approval ids, keyed by the bare id; the value is the approval's entry key. A claim makes an id
     * unique across scopes. It lives at least as long as the approval it guards.
     */
    public static final String APPROVAL_ID_MAP = "dataprism.approval.id.s1";

    /**
     * The pending approvals of each (kind, requester) with their expiries, which the per-requester cap counts.
     * One entry processor changes an entry, so the count and the add are one step.
     */
    public static final String APPROVAL_PENDING_MAP = "dataprism.approval.pending.s1";

    /** Per-caller fixed-window request counts. */
    public static final String CALLER_RATE_MAP = "dataprism.callerrate.s1";

    /** Consumed-approval tombstones: an approval that was used stays used, even after the approval entry is gone. */
    public static final String APPROVAL_CONSUMED_MAP = "dataprism.approval.consumed.s1";

    /** Member cards, and the sentinel under its reserved key. */
    public static final String MEMBER_CARD_MAP = "dataprism.membercard.s1";

    /** The 0.7 oversight map, where a serving 0.8 member keeps the fence entry. */
    static final String OVERSIGHT_FENCE_MAP = "dataprism.oversight";

    /**
     * The eight map names of 0.7 and earlier. A 0.8 member reads no entry of them; it asks only whether they are empty, how many entries they hold, and the value of the fence
     * (the cutover is non-rolling).
     */
    static final List<String> PRE_0_8_MAPS = List.of("dataprism.identity", "dataprism.reidentification",
            "dataprism.budget", "dataprism.oversight", "dataprism.approval", "dataprism.approval.id",
            "dataprism.approval.pending", "dataprism.callerrate");

    /** The split-brain protection on the approval map: writes only, so a minority can still read. */
    static final String APPROVAL_PROTECTION = "dataprism.approval.quorum";

    private final HazelcastInstance instance;
    private final boolean reidentificationEnabled;

    private final PendingSweeper sweeper;
    private final Duration approvalSweepInterval;
    private final ClusterKeys keys;
    private final ClusterSecuritySettings settings;
    private final KeyedNames names;
    private final ScopeKeys scopeKeys;
    /** Whether this wrapper still holds its share of the instance's sealing registration. */
    private final AtomicBoolean sealingRegistered = new AtomicBoolean();

    private final boolean owned;
    private final AtomicBoolean refused = new AtomicBoolean();
    private final AtomicInteger refusalsLogged = new AtomicInteger();
    private final AtomicReference<UUID> joinerWatch = new AtomicReference<>();

    private PrivacyCluster(HazelcastInstance instance, boolean reidentificationEnabled,
                           Duration approvalSweepInterval, ClusterKeys keys, boolean owned,
                           ClusterSecuritySettings settings) {
        this.instance = instance;
        this.owned = owned;
        this.keys = keys;
        this.settings = java.util.Objects.requireNonNull(settings, "settings");
        // A member without keys is solo by construction (OD-215-8 (a)): it names and seals under keys drawn at random
        // for this process, which nothing else can share, so two such members never derive the same name. A joining
        // member never gets here without keys.
        ClusterKeys effective = keys != null ? keys : generatedSoloKeys();
        this.names = new KeyedNames(effective);
        this.scopeKeys = new ScopeKeys(names);
        SealedWrites.register(instance, Sealer.builder(effective, PrivacyMetrics.none()).build(), names);
        this.sealingRegistered.set(true);
        this.reidentificationEnabled = reidentificationEnabled;
        this.approvalSweepInterval = approvalSweepInterval;
        this.sweeper = new PendingSweeper(instance, approvalSweepInterval, Clock.systemUTC());
        this.legacyWatch.set(startLegacyWatch(instance));
        if (keys == null) {
            watchForJoiners(instance);
        }
    }

    /**
     * A member without keys is solo by construction. Should another member appear anyway, nothing here may go on
     * serving a cluster nobody authenticated. This is detection, not prevention: partition replicas reach the joiner
     * before any listener runs, and an instance somebody else started keeps running. The wrapper is marked refused,
     * so {@link #instance()} fails from then on, its background work stops, and an instance this class started is
     * shut down. An instance somebody else started is left running. The refusal is logged by code and carries no
     * key material.
     */
    private void watchForJoiners(HazelcastInstance watched) {
        joinerWatch.set(watched.getCluster().addMembershipListener(new InitialMembershipListener() {
            /** Called as the listener is registered, with the members then present: a join that came earlier. */
            @Override
            public void init(InitialMembershipEvent event) {
                if (event.getMembers().size() > 1) {
                    refuseAfterJoin();
                }
            }

            @Override
            public void memberAdded(MembershipEvent event) {
                refuseAfterJoin();
            }

            @Override
            public void memberRemoved(MembershipEvent event) {
            }
        }));
    }

    private void refuseAfterJoin() {
        if (!refused.compareAndSet(false, true)) {
            return;
        }
        refusalsLogged.incrementAndGet();
        refusalLog.error("{}: a member joined a member that holds no cluster key; this member is closed",
                Code.MISSING_CLUSTER_KEY);
        legacyChecks.shutdownNow();
        legacyWatchdog.shutdownNow();
        sweeper.close();
        removeListeners();
        if (owned) {
            // Off the listener thread: a shutdown from inside a membership event can wait on itself.
            Thread stopper = new Thread(instance::shutdown, "dataprism-keyless-stop");
            stopper.setDaemon(true);
            stopper.start();
        }
    }

    /** Whether this member was closed for finding another member beside it without a cluster key. */
    boolean isRefused() {
        return refused.get();
    }

    /** How many times the refusal was logged; one, however many joins were announced. */
    int refusalsLogged() {
        return refusalsLogged.get();
    }

    /** Random cluster and name keys for one solo process. The material is never logged, stored or returned. */
    private static ClusterKeys generatedSoloKeys() {
        SecureRandom random = new SecureRandom();
        byte[] cluster = new byte[ClusterKeys.MIN_KEY_BYTES];
        byte[] name = new byte[ClusterKeys.MIN_KEY_BYTES];
        random.nextBytes(cluster);
        random.nextBytes(name);
        ClusterKeys built = new ClusterKeys("solo-cluster", cluster, null, null, null, null, "solo-name", name);
        // ClusterKeys holds its own copy; clear only the copy this code holds.
        java.util.Arrays.fill(cluster, (byte) 0);
        java.util.Arrays.fill(name, (byte) 0);
        return built;
    }

    /**
     * Removes both listeners this wrapper put on the instance, and the member-local sealing context; safe to call
     * twice and after a shutdown.
     */
    private void removeListeners() {
        if (sealingRegistered.compareAndSet(true, false)) {
            SealedWrites.unregister(instance);
        }
        for (var holder : List.of(legacyWatch, joinerWatch)) {
            UUID id = holder.getAndSet(null);
            if (id != null) {
                try {
                    instance.getCluster().removeMembershipListener(id);
                } catch (RuntimeException ignored) {
                    // The instance may already be gone.
                }
            }
        }
    }

    private static final Logger LOG = LoggerFactory.getLogger(PrivacyCluster.class);
    /** Where the late-join refusal is logged; a seam for tests. */
    volatile Logger refusalLog = LOG;
    private static final long LEGACY_WARN_INTERVAL_NANOS = TimeUnit.MINUTES.toNanos(1);

    /** One thread per check, so a check that ignores its interrupt never holds up the next join's check. */
    private final ExecutorService legacyChecks = Executors
            .newCachedThreadPool(task -> {
                Thread thread = new Thread(task, "dataprism-legacy-check");
                thread.setDaemon(true);
                return thread;
            });
    /** Enforces the limit from outside the check, so a stalled check cannot be what delays its own cancellation. */
    private final ScheduledExecutorService legacyWatchdog = Executors
            .newSingleThreadScheduledExecutor(task -> {
                Thread thread = new Thread(task, "dataprism-legacy-watchdog");
                thread.setDaemon(true);
                return thread;
            });
    /** How long one check may run before it is interrupted and abandoned. Settable for tests. */
    volatile Duration legacyCheckLimit = Duration.ofSeconds(10);
    /** What a check asks; a seam for tests. */
    volatile Function<HazelcastInstance, Map<String, Integer>> legacyProbe =
            ClusterMembership::legacyEntryCounts;
    private final java.util.concurrent.atomic.AtomicInteger legacyEntries = new java.util.concurrent.atomic.AtomicInteger();
    private volatile long lastLegacyWarnNanos = System.nanoTime() - LEGACY_WARN_INTERVAL_NANOS;
    private final AtomicReference<UUID> legacyWatch = new AtomicReference<>();

    /**
     * Re-runs the pre-0.8 check on every member join, off the listener thread and bounded in time (the limit is enforced by a separate watchdog thread). A running
     * member is never stopped by what it finds: the entries are counted and one WARN naming the map constants and a
     * count is logged at most once a minute, never an entry. The count is exported as the gauge
     * {@code dataprism.cluster.legacy.entries}.
     */
    private UUID startLegacyWatch(HazelcastInstance watched) {
        return watched.getCluster().addMembershipListener(new MembershipListener() {
            @Override
            public void memberAdded(MembershipEvent event) {
                try {
                    var check = legacyChecks.submit(() -> checkLegacy(watched));
                    legacyWatchdog.schedule(() -> check.cancel(true), legacyCheckLimit.toMillis(),
                            TimeUnit.MILLISECONDS);
                } catch (RejectedExecutionException closed) {
                    // Closing; nothing to check.
                }
            }

            @Override
            public void memberRemoved(MembershipEvent event) {
            }
        });
    }

    private void checkLegacy(HazelcastInstance watched) {
        try {
            Map<String, Integer> found = legacyProbe.apply(watched);
            int total = found.values().stream().mapToInt(Integer::intValue).sum();
            legacyEntries.set(total);
            long now = System.nanoTime();
            if (total > 0 && now - lastLegacyWarnNanos >= LEGACY_WARN_INTERVAL_NANOS) {
                lastLegacyWarnNanos = now;
                LOG.warn("pre-0.8 state is present in the cluster: {} entries in {}; a 0.7 member is probably "
                        + "running", total, found.keySet());
            }
        } catch (RuntimeException unreadable) {
            LOG.debug("pre-0.8 check did not complete: {}", unreadable.getClass().getSimpleName());
        }
    }

    /** Entries found in the pre-0.8 maps by the latest membership check; 0 if none. Read by the gauge. */
    public int legacyEntries() {
        return legacyEntries.get();
    }

    /** Runs one sweep of the pending-approval entries this member owns, as the schedule does. */
    int sweepPendingApprovals(Instant now) {
        return sweeper.sweep(now);
    }

    /** Runs one sweep of the id claims this member owns, as the schedule does. */
    int sweepStaleClaims() {
        return sweeper.sweepClaims();
    }

    /**
     * Tells the sweep the lock timeout in use, so the locks it takes carry the same lease as everyone's.
     *
     * <p>Last store wins: every {@code HazelcastApprovalStore} built over this cluster calls this, so with stores
     * built with different timeouts the sweep uses the timeout of the one built last. Build every store of a cluster
     * with the same timeout, as the Spring wiring does.
     */
    void useLockTimeout(Duration lockTimeout) {
        sweeper.useLockTimeout(lockTimeout);
    }

    /** How often this member sweeps the pending-approval entries and id claims it owns. */
    public Duration approvalSweepInterval() {
        return approvalSweepInterval;
    }

    boolean sweeperStopped() {
        return sweeper.isStopped();
    }

    /** Starts a member from an explicit, already validated membership description. */
    public static PrivacyCluster embedded(ClusterMembership membership, boolean reidentificationEnabled) {
        return embedded(membership, reidentificationEnabled, OptionalInt.empty());
    }

    /**
     * As above, with the fewest members that must see each other for the approval map to accept a write.
     * Empty uses the default: a majority of the listed {@code tcp-ip} members, after expanding comma lists and
     * last-octet ranges; one member for {@code none}.
     */
    public static PrivacyCluster embedded(ClusterMembership membership, boolean reidentificationEnabled,
                                          OptionalInt approvalMinimumMembers) {
        return embedded(membership, reidentificationEnabled, approvalMinimumMembers,
                PendingSweeper.DEFAULT_INTERVAL);
    }

    /**
     * As above, with the interval at which this member sweeps the pending-approval entries and id claims it owns.
     *
     * @param approvalSweepInterval from {@code PT1S} to {@code P1D}
     * @throws IllegalArgumentException if the interval is outside that range
     */
    public static PrivacyCluster embedded(ClusterMembership membership, boolean reidentificationEnabled,
                                          OptionalInt approvalMinimumMembers,
                                          Duration approvalSweepInterval) {
        return embedded(membership, reidentificationEnabled, approvalMinimumMembers, approvalSweepInterval, null);
    }

    /** As {@link #embedded(ClusterMembership, boolean)}, holding the cluster and name keys. */
    public static PrivacyCluster embedded(ClusterMembership membership, boolean reidentificationEnabled,
                                          ClusterKeys keys) {
        return embedded(membership, reidentificationEnabled, OptionalInt.empty(), keys);
    }

    /** As above, with the approval minimum. */
    public static PrivacyCluster embedded(ClusterMembership membership, boolean reidentificationEnabled,
                                          OptionalInt approvalMinimumMembers, ClusterKeys keys) {
        return embedded(membership, reidentificationEnabled, approvalMinimumMembers,
                PendingSweeper.DEFAULT_INTERVAL, keys);
    }

    /**
     * As above, holding the cluster and name keys. A {@code tcp-ip} or {@code kubernetes} membership needs them; a
     * {@code none} membership (a solo member) starts with or without them.
     *
     * @param keys the keys every member of the cluster shares, or {@code null} for none
     * @throws PrivacyClusterRefusal {@code MISSING_CLUSTER_KEY} for a joining membership without keys; nothing is
     *                               started
     */
    public static PrivacyCluster embedded(ClusterMembership membership, boolean reidentificationEnabled,
                                          OptionalInt approvalMinimumMembers,
                                          Duration approvalSweepInterval, ClusterKeys keys) {
        return embedded(membership, reidentificationEnabled, approvalMinimumMembers, approvalSweepInterval, keys,
                ClusterSecuritySettings.defaults());
    }

    /** As above, with the runtime cluster-security settings (the defaults when the overload above is used). */
    public static PrivacyCluster embedded(ClusterMembership membership, boolean reidentificationEnabled,
                                          OptionalInt approvalMinimumMembers,
                                          Duration approvalSweepInterval, ClusterKeys keys,
                                          ClusterSecuritySettings settings) {
        PendingSweeper.checked(approvalSweepInterval);
        if (keys == null && !(membership.join() instanceof ClusterMembership.None)) {
            throw missingKeys();
        }
        // toConfig has already made a none membership solo.
        return launch(membership.toConfig(keys == null ? Code.MISSING_CLUSTER_KEY : Code.INVALID_CLUSTER_INTERFACE),
                reidentificationEnabled,
                approvalMinimumMembers.isPresent() ? approvalMinimumMembers
                        : defaultApprovalMinimumMembers(membership), approvalSweepInterval, keys, false, settings);
    }

    private static PrivacyClusterRefusal missingKeys() {
        return new PrivacyClusterRefusal(Code.MISSING_CLUSTER_KEY,
                "a member that joins others needs the cluster key and the name key; none were supplied");
    }

    /**
     * Whether the instance can be reached only from this host. All must hold: it listens on no more than the
     * interfaces named ({@code bind.any} false, for the general and the server socket setting), those interfaces
     * are enabled and loopback entries only, no public address is advertised, and its member's own address is a
     * loopback address. The advertised address alone says nothing about the listening socket.
     */
    private static boolean boundToLoopback(HazelcastInstance instance, Config config) {
        if (!ClusterMembership.reachableOnlyFromThisHost(config)) {
            return false;
        }
        InetAddress address = instance.getCluster().getLocalMember().getSocketAddress().getAddress();
        return address != null && address.isLoopbackAddress();
    }

    /** Whether the config names any way of finding other members. */
    private static boolean joins(Config config) {
        var join = config.getNetworkConfig().getJoin();
        // The property (also read from -D) turns discovery on for a provider added later, with no strategy config.
        return new HazelcastProperties(config).getBoolean(ClusterProperty.DISCOVERY_SPI_ENABLED)
                || join.getTcpIpConfig().isEnabled() || join.getKubernetesConfig().isEnabled()
                || join.getAwsConfig().isEnabled() || join.getGcpConfig().isEnabled()
                || join.getAzureConfig().isEnabled() || join.getEurekaConfig().isEnabled()
                || join.getDiscoveryConfig().isEnabled()
                || join.getDiscoveryConfig().getDiscoveryServiceProvider() != null
                || join.getDiscoveryConfig().getDiscoveryStrategyConfigs().iterator().hasNext();
    }

    /**
     * The fewest members that may see each other for approvals to change: a majority of the distinct
     * {@code tcp-ip} members (see {@link #countTcpIpMembers}), which therefore must name every member including
     * this one, and {@code 1} for {@code none}. Empty for {@code kubernetes}, whose member count is not known here.
     *
     * @throws PrivacyClusterRefusal {@code MISSING_APPROVAL_QUORUM} if a {@code tcp-ip} entry is a hostname, whose
     *                               address count only Hazelcast's resolver knows, or {@code INVALID_APPROVAL_QUORUM}
     *                               if an entry cannot be counted; neither message names the entry
     */
    static OptionalInt defaultApprovalMinimumMembers(ClusterMembership membership) {
        return switch (membership.join()) {
            case ClusterMembership.TcpIp tcp -> {
                int count;
                try {
                    count = countTcpIpMembers(tcp.members(), membership.port());
                } catch (IllegalArgumentException uncountable) {
                    throw new PrivacyClusterRefusal(Code.INVALID_APPROVAL_QUORUM,
                            "a tcp-ip member entry cannot be expanded reliably to a member count");
                }
                if (hasHostname(tcp.members())) {
                    throw new PrivacyClusterRefusal(Code.MISSING_APPROVAL_QUORUM,
                            "a tcp-ip member entry is a hostname, so the member count is not known; "
                                    + "give the approval minimum explicitly");
                }
                yield OptionalInt.of(count / 2 + 1);
            }
            case ClusterMembership.None none -> OptionalInt.of(1);
            case ClusterMembership.Kubernetes k -> OptionalInt.empty();
        };
    }

    private static final Pattern MEMBER_SEPARATOR = Pattern.compile("[,; ]+");
    private static final Pattern OCTET_OR_RANGE = Pattern.compile("(\\d{1,3})(?:-(\\d{1,3}))?");

    /**
     * How many distinct members a {@code tcp-ip} list names, after expanding what Hazelcast accepts: entries joined
     * by commas, semicolons or spaces, an IPv4 range in the last octet such as {@code 10.0.0.1-3}, and an entry
     * without a port, which is one member with {@code memberPort}, the port this member listens on. A hostname counts
     * as one, which can undercount (see {@link #hasHostname}).
     *
     * <p>This exists for the starter's startup checks and the default above. It is not an API for applications and
     * may change without notice.
     *
     * @throws IllegalArgumentException for a form that cannot be expanded reliably (a wildcard, a range outside the
     *                                  last octet or reversed, a bad port); the message names no entry
     */
    public static int countTcpIpMembers(List<String> entries, int memberPort) {
        Set<String> distinct = new HashSet<>();
        for (String entry : entries) {
            for (String token : MEMBER_SEPARATOR.split(entry.strip())) {
                if (!token.isEmpty()) {
                    expand(token, memberPort, distinct);
                }
            }
        }
        return distinct.size();
    }

    /**
     * Whether any entry is not an IPv4 or IPv6 literal. Hazelcast resolves a hostname to every address it has (all
     * that match the member interface, when one is set), so how many members it names is not known statically.
     * Same exclusions as {@link #countTcpIpMembers}; an entry it would refuse is not reported here.
     */
    public static boolean hasHostname(List<String> entries) {
        for (String entry : entries) {
            for (String token : MEMBER_SEPARATOR.split(entry.strip())) {
                if (!token.isEmpty() && !isLiteral(hostOf(token))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String hostOf(String token) {
        if (token.startsWith("[")) {
            int close = token.indexOf(']');
            return close < 0 ? token : token.substring(0, close + 1);
        }
        int colon = token.indexOf(':');
        return colon >= 0 && colon == token.lastIndexOf(':') ? token.substring(0, colon) : token;
    }

    private static boolean isLiteral(String host) {
        if (host.startsWith("[") || host.indexOf(':') >= 0) {
            return true;
        }
        String[] labels = host.split("\\.", -1);
        return labels.length == 4 && host.chars().allMatch(c -> c == '.' || c == '-' || Character.isDigit(c));
    }

    private static void expand(String token, int memberPort, Set<String> into) {
        String host = token;
        int port = memberPort;
        if (token.startsWith("[")) {
            int close = token.indexOf(']');
            if (close < 0) {
                throw unexpandable();
            }
            host = token.substring(0, close + 1);
            String rest = token.substring(close + 1);
            if (!rest.isEmpty()) {
                port = port(rest.startsWith(":") ? rest.substring(1) : "x");
            }
        } else if (token.indexOf(':') >= 0 && token.indexOf(':') == token.lastIndexOf(':')) {
            host = token.substring(0, token.indexOf(':'));
            port = port(token.substring(token.indexOf(':') + 1));
        }
        if (host.isEmpty() || host.indexOf('*') >= 0 || (!host.startsWith("[") && host.indexOf('[') >= 0)) {
            throw unexpandable();
        }
        String[] labels = host.split("\\.", -1);
        if (labels.length == 4 && host.chars().allMatch(c -> c == '.' || c == '-' || Character.isDigit(c))) {
            Matcher[] octets = new Matcher[4];
            for (int i = 0; i < 4; i++) {
                octets[i] = OCTET_OR_RANGE.matcher(labels[i]);
                if (!octets[i].matches() || (i < 3 && octets[i].group(2) != null)) {
                    throw unexpandable();
                }
            }
            int low = Integer.parseInt(octets[3].group(1));
            int high = octets[3].group(2) == null ? low : Integer.parseInt(octets[3].group(2));
            if (high < low || high > 255) {
                throw unexpandable();
            }
            for (int last = low; last <= high; last++) {
                into.add(Integer.parseInt(octets[0].group(1)) + "." + Integer.parseInt(octets[1].group(1)) + "."
                        + Integer.parseInt(octets[2].group(1)) + "." + last + ":" + port);
            }
            return;
        }
        into.add(host.toLowerCase(Locale.ROOT) + ":" + port);
    }

    private static int port(String text) {
        try {
            int port = Integer.parseInt(text);
            if (port >= 1 && port <= 65535) {
                return port;
            }
        } catch (NumberFormatException notANumber) {
            // fall through
        }
        throw unexpandable();
    }

    private static IllegalArgumentException unexpandable() {
        return new IllegalArgumentException("a tcp-ip member entry cannot be expanded reliably to a member count");
    }

    /** Validates and hardens the config, then starts a member. Refuses before anything starts. */
    public static PrivacyCluster embedded(Config config, boolean reidentificationEnabled) {
        return embedded(config, reidentificationEnabled, OptionalInt.empty());
    }

    /**
     * As above, and when {@code approvalMinimumMembers} is present, writes to the approval map (create, approve,
     * reject, consume) throw on a member that sees fewer members than that. Empty, or 1, applies no such protection:
     * any running member meets a minimum of 1.
     *
     * @throws IllegalArgumentException if the minimum is below 1
     */
    public static PrivacyCluster embedded(Config config, boolean reidentificationEnabled,
                                          OptionalInt approvalMinimumMembers) {
        return embedded(config, reidentificationEnabled, approvalMinimumMembers, PendingSweeper.DEFAULT_INTERVAL);
    }

    /**
     * As above, with the interval at which this member sweeps the pending-approval entries and id claims it owns.
     *
     * @param approvalSweepInterval from {@code PT1S} to {@code P1D}
     * @throws IllegalArgumentException if the interval is outside that range; nothing is started
     */
    public static PrivacyCluster embedded(Config config, boolean reidentificationEnabled,
                                          OptionalInt approvalMinimumMembers,
                                          Duration approvalSweepInterval) {
        return embedded(config, reidentificationEnabled, approvalMinimumMembers, approvalSweepInterval, null);
    }

    /** As {@link #embedded(Config, boolean, OptionalInt, Duration)}, holding the cluster and name keys. */
    public static PrivacyCluster embedded(Config config, boolean reidentificationEnabled,
                                          OptionalInt approvalMinimumMembers, ClusterKeys keys) {
        return embedded(config, reidentificationEnabled, approvalMinimumMembers, PendingSweeper.DEFAULT_INTERVAL,
                keys);
    }

    /**
     * As above, holding the cluster and name keys. A config that joins other members needs them. Without keys the
     * member is made solo, as {@link ClusterMembership.None} does: it listens on {@code 127.0.0.1} only and its
     * cluster name gains a random suffix, replacing whatever interface the config named, so that no keyed member
     * can form a cluster with it. <strong>The {@code Config} passed in is modified</strong> (as it already is by
     * hardening); passing the same one again adds a second, fresh suffix.
     *
     * @param keys the keys every member of the cluster shares, or {@code null} for none
     * @throws PrivacyClusterRefusal {@code MISSING_CLUSTER_KEY} for a joining config without keys; nothing is started
     */
    public static PrivacyCluster embedded(Config config, boolean reidentificationEnabled,
                                          OptionalInt approvalMinimumMembers,
                                          Duration approvalSweepInterval, ClusterKeys keys) {
        return embedded(config, reidentificationEnabled, approvalMinimumMembers, approvalSweepInterval, keys,
                ClusterSecuritySettings.defaults());
    }

    /** As above, with the runtime cluster-security settings (the defaults when the overload above is used). */
    public static PrivacyCluster embedded(Config config, boolean reidentificationEnabled,
                                          OptionalInt approvalMinimumMembers,
                                          Duration approvalSweepInterval, ClusterKeys keys,
                                          ClusterSecuritySettings settings) {
        PendingSweeper.checked(approvalSweepInterval);
        if (keys == null && joins(config)) {
            throw missingKeys();
        }
        return launch(config, reidentificationEnabled, approvalMinimumMembers, approvalSweepInterval, keys,
                keys == null, settings);
    }

    private static PrivacyCluster launch(Config config, boolean reidentificationEnabled,
                                         OptionalInt approvalMinimumMembers,
                                         Duration approvalSweepInterval, ClusterKeys keys,
                                         boolean isolate, ClusterSecuritySettings settings) {
        Config hardened = configure(config, approvalMinimumMembers);
        if (isolate) {
            ClusterMembership.isolate(hardened);
        }
        HazelcastInstance started = Hazelcast.newHazelcastInstance(hardened);
        try {
            ClusterMembership.refuseUnsealedState(started);
        } catch (RuntimeException refused) {
            // Started here, so stopped here: a member that refuses serves nothing.
            started.shutdown();
            throw refused;
        }
        PrivacyCluster cluster = new PrivacyCluster(started, reidentificationEnabled, approvalSweepInterval, keys,
                true, settings);
        if (cluster.refused.get()) {
            cluster.removeListeners();
            throw missingKeys();
        }
        return cluster;
    }

    /**
     * Wraps a member someone else started, for a host that manages its own instance.
     * The instance is validated and, if refused, left running: it is not ours to stop.
     */
    public static PrivacyCluster using(HazelcastInstance instance, boolean reidentificationEnabled) {
        return using(instance, reidentificationEnabled, PendingSweeper.DEFAULT_INTERVAL);
    }

    /**
     * As above, with the interval at which this wrapper sweeps the pending-approval entries and id claims its
     * member owns.
     *
     * @param approvalSweepInterval from {@code PT1S} to {@code P1D}
     * @throws IllegalArgumentException if the interval is outside that range
     */
    public static PrivacyCluster using(HazelcastInstance instance, boolean reidentificationEnabled,
                                       Duration approvalSweepInterval) {
        return using(instance, reidentificationEnabled, approvalSweepInterval, null);
    }

    /** As {@link #using(HazelcastInstance, boolean)}, holding the cluster and name keys. */
    public static PrivacyCluster using(HazelcastInstance instance, boolean reidentificationEnabled,
                                       ClusterKeys keys) {
        return using(instance, reidentificationEnabled, PendingSweeper.DEFAULT_INTERVAL, keys);
    }

    /**
     * As above, holding the cluster and name keys. An instance whose config joins other members needs them, and so
     * does one that already has more than one member or whose member is not bound to a loopback address. Without
     * keys the supplied instance must be isolated by its owner: this cannot change a running member's name or
     * interface, so start it as {@link ClusterMembership.None} does (loopback only, a random cluster-name suffix).
     * Should a member join it later anyway, the returned wrapper is marked refused and {@link #instance()} throws
     * from then on; the instance itself is left running.
     *
     * @param keys the keys every member of the cluster shares, or {@code null} for none
     * @throws PrivacyClusterRefusal {@code MISSING_CLUSTER_KEY} for a joining or already clustered instance without
     *                               keys; the instance is left running
     */
    public static PrivacyCluster using(HazelcastInstance instance, boolean reidentificationEnabled,
                                       Duration approvalSweepInterval, ClusterKeys keys) {
        return using(instance, reidentificationEnabled, approvalSweepInterval, keys,
                ClusterSecuritySettings.defaults());
    }

    /** As above, with the runtime cluster-security settings (the defaults when the overload above is used). */
    public static PrivacyCluster using(HazelcastInstance instance, boolean reidentificationEnabled,
                                       Duration approvalSweepInterval, ClusterKeys keys,
                                       ClusterSecuritySettings settings) {
        PendingSweeper.checked(approvalSweepInterval);
        Config config = instance.getConfig();
        if (keys == null && (joins(config) || instance.getCluster().getMembers().size() > 1
                || !boundToLoopback(instance, config))) {
            throw missingKeys();
        }
        checkClusterName(config);
        checkNoAdvancedNetwork(config);
        var join = config.getNetworkConfig().getJoin();
        if (join.getAutoDetectionConfig().isEnabled() || join.getMulticastConfig().isEnabled()) {
            throw new PrivacyClusterRefusal(Code.UNSAFE_HAZELCAST_DISCOVERY,
                    "auto-detection and multicast must be disabled on the supplied instance");
        }
        checkInstanceSettings(config);
        ClusterMembership.refuseUnsealedState(instance, true);
        PrivacyCluster wrapped = new PrivacyCluster(instance, reidentificationEnabled, approvalSweepInterval, keys,
                false, settings);
        if (wrapped.refused.get()) {
            wrapped.removeListeners();
            // A member joined between the checks above and the listener's registration.
            throw missingKeys();
        }
        return wrapped;
    }

    /** As {@code using}'s tail, for a test that says whether this class owns the instance. */
    static PrivacyCluster wrap(HazelcastInstance instance, boolean reidentificationEnabled,
                               Duration approvalSweepInterval, ClusterKeys keys, boolean owned) {
        return new PrivacyCluster(instance, reidentificationEnabled, approvalSweepInterval, keys, owned,
                ClusterSecuritySettings.defaults());
    }

    /**
     * An enabled advanced network config replaces the plain one, with its own join and
     * endpoint TLS, none of which this class hardens or inspects. Refuse it outright.
     */
    private static void checkNoAdvancedNetwork(Config config) {
        if (config.getAdvancedNetworkConfig().isEnabled()) {
            throw new PrivacyClusterRefusal(Code.UNSAFE_HAZELCAST_DISCOVERY,
                    "the advanced network config is not supported and must be disabled");
        }
    }

    private static void checkClusterName(Config config) {
        String name = config.getClusterName();
        if (name == null || name.isBlank()) {
            throw new PrivacyClusterRefusal(Code.MISSING_CLUSTER_NAME, "clusterName is required");
        }
        if (ClusterMembership.isReserved(name)) {
            throw new PrivacyClusterRefusal(Code.RESERVED_CLUSTER_NAME,
                    "clusterName is the Hazelcast default and is refused");
        }
    }

    /**
     * Refuses an unsafe config, forces discovery and phone-home off, and applies the
     * settings that are not the caller's to choose. The join list and interface are
     * still the caller's, but nothing is discovered implicitly.
     */
    static Config configure(Config config) {
        return configure(config, OptionalInt.empty());
    }

    static Config configure(Config config, OptionalInt approvalMinimumMembers) {
        if (approvalMinimumMembers.isPresent() && approvalMinimumMembers.getAsInt() < 1) {
            throw new IllegalArgumentException("approvalMinimumMembers must be at least 1");
        }
        checkClusterName(config);
        checkNoAdvancedNetwork(config);
        var ssl = config.getNetworkConfig().getSSLConfig();
        if ((ssl != null && ssl.isEnabled()) || config.getSecurityConfig().isEnabled()) {
            throw new PrivacyClusterRefusal(Code.HAZELCAST_TLS_UNSUPPORTED,
                    "TLS and member authentication are not available in the open-source distribution");
        }
        config.getNetworkConfig().getJoin().getAutoDetectionConfig().setEnabled(false);
        config.getNetworkConfig().getJoin().getMulticastConfig().setEnabled(false);
        config.setProperty("hazelcast.phone.home.enabled", "false");
        forceSafeSettings(config);
        IdentityMaps.configure(config);
        OversightMaps.configure(config);
        ApprovalMaps.configure(config, approvalMinimumMembers);
        CounterMaps.configure(config);
        return config;
    }

    /**
     * The Java serialisation allowlist. Values and processors are Strings, Integers and Booleans and Data Prism's
     * own records (the prefix below), with {@code java.time.Ser} for the sweep processor's {@code Instant}.
     *
     * <p>The rest is the Java form of an error reply. A member that cannot run an operation answers with a
     * Java-serialised exception, and a reply the filter refuses is not an error at the caller but a lost answer,
     * so the call hangs until the operation timeout. Observed with only the three classes listed (spike 219, Q4):
     * a map's first use on a two-member cluster blocked, because Hazelcast's own {@code PartitionMigratingException}
     * and {@code WrongTargetException} replies were refused. The retryable family, the base exception types and the
     * exceptions Data Prism's calls are told about are therefore listed by name, and no wider prefix.
     */
    static final Set<String> ALLOWED_CLASSES = Set.of("java.lang.String", "java.lang.Integer", "java.lang.Boolean",
            "java.time.Ser",
            "java.lang.Throwable", "java.lang.Exception", "java.lang.RuntimeException",
            "java.lang.IllegalStateException", "java.lang.IllegalMonitorStateException",
            "java.lang.StackTraceElement", "[Ljava.lang.StackTraceElement;", "java.util.Collections$EmptyList",
            "com.hazelcast.core.HazelcastException", "com.hazelcast.core.OperationTimeoutException",
            "com.hazelcast.core.HazelcastInstanceNotActiveException",
            "com.hazelcast.transaction.TransactionException",
            "com.hazelcast.splitbrainprotection.SplitBrainProtectionException",
            // What an entry processor or merge policy can throw back to its caller: a foreign value under a key
            // (ClassCastException, NullPointerException), an unreadable stored string (NumberFormatException,
            // StringIndexOutOfBounds, DateTimeParseException) or a refused argument, and the filter's own refusal.
            "com.hazelcast.nio.serialization.HazelcastSerializationException",
            "java.lang.SecurityException", "java.lang.ClassCastException", "java.lang.IllegalArgumentException",
            "java.lang.NullPointerException", "java.lang.NumberFormatException",
            "java.lang.IndexOutOfBoundsException", "java.lang.StringIndexOutOfBoundsException",
            "java.lang.ArrayIndexOutOfBoundsException", "java.time.DateTimeException",
            "java.time.format.DateTimeParseException");

    /** Every Data Prism processor and merge policy lives under the first; Hazelcast's retryable exceptions, the second. */
    static final Set<String> ALLOWED_PREFIXES = Set.of("io.github.aindriub.dataprism.hazelcast.",
            "com.hazelcast.spi.exception.");

    /**
     * An address no client can have, so the Management Center trusted-interface list is non-empty and matches none.
     * An empty list is Hazelcast's default and means unrestricted: a client that knows only the cluster name can
     * then freeze or shut down the cluster (spike 219, Q3 and Q13).
     */
    static final String UNMATCHABLE_MANAGEMENT_ADDRESS = "255.255.255.255";

    private static void forceSafeSettings(Config config) {
        config.getJetConfig().setEnabled(false);
        config.getUserCodeDeploymentConfig().setEnabled(false);
        config.getManagementCenterConfig().setTrustedInterfaces(Set.of(UNMATCHABLE_MANAGEMENT_ADDRESS));
        // Data Prism has its own health endpoint. The member REST API can change the cluster state whatever the
        // trusted interfaces say, and the memcache protocol opens the maps to anything that can reach the port.
        config.getNetworkConfig().getRestApiConfig().setEnabled(false);
        config.getNetworkConfig().getMemcacheProtocolConfig().setEnabled(false);
        if (overridesJavaSerialization(config)) {
            throw new PrivacyClusterRefusal(Code.UNSAFE_HAZELCAST_SERIALIZATION,
                    "a global serializer that overrides Java serialization bypasses the java-serialization-filter");
        }
        JavaSerializationFilterConfig supplied = config.getSerializationConfig().getJavaSerializationFilterConfig();
        if (supplied != null && (!withinAllowlist(supplied) || blocksRequired(supplied.getBlacklist()))) {
            throw new PrivacyClusterRefusal(Code.UNSAFE_HAZELCAST_SERIALIZATION,
                    "the java-serialization-filter allows more than Data Prism needs; remove it to take the default "
                            + "or narrow it");
        }
        config.getSerializationConfig().setJavaSerializationFilterConfig(requiredSerializationFilter());
    }

    private static boolean overridesJavaSerialization(Config config) {
        var global = config.getSerializationConfig().getGlobalSerializerConfig();
        return global != null && global.isOverrideJavaSerialization();
    }

    /**
     * A fresh copy of the {@code java-serialization-filter} a member needs: defaults disabled and an allowlist of
     * Data Prism's own classes and the exception types Hazelcast replies with. {@link #embedded} installs exactly
     * this. An integrator who starts the instance for {@link #using} sets it (or a filter that also names classes of
     * their own, but no further prefix or package) on the {@code Config}.
     */
    public static JavaSerializationFilterConfig requiredSerializationFilter() {
        return new JavaSerializationFilterConfig().setDefaultsDisabled(true)
                .setWhitelist(new ClassFilter().setClasses(new HashSet<>(ALLOWED_CLASSES))
                        .setPrefixes(new HashSet<>(ALLOWED_PREFIXES)));
    }

    /** Defaults off, and nothing listed that the allowlist does not already list. */
    private static boolean withinAllowlist(JavaSerializationFilterConfig filter) {
        ClassFilter allow = filter.getWhitelist();
        return filter.isDefaultsDisabled() && allow != null
                && ALLOWED_CLASSES.containsAll(allow.getClasses())
                && ALLOWED_PREFIXES.containsAll(allow.getPrefixes())
                && allow.getPackages().isEmpty();
    }

    /**
     * What can be checked on an instance somebody else started and cannot be changed, each refused with
     * {@code UNSAFE_HAZELCAST_SERIALIZATION} (the code decided in 218; no new code is added):
     * a filter with defaults disabled that allows all of {@link #requiredSerializationFilter()} and names no prefix
     * or package beyond Data Prism's own (extra named classes are the integrator's to add); no Jet, no user-code
     * deployment, no memcache, no REST group beyond {@code HEALTH_CHECK}; no global serializer overriding Java
     * serialization; a non-empty Management Center trusted-interface list (an empty list is unrestricted); and
     * phone-home off. Which addresses a non-empty trusted list names is the integrator's responsibility.
     */
    private static void checkInstanceSettings(Config config) {
        JavaSerializationFilterConfig filter = config.getSerializationConfig().getJavaSerializationFilterConfig();
        if (filter == null || !filter.isDefaultsDisabled() || filter.getWhitelist() == null
                || !allowsDataPrism(filter.getWhitelist()) || !addsNoWideEntries(filter.getWhitelist())
                || blocksRequired(filter.getBlacklist())) {
            throw new PrivacyClusterRefusal(Code.UNSAFE_HAZELCAST_SERIALIZATION,
                    "the supplied instance needs a java-serialization-filter with defaults disabled that allows "
                            + "Data Prism's classes and no other prefix or package");
        }
        if (overridesJavaSerialization(config)) {
            throw new PrivacyClusterRefusal(Code.UNSAFE_HAZELCAST_SERIALIZATION,
                    "a global serializer must not override Java serialization on the supplied instance");
        }
        if (config.getJetConfig().isEnabled()) {
            throw new PrivacyClusterRefusal(Code.UNSAFE_HAZELCAST_SERIALIZATION,
                    "jet must be disabled on the supplied instance");
        }
        if (config.getUserCodeDeploymentConfig().isEnabled()) {
            throw new PrivacyClusterRefusal(Code.UNSAFE_HAZELCAST_SERIALIZATION,
                    "user-code-deployment must be disabled on the supplied instance");
        }
        var network = config.getNetworkConfig();
        if (network.getMemcacheProtocolConfig().isEnabled()) {
            throw new PrivacyClusterRefusal(Code.UNSAFE_HAZELCAST_SERIALIZATION,
                    "the memcache protocol must be disabled on the supplied instance");
        }
        var rest = network.getRestApiConfig();
        if (rest.isEnabled() && !Set.of(RestEndpointGroup.HEALTH_CHECK).containsAll(rest.getEnabledGroups())) {
            throw new PrivacyClusterRefusal(Code.UNSAFE_HAZELCAST_SERIALIZATION,
                    "the member REST API may enable only the HEALTH_CHECK group on the supplied instance");
        }
        if (config.getManagementCenterConfig().getTrustedInterfaces().isEmpty()) {
            throw new PrivacyClusterRefusal(Code.UNSAFE_HAZELCAST_SERIALIZATION,
                    "management-center trusted-interfaces must not be empty on the supplied instance");
        }
        if (new HazelcastProperties(config).getBoolean(ClusterProperty.PHONE_HOME_ENABLED)) {
            throw new PrivacyClusterRefusal(Code.UNSAFE_HAZELCAST_SERIALIZATION,
                    "phone-home must be disabled on the supplied instance");
        }
    }

    /**
     * Hazelcast gives the blacklist precedence over the whitelist, so a blacklist that names, or covers by package or
     * prefix, any required class or prefix (or reaches inside a required prefix) would pass the whitelist check and
     * then lose processor calls or error replies. Such a filter is refused; a blacklist clear of the required set is
     * the integrator's own and is accepted on {@link #using}.
     */
    private static boolean blocksRequired(ClassFilter deny) {
        if (deny == null) {
            return false;
        }
        for (String name : ALLOWED_CLASSES) {
            if (denies(deny, name, false)) {
                return true;
            }
        }
        for (String prefix : ALLOWED_PREFIXES) {
            if (denies(deny, prefix, true)) {
                return true;
            }
        }
        return false;
    }

    /** Whether {@code deny} matches {@code token}; for a prefix, also anything inside it. */
    private static boolean denies(ClassFilter deny, String token, boolean isPrefix) {
        if (deny.getClasses().contains(token) || (isPrefix && deny.getClasses().stream().anyMatch(c -> c.startsWith(token)))) {
            return true;
        }
        for (String prefix : deny.getPrefixes()) {
            if (token.startsWith(prefix) || (isPrefix && prefix.startsWith(token))) {
                return true;
            }
        }
        for (String pkg : deny.getPackages()) {
            if (token.startsWith(pkg + ".") || (isPrefix && pkg.startsWith(token))) {
                return true;
            }
        }
        return false;
    }

    /** Only Data Prism's own prefixes; no package at all. Named classes are free. */
    private static boolean addsNoWideEntries(ClassFilter allow) {
        return ALLOWED_PREFIXES.containsAll(allow.getPrefixes()) && allow.getPackages().isEmpty();
    }

    private static boolean allowsDataPrism(ClassFilter allow) {
        return ALLOWED_CLASSES.stream().allMatch(name -> allowed(allow, name))
                && ALLOWED_PREFIXES.stream().allMatch(prefix -> allowed(allow, prefix));
    }

    private static boolean allowed(ClassFilter allow, String nameOrPrefix) {
        return allow.getClasses().contains(nameOrPrefix)
                || allow.getPrefixes().stream().anyMatch(nameOrPrefix::startsWith)
                || allow.getPackages().stream().anyMatch(p -> nameOrPrefix.startsWith(p + "."));
    }

    /** The settings every Data Prism map has; an area class then adjusts eviction, merge policy and protection. */
    static MapConfig privacyMap(MapConfig map) {
        map.getMapStoreConfig().setEnabled(false);
        map.setBackupCount(1);
        map.setStatisticsEnabled(true);
        // Scope-prefixed keys, so ending a scope is a prefix scan rather than a
        // walk of every entry in the cluster.
        map.addIndexConfig(new IndexConfig(IndexType.HASH, "__key"));
        map.setEvictionConfig(new EvictionConfig()
                .setEvictionPolicy(EvictionPolicy.LRU)
                .setMaxSizePolicy(MaxSizePolicy.PER_NODE)
                .setSize(100_000));
        return map;
    }

    public HazelcastInstance instance() {
        if (refused.get()) {
            throw new PrivacyClusterRefusal(Code.MISSING_CLUSTER_KEY,
                    "a member joined this member, which holds no cluster key; it is closed");
        }
        return instance;
    }

    public boolean reidentificationEnabled() {
        return reidentificationEnabled;
    }

    /**
     * The runtime cluster-security settings this member was started with: the defaults of design 4.1 unless supplied.
     * The stores of tasks 226 to 228 read them.
     */
    public ClusterSecuritySettings securitySettings() {
        return settings;
    }

    /**
     * The keyed names this member derives every map key and lock key with: the supplied name key, or for a solo
     * member without keys the one drawn at random for this process. For diagnostics and tests; a name reveals nothing
     * without the name key. Internal: for tests and cluster code; not a stable API.
     */
    public KeyedNames keyedNames() {
        return names;
    }

    ScopeKeys scopeKeys() {
        return scopeKeys;
    }

    /**
     * The keys this member was started with, for the stores of this package. Not public: key material does not
     * leave this module.
     */
    Optional<ClusterKeys> keys() {
        return Optional.ofNullable(keys);
    }

    /** The ids of the keys this member holds, never their material. Empty for a solo member given none. */
    public Optional<KeyIds> keyIds() {
        return keys == null ? Optional.empty() : Optional.of(new KeyIds(keys.current().id(),
                keys.next().map(ClusterKeys.Key::id).orElse(null),
                keys.previous().map(ClusterKeys.Key::id).orElse(null), keys.nameKeyId()));
    }

    /**
     * The ids of the held keys. {@code nextId} and {@code previousId} are {@code null} when no such key is held;
     * {@link #next()} and {@link #previous()} give them as an {@code Optional}.
     */
    public record KeyIds(String current, String nextId, String previousId, String name) {
        public Optional<String> next() {
            return Optional.ofNullable(nextId);
        }

        public Optional<String> previous() {
            return Optional.ofNullable(previousId);
        }
    }

    @Override
    public void close() {
        removeListeners();
        legacyChecks.shutdownNow();
        legacyWatchdog.shutdownNow();
        sweeper.close();
        instance.shutdown();
    }
}

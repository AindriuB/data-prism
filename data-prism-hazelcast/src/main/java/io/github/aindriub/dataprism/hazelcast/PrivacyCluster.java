package io.github.aindriub.dataprism.hazelcast;

import com.hazelcast.config.Config;
import com.hazelcast.config.EvictionConfig;
import com.hazelcast.config.EvictionPolicy;
import com.hazelcast.config.IndexConfig;
import com.hazelcast.config.IndexType;
import com.hazelcast.config.MapConfig;
import com.hazelcast.config.MaxSizePolicy;
import com.hazelcast.config.MergePolicyConfig;
import com.hazelcast.config.SplitBrainProtectionConfig;
import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.splitbrainprotection.SplitBrainProtectionOn;
import io.github.aindriub.dataprism.hazelcast.PrivacyClusterRefusal.Code;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.OptionalInt;
import java.util.Set;
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
 * {@link ClusterMembership} with an interface also stops the member listening on other
 * interfaces; without one the member binds every interface, which is Hazelcast's default.
 * {@link #using} validates a supplied instance the same way and never shuts down
 * an instance it did not start.
 */
public final class PrivacyCluster implements AutoCloseable {

    /** Synthetic values, recomputable. Losing this map costs CPU and nothing else. */
    public static final String IDENTITY_MAP = "dataprism.identity";

    /** Pseudonym to subject. Not recomputable, and the most sensitive object here. */
    public static final String REIDENTIFICATION_MAP = "dataprism.reidentification";

    /** Per-subject read counts, shared so the budget means what it says. */
    public static final String BUDGET_MAP = "dataprism.budget";

    /** Pause flags: global, per tool, per scope. No TTL and no eviction, because losing one reopens a paused path. */
    public static final String OVERSIGHT_MAP = "dataprism.oversight";

    /** Approval requests, keyed scope first. Each entry lives until the request's expiry. */
    public static final String APPROVAL_MAP = "dataprism.approval";

    /**
     * Claims on approval ids, keyed by the bare id; the value is the approval's entry key. A claim makes an id
     * unique across scopes. It lives at least as long as the approval it guards.
     */
    public static final String APPROVAL_ID_MAP = "dataprism.approval.id";

    /**
     * The pending approvals of each (kind, requester) with their expiries, which the per-requester cap counts.
     * One entry processor changes an entry, so the count and the add are one step.
     */
    public static final String APPROVAL_PENDING_MAP = "dataprism.approval.pending";

    /** Per-caller fixed-window request counts. */
    public static final String CALLER_RATE_MAP = "dataprism.callerrate";

    /** The split-brain protection on the approval map: writes only, so a minority can still read. */
    static final String APPROVAL_PROTECTION = "dataprism.approval.quorum";

    private final HazelcastInstance instance;
    private final boolean reidentificationEnabled;

    private final PendingSweeper sweeper;

    private PrivacyCluster(HazelcastInstance instance, boolean reidentificationEnabled) {
        this.instance = instance;
        this.reidentificationEnabled = reidentificationEnabled;
        this.sweeper = new PendingSweeper(instance, PendingSweeper.intervalOf(instance.getConfig()),
                java.time.Clock.systemUTC());
    }

    /** Runs one sweep of the pending-approval entries this member owns, as the schedule does. */
    int sweepPendingApprovals(java.time.Instant now) {
        return sweeper.sweep(now);
    }

    /** Runs one sweep of the id claims this member owns, as the schedule does. */
    int sweepStaleClaims() {
        return sweeper.sweepClaims();
    }

    /** Tells the sweep the lock timeout in use, so the locks it takes carry the same lease as everyone's. */
    void useLockTimeout(java.time.Duration lockTimeout) {
        sweeper.useLockTimeout(lockTimeout);
    }

    /** How often this member sweeps the pending-approval entries and id claims it owns. */
    public java.time.Duration approvalSweepInterval() {
        return PendingSweeper.DEFAULT_INTERVAL;
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
        return embedded(membership.toConfig(), reidentificationEnabled,
                approvalMinimumMembers.isPresent() ? approvalMinimumMembers
                        : defaultApprovalMinimumMembers(membership));
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

    public static PrivacyCluster embedded(ClusterMembership membership, boolean reidentificationEnabled,
                                          OptionalInt approvalMinimumMembers, java.time.Duration interval) {
        return embedded(membership, reidentificationEnabled, approvalMinimumMembers);
    }

    public static PrivacyCluster embedded(Config config, boolean reidentificationEnabled,
                                          OptionalInt approvalMinimumMembers, java.time.Duration interval) {
        return embedded(config, reidentificationEnabled, approvalMinimumMembers);
    }

    public static PrivacyCluster using(HazelcastInstance instance, boolean reidentificationEnabled,
                                       java.time.Duration interval) {
        return using(instance, reidentificationEnabled);
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
        return new PrivacyCluster(Hazelcast.newHazelcastInstance(configure(config, approvalMinimumMembers)),
                reidentificationEnabled);
    }

    /**
     * Wraps a member someone else started, for a host that manages its own instance.
     * The instance is validated and, if refused, left running: it is not ours to stop.
     */
    public static PrivacyCluster using(HazelcastInstance instance, boolean reidentificationEnabled) {
        Config config = instance.getConfig();
        checkClusterName(config);
        checkNoAdvancedNetwork(config);
        var join = config.getNetworkConfig().getJoin();
        if (join.getAutoDetectionConfig().isEnabled() || join.getMulticastConfig().isEnabled()) {
            throw new PrivacyClusterRefusal(Code.UNSAFE_HAZELCAST_DISCOVERY,
                    "auto-detection and multicast must be disabled on the supplied instance");
        }
        return new PrivacyCluster(instance, reidentificationEnabled);
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
        PendingSweeper.intervalOf(config);
        var ssl = config.getNetworkConfig().getSSLConfig();
        if ((ssl != null && ssl.isEnabled()) || config.getSecurityConfig().isEnabled()) {
            throw new PrivacyClusterRefusal(Code.HAZELCAST_TLS_UNSUPPORTED,
                    "TLS and member authentication are not available in the open-source distribution");
        }
        config.getNetworkConfig().getJoin().getAutoDetectionConfig().setEnabled(false);
        config.getNetworkConfig().getJoin().getMulticastConfig().setEnabled(false);
        config.setProperty("hazelcast.phone.home.enabled", "false");
        config.addMapConfig(privacyMap(new MapConfig(IDENTITY_MAP)));
        config.addMapConfig(privacyMap(new MapConfig(REIDENTIFICATION_MAP)));
        config.addMapConfig(privacyMap(new MapConfig(BUDGET_MAP)));
        MapConfig oversight = privacyMap(new MapConfig(OVERSIGHT_MAP));
        // An evicted pause flag is a path silently reopened.
        oversight.getEvictionConfig().setEvictionPolicy(EvictionPolicy.NONE);
        config.addMapConfig(oversight);
        MapConfig approval = privacyMap(new MapConfig(APPROVAL_MAP));
        // After a heal an approval either side used stays used; the default keeps the larger side's copy.
        approval.setMergePolicyConfig(new MergePolicyConfig(ApprovalMergePolicy.class.getName(),
                MergePolicyConfig.DEFAULT_BATCH_SIZE));
        // A minimum of 1 is met by any member that is running, so it needs no protection. Configuring it would
        // only refuse approvals for the first seconds after start, until Hazelcast first evaluates the members.
        if (approvalMinimumMembers.isPresent() && approvalMinimumMembers.getAsInt() > 1) {
            config.addSplitBrainProtectionConfig(new SplitBrainProtectionConfig(APPROVAL_PROTECTION, true,
                    approvalMinimumMembers.getAsInt()).setProtectOn(SplitBrainProtectionOn.WRITE));
            approval.setSplitBrainProtectionName(APPROVAL_PROTECTION);
        }
        config.addMapConfig(approval);
        // The id claims and pending sets guard the approval map, so they get its write quorum, never evict, and a
        // heal never frees a held claim or lowers a requester's pending set.
        MapConfig idClaims = approvalGuard(new MapConfig(APPROVAL_ID_MAP), "PutIfAbsentMergePolicy");
        MapConfig pending = approvalGuard(new MapConfig(APPROVAL_PENDING_MAP), PendingMergePolicy.class.getName());
        if (approvalMinimumMembers.isPresent() && approvalMinimumMembers.getAsInt() > 1) {
            idClaims.setSplitBrainProtectionName(APPROVAL_PROTECTION);
            pending.setSplitBrainProtectionName(APPROVAL_PROTECTION);
        }
        config.addMapConfig(idClaims);
        config.addMapConfig(pending);
        MapConfig callerRate = privacyMap(new MapConfig(CALLER_RATE_MAP));
        // An evicted counter resets a caller's window; the 2-window TTL bounds size.
        callerRate.getEvictionConfig().setEvictionPolicy(EvictionPolicy.NONE);
        config.addMapConfig(callerRate);
        return config;
    }

    private static MapConfig approvalGuard(MapConfig map, String mergePolicy) {
        privacyMap(map);
        // An evicted claim or pending id reopens the path it closes. Entries leave by explicit removal or TTL.
        map.getEvictionConfig().setEvictionPolicy(EvictionPolicy.NONE);
        map.setMergePolicyConfig(new MergePolicyConfig(mergePolicy, MergePolicyConfig.DEFAULT_BATCH_SIZE));
        return map;
    }

    private static MapConfig privacyMap(MapConfig map) {
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
        return instance;
    }

    public boolean reidentificationEnabled() {
        return reidentificationEnabled;
    }

    @Override
    public void close() {
        sweeper.close();
        instance.shutdown();
    }
}

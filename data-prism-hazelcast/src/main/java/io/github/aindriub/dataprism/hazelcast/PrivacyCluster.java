package io.github.aindriub.dataprism.hazelcast;

import com.hazelcast.config.Config;
import com.hazelcast.config.EvictionConfig;
import com.hazelcast.config.EvictionPolicy;
import com.hazelcast.config.IndexConfig;
import com.hazelcast.config.IndexType;
import com.hazelcast.config.MapConfig;
import com.hazelcast.config.MaxSizePolicy;
import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;
import io.github.aindriub.dataprism.hazelcast.PrivacyClusterRefusal.Code;

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

    /** Per-caller fixed-window request counts. */
    public static final String CALLER_RATE_MAP = "dataprism.callerrate";

    private final HazelcastInstance instance;
    private final boolean reidentificationEnabled;

    private PrivacyCluster(HazelcastInstance instance, boolean reidentificationEnabled) {
        this.instance = instance;
        this.reidentificationEnabled = reidentificationEnabled;
    }

    /** Starts a member from an explicit, already validated membership description. */
    public static PrivacyCluster embedded(ClusterMembership membership, boolean reidentificationEnabled) {
        return embedded(membership.toConfig(), reidentificationEnabled);
    }

    /** Validates and hardens the config, then starts a member. Refuses before anything starts. */
    public static PrivacyCluster embedded(Config config, boolean reidentificationEnabled) {
        return new PrivacyCluster(Hazelcast.newHazelcastInstance(configure(config)),
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
        config.addMapConfig(privacyMap(new MapConfig(IDENTITY_MAP)));
        config.addMapConfig(privacyMap(new MapConfig(REIDENTIFICATION_MAP)));
        config.addMapConfig(privacyMap(new MapConfig(BUDGET_MAP)));
        MapConfig oversight = privacyMap(new MapConfig(OVERSIGHT_MAP));
        // An evicted pause flag is a path silently reopened.
        oversight.getEvictionConfig().setEvictionPolicy(EvictionPolicy.NONE);
        config.addMapConfig(oversight);
        config.addMapConfig(privacyMap(new MapConfig(APPROVAL_MAP)));
        MapConfig callerRate = privacyMap(new MapConfig(CALLER_RATE_MAP));
        // An evicted counter resets a caller's window; the 2-window TTL bounds size.
        callerRate.getEvictionConfig().setEvictionPolicy(EvictionPolicy.NONE);
        config.addMapConfig(callerRate);
        return config;
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
        instance.shutdown();
    }
}
